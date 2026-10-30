package com.antigravity.kickclicker.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.antigravity.kickclicker.R
import com.antigravity.kickclicker.data.AppPrefs
import com.antigravity.kickclicker.data.ClickMode
import com.antigravity.kickclicker.data.TargetPoint
import com.antigravity.kickclicker.engine.ClickEngine
import com.antigravity.kickclicker.time.TimeSyncManager
import com.antigravity.kickclicker.ui.MainActivity
import java.util.Locale
import kotlin.math.hypot

class FloatingControlService : Service() {

    companion object {
        const val CHANNEL_ID = "kick_clicker_floating"
        const val NOTIFICATION_ID = 1001
        const val ACTION_EMERGENCY_STOP = "com.antigravity.kickclicker.action.EMERGENCY_STOP"
        const val ACTION_DISMISS = "com.antigravity.kickclicker.action.DISMISS"

        private const val TAG = "FloatingControlService"
        private const val TARGET_SIZE_DP = 48f
        private const val CLOCK_TICK_MS = 66L
        private const val STATUS_REFRESH_TICKS = 10
        private const val NOTICE_HOLD_MS = 4500L
        private const val DIALOG_WIDTH_DP = 260f
        private const val DIALOG_HEIGHT_DP = 230f
        private const val MILLI_DIGITS = 1

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private var floatingBarView: View? = null
    private var barParams: WindowManager.LayoutParams? = null
    private var statusView: TextView? = null
    private var floatingClockView: View? = null
    private var clockTimeView: TextView? = null
    private var clockStatusView: TextView? = null
    private var configDialogView: View? = null

    private val targetViews = mutableListOf<TargetEntry>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tickerRunnable: Runnable? = null
    private var tickerTicks = 0
    private var pendingNotice: Runnable? = null
    private var transientNotice: String? = null
    private var lastStatusText: String? = null
    private var lastClockText: String? = null

    private val density: Float get() = resources.displayMetrics.density
    private val screenWidthPx: Int get() = resources.displayMetrics.widthPixels
    private val screenHeightPx: Int get() = resources.displayMetrics.heightPixels
    private val targetSizePx: Int get() = (TARGET_SIZE_DP * density + 0.5f).toInt()
    private val touchSlop: Int get() = ViewConfiguration.get(this).scaledTouchSlop

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        TimeSyncManager.init(this)
        ClickEngine.displayDensity = density
        ClickEngine.screenWidthPx = screenWidthPx
        ClickEngine.screenHeightPx = screenHeightPx

        startForegroundNotification()
        restoreTargetsIfNeeded()
        initFloatingBar()

        ClickEngine.onStateChanged = { state ->
            val updateAction = Runnable {
                updatePlayButtonState(state)
                setTargetsTouchable(state != ClickEngine.State.RUNNING)
                if (state == ClickEngine.State.RUNNING) dismissConfigDialog()
                refreshTargetLabels()
                renderStatus(force = true)
                updateNotification()
                updateTickerNeed()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                updateAction.run()
            } else {
                mainHandler.post(updateAction)
            }
        }

        ClickEngine.onNotice = { message -> mainHandler.post { showNotice(message, toast = false) } }
        ClickEngine.onEmergencyVibrate = { vibrateEmergency() }

        renderStatus(force = true)
        updateTickerNeed()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EMERGENCY_STOP -> ClickEngine.emergencyStop("通知栏停止")
            ACTION_DISMISS -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val state = ClickEngine.state
        if (state != ClickEngine.State.RUNNING && state != ClickEngine.State.SCHEDULED) stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        ClickEngine.onStateChanged = null
        ClickEngine.onNotice = null
        ClickEngine.onEmergencyVibrate = null
        persistTargets()
        stopTicker()
        dismissConfigDialog()
        stopClock()
        removeFloatingBar()
        removeAllTargetViews()
        ClickEngine.stop()
        super.onDestroy()
    }

    private fun restoreTargetsIfNeeded() {
        if (targetViews.isNotEmpty()) return
        val saved = AppPrefs.loadTargets(this, screenWidthPx, screenHeightPx)
        if (saved.isEmpty()) return
        ClickEngine.replaceTargets(saved)
        for (point in saved) addTargetViewFor(point)
        refreshTargetLabels()
    }

