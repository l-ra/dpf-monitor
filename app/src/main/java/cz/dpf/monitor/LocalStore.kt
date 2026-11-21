package cz.dpf.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LocalStore(context: Context) {
    private val p = context.getSharedPreferences("dpf_monitor_private", Context.MODE_PRIVATE)
    var adapterAddress: String?
        get() = p.getString("adapter", null)
        set(value) { p.edit().putString("adapter", value).apply() }
    var vin: String?
        get() = p.getString("vin", null)
        set(value) { p.edit().putString("vin", value).apply() }

    @Synchronized fun restore(engine: RegenEngine, now: Long) {
        val s = JSONObject(p.getString("engine", "{}") ?: "{}")
        val phase = runCatching { RegenPhase.valueOf(s.optString("phase", "UNKNOWN")) }
            .getOrDefault(RegenPhase.UNKNOWN)
        engine.restore(phase, s.optLong("activeSince"), s.optLong("lastCompletedAt"),
            s.optLong("lastDurationSeconds").takeIf { it > 0 },
            s.optDouble("lastDistanceBefore").takeUnless { it.isNaN() },
            s.optDouble("lastTimeBefore").takeUnless { it.isNaN() }, now)
    }

    @Synchronized fun save(engine: RegenEngine, view: EngineView) {
        val s = JSONObject()
            .put("phase", engine.phase.name).put("activeSince", engine.activeSince)
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
            .put("next", view.nextStartSeconds)
            .put("partial", view.partial).put("lastSample", view.lastSampleAt)
            .put("updated", System.currentTimeMillis())
        p.edit().putString("engine", s.toString()).putString("view", v.toString()).apply()
    }

    fun view(): JSONObject = runCatching { JSONObject(p.getString("view", "{}") ?: "{}") }
        .getOrElse { JSONObject() }

    @Synchronized fun event(type: String, message: String) {
        val arr = runCatching { JSONArray(p.getString("events", "[]")) }.getOrDefault(JSONArray())
        arr.put(JSONObject().put("at", System.currentTimeMillis()).put("type", type).put("message", message))
        val trimmed = JSONArray()
        for (i in maxOf(0, arr.length() - 100) until arr.length()) trimmed.put(arr.get(i))
        p.edit().putString("events", trimmed.toString()).apply()
    }

    fun events(): JSONArray = runCatching { JSONArray(p.getString("events", "[]")) }.getOrDefault(JSONArray())

    @Synchronized fun appendDiagnostic(line: String) {
        val current = p.getString("diagnostic", "") ?: ""
        p.edit().putString("diagnostic", (current + line + "\n").takeLast(160_000)).apply()
    }

    fun diagnostic(): String = p.getString("diagnostic", "") ?: ""
}
