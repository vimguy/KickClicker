package com.antigravity.kickclicker.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object AppPrefs {

    private const val PREFS = "kick_clicker_prefs"
    private const val KEY_INTERVAL = "click_interval_ms"
    private const val KEY_MODE = "click_mode"
    private const val KEY_ANTI_BAN = "anti_ban"
    private const val KEY_SMART_TEXT = "smart_text"
    private const val KEY_BURST = "burst_count"
    private const val KEY_BURST_GAP = "burst_gap_ms"
    private const val KEY_REPEAT = "repeat_count"
    private const val KEY_PRESS_DURATION = "press_duration_ms"
    private const val KEY_JITTER_DP = "jitter_radius_dp"
    private const val KEY_FIRST_DELAY = "first_click_delay_ms"
    private const val KEY_TARGETS = "targets_json"
    private const val KEY_TIME_OFFSET = "time_offset_ms"
    private const val KEY_TIME_SYNCED_AT = "time_synced_at"
    private const val KEY_TIME_SOURCE = "time_source"
    private const val KEY_TIME_PRECISION = "time_precision_digits"
    private const val KEY_SCHEDULE_TIMES = "schedule_times_json"
    private const val KEY_SELECTED_SCHEDULE_INDEX = "selected_schedule_index"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun restoreConfig(context: Context, config: ClickConfig) {
        val p = prefs(context)
        config.intervalMs = p.getLong(KEY_INTERVAL, config.intervalMs).coerceIn(0L, 5000L)
        config.mode = ClickMode.fromName(p.getString(KEY_MODE, ClickMode.SEQUENCE.name))
        config.antiBanEnabled = p.getBoolean(KEY_ANTI_BAN, config.antiBanEnabled)
        config.smartTargetText = p.getString(KEY_SMART_TEXT, config.smartTargetText).orEmpty()
            .ifBlank { "立即购买" }
        val savedBurst = p.getInt(KEY_BURST, config.burstCount)
        config.burstCount = savedBurst.coerceIn(1, 5)
        config.burstGapMs = p.getLong(KEY_BURST_GAP, config.burstGapMs).coerceIn(5L, 200L)
        config.repeatCount = p.getInt(KEY_REPEAT, config.repeatCount).coerceIn(0, 100000)
        config.pressDurationMs = p.getLong(KEY_PRESS_DURATION, config.pressDurationMs)
            .coerceIn(1L, 1000L)
        val savedJitter = p.getFloat(KEY_JITTER_DP, 1.2f)
        config.jitterRadiusDp = if (savedJitter > 3f) 1.2f else savedJitter.coerceIn(0f, 3f)
        val savedFirstDelay = p.getLong(KEY_FIRST_DELAY, 0L)
        config.firstClickDelayMs = if (savedFirstDelay >= 200L) 0L else savedFirstDelay.coerceIn(0L, 2000L)
        config.timePrecisionDigits = p.getInt(KEY_TIME_PRECISION, config.timePrecisionDigits)
            .coerceIn(1, 3)
    }

    fun saveConfig(context: Context, config: ClickConfig) {
        prefs(context).edit()
            .putLong(KEY_INTERVAL, config.intervalMs)
            .putString(KEY_MODE, config.mode.name)
            .putBoolean(KEY_ANTI_BAN, config.antiBanEnabled)
            .putString(KEY_SMART_TEXT, config.smartTargetText)
            .putInt(KEY_BURST, config.burstCount)
            .putLong(KEY_BURST_GAP, config.burstGapMs)
            .putInt(KEY_REPEAT, config.repeatCount)
            .putLong(KEY_PRESS_DURATION, config.pressDurationMs)
            .putFloat(KEY_JITTER_DP, config.jitterRadiusDp)
            .putLong(KEY_FIRST_DELAY, config.firstClickDelayMs)
            .putInt(KEY_TIME_PRECISION, config.timePrecisionDigits)
            .apply()
    }

    fun loadTargets(context: Context, widthPx: Int, heightPx: Int): List<TargetPoint> {
        val raw = prefs(context).getString(KEY_TARGETS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val fx = o.optDouble("fx", -1.0).toFloat()
                val fy = o.optDouble("fy", -1.0).toFloat()
                if (fx !in 0f..1f || fy !in 0f..1f) return@mapNotNull null
                TargetPoint(
                    id = i + 1,
                    x = fx * widthPx,
                    y = fy * heightPx,
                    delayMs = o.optLong("delay", 0L).coerceIn(0L, 5000L),
                    durationMs = o.optLong("duration", 1L).coerceIn(1L, 1000L)
                )
            }.sortedBy { it.id }.mapIndexed { index, point -> point.also { it.id = index + 1 } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveTargets(context: Context, targets: List<TargetPoint>, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val array = JSONArray()
        for (t in targets) {
            array.put(
                JSONObject()
                    .put("fx", (t.x / widthPx).coerceIn(0f, 1f).toDouble())
                    .put("fy", (t.y / heightPx).coerceIn(0f, 1f).toDouble())
                    .put("delay", t.delayMs)
                    .put("duration", t.durationMs)
            )
        }
        prefs(context).edit().putString(KEY_TARGETS, array.toString()).apply()
    }

    fun clearTargets(context: Context) {
        prefs(context).edit().remove(KEY_TARGETS).apply()
    }

    fun loadScheduleState(context: Context): ScheduleState {
        val p = prefs(context)
        val defaultTimes = listOf(
            "20:00:00.000",
            "20:30:00.000",
            "21:00:00.000",
            "22:00:00.000"
        )
        val savedRaw = p.getString(KEY_SCHEDULE_TIMES, null)
        val times = if (savedRaw != null) {
            try {
                val arr = JSONArray(savedRaw)
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i, "").trim()
                    if (item.isNotEmpty()) out.add(item)
                }
                out
            } catch (_: Exception) {
                defaultTimes.toMutableList()
            }
        } else {
            defaultTimes.toMutableList()
        }

        val selectedIndex = p.getInt(
            KEY_SELECTED_SCHEDULE_INDEX,
            0
        ).coerceIn(0, (times.size - 1).coerceAtLeast(0))

        return ScheduleState(times = times.ifEmpty { defaultTimes }, selectedIndex = selectedIndex)
    }

    fun saveScheduleState(context: Context, times: List<String>, selectedIndex: Int) {
        val valid = times
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(4)

        val safeIndex = selectedIndex.coerceIn(0, (valid.size - 1).coerceAtLeast(0))
        prefs(context).edit()
            .putString(KEY_SCHEDULE_TIMES, JSONArray(valid).toString())
            .putInt(KEY_SELECTED_SCHEDULE_INDEX, safeIndex)
            .apply()
    }

    fun loadTimeState(context: Context): TimeState {
        val p = prefs(context)
        return TimeState(
            offsetMillis = p.getLong(KEY_TIME_OFFSET, 0L),
            syncedAtMillis = p.getLong(KEY_TIME_SYNCED_AT, 0L),
            source = p.getString(KEY_TIME_SOURCE, null).orEmpty()
        )
    }

    fun saveTimeOffset(context: Context, offsetMillis: Long, syncedAtWallMillis: Long, sourceName: String) {
        prefs(context).edit()
            .putLong(KEY_TIME_OFFSET, offsetMillis)
            .putLong(KEY_TIME_SYNCED_AT, syncedAtWallMillis)
            .putString(KEY_TIME_SOURCE, sourceName)
            .apply()
    }

    data class TimeState(val offsetMillis: Long, val syncedAtMillis: Long, val source: String)
    data class ScheduleState(
        val times: List<String>,
        val selectedIndex: Int
    )
}
