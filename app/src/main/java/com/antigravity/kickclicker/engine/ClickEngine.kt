package com.antigravity.kickclicker.engine

import android.os.SystemClock
import android.util.Log
import com.antigravity.kickclicker.data.ClickConfig
import com.antigravity.kickclicker.data.ClickMode
import com.antigravity.kickclicker.data.TargetPoint
import com.antigravity.kickclicker.service.ClickAccessibilityService
import com.antigravity.kickclicker.time.TimeSyncManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

object ClickEngine {

    enum class State { IDLE, SCHEDULED, RUNNING, PAUSED }

    private data class PlannedTarget(
        val x: Float,
        val y: Float,
        val delayMs: Long,
        val durationMs: Long
    )

    private const val TAG = "KickClickerEngine"
    private const val HARD_MAX_RUN_MS = 10 * 60 * 1000L
    private const val FINE_SPIN_WINDOW_MS = 30L
    private const val MIN_SEQUENCE_GAP_MS = 0L

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var scheduledAtMillis: Long = 0L
        private set

    val config = ClickConfig()

    @Volatile
    var displayDensity: Float = 1f

    @Volatile
    var screenWidthPx: Int = 0

    @Volatile
    var screenHeightPx: Int = 0

    var onStateChanged: ((State) -> Unit)? = null
    var onNotice: ((String) -> Unit)? = null
    var onEmergencyVibrate: (() -> Unit)? = null

    private val targetsLock = Any()
    private val targetList = mutableListOf<TargetPoint>()

    private var executionJob: Job? = null
    private var scheduledJob: Job? = null
    private var runStartedAtElapsed = 0L
    private var pausedFromScheduledAt = 0L

