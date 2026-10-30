package com.antigravity.kickclicker.data

data class TargetPoint(
    var id: Int,
    var x: Float,
    var y: Float,
    var delayMs: Long = 0L,
    var durationMs: Long = 1L
)

enum class ClickMode {
    RAPID_SINGLE,
    SEQUENCE,
    SMART_TEXT;

    companion object {
        fun fromName(name: String?): ClickMode =
            entries.firstOrNull { it.name == name } ?: SEQUENCE
    }
}

data class ClickConfig(
    var mode: ClickMode = ClickMode.SEQUENCE,
    var repeatCount: Int = 0,
    var intervalMs: Long = 0L,
    var antiBanEnabled: Boolean = true,
    var smartTargetText: String = "立即购买",
    var burstCount: Int = 1,
    var burstGapMs: Long = 15L,
    var pressDurationMs: Long = 1L,
    var jitterRadiusDp: Float = 1.2f,
    var firstClickDelayMs: Long = 0L
)
