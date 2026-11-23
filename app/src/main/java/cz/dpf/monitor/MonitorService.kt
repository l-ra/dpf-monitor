package cz.dpf.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground BLE service, launched manually or from CompanionDeviceService.
 * The service survives Activity disappearance but is still subject to OEM / OS
 * battery management, Bluetooth permissions and companion presence callbacks.
 */
@SuppressLint("MissingPermission")
class MonitorService : Service() {
    companion object {
        const val ACTION_START = "cz.dpf.monitor.START"
        const val ACTION_AUTO = "cz.dpf.monitor.AUTO"
        const val ACTION_STOP = "cz.dpf.monitor.STOP"
        const val ACTION_RECONNECT = "cz.dpf.monitor.RECONNECT"
        const val STATUS = "monitor_status"
        const val EVENTS = "monitor_events"
        const val ALARM = "monitor_alarm"
        const val SERVICE_ID = 201
        fun start(context: Context, automatic: Boolean = false) {
            val i = Intent(context, MonitorService::class.java).setAction(if (automatic) ACTION_AUTO else ACTION_START)
            context.startForegroundService(i)
        }

        fun reconnect(context: Context) {
            context.startForegroundService(Intent(context, MonitorService::class.java).setAction(ACTION_RECONNECT))
        }
    }
    private val running = AtomicBoolean(false)
    private val requestReconnect = AtomicBoolean(false)
    private val retryLock = Object()
    private var worker: Thread? = null
    private var elm: BleElm? = null
    private lateinit var store: LocalStore
    private lateinit var notifier: NotificationManager
    private var player: MediaPlayer? = null
    private var lastPublished = 0L

