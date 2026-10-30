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

    private const val KEY_MILLIS_DIGITS = "time_millis_digits"
    private const val KEY_SCHEDULE_TIMES = "schedule_times"
    private const val KEY_SCHEDULE_SELECTED = "schedule_selected_index"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun restoreConfig(context: Context, config: ClickConfig) {
        val p = prefs(context)
        config.intervalMs = p.getLong(KEY_INTERVAL, config.intervalMs).coerceIn(0L, 5000L)
        config.mode = ClickMode.fromName(p.getString(KEY_MODE, ClickMode.SEQUENCE.name))
        config.antiBanEnabled = p.getBoolean(KEY_ANTI_BAN, config.antiBanEnabled)
        config.smartTargetText = p.getString(KEY_SMART_TEXT, config.smartTargetText).orEmpty()
            .ifBlank { "立即购买" }
        config.burstCount = p.getInt(KEY_BURST, config.burstCount).coerceIn(1, 5)
        config.burstGapMs = p.getLong(KEY_BURST_GAP, config.burstGapMs).coerceIn(0L, 200L)
        config.repeatCount = p.getInt(KEY_REPEAT, config.repeatCount).coerceIn(0, 100000)
        config.pressDurationMs = p.getLong(KEY_PRESS_DURATION, config.pressDurationMs)
            .coerceIn(1L, 1000L)
        val savedJitter = p.getFloat(KEY_JITTER_DP, 1.2f)
        config.jitterRadiusDp = if (savedJitter > 3f) 1.2f else savedJitter.coerceIn(0f, 3f)
        val savedFirstDelay = p.getLong(KEY_FIRST_DELAY, 0L)
        config.firstClickDelayMs = if (savedFirstDelay >= 200L) 0L else savedFirstDelay.coerceIn(0L, 2000L)
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

    fun loadMillisDisplayDigits(context: Context): Int {
        val v = prefs(context).getInt(KEY_MILLIS_DIGITS, 1)
        return when (v) {
            1, 2, 3 -> v
            else -> 1
        }
    }

    fun saveMillisDisplayDigits(context: Context, digits: Int) {
        val normalized = when (digits) {
            1, 2, 3 -> digits
            else -> 1
        }
        prefs(context).edit().putInt(KEY_MILLIS_DIGITS, normalized).apply()
    }

    fun loadScheduleTimes(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_SCHEDULE_TIMES, null) ?: return emptyList()
        return raw.split(Regex("[,;\\n\\r]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it }
    }

    fun saveScheduleTimes(context: Context, times: List<String>, selectedIndex: Int) {
        val clean = times.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(10)
        val safeIndex = if (clean.isEmpty()) 0 else selectedIndex.coerceIn(0, clean.size - 1)
        prefs(context).edit()
            .putString(KEY_SCHEDULE_TIMES, clean.joinToString(","))
            .putInt(KEY_SCHEDULE_SELECTED, safeIndex)
            .apply()
    }

    fun loadSelectedScheduleIndex(context: Context): Int =
        prefs(context).getInt(KEY_SCHEDULE_SELECTED, 0).coerceAtLeast(0)

    data class TimeState(val offsetMillis: Long, val syncedAtMillis: Long, val source: String)
}
