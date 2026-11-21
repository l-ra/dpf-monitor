package cz.dpf.monitor

import kotlin.math.max
import kotlin.math.min

enum class RegenPhase { UNKNOWN, IDLE, APPROACHING, ACTIVE, COOL_DOWN, COMPLETED, INTERRUPTED, UNRESOLVED }
enum class EventKind { START, COMPLETE, INTERRUPT, UNRESOLVED, NEXT_TRIP_WARNING }
data class EngineEvent(val kind: EventKind, val text: String, val sound: Boolean = false)

data class EngineView(
    val phase: RegenPhase,
    val flag: RegenFlag,
    val triggerPercent: Double?,
    val distanceKm: Double?,
    val secondsSinceLast: Double?,
    val sootGrams: Double?,
    val tempA: Double?,
    val tempB: Double?,
    val elapsedSeconds: Long?,
    val remainingSeconds: Long?,
    val nextStartSeconds: Long?,
    val activeSince: Long,
    val lastCompletedAt: Long,
    val lastDurationSeconds: Long?,
    val partial: Boolean,
    val lastSampleAt: Long,
    val dataAgeSeconds: Long
)

/**
 * Pure reducer: no Android/BLE APIs, so the transition rules are replayable in unit tests.
 * Timestamp arguments are epoch milliseconds. Outputs are NOT a diagnostic guarantee.
 */
class RegenEngine {
    var phase = RegenPhase.UNKNOWN
        private set
    var activeSince: Long = 0
        private set
    var lastCompletedAt: Long = 0
        private set
    var lastDurationSeconds: Long? = null
        private set
    var lastDistanceBefore: Double? = null
        private set
    var lastTimeBefore: Double? = null
        private set
    var pendingSince: Long = 0
        private set

    private val values = mutableMapOf<String, SensorValue>()
    private val triggerPoints = ArrayDeque<Pair<Long, Double>>()
    private val durations = ArrayDeque<Long>()
    private var activeStreak = 0
    private var offStreak = 0
    private var alertForUnresolvedTrip = false
    private var resetDistance = false
    private var resetTime = false
    private var minSootDuring = Double.POSITIVE_INFINITY
    private var startSoot: Double? = null
    private var lastPlausibleTrigger: Double? = null

    // Persist and restore the state per verified VIN.
    fun restore(
        savedPhase: RegenPhase,
        savedActiveSince: Long,
        savedCompletedAt: Long,
        savedDuration: Long?,
        savedDistanceBefore: Double?,
        savedTimeBefore: Double?,
        now: Long
    ) {
        activeSince = savedActiveSince
        lastCompletedAt = savedCompletedAt
        lastDurationSeconds = savedDuration
        lastDistanceBefore = savedDistanceBefore
        lastTimeBefore = savedTimeBefore
        phase = if (savedPhase in setOf(RegenPhase.ACTIVE, RegenPhase.COOL_DOWN, RegenPhase.UNRESOLVED))
            RegenPhase.UNRESOLVED else savedPhase
        alertForUnresolvedTrip = phase == RegenPhase.UNRESOLVED
        pendingSince = if (phase == RegenPhase.UNRESOLVED) now else 0
    }

    fun onConnectionLost(): EngineEvent? {
        if (phase !in setOf(RegenPhase.ACTIVE, RegenPhase.COOL_DOWN)) return null
        phase = RegenPhase.UNRESOLVED
        alertForUnresolvedTrip = true
        return EngineEvent(EventKind.UNRESOLVED,
            "Spojení skončilo během regenerace. Výsledek zatím nelze ověřit.")
    }

    fun onTripVerified(): EngineEvent? {
        if (!alertForUnresolvedTrip) return null
        alertForUnresolvedTrip = false
        return EngineEvent(EventKind.NEXT_TRIP_WARNING,
            "Minulá regenerace nebyla ověřeně dokončena. Kontroluji čítače DPF.")
    }

