package cz.dpf.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Only small, mutable state lives in preferences.
 * 30-day history and full communication traces live in TelemetryDatabase.
 */
class LocalStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("dpf_monitor_private", Context.MODE_PRIVATE)
    private val history = TelemetryDatabase.get(context.applicationContext)

    var adapterAddress: String?
        get() = p.getString("adapter", null)
        set(value) { p.edit().putString("adapter", value).apply() }

    var adapterName: String?
        get() = p.getString("adapter_name", null)
        set(value) { p.edit().putString("adapter_name", value).apply() }

    var linkState: String
        get() = p.getString("link_state", "NEZNÁMÝ") ?: "NEZNÁMÝ"
        set(value) { p.edit().putString("link_state", value).apply() }

    var linkUpdatedAt: Long
        get() = p.getLong("link_updated", 0L)
        set(value) { p.edit().putLong("link_updated", value).apply() }

    fun updateLink(state: String) {
        p.edit().putString("link_state", state)
            .putLong("link_updated", System.currentTimeMillis()).apply()
    }

    var vin: String?
        get() = p.getString("vin", null)
        set(value) { p.edit().putString("vin", value).apply() }

    var autoEnabled: Boolean
        get() = p.getBoolean("auto_enabled", true)
        set(value) { p.edit().putBoolean("auto_enabled", value).apply() }

    var setupComplete: Boolean
        get() = p.getBoolean("setup_complete", false)
        set(value) { p.edit().putBoolean("setup_complete", value).apply() }

    @Synchronized
    fun restore(engine: RegenEngine, now: Long) {
        val s = JSONObject(p.getString("engine", "{}") ?: "{}")
        val phase = runCatching { RegenPhase.valueOf(s.optString("phase", "UNKNOWN")) }
            .getOrDefault(RegenPhase.UNKNOWN)
        engine.restore(
            phase, s.optLong("activeSince"), s.optLong("lastCompletedAt"),
            s.optLong("lastDurationSeconds").takeIf { it > 0 },
            s.optDouble("lastDistanceBefore").takeUnless { it.isNaN() },
            s.optDouble("lastTimeBefore").takeUnless { it.isNaN() }, now
        )
    }

    @Synchronized
    fun save(engine: RegenEngine, view: EngineView) {
        val state = JSONObject()
            .put("phase", engine.phase.name)
            .put("activeSince", engine.activeSince)
            .put("lastCompletedAt", engine.lastCompletedAt)
            .put("lastDurationSeconds", engine.lastDurationSeconds ?: 0L)
            .put("lastDistanceBefore", engine.lastDistanceBefore)
            .put("lastTimeBefore", engine.lastTimeBefore)
        val v = JSONObject()
            .put("phase", view.phase.name).put("flag", view.flag.name)
            .put("trigger", view.triggerPercent).put("distance", view.distanceKm)
            .put("since", view.secondsSinceLast).put("soot", view.sootGrams)
            .put("tempA", view.tempA).put("tempB", view.tempB)
            .put("elapsed", view.elapsedSeconds).put("remaining", view.remainingSeconds)
            .put("next", view.nextStartSeconds).put("partial", view.partial)
            .put("lastSample", view.lastSampleAt)
            .put("updated", System.currentTimeMillis())
        // Synchronous: preserve the regeneration phase if Android kills the process.
        p.edit().putString("engine", state.toString()).putString("view", v.toString()).commit()
    }

    fun view(): JSONObject = runCatching { JSONObject(p.getString("view", "{}") ?: "{}") }
        .getOrElse { JSONObject() }

    fun event(type: String, message: String) {
        history.recordEvent(type, message)
    }

    fun events(): JSONArray = history.latestEvents(16)

    fun appendDiagnostic(line: String) {
        history.recordCommunication(line)
    }

    fun diagnostic(): String = "Zvol Nastavení → Export 30 dnů. " +
        "Archiv ZIP obsahuje kompletní telemetrii, události a komunikaci BLE/ELM327."
}
