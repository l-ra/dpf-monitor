package cz.dpf.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
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
        const val ACTION_STOP = "cz.dpf.monitor.STOP"
        const val STATUS = "monitor_status"
        const val EVENTS = "monitor_events"
        const val ALARM = "monitor_alarm"
        const val SERVICE_ID = 201
        fun start(context: Context) {
            val i = Intent(context, MonitorService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }
    }
    private val running = AtomicBoolean(false)
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
            stopSelf()
            return START_NOT_STICKY
        }
        val note = statusNotification("Připojuji se k adaptéru OBDII")
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(SERVICE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(SERVICE_ID, note)
        if (running.compareAndSet(false, true)) {
            worker = Thread({ monitorLoop() }, "dpf-ble-monitor").also { it.start() }
        }
        return START_STICKY
    }

    private fun notification(channel: String, title: String, body: String): Notification {
        val click = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
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
            val text = when (v.phase) {
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

    private fun monitorLoop() {
        val engine = RegenEngine()
        store.restore(engine, System.currentTimeMillis())
        var backoff = 4000L
        var diagnosticFailures = 0
        try {
            while (running.get()) {
                val mac = store.adapterAddress
                if (mac == null) { store.appendDiagnostic("No paired adapter"); break }
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    store.appendDiagnostic("Missing BLUETOOTH_CONNECT permission"); break
                }
                try {
                    val client = BleElm(this) { store.appendDiagnostic(System.currentTimeMillis().toString() + " " + it) }
                    elm = client
                    client.connect(mac)
                    client.initialize()
                    val vin = client.verifyVin()
                    if (vin == null) {
                        store.event("VIN_FAILURE", "Nelze přečíst úplné VIN z ECU. Párování vozidla není dokončeno.")
                        store.appendDiagnostic("VIN cannot be read (0902 / 22F190); not monitoring wrong/unknown car")
                        break
                    }
                    if (store.vin == null) {
                        store.vin = vin
                        store.event("BOUND", "Vozidlo ověřeno pomocí VIN (uloženo jen lokálně).")
                    } else if (store.vin != vin) {
                        store.event("VIN_MISMATCH", "Jiné vozidlo. Sledování zastaveno.")
                        break
                    }
                    engine.onTripVerified()?.let(::inform)
                    diagnosticFailures = 0
                    backoff = 4000L
                    val lastPoll = mutableMapOf<String, Long>()
                    val slow = listOf("22208D", "222089", "22207D", "220809", "22208B", "22208C")
                    val fast = listOf("22449A", "22449B")
                    while (running.get()) {
                        val start = SystemClock.elapsedRealtime()
                        val now = System.currentTimeMillis()
                        val due = (fast + slow).filter { now - (lastPoll[it] ?: 0L) >=
                            (if (it in fast) 8000 else 20000) }
                        val cmds = listOf("018B") + due.take(1)
                        for (cmd in cmds) {
                            if (!running.get()) break
                            val raw = client.read(cmd, if (cmd == "018B") "7DF" else "7E0")
                            val obs = ObdProtocol.decode(cmd, raw, System.currentTimeMillis())
                            if (obs == null) {
                                diagnosticFailures++
                                if (diagnosticFailures == 4) store.event("ECU", "ECU momentálně neodpovídá.")
                            } else {
                                diagnosticFailures = 0
                                engine.consume(obs).forEach(::inform)
                            }
                            if (cmd != "018B") lastPoll[cmd] = System.currentTimeMillis()
                        }
                        publish(engine)
                        if (diagnosticFailures >= 15) {
                            store.appendDiagnostic("Engine asleep? Backing off 20 seconds")
                            Thread.sleep(20_000)
                        } else {
                            val cost = SystemClock.elapsedRealtime() - start
                            Thread.sleep(maxOf(100, 1500 - cost))
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    store.appendDiagnostic("LINK ERROR: " + e.javaClass.simpleName + " " + e.message)
                    engine.onConnectionLost()?.let(::inform)
                    publish(engine, true)
                    elm?.close(); elm = null
                    try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                    backoff = minOf(backoff * 2, 90_000L)
                } finally {
                    elm?.close(); elm = null
                }
            }
        } finally {
            publish(engine, true)
            running.set(false)
            stopSelf()
        }
    }

    override fun onDestroy() {
        running.set(false)
        worker?.interrupt()
        elm?.close()
        player?.release(); player = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
