package cz.dpf.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * BLE advertisements are used for candidate discovery, NOT proof of adapter
 * identity. After an address change, the ECU's complete VIN must match the
 * previously bound vehicle before the new address is accepted.
 */
@SuppressLint("MissingPermission")
class AdapterDiscovery(private val context: Context, private val log: (String) -> Unit) {
    data class Candidate(val address: String, val name: String?, val rssi: Int, val addressChanged: Boolean)

    fun find(expectedAddress: String, expectedName: String, timeoutMs: Long = 7_000): Candidate? {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return null
        if (!adapter.isEnabled ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            return null
        val scanner = adapter.bluetoothLeScanner ?: return null
        val oldAddress = expectedAddress.trim().uppercase(Locale.ROOT)
        if (!BluetoothAdapter.checkBluetoothAddress(oldAddress)) return null
        val nameToFind = expectedName.ifBlank { "OBDII" }
        val results = linkedMapOf<String, Candidate>()
        val expectedFound = CountDownLatch(1)
        var failedCode = 0
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address?.uppercase(Locale.ROOT) ?: return
                val localName = result.scanRecord?.deviceName ?:
                    runCatching { result.device.name }.getOrNull()
                if (address != oldAddress && !localName.equals(nameToFind, ignoreCase = true)) return
                synchronized(results) {
                    results[address] = Candidate(address, localName, result.rssi, address != oldAddress)
                }
                if (address == oldAddress) expectedFound.countDown()
            }
            override fun onBatchScanResults(batchResults: MutableList<ScanResult>) {
                batchResults.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }
            override fun onScanFailed(errorCode: Int) {
                failedCode = errorCode
                log("SCAN failed code=" + errorCode)
                expectedFound.countDown()
            }
        }
        val filters = listOf(
            ScanFilter.Builder().setDeviceAddress(oldAddress).build(),
            ScanFilter.Builder().setDeviceName(nameToFind).build()
        )
        try {
            scanner.startScan(filters,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            expectedFound.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            log("SCAN error: " + e.javaClass.simpleName + " " + e.message)
            return null
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        val found = synchronized(results) { results.values.toList() }
        val choice = BleRecoveryPolicy.chooseAddress(oldAddress, found.map { it.address })
        if (choice != null) {
            val match = found.first { it.address.equals(choice, true) }
            if (match.addressChanged) log("SCAN unique new BLE candidate; VIN verification required")
            return match
        }
        if (found.size > 1) log("SCAN ambiguous: " + found.size + " matching addresses")
        else if (failedCode == 0) log("SCAN no matching advertisements")
        return null
    }
}

/** OS-managed scan can wake the process after it is killed/restarted. */
@SuppressLint("MissingPermission")
object PersistentAdapterScan {
    const val ACTION = "cz.dpf.monitor.ADAPTER_ADVERTISEMENT"

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 83, Intent(context, AdapterScanReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    fun register(context: Context) {
        val store = LocalStore(context)
        val mac = store.adapterAddress ?: return
        if (!store.autoEnabled) return
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return
        if (!adapter.isEnabled) return
        val scanner = adapter.bluetoothLeScanner ?: return
        val filters = listOf(
            ScanFilter.Builder().setDeviceAddress(mac.uppercase(Locale.ROOT)).build(),
            ScanFilter.Builder().setDeviceName(store.adapterName ?: "OBDII").build()
        )
        val pi = pending(context)
        runCatching { scanner.stopScan(pi) }
        val status = scanner.startScan(
            filters,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH).build(),
            pi
        )
        store.appendDiagnostic(if (status == 0) "PRESENCE background scan registered"
            else "PRESENCE background scan failed code=" + status)
    }

    fun unregister(context: Context) {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return
        runCatching { adapter.bluetoothLeScanner?.stopScan(pending(context)) }
    }
}

/** Persistent scan intent is explicit and this receiver is not exported. */
@SuppressLint("MissingPermission")
class AdapterScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != PersistentAdapterScan.ACTION) return
        val store = LocalStore(context)
        val mac = store.adapterAddress ?: return
        if (!store.autoEnabled) return
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val scanResults: List<ScanResult> = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java
            ) ?: emptyList()
        else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
                ?: emptyList()
        }
        val expectedName = store.adapterName ?: "OBDII"
        val found = scanResults.any { result ->
            result.device.address.equals(mac, true) ||
                (result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull())
                    .equals(expectedName, true)
        }
        if (!found) return
        store.appendDiagnostic("PRESENCE adapter advertisement; wake requested")
        try {
            MonitorService.start(context, automatic = true)
        } catch (e: Exception) {
            store.event("PRESENCE_START_FAILED", "BLE wake was refused: " + e.message)
        }
    }
}
