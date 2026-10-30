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

    private lateinit var btnTimePrecision1: Button
    private lateinit var btnTimePrecision2: Button
    private lateinit var btnTimePrecision3: Button

    private lateinit var etScheduleTime1: EditText
    private lateinit var etScheduleTime2: EditText
    private lateinit var etScheduleTime3: EditText
    private lateinit var etScheduleTime4: EditText
    private lateinit var scheduleTimeViews: List<EditText>
    private var selectedScheduleIndex: Int = 0

    private lateinit var btnStartSchedule: Button
    private lateinit var tvScheduleStatus: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastClockText: String? = null
    private var lastFloatingButtonText: String? = null
    private var lastEngineState: ClickEngine.State? = null
    private var suppressTextWatcher = false
    private var pendingAutoStartOverlay = false

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
        bindConfigToViews()
        bindTimePrecisionButtons()
        bindScheduleTimeState()
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

        btnTimePrecision1 = findViewById(R.id.btn_time_precision_1)
        btnTimePrecision2 = findViewById(R.id.btn_time_precision_