    private fun persistTargets() {
        AppPrefs.saveTargets(this, ClickEngine.snapshotTargets(), screenWidthPx, screenHeightPx)
    }

    private fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, FloatingControlService::class.java).setAction(action),
            pendingFlags()
        )

    private fun notificationText(): String = when (ClickEngine.state) {
        ClickEngine.State.RUNNING -> {
            val stats = ClickAccessibilityService.instance?.dispatchStats()
            val rejected = stats?.rejected ?: 0L
            "连点中 · 已派发 ${stats?.accepted ?: 0} · 漏点 $rejected"
        }
        ClickEngine.State.SCHEDULED -> "等待定时开抢"
        ClickEngine.State.PAUSED -> "已暂停"
        ClickEngine.State.IDLE -> "悬浮控制台已就绪"
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "KickClicker 悬浮服务", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingFlags()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KickClicker")
            .setContentText(notificationText())
            .setSmallIcon(R.drawable.ic_status)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_close, "停止连点", serviceIntent(ACTION_EMERGENCY_STOP, 1))
            .addAction(R.drawable.ic_close, "关闭控制台", serviceIntent(ACTION_DISMISS, 2))
            .build()
    }

    private fun updateNotification() {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "通知更新失败", t)
        }
    }

    private fun startForegroundNotification() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务通知异常", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initFloatingBar() {
        try {
            val barView = inflateOverlay(R.layout.view_floating_bar)
            floatingBarView = barView
            statusView = barView.findViewById(R.id.tv_float_status)

            val params = overlayParams(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            ).apply {
                x = dp(48f)
                y = dp(120f)
            }
            barParams = params

            barView.findViewById<View>(R.id.btn_float_drag)
                .setOnTouchListener(OverlayTouchListener(barView, params))

            val btnPlay = barView.findViewById<ImageButton>(R.id.btn_float_play)
            btnPlay.setOnClickListener { onPlayClicked() }
            btnPlay.setOnLongClickListener {
                ClickEngine.emergencyStop("长按停止键")
                true
            }

            barView.findViewById<ImageButton>(R.id.btn_float_add).setOnClickListener { addNewTarget() }

            val btnRemove = barView.findViewById<ImageButton>(R.id.btn_float_remove)
            btnRemove.setOnClickListener { removeLastTarget() }
            btnRemove.setOnLongClickListener {
                clearAllTargets()
                true
            }

            barView.findViewById<ImageButton>(R.id.btn_float_clock).setOnClickListener { toggleClock() }

            barView.findViewById<ImageButton>(R.id.btn_float_settings)?.setOnClickListener {
                val x = barParams?.x ?: 0
                val y = barParams?.y ?: 0
                showConfigDialog(x + dp(12f), y + dp(48f), target = null)
            }

            barView.findViewById<ImageButton>(R.id.btn_float_close).setOnClickListener { stopSelf() }

            windowManager.addView(barView, params)
            barView.post { clampOverlayPosition(params, barView) }
            renderStatus(force = true)
        } catch (e: Exception) {
            Log.e(TAG, "初始化悬浮窗失败", e)
            Toast.makeText(this, "悬浮窗启动失败，请检查手机【应用悬浮窗】权限", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun onPlayClicked() {
        if (ClickAccessibilityService.instance == null) {
            showNotice("请先在主页开启【无障碍服务】", toast = true)
            return
        }
        if (ClickEngine.state == ClickEngine.State.IDLE &&
            ClickEngine.targetCount() == 0 &&
            ClickEngine.config.mode != ClickMode.SMART_TEXT
        ) {
            showNotice("请点击 ➕ 添加点击标靶", toast = true)
            return
        }
        if (ClickEngine.state != ClickEngine.State.RUNNING) {
            ClickAccessibilityService.instance?.resetDispatchStats()
            setTargetsTouchable(false)
        }
        dismissConfigDialog()
        ClickEngine.togglePlay()
    }

    private class TargetEntry(
        val point: TargetPoint,
        val view: View,
        val params: WindowManager.LayoutParams
    )

    private fun updatePlayButtonState(state: ClickEngine.State) {
        val btnPlay = floatingBarView?.findViewById<ImageButton>(R.id.btn_float_play) ?: return
        btnPlay.setImageResource(
            if (state == ClickEngine.State.RUNNING || state == ClickEngine.State.SCHEDULED) {
                R.drawable.ic_pause
            } else {
                R.drawable.ic_play
            }
        )
    }

    private fun addNewTarget() {
        val anchor = barParams ?: return
        val barWidth = floatingBarView?.width?.takeIf { it > 0 } ?: dp(DIALOG_WIDTH_DP)
        val half = targetSizePx / 2f
        val existing = ClickEngine.snapshotTargets()
        val count = existing.size
        val step = dp(24f)
        val baseX = anchor.x + barWidth / 2f + dp(4f)
        val baseY = (anchor.y + dp(72f)).toFloat()
        val candidateX = (baseX + (count % 5) * step).coerceIn(half, (screenWidthPx - half).coerceAtLeast(half))
        val candidateY = (baseY + (count % 5) * step).coerceIn(half, (screenHeightPx - half).coerceAtLeast(half))

        val point = ClickEngine.addTarget(candidateX, candidateY)
        addTargetViewFor(point)
        onTargetsMutated()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addTargetViewFor(point: TargetPoint) {
        try {
            val targetView = inflateOverlay(R.layout.view_floating_target)
            val half = targetSizePx / 2f
            val params = overlayParams(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            ).apply {
                x = (point.x - half).toInt()
                y = (point.y - half).toInt()
            }

            targetView.setOnTouchListener(
                OverlayTouchListener(
                    view = targetView,
                    params = params,
                    onTap = {
                        if (ClickEngine.state != ClickEngine.State.RUNNING) {
                            showConfigDialog(params.x, params.y, target = point)
                        }
                    },
                    onLongPress = null,
                    onMoved = { commitTargetPosition(point, targetView, params) }
                )
            )

            windowManager.addView(targetView, params)
            targetViews.add(TargetEntry(point, targetView, params))
            targetView.post {
                clampOverlayPosition(params, targetView)
                commitTargetPosition(point, targetView, params)
                if (ClickEngine.state == ClickEngine.State.RUNNING) {
                    applyTouchable(targetView, params, false)
                }
            }
            refreshTargetLabel(point, targetView)
        } catch (e: Exception) {
            Log.e(TAG, "添加准星标靶失败", e)
        }
    }

    private fun commitTargetPosition(point: TargetPoint, view: View, params: WindowManager.LayoutParams) {
        val w = if (view.width > 0) view.width else targetSizePx
        val h = if (view.height > 0) view.height else targetSizePx
        ClickEngine.setTargetPosition(point, params.x + w / 2f, params.y + h / 2f)
    }

    private fun removeTargetPair(point: TargetPoint) {
        val entry = targetViews.firstOrNull { it.point === point }
        if (entry != null) {
            targetViews.remove(entry)
            runCatching { windowManager.removeView(entry.view) }
        }
        ClickEngine.removeTarget(point)
        onTargetsMutated()
        showNotice("已删除该标靶", toast = false)
    }

    private fun removeLastTarget() {
        if (targetViews.isEmpty()) {
            showNotice("没有可删除的标靶", toast = true)
            return
        }
        val last = targetViews.removeAt(targetViews.size - 1)
        runCatching { windowManager.removeView(last.view) }
        ClickEngine.removeLastTarget()
        onTargetsMutated()
    }

    private fun clearAllTargets() {
        removeAllTargetViews()
        ClickEngine.clearTargets()
        AppPrefs.clearTargets(this)
        renderStatus(force = true)
        persistTargets()
        showNotice("已清空全部标靶", toast = true)
    }

    private fun removeAllTargetViews() {
        for (entry in targetViews) runCatching { windowManager.removeView(entry.view) }
        targetViews.clear()
    }

    private fun onTargetsMutated() {
        refreshTargetLabels()
        renderStatus(force = true)
        persistTargets()
    }

    private fun refreshTargetLabels() {
        for (entry in targetViews) refreshTargetLabel(entry.point, entry.view)
    }

    private fun refreshTargetLabel(point: TargetPoint, view: View) {
        view.findViewById<TextView>(R.id.tv_target_index)?.text = point.id.toString()
    }

    private fun setTargetsTouchable(touchable: Boolean) {
        for (entry in targetViews) {
            applyTouchable(entry.view, entry.params, touchable)
        }
    }

    private fun applyTouchable(view: View, params: WindowManager.LayoutParams, touchable: Boolean) {
        val isCurrentlyTouchable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
        if (touchable == isCurrentlyTouchable) return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (t: Throwable) {
            Log.e(TAG, "切换标靶可触摸状态失败 touchable=$touchable", t)
        }
    }

    private fun showConfigDialog(anchorX: Int, anchorY: Int, target: TargetPoint?) {
        dismissConfigDialog()
        try {
            val dialogView = inflateOverlay(R.layout.view_dialog_point_config)
            configDialogView = dialogView

            val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)
            val tvSubtitle = dialogView.findViewById<TextView>(R.id.tv_dialog_subtitle)
            val tvVal = dialogView.findViewById<TextView>(R.id.tv_interval_value)
            val btnSave = dialogView.findViewById<Button>(R.id.btn_dialog_save)

            if (target == null) {
                tvTitle.text = "全局连点频率配置"
                tvSubtitle.text = "作为新标靶默认值，保存时应用到全部标靶"
            } else {
                tvTitle.text = "标靶 ${target.id} 点击间隔"
                tvSubtitle.text = "仅修改此标靶点击后的等待时间"
            }

            var current = (target?.delayMs ?: ClickEngine.config.intervalMs).coerceIn(10L, 5000L)

            fun updateDisplay(ms: Long) {
                current = ms.coerceIn(10L, 5000L)
                tvVal.text = "$current ms"
            }

            updateDisplay(current)
            dialogView.findViewById<Button>(R.id.btn_step_minus).setOnClickListener {
                updateDisplay(current - 10L)
            }
            dialogView.findViewById<Button>(R.id.btn_step_plus).setOnClickListener {
                updateDisplay(current + 10L)
            }
            dialogView.findViewById<Button>(R.id.btn_quick_30).setOnClickListener { updateDisplay(30L) }
            dialogView.findViewById<Button>(R.id.btn_quick_50).setOnClickListener { updateDisplay(50L) }
            dialogView.findViewById<Button>(R.id.btn_quick_100).setOnClickListener { updateDisplay(100L) }
            dialogView.findViewById<Button>(R.id.btn_quick_200).setOnClickListener { updateDisplay(200L) }
            dialogView.findViewById<ImageButton>(R.id.btn_dialog_close).setOnClickListener {
                dismissConfigDialog()
            }

            btnSave.text = if (target == null) "保存并应用到全部标靶" else "保存此标靶"
            btnSave.setOnClickListener {
                if (target == null) {
                    ClickEngine.applyDelayToAllTargets(current)
                    AppPrefs.saveConfig(this, ClickEngine.config)
                    showNotice("全局间隔 ${current}ms 已应用到全部标靶", toast = true)
                } else {
                    ClickEngine.setTargetDelay(target, current)
                    showNotice("标靶 ${target.id} 间隔已设为 ${current}ms", toast = true)
                }
                renderStatus(force = true)
                dismissConfigDialog()
            }

            val params = overlayParams(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            ).apply {
                x = anchorX.coerceIn(0, (screenWidthPx - dp(DIALOG_WIDTH_DP)).coerceAtLeast(0))
                y = anchorY.coerceIn(0, (screenHeightPx - dp(DIALOG_HEIGHT_DP)).coerceAtLeast(0))
            }

            dialogView.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    dismissConfigDialog()
                    true
                } else {
                    false
                }
            }

            windowManager.addView(dialogView, params)
        } catch (e: Exception) {
            Log.e(TAG, "显示配置弹窗失败", e)
        }
    }

    private fun dismissConfigDialog() {
        configDialogView?.let { runCatching { windowManager.removeView(it) } }
        configDialogView = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun toggleClock() {
        if (floatingClockView != null) {
            stopClock()
            updateTickerNeed()
            return
        }

        try {
            val clockView = inflateOverlay(R.layout.view_floating_clock)
            floatingClockView = clockView
            clockTimeView = clockView.findViewById(R.id.tv_clock_time)
            clockStatusView = clockView.findViewById(R.id.tv_clock_status)

            val anchor = barParams
            val params = overlayParams(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            ).apply {
                x = (anchor?.x ?: 0).coerceAtLeast(0)
                y = ((anchor?.y ?: 0) - dp(64f)).coerceAtLeast(0)
            }

            clockView.setOnTouchListener(
                OverlayTouchListener(
                    view = clockView,
                    params = params,
                    onTap = {
                        stopClock()
                        updateTickerNeed()
                    }
                )
            )

            windowManager.addView(clockView, params)
            clockView.post { clampOverlayPosition(params, clockView) }
            lastClockText = null
            renderClock()
            updateTickerNeed()
        } catch (e: Exception) {
            Log.e(TAG, "显示悬浮时钟失败", e)
        }
    }

    private fun renderClock() {
        val time = formatClockForDisplay(TimeSyncManager.nowMillis())
        if (time != lastClockText) {
            lastClockText = time
            clockTimeView?.text = time
        }

        val sameColor = Color.WHITE
        clockTimeView?.setTextColor(sameColor)
        clockStatusView?.setTextColor(sameColor)
        clockTimeView?.alpha = 1f
        clockStatusView?.alpha = 1f

        val status = clockStatusLabel()
        clockStatusView?.text = status
    }

    private fun clockStatusLabel(): String {
        if (ClickEngine.state == ClickEngine.State.SCHEDULED) {
            val target = ClickEngine.scheduledAtMillis
            if (target > 0) {
                val remaining = TimeSyncManager.remainingTo(target)
                if (remaining > 0) {
                    return "倒计时 ${formatCountdownForDisplay(remaining)}"
                }
            }
        }
        return TimeSyncManager.statusLabel()
    }

    private fun stopClock() {
        floatingClockView?.let { runCatching { windowManager.removeView(it) } }
        floatingClockView = null
        clockTimeView = null
        clockStatusView = null
        lastClockText = null
    }

    private fun renderStatus(force: Boolean = false) {
        val view = statusView ?: return
        val text = buildStatusText()
        if (force || text != lastStatusText) {
            lastStatusText = text
            view.text = text
        }
    }

    private fun buildStatusText(): String {
        transientNotice?.let { return it }
        val modeLabel = when (ClickEngine.config.mode) {
            ClickMode.RAPID_SINGLE ->
                if (ClickEngine.targetCount() > 1) "单点·仅①" else "单点"
            ClickMode.SEQUENCE -> "多点"
            ClickMode.SMART_TEXT -> "文本"
        }
        val engineLabel = when (ClickEngine.state) {
            ClickEngine.State.IDLE -> "待命"
            ClickEngine.State.PAUSED -> "已暂停"
            ClickEngine.State.SCHEDULED -> {
                val target = ClickEngine.scheduledAtMillis
                val remaining = if (target > 0) TimeSyncManager.remainingTo(target) else 0L
                if (remaining > 0) "定时 ${formatCountdownForDisplay(remaining)}" else "定时触发中"
            }
            ClickEngine.State.RUNNING -> {
                val stats = ClickAccessibilityService.instance?.dispatchStats()
                val rejected = stats?.rejected ?: 0L
                if (rejected > 0) "连点 ${stats?.accepted ?: 0} · 漏点 $rejected" else "连点中"
            }
        }
        return "$modeLabel · $engineLabel · 靶${ClickEngine.targetCount()} · ${ClickEngine.config.intervalMs}ms"
    }

    private fun showNotice(message: String, toast: Boolean) {
        transientNotice = message
        renderStatus(force = true)
        if (toast) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        pendingNotice?.let { mainHandler.removeCallbacks(it) }
        pendingNotice = Runnable {
            transientNotice = null
            pendingNotice = null
            renderStatus(force = true)
        }.also { mainHandler.postDelayed(it, NOTICE_HOLD_MS) }
    }

    private fun updateTickerNeed() {
        val needed = floatingClockView != null || ClickEngine.state != ClickEngine.State.IDLE
        if (!needed) {
            stopTicker()
            return
        }
        if (tickerRunnable != null) return
        tickerRunnable = object : Runnable {
            override fun run() {
                tickerTicks++
                val clockVisible = floatingClockView != null
                val engineActive = ClickEngine.state != ClickEngine.State.IDLE
                if (clockVisible) renderClock()
                if (engineActive && tickerTicks % STATUS_REFRESH_TICKS == 0) renderStatus()
                if (clockVisible || ClickEngine.state != ClickEngine.State.IDLE) {
                    mainHandler.postDelayed(this, CLOCK_TICK_MS)
                } else {
                    tickerRunnable = null
                }
            }
        }.also { mainHandler.post(it) }
    }

    private fun removeFloatingBar() {
        floatingBarView?.let { runCatching { windowManager.removeView(it) } }
        floatingBarView = null
        statusView = null
        barParams = null
    }

    private fun stopTicker() {
        tickerRunnable?.let { mainHandler.removeCallbacks(it) }
        tickerRunnable = null
    }

    private fun inflateOverlay(layout: Int): View {
        val themed = ContextThemeWrapper(this, R.style.Theme_KickClicker)
        return LayoutInflater.from(themed).inflate(layout, null)
    }

    private fun overlayParams(flags: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    private fun clampOverlayPosition(params: WindowManager.LayoutParams, view: View) {
        val w = if (view.width > 0) view.width else targetSizePx
        val h = if (view.height > 0) view.height else targetSizePx
        params.x = params.x.coerceIn(0, (screenWidthPx - w).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeightPx - h).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    private fun vibrateEmergency() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200L)
            }
        } catch (_: Exception) {
        }
    }

    private inner class OverlayTouchListener(
        private val view: View,
        private val params: WindowManager.LayoutParams,
        private val onTap: (() -> Unit)? = null,
        private val onLongPress: (() -> Unit)? = null,
        private val onMoved: (() -> Unit)? = null
    ) : View.OnTouchListener {

        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false
        private var longPressFired = false
        private var tracking = false

        private val longPressRunnable = Runnable {
            if (!moved) {
                longPressFired = true
                onLongPress?.invoke()
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    longPressFired = false
                    tracking = true
                    if (onLongPress != null) {
                        mainHandler.postDelayed(
                            longPressRunnable,
                            ViewConfiguration.getLongPressTimeout().toLong()
                        )
                    }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return false
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!moved && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        moved = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        val maxX = (screenWidthPx - view.width).coerceAtLeast(0)
                        val maxY = (screenHeightPx - view.height).coerceAtLeast(0)
                        params.x = (startX + dx.toInt()).coerceIn(0, maxX)
                        params.y = (startY + dy.toInt()).coerceIn(0, maxY)
                        runCatching { windowManager.updateViewLayout(view, params) }
                        onMoved?.invoke()
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (!moved && !longPressFired) {
                            onTap?.invoke()
                        } else if (moved) {
                            persistTargets()
                        }
                    }
                    return true
                }

                else -> return false
            }
        }
    }

    private fun formatClockForDisplay(epochMillis: Long): String {
        val total = epochMillis.coerceAtLeast(0L)
        val ms = (total % 1000L).toInt()
        val seconds = (total / 1000L % 60L).toInt()
        val minutes = (total / 60000L % 60L).toInt()
        val hours = (total / 3600000L % 24L).toInt()
        val milliText = when (MILLI_DIGITS) {
            1 -> (ms / 100).toString()
            2 -> String.format(Locale.US, "%02d", ms / 10)
            3 -> String.format(Locale.US, "%03d", ms)
            else -> String.format(Locale.US, "%03d", ms)
        }
        return String.format(Locale.US, "%02d:%02d:%02d.%s", hours, minutes, seconds, milliText)
    }

    private fun formatCountdownForDisplay(remainingMillis: Long): String {
        val total = remainingMillis.coerceAtLeast(0L)
        val ms = (total % 1000L).toInt()
        val seconds = (total / 1000L % 60L).toInt()
        val minutes = (total / 60000L % 60L).toInt()
        val hours = (total / 3600000L % 24L).toInt()
        val milliText = when (MILLI_DIGITS) {
            1 -> (ms / 100).toString()
            2 -> String.format(Locale.US, "%02d", ms / 10)
            3 -> String.format(Locale.US, "%03d", ms)
            else -> String.format(Locale.US, "%03d", ms)
        }
        return String.format(Locale.US, "%02d:%02d:%02d.%s", hours, minutes, seconds, milliText)
    }
}