    private val faultHandler = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "连点任务异常", error)
        executionJob = null
        scheduledJob = null
        setStateInternal(State.IDLE)
        onNotice?.invoke("连点异常已中止：${error.javaClass.simpleName}")
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + faultHandler)

    fun addTarget(x: Float, y: Float): TargetPoint = synchronized(targetsLock) {
        val point = TargetPoint(
            id = targetList.size + 1,
            x = x,
            y = y,
            delayMs = config.intervalMs,
            durationMs = config.pressDurationMs
        )
        targetList.add(point)
        point
    }

    fun setTargetPosition(point: TargetPoint, x: Float, y: Float) {
        synchronized(targetsLock) {
            point.x = x
            point.y = y
        }
    }

    fun setTargetDelay(point: TargetPoint, delayMs: Long) {
        synchronized(targetsLock) {
            point.delayMs = delayMs.coerceAtLeast(0L)
        }
    }

    fun applyDelayToAllTargets(delayMs: Long) {
        synchronized(targetsLock) {
            val clamped = delayMs.coerceAtLeast(0L)
            config.intervalMs = clamped
            for (p in targetList) p.delayMs = clamped
        }
    }

    fun applyPressDurationToAllTargets(durationMs: Long) {
        synchronized(targetsLock) {
            val clamped = durationMs.coerceIn(1L, 1000L)
            config.pressDurationMs = clamped
            for (p in targetList) p.durationMs = clamped
        }
    }

    fun removeTarget(point: TargetPoint): Boolean = synchronized(targetsLock) {
        val removed = targetList.remove(point)
        if (removed) renumberLocked()
        removed
    }

    fun removeLastTarget(): TargetPoint? = synchronized(targetsLock) {
        if (targetList.isEmpty()) return null
        val removed = targetList.removeAt(targetList.size - 1)
        renumberLocked()
        removed
    }

    fun replaceTargets(points: List<TargetPoint>) {
        synchronized(targetsLock) {
            targetList.clear()
            points.forEachIndexed { index, p ->
                p.id = index + 1
                targetList.add(p)
            }
        }
    }

    fun clearTargets() {
        synchronized(targetsLock) { targetList.clear() }
    }

    fun snapshotTargets(): List<TargetPoint> = synchronized(targetsLock) { targetList.toList() }

    fun targetCount(): Int = synchronized(targetsLock) { targetList.size }

    private fun plannedTargets(): List<PlannedTarget> = synchronized(targetsLock) {
        targetList.sortedBy { it.id }
            .map { PlannedTarget(it.x, it.y, it.delayMs, it.durationMs) }
    }

    private fun renumberLocked() {
        targetList.forEachIndexed { index, point -> point.id = index + 1 }
    }

    private fun setStateInternal(newState: State) {
        state = newState
        onStateChanged?.invoke(newState)
    }

    fun togglePlay() {
        when (state) {
            State.RUNNING -> pause()
            State.PAUSED -> resume()
            State.SCHEDULED -> {
                stop()
                onNotice?.invoke("已取消定时开抢")
            }
            State.IDLE -> start()
        }
    }

    fun start(fromSchedule: Boolean = false) {
        if (state == State.RUNNING) return
        if (ClickAccessibilityService.instance == null) {
            setStateInternal(State.IDLE)
            onNotice?.invoke("无障碍服务未开启，连点未启动")
            return
        }
        val plan = plannedTargets()
        if (plan.isEmpty() && config.mode != ClickMode.SMART_TEXT) {
            setStateInternal(State.IDLE)
            onNotice?.invoke("请先用 ➕ 添加点击标靶")
            return
        }

        scheduledJob?.cancel()
        scheduledJob = null
        executionJob?.cancel()
        executionJob = null
        pausedFromScheduledAt = 0L
        scheduledAtMillis = 0L
        runStartedAtElapsed = SystemClock.elapsedRealtime()

        setStateInternal(State.RUNNING)

        val warmupMs = if (fromSchedule) 0L else config.firstClickDelayMs.coerceIn(0L, 2000L)

        executionJob = engineScope.launch {
            try {
                if (warmupMs > 0) delay(warmupMs)
                var loopCount = 0
                while (isActive && state == State.RUNNING) {
                    val service = ClickAccessibilityService.instance
                    if (service == null) {
                        onNotice?.invoke("无障碍服务已断开，连点停止")
                        break
                    }
                    val targets = plannedTargets()
                    if (targets.isEmpty() && config.mode != ClickMode.SMART_TEXT) {
                        onNotice?.invoke("标靶已全部删除，连点停止")
                        break
                    }

                    when (config.mode) {
                        ClickMode.SMART_TEXT -> {
                            val clickedByText = service.clickByTextGlobally(config.smartTargetText)
                            if (!clickedByText && targets.isNotEmpty()) {
                                val burst = config.burstCount.coerceIn(1, 5)
                                val gap = config.burstGapMs.coerceAtLeast(0L)
                                for (b in 0 until burst) {
                                    if (!isActive || state != State.RUNNING) break
                                    dispatch(service, targets[0])
                                    if (b < burst - 1) delay(gap)
                                }
                            }
                            delay(config.intervalMs.coerceAtLeast(0L))
                        }

                        ClickMode.RAPID_SINGLE,
                        ClickMode.SEQUENCE -> {
                            if (targets.isNotEmpty()) {
                                val burst = config.burstCount.coerceIn(1, 5)
                                val gap = config.burstGapMs.coerceAtLeast(0L)
                                for (point in targets) {
                                    if (!isActive || state != State.RUNNING) break
                                    for (b in 0 until burst) {
                                        if (!isActive || state != State.RUNNING) break
                                        dispatch(service, point)
                                        if (b < burst - 1) delay(gap)
                                    }
                                    delay(jitteredDelay(point.delayMs))
                                }
                            } else {
                                delay(100L)
                            }
                        }
                    }

                    loopCount++
                    if (config.repeatCount in 1..loopCount) {
                        onNotice?.invoke("已完成 $loopCount 轮连点，自动停止")
                        break
                    }
                    val elapsed = SystemClock.elapsedRealtime() - runStartedAtElapsed
                    if (elapsed > HARD_MAX_RUN_MS) {
                        onNotice?.invoke("连点超过 ${HARD_MAX_RUN_MS / 60000} 分钟，安全自动停止")
                        break
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                Log.e(TAG, "连点循环异常", error)
                onNotice?.invoke("连点异常中止：${error.javaClass.simpleName}")
            }
            if (state == State.RUNNING) setStateInternal(State.IDLE)
        }
    }

    fun scheduleStart(targetTimeMillis: Long) {
        executionJob?.cancel()
        executionJob = null
        scheduledJob?.cancel()
        scheduledJob = null

        if (ClickAccessibilityService.instance == null) {
            scheduledAtMillis = 0L
            setStateInternal(State.IDLE)
            onNotice?.invoke("无障碍服务未开启，定时未启动")
            return
        }
        if (plannedTargets().isEmpty() && config.mode != ClickMode.SMART_TEXT) {
            scheduledAtMillis = 0L
            setStateInternal(State.IDLE)
            onNotice?.invoke("请先添加标靶再开启定时")
            return
        }

        val remaining = targetTimeMillis - TimeSyncManager.nowMillis()
        if (remaining <= 0) {
            scheduledAtMillis = 0L
            setStateInternal(State.IDLE)
            onNotice?.invoke("定时时间已过，未启动连点")
            return
        }

        val deadlineElapsed = SystemClock.elapsedRealtime() + remaining
        scheduledAtMillis = targetTimeMillis
        pausedFromScheduledAt = targetTimeMillis
        setStateInternal(State.SCHEDULED)

        scheduledJob = engineScope.launch {
            try {
                while (isActive && state == State.SCHEDULED) {
                    val left = deadlineElapsed - SystemClock.elapsedRealtime()
                    if (left <= 0L) {
                        scheduledJob = null
                        start(fromSchedule = true)
                        return@launch
                    }
                    if (left > FINE_SPIN_WINDOW_MS + 20) {
                        delay(left - FINE_SPIN_WINDOW_MS)
                    } else {
                        SystemClock.sleep(1L)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                Log.e(TAG, "定时任务异常", error)
                onNotice?.invoke("定时任务异常中止：${error.javaClass.simpleName}")
            }
            if (state == State.SCHEDULED) {
                scheduledAtMillis = 0L
                setStateInternal(State.IDLE)
            }
        }
    }

    fun pause() {
        if (state == State.RUNNING || state == State.SCHEDULED) {
            if (state == State.SCHEDULED) pausedFromScheduledAt = scheduledAtMillis
            executionJob?.cancel()
            executionJob = null
            scheduledJob?.cancel()
            scheduledJob = null
            setStateInternal(State.PAUSED)
        }
    }

    private fun resume() {
        val pending = pausedFromScheduledAt
        if (pending > 0 && pending - TimeSyncManager.nowMillis() > 0) {
            scheduleStart(pending)
        } else {
            pausedFromScheduledAt = 0L
            start()
        }
    }

    fun stop() {
        executionJob?.cancel()
        executionJob = null
        scheduledJob?.cancel()
        scheduledJob = null
        pausedFromScheduledAt = 0L
        scheduledAtMillis = 0L
        setStateInternal(State.IDLE)
    }

    fun emergencyStop(reason: String) {
        val wasActive = state == State.RUNNING || state == State.SCHEDULED
        stop()
        if (wasActive) {
            onEmergencyVibrate?.invoke()
            onNotice?.invoke("$reason：已紧急停止")
        }
    }

    private fun dispatch(service: ClickAccessibilityService, point: PlannedTarget) {
        val radiusPx = if (config.antiBanEnabled) {
            config.jitterRadiusDp.coerceAtLeast(0f) * displayDensity
        } else {
            0f
        }
        var x = point.x
        var y = point.y
        if (radiusPx > 0f) {
            x += uniform(-radiusPx, radiusPx)
            y += uniform(-radiusPx, radiusPx)
        }
        val maxX = if (screenWidthPx > 0) screenWidthPx - 1f else Float.MAX_VALUE
        val maxY = if (screenHeightPx > 0) screenHeightPx - 1f else Float.MAX_VALUE
        x = x.coerceIn(0f, maxX)
        y = y.coerceIn(0f, maxY)
        service.dispatchTap(x, y, point.durationMs.coerceIn(1L, 1000L))
    }

    private fun uniform(from: Float, to: Float): Float =
        if (to <= from) 0f else Random.nextDouble(from.toDouble(), to.toDouble()).toFloat()

    private fun jitteredDelay(baseDelay: Long): Long {
        val base = baseDelay.coerceAtLeast(0L)
        if (!config.antiBanEnabled) return base
        val jitter = (base * 0.1).toLong().coerceIn(1L, 40L)
        return (base + Random.nextLong(-jitter, jitter + 1)).coerceAtLeast(0L)
    }
}