    override fun onCreate() {
        super.onCreate()
        store = LocalStore(this)
        notifier = getSystemService(NotificationManager::class.java)
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        notifier.createNotificationChannel(NotificationChannel(STATUS, "Sledování DPF", NotificationManager.IMPORTANCE_LOW))
        notifier.createNotificationChannel(NotificationChannel(EVENTS, "DPF události", NotificationManager.IMPORTANCE_HIGH))
        notifier.createNotificationChannel(
            NotificationChannel(ALARM, "Hlasitá regenerace DPF", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(alarmUri, attributes)
                enableVibration(true)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running.set(false)
            worker?.interrupt()
            synchronized(retryLock) { retryLock.notifyAll() }
            store.updateLink("Sledování vypnuto")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_AUTO && !store.autoEnabled) {
            stopSelf(); return START_NOT_STICKY
        }
        val note = statusNotification("Připojuji se k adaptéru OBDII")
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(SERVICE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(SERVICE_ID, note)
        if (running.compareAndSet(false, true)) {
            worker = Thread({ monitorLoop() }, "dpf-ble-monitor").also { it.start() }
        } else if (intent?.action == ACTION_RECONNECT) {
            // Previously this menu action was a no-op when the worker was active.
            requestReconnect.set(true)
            store.appendDiagnostic("MANUAL RECONNECT requested: restarting GATT session")
            elm?.close()
            synchronized(retryLock) { retryLock.notifyAll() }
        }
        return START_STICKY
    }

    private fun notification(channel: String, title: String, body: String): Notification {
        val click = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_dpf_notification)
            .setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(click).setAutoCancel(channel != STATUS)
            .setOngoing(channel == STATUS).build()
    }
    private fun statusNotification(body: String): Notification =
        notification(STATUS, "DPF Monitor · sledování", body)

    private fun inform(event: EngineEvent) {
        store.event(event.kind.name, event.text)
        store.appendDiagnostic("EVENT " + event.kind + ": " + event.text)
        val channel = if (event.sound) ALARM else EVENTS
        val id = when (event.kind) {
            EventKind.APPROACHING -> 206
            EventKind.START -> 203
            EventKind.INTERRUPT, EventKind.NEXT_TRIP_WARNING, EventKind.UNRESOLVED -> 204
            EventKind.COMPLETE -> 205
        }
        notifier.notify(id, notification(channel, "DPF · " + event.kind.name, event.text))
        if (event.sound) {
            try {
                player?.release()
                val p = MediaPlayer()
                p.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                p.setDataSource(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                p.setOnCompletionListener { it.release(); if (player === it) player = null }
                p.prepare()
                p.start()
                player = p
            } catch (e: Exception) {
                store.appendDiagnostic("ALARM playback failed: " + e.message)
            }
        }
    }

    private fun publish(engine: RegenEngine, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastPublished >= 4000) {
            val v = engine.snapshot(now)
            store.save(engine, v)
            val text = if (v.lastSampleAt <= 0 || now - v.lastSampleAt > 18_000)
                store.linkState + " · poslední měření není aktuální"
            else when (v.phase) {
                RegenPhase.ACTIVE -> "Regenerace běží · " + (v.elapsedSeconds ?: 0) / 60 + " min"
                RegenPhase.COOL_DOWN -> "Regenerace dobíhá · ověřuji reset čítačů"
                RegenPhase.UNRESOLVED -> "Minulé dokončení neověřeno"
                RegenPhase.INTERRUPTED -> "Pravděpodobně přerušená regenerace"
                else -> "DPF " + (v.triggerPercent?.toInt()?.toString() ?: "?") + " % · " +
                    (v.distanceKm?.toInt()?.toString() ?: "?") + " km"
            }
            notifier.notify(SERVICE_ID, statusNotification(text))
            lastPublished = now
        }
    }


    private fun waitForRetry(ms: Long) {
        synchronized(retryLock) {
            if (running.get() && !requestReconnect.getAndSet(false)) {
                retryLock.wait(ms)
            }
        }
    }

    private fun monitorLoop() {
        val engine = RegenEngine()
        val telemetry = TelemetryDatabase.get(this)
        telemetry.purgeExpired()
        store.restore(engine, System.currentTimeMillis())
        var backoff = 5_000L
        var diagnosticFailures = 0
        var scanAttempts = 0
        try {
            runCatching { PersistentAdapterScan.register(this) }.onFailure {
                store.appendDiagnostic("BACKGROUND_SCAN registration: " + it.message)
            }
            while (running.get()) {
                val mac = store.adapterAddress
                if (mac == null) {
                    store.updateLink("Adaptér není přiřazen")
                    store.appendDiagnostic("No paired adapter")
                    break
                }
                if (!BluetoothAdapter.checkBluetoothAddress(mac.trim().uppercase(Locale.ROOT))) {
                    store.event("ADAPTER_ADDRESS_INVALID", "Neplatná BLE adresa adaptéru")
                    store.updateLink("Neplatná BLE adresa")
                    break
                }
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    store.updateLink("Chybí oprávnění Bluetooth")
                    store.appendDiagnostic("BLE permissions missing; monitoring stopped")
                    break
                }
                val btAdapter = getSystemService(BluetoothManager::class.java).adapter
                if (btAdapter == null || !btAdapter.isEnabled) {
                    store.updateLink("Bluetooth vypnuto")
                    publish(engine, true)
                    waitForRetry(15_000L)
                    continue
                }

                var candidate: AdapterDiscovery.Candidate? = null
                try {
                    store.updateLink("Vyhledávám BLE adaptér")
                    candidate = AdapterDiscovery(this) { store.appendDiagnostic(it) }.find(
                        mac, store.adapterName ?: "OBDII", 7_000
                    )
                } catch (e: InterruptedException) {
                    if (!running.get()) break
                    continue
                } catch (e: Exception) {
                    store.appendDiagnostic("BLE SCAN exception: " + e.message)
                }

                // Only occasionally try a direct connection when no advertisement
                // is visible; this prevents hundreds of doomed GATT 147 retries.
                scanAttempts++
                val target = candidate?.address ?: if (scanAttempts % 3 == 0) mac else null
                if (target == null) {
                    store.updateLink("Adaptér není v dosahu / neodpovídá")
                    publish(engine, true)
                    try { waitForRetry(backoff) } catch (_: InterruptedException) { if (!running.get()) break }
                    backoff = minOf(backoff * 2, 30_000L)
                    continue
                }

                try {
                    store.updateLink("Navazuji spojení BLE")
                    val client = BleElm(this) {
                        store.appendDiagnostic(System.currentTimeMillis().toString() + " " + it)
                    }
                    elm = client
                    store.appendDiagnostic("CONNECT attempt: " +
                        if (target.equals(mac, true)) "stored BLE address" else "scanned candidate (unverified)")
                    client.connect(target)
                    store.updateLink("BLE připojeno, ověřuji ECU")
                    client.initialize()
                    val vin = client.verifyVin()
                    if (vin == null) {
                        store.event("VIN_RETRY", "Úplné VIN nyní nelze přečíst; zkusím znovu automaticky.")
                        throw IllegalStateException("ECU VIN not available; preserving adapter association")
                    }
                    if (store.vin == null) {
                        store.vin = vin
                        store.event("BOUND", "Vozidlo ověřeno pomocí VIN (pouze lokálně).")
                    } else if (store.vin != vin) {
                        store.event("VIN_MISMATCH", "Jiné VIN; odmítám neznámé vozidlo.")
                        // Never bind to a different vehicle just because it advertises OBDII.
                        throw SecurityException("Candidate ECU VIN differs from bound vehicle")
                    }
                    if (!target.equals(mac, true)) {
                        // A unique BLE candidate with the same validated ECU VIN.
                        // CDM association stays untouched; OS PendingIntent scan acts
                        // as a second wake source across changing BLE addresses.
                        store.adapterAddress = target
                        store.event("BLE_ADDRESS_RECOVERED",
                            "Adresa BLE se změnila, spojení obnoveno po shodě VIN.")
                        store.appendDiagnostic("BLE ADDRESS rotated: old=" + mac + ", new=" + target)
                        runCatching { PersistentAdapterScan.register(this) }
                    }
                    store.adapterName = client.connectedName ?: store.adapterName ?: "OBDII"
                    store.setupComplete = true
                    engine.onTripVerified()?.let(::inform)
                    store.updateLink("Monitoruji DPF")
                    diagnosticFailures = 0
                    backoff = 5_000L
                    scanAttempts = 0
                    val lastPoll = mutableMapOf<String, Long>()
                    val slow = listOf("22208D", "222089", "22207D", "220809", "22208B", "22208C")
                    val fast = listOf("22449A", "22449B")
                    while (running.get() && !requestReconnect.get()) {
                        val started = SystemClock.elapsedRealtime()
                        val now = System.currentTimeMillis()
                        val due = (fast + slow).filter {
                            now - (lastPoll[it] ?: 0L) >= if (it in fast) 8_000L else 20_000L
                        }
                        for (cmd in listOf("018B") + due.take(1)) {
                            if (!running.get() || requestReconnect.get()) break
                            val raw = client.read(cmd, if (cmd == "018B") "7DF" else "7E0")
                            val at = System.currentTimeMillis()
                            val observation = ObdProtocol.decode(cmd, raw, at)
                            telemetry.recordSample(store.vin, cmd, raw, observation, at)
                            if (observation == null) {
                                diagnosticFailures++
                                if (diagnosticFailures == 4) {
                                    store.event("ECU", "BLE připojeno, ECU momentálně neodpovídá.")
                                    store.updateLink("BLE připojeno · ECU spí")
                                }
                            } else {
                                if (diagnosticFailures >= 4) store.updateLink("Monitoruji DPF")
                                diagnosticFailures = 0
                                val events = engine.consume(observation)
                                events.forEach(::inform)
                                if (events.isNotEmpty()) publish(engine, true)
                            }
                            if (cmd != "018B") lastPoll[cmd] = System.currentTimeMillis()
                        }
                        publish(engine)
                        val delay = if (diagnosticFailures >= 15) 20_000L
                            else maxOf(100L, 1_500L - (SystemClock.elapsedRealtime() - started))
                        if (diagnosticFailures == 15) store.appendDiagnostic("ECU asleep; reducing polling")
                        synchronized(retryLock) {
                            if (running.get() && !requestReconnect.get()) retryLock.wait(delay)
                        }
                    }
                } catch (e: InterruptedException) {
                    if (!running.get()) break
                    store.appendDiagnostic("LINK reconnect wake interrupted sleep")
                } catch (e: SecurityException) {
                    store.appendDiagnostic("LINK SECURITY: " + e.message)
                    store.updateLink("VIN se neshoduje")
                    // Continue scanning old address; new candidate must never be saved.
                } catch (e: Exception) {
                    store.appendDiagnostic("LINK ERROR: " + e.javaClass.simpleName + " " + e.message)
                    store.updateLink("Ztráta BLE / ECU · obnovuji")
                    engine.onConnectionLost()?.let(::inform)
                    publish(engine, true)
                } finally {
                    elm?.close()
                    elm = null
                }
                if (!running.get()) break
                if (requestReconnect.getAndSet(false)) {
                    backoff = 5_000L
                    continue
                }
                try { waitForRetry(backoff) } catch (_: InterruptedException) { if (!running.get()) break }
                backoff = minOf(backoff * 2, 30_000L)
            }
        } finally {
            engine.onConnectionLost()?.let(::inform)
            publish(engine, true)
            running.set(false)
            stopSelf()
        }
    }

    override fun onDestroy() {
        running.set(false)
        worker?.interrupt()
        synchronized(retryLock) { retryLock.notifyAll() }
        elm?.close()
        player?.release(); player = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
