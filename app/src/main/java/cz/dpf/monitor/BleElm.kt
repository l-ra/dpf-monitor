package cz.dpf.monitor

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Tested GATT profile FFF0 (service), FFF2 (write), FFF1 (notify).
 * Serialized ELM327 commands with hard desynchronization on missing prompt. */
@SuppressLint("MissingPermission")
class BleElm(private val context: Context, private val logger: (String) -> Unit) : AutoCloseable {
    companion object {
        private fun uuid(s: String) = UUID.fromString("0000" + s + "-0000-1000-8000-00805f9b34fb")
        private val SERVICE = uuid("fff0")
        private val RX = uuid("fff1")
        private val TX = uuid("fff2")
        private val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val receive = LinkedBlockingQueue<ByteArray>()
    @Volatile private var connected = false
    private var ready = CountDownLatch(1)
    private var failure: String? = null
    private var currentHeader = ""
    @Volatile var connectedName: String? = null
        private set
    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) failure = "GATT status=" + status
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true
                if (!g.discoverServices()) { failure = "discoverServices failed"; ready.countDown() }
            } else {
                connected = false
                ready.countDown()
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failure = "discover services: " + status; ready.countDown(); return
            }
            val svc = g.getService(SERVICE)
            val r = svc?.getCharacteristic(RX)
            tx = svc?.getCharacteristic(TX)
            if (r == null || tx == null) {
                failure = "GATT FFF0/FFF1/FFF2 not found"; ready.countDown(); return
            }
            if (!g.setCharacteristicNotification(r, true)) {
                failure = "setCharacteristicNotification failed"; ready.countDown(); return
            }
            val descriptor = r.getDescriptor(CCC)
            if (descriptor == null) {
                failure = "CCC descriptor missing"; ready.countDown(); return
            }
            @Suppress("DEPRECATION")
            val result = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(descriptor)
            }
            if (!result) { failure = "CCC write failed"; ready.countDown() }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) failure = "CCC write: " + status
            ready.countDown()
        }
        @Deprecated("Pre-Android 13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val b = c.value
            if (c.uuid == RX && b != null) receive.offer(b.clone())
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == RX) receive.offer(value.clone())
        }
    }
    fun connect(address: String) {
        close()
        failure = null
        ready = CountDownLatch(1)
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        // Android BluetoothAdapter accepts only uppercase hexadecimal MAC addresses.
        // CompanionDeviceManager can return lowercase strings; normalize at the API boundary.
        val normalizedAddress = address.trim().uppercase(Locale.ROOT)
        require(BluetoothAdapter.checkBluetoothAddress(normalizedAddress)) {
            "Invalid Bluetooth MAC address: " + address
        }
        val device = adapter.getRemoteDevice(normalizedAddress)
        gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        if (!ready.await(15, TimeUnit.SECONDS) || !connected || failure != null || tx == null)
            throw IllegalStateException("BLE connection failed: " + (failure ?: "timeout"))
        connectedName = runCatching { device.name }.getOrNull()
        logger("BLE GATT connected: " + (connectedName ?: "unknown"))
    }
    fun command(text: String, timeoutMs: Long = 6500): String {
        require(text.matches(Regex("[A-Za-z0-9]+"))) { "Illegal ELM command" }
        val g = gatt ?: error("BLE disconnected")
        val characteristic = tx ?: error("Missing TX characteristic")
        if (!connected) error("BLE link lost")
        receive.clear()
        val bytes = (text.uppercase() + "\r").toByteArray(StandardCharsets.US_ASCII)
        val result = if (Build.VERSION.SDK_INT >= 33)
            g.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }
        if (!result) throw IllegalStateException("BLE write rejected")
        val out = StringBuilder()
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (!connected) error("BLE disconnected during " + text)
            val remaining = end - SystemClock.elapsedRealtime()
            val packet = receive.poll(maxOf(1, remaining), TimeUnit.MILLISECONDS) ?: continue
            out.append(String(packet, StandardCharsets.US_ASCII))
            if (out.contains(">")) {
                val raw = out.toString().substringBefore('>') + ">"
                logger("TX " + text + " | RX " + raw.replace("\r", "|").take(240))
                return raw
            }
        }
        close()
        throw IllegalStateException("ELM prompt timeout for " + text)
    }
    fun initialize() {
        for (c in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP6", "ATCAF1",
            "ATCFC1", "ATM0", "ATAT1", "ATAL", "ATST64", "ATSH7DF")) {
            val answer = command(c)
            if (c != "ATZ" && !answer.contains("OK")) error("ELM rejected " + c + ": " + answer)
        }
        currentHeader = "7DF"
        require(ObdProtocol.parse("0100", command("0100")).valid) { "Engine ECU not reachable" }
    }
    fun read(cmd: String, header: String = "7E0"): String {
        if (currentHeader != header) {
            require(command("ATSH" + header).contains("OK")) { "Cannot address ECU " + header }
            currentHeader = header
        }
        return command(cmd)
    }
    fun verifyVin(): String? {
        for ((header, cmd) in listOf("7DF" to "0902", "7E0" to "22F190")) {
            try {
                val vin = ObdProtocol.vin(read(cmd, header), cmd)
                if (vin != null) return vin
            } catch (e: Exception) {
                logger("VIN " + cmd + " failed: " + e.message)
            }
        }
        // Some ELM327 clones never deliver the consecutive ISO-TP frame unless
        // the Flow Control TX header and payload are configured explicitly.
        // This is still a read-only request to the ECU; AT commands configure adapter.
        try {
            for (c in listOf("ATSH7E0", "ATCRA7E8", "ATCFC1",
                "ATFCSH7E0", "ATFCSD300000", "ATFCSM1", "ATSTFF")) {
                if (!command(c).contains("OK")) error("ELM rejects " + c)
            }
            currentHeader = "7E0"
            val candidate = ObdProtocol.vin(command("22F190", 15_000), "22F190")
            if (candidate != null) return candidate
        } catch (e: Exception) {
            logger("Explicit ISO-TP FC VIN fallback failed: " + e.message)
        } finally {
            // Never leave the adapter with manual FC parameters for normal polling.
            runCatching { command("ATFCSM0") }
            runCatching { command("ATCRA") }
            runCatching { command("ATST64") }
            runCatching { command("ATSH7DF"); currentHeader = "7DF" }
        }
        return null
    }
    override fun close() {
        connected = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        tx = null
        currentHeader = ""
        connectedName = null
        receive.clear()
    }
}