    fun consume(sensor: SensorValue): List<EngineEvent> {
        values[sensor.command] = sensor
        val events = mutableListOf<EngineEvent>()
        val now = sensor.timestamp
        when (sensor.command) {
            "018B" -> {
                val flag = sensor.flag ?: RegenFlag.UNKNOWN
                if (sensor.triggerPercent != null) {
                    lastPlausibleTrigger = sensor.triggerPercent
                    triggerPoints.addLast(now to sensor.triggerPercent)
                    while (triggerPoints.isNotEmpty() && now - triggerPoints.first().first > 45 * 60_000)
                        triggerPoints.removeFirst()
                }
                if (flag == RegenFlag.ACTIVE) {
                    activeStreak++
                    offStreak = 0
                    if (activeStreak >= 2 && phase != RegenPhase.ACTIVE) {
                        if (phase == RegenPhase.COOL_DOWN) {
                            phase = RegenPhase.ACTIVE // temporary state flip; don't notify twice
                        } else {
                            if (phase != RegenPhase.UNRESOLVED) activeSince = now
                            if (activeSince <= 0) activeSince = now
                            phase = RegenPhase.ACTIVE
                            minSootDuring = Double.POSITIVE_INFINITY
                            startSoot = values["22208B"]?.number
                            events += EngineEvent(EventKind.START,
                                "Spuštěna aktivní regenerace DPF.", sound = true)
                        }
                    }
                } else if (flag == RegenFlag.INACTIVE || flag == RegenFlag.PASSIVE) {
                    activeStreak = 0
                    offStreak++
                    if (phase == RegenPhase.ACTIVE && offStreak >= 2) {
                        phase = RegenPhase.COOL_DOWN
                        pendingSince = now
                        lastDistanceBefore = values["22208D"]?.number
                        lastTimeBefore = values["222089"]?.number
                        resetDistance = false
                        resetTime = false
                    } else if (phase == RegenPhase.UNKNOWN && offStreak >= 2) {
                        phase = RegenPhase.IDLE
                    }
                } else {
                    activeStreak = 0
                    offStreak = 0
                }
            }
            "22208B" -> if (phase == RegenPhase.ACTIVE && sensor.number != null)
                minSootDuring = min(minSootDuring, sensor.number)
            "22208D" -> if (phase in setOf(RegenPhase.COOL_DOWN, RegenPhase.UNRESOLVED)) {
                val before = lastDistanceBefore
                if (before != null && before >= 10 && sensor.number != null &&
                    sensor.number < 2 && before - sensor.number > 5) resetDistance = true
            }
            "222089" -> if (phase in setOf(RegenPhase.COOL_DOWN, RegenPhase.UNRESOLVED)) {
                val before = lastTimeBefore
                if (before != null && before >= 180 && sensor.number != null &&
                    sensor.number < 45 && before - sensor.number > 120) resetTime = true
            }
        }
        // Physical evidence: both counters reset; stronger than an 018B 03->01 transition.
        if (phase in setOf(RegenPhase.COOL_DOWN, RegenPhase.UNRESOLVED) && resetDistance && resetTime) {
            val duration = if (activeSince > 0) max(0L, (now - activeSince) / 1000) else 0L
            if (duration in 180L..3600L) {
                lastDurationSeconds = duration
                durations.add(duration)
                while (durations.size > 20) durations.removeFirst()
            }
            lastCompletedAt = now
            activeSince = 0
            pendingSince = 0
            phase = RegenPhase.COMPLETED
            resetDistance = false
            resetTime = false
            events += EngineEvent(EventKind.COMPLETE, "Regenerace DPF dokončena – oba čítače se vynulovaly.")
        } else if (phase == RegenPhase.COOL_DOWN && pendingSince > 0 && now - pendingSince >= 90_000) {
            // Observed departure from regeneration with continuing vehicle communication.
            // Do not declare interruption merely because BLE disconnected.
            phase = RegenPhase.INTERRUPTED
            events += EngineEvent(EventKind.INTERRUPT,
                "Regenerace pravděpodobně přerušena (bez resetu čítačů).")
        }
        if (phase == RegenPhase.IDLE && (lastPlausibleTrigger ?: 0.0) >= 85.0)
            phase = RegenPhase.APPROACHING
        if (phase == RegenPhase.APPROACHING && (lastPlausibleTrigger ?: 0.0) < 75.0)
            phase = RegenPhase.IDLE
        if (phase == RegenPhase.COMPLETED && sensor.command == "018B" &&
            sensor.flag == RegenFlag.INACTIVE) phase = RegenPhase.IDLE
        return events
    }

    fun snapshot(now: Long): EngineView {
        val pid = values["018B"]
        val elapsed = if (phase == RegenPhase.ACTIVE) {
            val t = values["22207D"]
            // ECU timer is 0.64 seconds/tick, preferred only if fresh.
            if (t != null && t.number != null && now - t.timestamp < 30_000)
                t.number.toLong() else if (activeSince > 0) (now - activeSince) / 1000 else null
        } else null
        val target = if (durations.isNotEmpty()) {
            durations.sorted()[durations.size / 2]
        } else lastDurationSeconds ?: 1036L // one observed 2026-11-21 reference run
        val remaining = elapsed?.let { max(0L, target - it) }
        val last = values.values.maxOfOrNull { it.timestamp } ?: 0L
        return EngineView(
            phase, pid?.flag ?: RegenFlag.UNKNOWN, pid?.triggerPercent,
            values["22208D"]?.number, values["222089"]?.number,
            values["22208B"]?.number, values["22449A"]?.number, values["22449B"]?.number,
            elapsed, remaining, estimateNext(now), activeSince,
            lastCompletedAt, lastDurationSeconds, pid?.partial ?: false,
            last, if (last <= 0) Long.MAX_VALUE else max(0, (now - last) / 1000)
        )
    }

    private fun estimateNext(now: Long): Long? {
        if (phase in setOf(RegenPhase.ACTIVE, RegenPhase.COOL_DOWN, RegenPhase.UNRESOLVED)) return null
        val current = lastPlausibleTrigger ?: return null
        if (current < 80) return null
        val start = triggerPoints.firstOrNull { now - it.first >= 8 * 60_000 } ?: return null
        val delta = current - start.second
        val seconds = (now - start.first) / 1000.0
        if (delta < 1.0 || seconds < 480.0) return null
        val rate = delta / seconds
        if (rate !in 0.0001..0.05) return null
        return ((100 - current) / rate).toLong().coerceIn(0L, 4 * 3600L)
    }
}
