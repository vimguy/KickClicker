package com.antigravity.kickclicker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.antigravity.kickclicker.R
import com.antigravity.kickclicker.data.AppPrefs
import com.antigravity.kickclicker.data.ClickMode
import com.antigravity.kickclicker.engine.ClickEngine
import com.antigravity.kickclicker.service.ClickAccessibilityService
import com.antigravity.kickclicker.service.FloatingControlService
import com.antigravity.kickclicker.time.TimeSyncManager
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var btnOverlay: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnBattery: Button
    private lateinit var btnToggleFloating: Button
    private lateinit var btnExitClean: Button
    private lateinit var btnSyncTime: Button
    private lateinit var tvTimeSyncStatus: TextView
    private lateinit var tvCurrentBeijingTime: TextView

    private lateinit var btnIntervalMinus: Button
    private lateinit var tvIntervalVal: TextView
    private lateinit var btnIntervalPlus: Button
    private lateinit var btnQuick30: Button
    private lateinit var btnQuick50: Button
    private lateinit var btnQuick100: Button
    private lateinit var btnQuick200: Button

    private lateinit var btnModeSingle: Button
    private lateinit var btnModeMulti: Button
    private lateinit var btnModeSmart: Button
    private lateinit var etSmartText: EditText
    private lateinit var btnAntiBan: Button

    private lateinit var btnBurstMinus: Button
    private lateinit var tvBurstVal: TextView
    private lateinit var btnBurstPlus: Button
    private lateinit var btnRepeatMinus: Button
    private lateinit var tvRepeatVal: TextView
    private lateinit var btnRepeatPlus: Button
    private lateinit var btnPressMinus: Button
    private lateinit var tvPressVal: TextView
    private lateinit var btnPressPlus: Button

    private lateinit var etScheduleTime: EditText
    private lateinit var btnStartSchedule: Button
    private lateinit var tvScheduleStatus: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastClockText: String? = null
    private var lastFloatingButtonText: String? = null
    private var lastEngineState: ClickEngine.State? = null
    private var suppressTextWatcher = false
    private var pendingAutoStartOverlay = false
    private var millisDisplayDigits = 1
    private var scheduleTimes = mutableListOf<String>()
    private var selectedScheduleIndex = 0

    private val uiTicker = object : Runnable {
        override fun run() {
            tickUi()
            mainHandler.postDelayed(this, UI_TICK_MS)
        }
    }

    private lateinit var overlayLauncher: ActivityResultLauncher<Intent>
    private lateinit var accessibilityLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationLauncher: ActivityResultLauncher<String>
    private var lastScheduleButtonLabel: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        TimeSyncManager.init(this)
        AppPrefs.restoreConfig(this, ClickEngine.config)
        loadSavedScheduleTimes()

        overlayLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { onPermissionReturned() }
        accessibilityLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { onPermissionReturned() }
        notificationLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

        initViews()
        setupListeners()
        bindScheduleInput()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsState()
        updateFloatingButtonText()
        bindConfigToViews()
        updateSyncStatus()
        mainHandler.post(uiTicker)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(uiTicker)
        AppPrefs.saveConfig(this, ClickEngine.config)
    }

    private fun initViews() {
        btnOverlay = findViewById(R.id.btn_permission_overlay)
        btnAccessibility = findViewById(R.id.btn_permission_accessibility)
        btnBattery = findViewById(R.id.btn_permission_battery)
        btnToggleFloating = findViewById(R.id.btn_toggle_floating)
        btnExitClean = findViewById(R.id.btn_exit_clean)
        btnSyncTime = findViewById(R.id.btn_sync_time)
        tvTimeSyncStatus = findViewById(R.id.tv_time_sync_status)
        tvCurrentBeijingTime = findViewById(R.id.tv_current_beijing_time)

        btnIntervalMinus = findViewById(R.id.btn_main_interval_minus)
        tvIntervalVal = findViewById(R.id.tv_main_interval_val)
        btnIntervalPlus = findViewById(R.id.btn_main_interval_plus)
        btnQuick30 = findViewById(R.id.btn_main_quick_30)
        btnQuick50 = findViewById(R.id.btn_main_quick_50)
        btnQuick100 = findViewById(R.id.btn_main_quick_100)
        btnQuick200 = findViewById(R.id.btn_main_quick_200)

        btnModeSingle = findViewById(R.id.btn_mode_single)
        btnModeMulti = findViewById(R.id.btn_mode_multi)
        btnModeSmart = findViewById(R.id.btn_mode_smart)
        etSmartText = findViewById(R.id.et_smart_text)
        btnAntiBan = findViewById(R.id.btn_toggle_antiban)

        btnBurstMinus = findViewById(R.id.btn_burst_minus)
        tvBurstVal = findViewById(R.id.tv_burst_val)
        btnBurstPlus = findViewById(R.id.btn_burst_plus)
        btnRepeatMinus = findViewById(R.id.btn_repeat_minus)
        tvRepeatVal = findViewById(R.id.tv_repeat_val)
        btnRepeatPlus = findViewById(R.id.btn_repeat_plus)
        btnPressMinus = findViewById(R.id.btn_press_minus)
        tvPressVal = findViewById(R.id.tv_press_val)
        btnPressPlus = findViewById(R.id.btn_press_plus)

        etScheduleTime = findViewById(R.id.et_schedule_time)
        btnStartSchedule = findViewById(R.id.btn_start_schedule)
        tvScheduleStatus = findViewById(R.id.tv_schedule_status)
    }

    private fun setupListeners() {
        fun setInterval(ms: Long) {
            val clamped = ms.coerceIn(10L, 5000L)
            ClickEngine.applyDelayToAllTargets(clamped)
            AppPrefs.saveConfig(this, ClickEngine.config)
            tvIntervalVal.text = "${ClickEngine.config.intervalMs} ms"
        }

        btnIntervalMinus.setOnClickListener { setInterval(ClickEngine.config.intervalMs - 10L) }
        btnIntervalPlus.setOnClickListener { setInterval(ClickEngine.config.intervalMs + 10L) }
        btnQuick30.setOnClickListener { setInterval(30L) }
        btnQuick50.setOnClickListener { setInterval(50L) }
        btnQuick100.setOnClickListener { setInterval(100L) }
        btnQuick200.setOnClickListener { setInterval(200L) }

        btnModeSingle.setOnClickListener { selectMode(ClickMode.RAPID_SINGLE) }
        btnModeMulti.setOnClickListener { selectMode(ClickMode.SEQUENCE) }
        btnModeSmart.setOnClickListener { selectMode(ClickMode.SMART_TEXT) }

        etSmartText.addTextChangedListener(afterTextChanged = { text ->
            if (!suppressTextWatcher) {
                ClickEngine.config.smartTargetText = text?.toString()?.trim().orEmpty()
                AppPrefs.saveConfig(this, ClickEngine.config)
            }
        })

        btnAntiBan.setOnClickListener {
            ClickEngine.config.antiBanEnabled = !ClickEngine.config.antiBanEnabled
            AppPrefs.saveConfig(this, ClickEngine.config)
            bindAntiBan()
        }

        btnBurstMinus.setOnClickListener { stepBurst(-1) }
        btnBurstPlus.setOnClickListener { stepBurst(1) }
        btnRepeatMinus.setOnClickListener { stepRepeat(-1) }
        btnRepeatPlus.setOnClickListener { stepRepeat(1) }
        btnPressMinus.setOnClickListener { stepPress(-10L) }
        btnPressPlus.setOnClickListener { stepPress(10L) }

        btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                overlayLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                Toast.makeText(this, "悬浮窗权限已开启，可在系统设置中关闭", Toast.LENGTH_SHORT).show()
                overlayLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                )
            }
        }

        btnAccessibility.setOnClickListener {
            if (ClickAccessibilityService.isServiceEnabled(this)) {
                if (ClickEngine.state == ClickEngine.State.RUNNING) {
                    ClickEngine.stop()
                    Toast.makeText(this, "已先停止连点", Toast.LENGTH_SHORT).show()
                }
                if (ClickAccessibilityService.disableSelfIfRunning()) {
                    Toast.makeText(this, "无障碍服务已主动关闭", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "请在列表中关闭【KickClicker】无障碍服务", Toast.LENGTH_LONG).show()
                }
            } else {
                pendingAutoStartOverlay = true
                accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "请在列表中找到【KickClicker】并开启服务", Toast.LENGTH_LONG).show()
            }
            checkPermissionsState()
        }

        btnBattery.setOnClickListener {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                @SuppressLint("BatteryLife")
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "已加入白名单；关闭控制台即可释放全部后台", Toast.LENGTH_SHORT).show()
            }
        }

        btnSyncTime.setOnClickListener {
            if (!btnSyncTime.isEnabled) return@setOnClickListener
            btnSyncTime.isEnabled = false
            btnSyncTime.text = "校时中…"
            tvTimeSyncStatus.text = "正在通过 SNTP 对时…"
            tvTimeSyncStatus.setTextColor(color(R.color.accent_purple))
            lifecycleScope.launch {
                val result = TimeSyncManager.syncWithNetwork()
                btnSyncTime.isEnabled = true
                btnSyncTime.text = "一键校时"
                val offset = result.getOrNull()
                if (offset != null) {
                    val sign = if (offset >= 0) "+" else ""
                    tvTimeSyncStatus.text =
                        "${TimeSyncManager.statusLabel()} · 本机偏差 ${sign}${offset} ms"
                    tvTimeSyncStatus.setTextColor(color(R.color.accent_green))
                } else {
                    tvTimeSyncStatus.text = result.exceptionOrNull()?.message
                        ?: "校准失败，使用本机时钟"
                    tvTimeSyncStatus.setTextColor(color(R.color.accent_red))
                }
            }
        }

        btnStartSchedule.setOnClickListener { onScheduleClicked() }

        btnToggleFloating.setOnClickListener {
            if (FloatingControlService.isRunning) {
                stopService(Intent(this, FloatingControlService::class.java))
                Toast.makeText(this, "悬浮控制台已关闭", Toast.LENGTH_SHORT).show()
                updateFloatingButtonText()
                return@setOnClickListener
            }

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予【悬浮窗权限】", Toast.LENGTH_SHORT).show()
                pendingAutoStartOverlay = true
                btnOverlay.performClick()
                return@setOnClickListener
            }

            if (!ClickAccessibilityService.isServiceEnabled(this)) {
                Toast.makeText(this, "请先开启【无障碍服务】以模拟点击", Toast.LENGTH_SHORT).show()
                btnAccessibility.performClick()
                return@setOnClickListener
            }

            startFloatingConsole()
        }

        btnExitClean.setOnClickListener {
            ClickEngine.stop()
            if (FloatingControlService.isRunning) {
                stopService(Intent(this, FloatingControlService::class.java))
            }
            ClickAccessibilityService.disableSelfIfRunning()
            AppPrefs.saveConfig(this, ClickEngine.config)
            Toast.makeText(this, "所有服务已完全关闭", Toast.LENGTH_SHORT).show()
            finishAffinity()
        }
    }

    private fun startFloatingConsole() {
        ContextCompat.startForegroundService(this, Intent(this, FloatingControlService::class.java))
        mainHandler.postDelayed({ updateFloatingButtonText() }, 250L)
        Toast.makeText(this, "悬浮控制台已启动，返回桌面或抢票软件使用", Toast.LENGTH_LONG).show()
        updateFloatingButtonText()
    }

    private fun onPermissionReturned() {
        checkPermissionsState()
        updateFloatingButtonText()
        if (!pendingAutoStartOverlay) return
        val ready = Settings.canDrawOverlays(this) &&
            ClickAccessibilityService.isServiceEnabled(this)
        if (ready && !FloatingControlService.isRunning) {
            pendingAutoStartOverlay = false
            startFloatingConsole()
        }
    }

    private fun selectMode(mode: ClickMode) {
        ClickEngine.config.mode = mode
        AppPrefs.saveConfig(this, ClickEngine.config)
        bindModeButtons()
        when (mode) {
            ClickMode.RAPID_SINGLE -> Toast.makeText(
                this, "单点连发：每点连发 ${ClickEngine.config.burstCount} 次", Toast.LENGTH_SHORT
            ).show()
            ClickMode.SEQUENCE -> Toast.makeText(
                this, "多点顺序：按①②③依次连发并按各自间隔切换", Toast.LENGTH_SHORT
            ).show()
            ClickMode.SMART_TEXT -> Toast.makeText(
                this, "智能文本：优先点击含关键词的控件，未命中回退标靶①", Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun stepBurst(delta: Int) {
        ClickEngine.config.burstCount = (ClickEngine.config.burstCount + delta).coerceIn(1, 5)
        AppPrefs.saveConfig(this, ClickEngine.config)
        tvBurstVal.text = ClickEngine.config.burstCount.toString()
    }

    private fun stepRepeat(delta: Int) {
        ClickEngine.config.repeatCount = (ClickEngine.config.repeatCount + delta).coerceIn(0, 999)
        AppPrefs.saveConfig(this, ClickEngine.config)
        tvRepeatVal.text = ClickEngine.config.repeatCount.toString()
    }

    private fun stepPress(delta: Long) {
        val next = (ClickEngine.config.pressDurationMs + delta).coerceIn(1L, 500L)
        ClickEngine.applyPressDurationToAllTargets(next)
        AppPrefs.saveConfig(this, ClickEngine.config)
        tvPressVal.text = ClickEngine.config.pressDurationMs.toString()
    }

    private fun bindScheduleInput() {
        val current = if (scheduleTimes.isNotEmpty()) scheduleTimes[selectedScheduleIndex.coerceIn(0, scheduleTimes.size - 1)] else ""
        etScheduleTime.setText(current)
    }

    private fun loadSavedScheduleTimes() {
        val prefs = getSharedPreferences("kick_clicker_schedule", MODE_PRIVATE)
        val raw = prefs.getString("times", null) ?: return
        val parsed = raw.split(Regex("[,;\\n\\r]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeScheduleTime(it) }
            .filter { it != null }
        if (parsed.isEmpty()) {
            scheduleTimes.clear()
            selectedScheduleIndex = 0
            return
        }
        scheduleTimes.clear()
        scheduleTimes.addAll(parsed.filterNotNull())
        selectedScheduleIndex = prefs.getInt("selected_index", 0).coerceIn(0, scheduleTimes.size - 1)
    }

    private fun saveScheduleTimes() {
        val prefs = getSharedPreferences("kick_clicker_schedule", MODE_PRIVATE)
        prefs.edit()
            .putString("times", scheduleTimes.joinToString(","))
            .putInt("selected_index", selectedScheduleIndex.coerceIn(0, (scheduleTimes.size - 1).coerceAtLeast(0)))
            .apply()
    }

    private fun parseScheduleEntries(input: String): List<String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split(Regex("[,;\\n\\r]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { normalizeScheduleTime(it) }
            .filter { it != null }
            .map { it!! }
    }

    private fun normalizeScheduleTime(value: String): String? {
        val cleaned = value.trim()
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(":")
        if (parts.size !in 2..3) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        var s = 0
        var ms = 0
        if (parts.size == 3) {
            val secParts = parts[2].split(".")
            if (secParts.size !in 1..2) return null
            s = secParts[0].toIntOrNull() ?: return null
            if (secParts.size == 2) {
                ms = secParts[1].padEnd(3, '0').take(3).toIntOrNull() ?: return null
            }
        }
        if (h !in 0..23 || m !in 0..59 || s !in 0..59 || ms !in 0..999) return null
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, ms)
    }

    private fun onScheduleClicked() {
        when (ClickEngine.state) {
            ClickEngine.State.SCHEDULED -> {
                ClickEngine.stop()
                tvScheduleStatus.text = "已取消定时"
                tvScheduleStatus.setTextColor(color(R.color.text_secondary))
                updateScheduleButton()
                return
            }
            ClickEngine.State.RUNNING -> {
                Toast.makeText(this, "连点进行中，请先用播放键暂停或停止", Toast.LENGTH_SHORT).show()
                return
            }
            else -> Unit
        }

        if (!ClickAccessibilityService.isServiceEnabled(this)) {
            Toast.makeText(this, "请先开启【无障碍服务】", Toast.LENGTH_SHORT).show()
            pendingAutoStartOverlay = false
            btnAccessibility.performClick()
            return
        }
        if (ClickEngine.targetCount() == 0 && ClickEngine.config.mode != ClickMode.SMART_TEXT) {
            Toast.makeText(this, "请先在悬浮控制台添加标靶", Toast.LENGTH_SHORT).show()
            return
        }

        val entries = parseScheduleEntries(etScheduleTime.text.toString())
        if (entries.isEmpty()) {
            if (scheduleTimes.isEmpty()) {
                tvScheduleStatus.text = "时间格式应为 HH:mm:ss.SSS（例：20:00:00.000）"
                tvScheduleStatus.setTextColor(color(R.color.accent_red))
                return
            }
            selectedScheduleIndex = selectedScheduleIndex.coerceIn(0, scheduleTimes.size - 1)
        }
        if (entries.isNotEmpty()) {
            scheduleTimes.clear()
            scheduleTimes.addAll(entries)
            selectedScheduleIndex = 0
            saveScheduleTimes()
            etScheduleTime.setText(scheduleTimes.joinToString(", "))
        }

        val currentTarget = scheduleTimes.getOrNull(selectedScheduleIndex)
            ?: scheduleTimes.firstOrNull()
            ?: return

        val target = parseBeijingTime(currentTarget)
        if (target == null) {
            tvScheduleStatus.text = "时间格式应为 HH:mm:ss.SSS（例：20:00:00.000）"
            tvScheduleStatus.setTextColor(color(R.color.accent_red))
            return
        }
        if (!TimeSyncManager.isSynchronized) {
            Toast.makeText(this, "尚未对时，定时精度依赖本机时钟", Toast.LENGTH_LONG).show()
        }
        ClickEngine.scheduleStart(target)
        tvScheduleStatus.setTextColor(color(R.color.accent_purple))
        tvScheduleStatus.text = "已选 ${formatBeijingClock(target)} · 倒计时启动"
        updateScheduleButton()
    }

    private fun parseBeijingTime(input: String): Long? {
        val parts = input.trim().split(":")
        if (parts.isEmpty()) return null
        if (parts.size !in 2..3) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        var s = 0
        var ms = 0
        if (parts.size == 3) {
            val secParts = parts[2].split(".")
            s = secParts[0].toIntOrNull() ?: return null
            if (secParts.size > 1) {
                ms = secParts[1].padEnd(3, '0').take(3).toIntOrNull() ?: return null
            }
        }
        if (h !in 0..23 || m !in 0..59 || s !in 0..59 || ms !in 0..999) return null

        val now = TimeSyncManager.nowMillis()
        val beijingNow = now + BEIJING_OFFSET_MS
        val midnightUtc = beijingNow / DAY_MS * DAY_MS - BEIJING_OFFSET_MS
        var target = midnightUtc + h * 3600000L + m * 60000L + s * 1000L + ms
        if (target <= now) target += DAY_MS
        return target
    }

    private fun updateScheduleButton() {
        val label = if (ClickEngine.state == ClickEngine.State.SCHEDULED) "取消定时" else "开启倒计时"
        if (label != lastScheduleButtonLabel) {
            lastScheduleButtonLabel = label
            btnStartSchedule.text = label
        }
    }

    private fun tickUi() {
        val time = formatBeijingClock(TimeSyncManager.nowMillis())
        if (time != lastClockText) {
            lastClockText = time
            tvCurrentBeijingTime.text = time
        }

        val state = ClickEngine.state
        if (state != lastEngineState) {
            lastEngineState = state
            updateScheduleButton()
            when (state) {
                ClickEngine.State.RUNNING -> {
                    tvScheduleStatus.text = "连点进行中"
                    tvScheduleStatus.setTextColor(color(R.color.accent_green))
                }
                ClickEngine.State.PAUSED -> {
                    tvScheduleStatus.text = "已暂停（再按播放键恢复）"
                    tvScheduleStatus.setTextColor(color(R.color.accent_orange))
                }
                else -> Unit
            }
        }

        if (state == ClickEngine.State.SCHEDULED) {
            val remaining = TimeSyncManager.remainingTo(ClickEngine.scheduledAtMillis)
            tvScheduleStatus.text = if (remaining > 0) {
                "定时 ${formatBeijingClock(ClickEngine.scheduledAtMillis)} · 倒计时 ${formatCountdown(remaining)}"
            } else {
                "定时已触发"
            }
        }

        updateFloatingButtonText()
    }

    private fun bindConfigToViews() {
        val config = ClickEngine.config
        suppressTextWatcher = true
        tvIntervalVal.text = "${config.intervalMs} ms"
        etSmartText.setText(config.smartTargetText)
        suppressTextWatcher = false
        tvBurstVal.text = config.burstCount.toString()
        tvRepeatVal.text = config.repeatCount.toString()
        tvPressVal.text = config.pressDurationMs.toString()
        bindModeButtons()
        bindAntiBan()
        updateScheduleButton()
        if (ClickEngine.state != ClickEngine.State.SCHEDULED) {
            tvScheduleStatus.text = "未设定"
            tvScheduleStatus.setTextColor(color(R.color.text_secondary))
        }
    }

    private fun bindModeButtons() {
        val mode = ClickEngine.config.mode
        styleModeButton(btnModeSingle, mode == ClickMode.RAPID_SINGLE)
        styleModeButton(btnModeMulti, mode == ClickMode.SEQUENCE)
        styleModeButton(btnModeSmart, mode == ClickMode.SMART_TEXT)
    }

    private fun styleModeButton(button: Button, selected: Boolean) {
        button.setBackgroundResource(
            if (selected) R.drawable.bg_button_primary else R.drawable.bg_button_secondary
        )
        button.setTextColor(
            color(if (selected) R.color.on_primary else R.color.text_secondary)
        )
    }

    private fun bindAntiBan() {
        val enabled = ClickEngine.config.antiBanEnabled
        btnAntiBan.text = if (enabled) "已开启" else "已关闭"
        btnAntiBan.setTextColor(color(if (enabled) R.color.accent_green else R.color.text_secondary))
    }

    private fun updateSyncStatus() {
        val offset = TimeSyncManager.offsetMillis
        val label = TimeSyncManager.statusLabel()
        if (TimeSyncManager.isSynchronized) {
            val sign = if (offset >= 0) "+" else ""
            tvTimeSyncStatus.text = "$label · 本机偏差 $sign${offset} ms"
            tvTimeSyncStatus.setTextColor(color(R.color.accent_green))
        } else {
            tvTimeSyncStatus.text = "$label · 点击一键校时"
            tvTimeSyncStatus.setTextColor(color(R.color.text_secondary))
        }
    }

    private fun checkPermissionsState() {
        if (Settings.canDrawOverlays(this)) {
            btnOverlay.text = "已授权(可关闭)"
            btnOverlay.setTextColor(color(R.color.accent_green))
        } else {
            btnOverlay.text = "去授权"
            btnOverlay.setTextColor(color(R.color.primary))
        }

        if (ClickAccessibilityService.isServiceEnabled(this)) {
            btnAccessibility.text = "已开启(点击关闭)"
            btnAccessibility.setTextColor(color(R.color.accent_green))
        } else {
            btnAccessibility.text = "去开启"
            btnAccessibility.setTextColor(color(R.color.primary))
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            btnBattery.text = "已优化"
            btnBattery.setTextColor(color(R.color.accent_green))
        } else {
            btnBattery.text = "去设置"
            btnBattery.setTextColor(color(R.color.primary))
        }
    }

    private fun updateFloatingButtonText() {
        val running = FloatingControlService.isRunning
        val label = if (running) "关闭悬浮控制台" else "启动悬浮控制台"
        if (label != lastFloatingButtonText) {
            lastFloatingButtonText = label
            btnToggleFloating.text = label
            btnToggleFloating.setBackgroundResource(
                if (running) R.drawable.bg_button_secondary else R.drawable.bg_button_primary
            )
            btnToggleFloating.setTextColor(
                color(if (running) R.color.accent_red else R.color.on_primary)
            )
        }
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun formatBeijingClock(epochMillis: Long): String {
        return formatTimeOfDay(epochMillis + BEIJING_OFFSET_MS, millisDisplayDigits)
    }

    private fun formatCountdown(remainingMillis: Long): String {
        return formatTimeOfDay(remainingMillis.coerceAtLeast(0L), millisDisplayDigits)
    }

    private fun formatTimeOfDay(rawEpochMs: Long, digits: Int): String {
        val total = rawEpochMs.coerceAtLeast(0L)
        val ms = (total % 1000L).toInt()
        val seconds = (total / 1000L % 60L).toInt()
        val minutes = (total / 60000L % 60L).toInt()
        val hours = (total / 3600000L % 24L).toInt()
        val milliText = when (digits) {
            1 -> (ms / 100).toString()
            2 -> String.format(Locale.US, "%02d", ms / 10)
            3 -> String.format(Locale.US, "%03d", ms)
            else -> String.format(Locale.US, "%03d", ms)
        }
        return String.format(Locale.US, "%02d:%02d:%02d.%s", hours, minutes, seconds, milliText)
    }

    private companion object {
        private const val UI_TICK_MS = 200L
        private const val BEIJING_OFFSET_MS = 8 * 60 * 60 * 1000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
