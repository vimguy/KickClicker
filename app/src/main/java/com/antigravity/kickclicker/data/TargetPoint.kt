package com.antigravity.kickclicker.data

data class TargetPoint(
    var id: Int,
    var x: Float,
    var y: Float,
    var delayMs: Long = 50L,
    var durationMs: Long = 15L
)

enum class ClickMode {
    RAPID_SINGLE,
    SEQUENCE,
    SMART_TEXT;

    companion object {
        /** 默认使用多点顺序：当只有 1 个标靶时与单点连发行为完全一致。 */
        fun fromName(name: String?): ClickMode =
            entries.firstOrNull { it.name == name } ?: SEQUENCE
    }
}

data class ClickConfig(
    var mode: ClickMode = ClickMode.SEQUENCE,
    var repeatCount: Int = 0, // 0 = 无限循环
    var intervalMs: Long = 50L, // 新增标靶的默认切换间隔
    var antiBanEnabled: Boolean = true,
    var smartTargetText: String = "立即购买",
    var burstCount: Int = 3, // 默认每点连发 3 次
    var burstGapMs: Long = 15L, // 连发间隔 15ms
    var pressDurationMs: Long = 15L, // 按下时长 15ms
    var jitterRadiusDp: Float = 1.2f, // 微抖动半径 1.2dp（严格在粉色靶心内）
    var firstClickDelayMs: Long = 0L // 0 延迟，点击播放立即开打
)
