package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.database.AppDatabase
import com.example.database.AppConfiguration
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayControlService : Service() {

    companion object {
        var instance: OverlayControlService? = null
            private set
    }

    fun isControlPanelVisible(): Boolean {
        return controlPanel != null
    }

    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var logsJob: Job? = null

    // UI Views
    private var floatingBubble: FrameLayout? = null
    private var controlPanel: FrameLayout? = null

    private var autoBuyActive = false
    private var currentConfig: AppConfiguration? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // Start Foreground Service with proper notification channel & media projection type
        createNotificationChannel()
        val notification = androidx.core.app.NotificationCompat.Builder(this, "OverlayServiceChannel")
            .setContentTitle("Lot Monitor Screen Overlay")
            .setContentText("Actively scanning game screen and logging found lots")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                101,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(101, notification)
        }

        // Load initial config from Room database
        serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            db.configurationDao().getConfigurationFlow().onEach { config ->
                if (config != null) {
                    val wasCalibrationEnabled = currentConfig?.isCalibrationEnabled ?: false
                    currentConfig = config
                    autoBuyActive = config.autoBuyEnabled
                    updatePanelUi()
                    updateBubbleColor()
                    
                    if (config.isCalibrationEnabled && !wasCalibrationEnabled) {
                        AutoBuyerLogs.addLogBlocking("Режим калибровки подготовлен. Сверните бот, перейдите в игру и нажмите на круглую кнопку бота!")
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(
                                applicationContext,
                                "Режим калибровки готов! Сверните бот, откройте игру и нажмите кружок бота для начала",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    } else if (!config.isCalibrationEnabled && wasCalibrationEnabled) {
                        stopCalibrationFlow()
                    }
                }
            }.launchIn(serviceScope)
        }

        createFloatingBubble()
    }

    private fun updateBubbleColor() {
        val bubble = floatingBubble ?: return
        val size = (50 * resources.displayMetrics.density).toInt()
        val config = currentConfig
        val strokeColor = when {
            config?.isCalibrationEnabled == true -> "#FFEB3B" // Yellow for calibration ready
            autoBuyActive -> "#4CAF50" // Green for active automation
            else -> "#448AFF" // Blue for idle/passive
        }
        val shape = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#121212")) // Slate dark grey
            setStroke((2 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeColor))
            cornerRadius = size / 2f
        }
        bubble.background = shape
        
        // Find and update the inner icon's color filter to give direct visual state feedback
        val icon = bubble.getChildAt(0) as? ImageView
        if (icon != null) {
            val iconColor = when {
                config?.isCalibrationEnabled == true -> Color.parseColor("#FFEB3B")
                autoBuyActive -> Color.parseColor("#4CAF50")
                else -> Color.WHITE
            }
            icon.setColorFilter(iconColor)
        }
        bubble.invalidate()
    }

    override fun onDestroy() {
        instance = null
        removeFloatingBubble()
        removeControlPanel()
        stopCalibrationFlow()
        logsJob?.cancel()
        super.onDestroy()
    }

    /**
     * Create the primary floating overlay icon/bubble.
     */
    private fun createFloatingBubble() {
        if (floatingBubble != null) return // Safety check: do not recreate if it already exists
        if (!android.provider.Settings.canDrawOverlays(this)) {
            AutoBuyerLogs.addLogBlocking("Overlay permission not granted. Floating bubble will not be shown.")
            return
        }
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        val bubble = FrameLayout(this).apply {
            // Inner visual background circle
            val size = (50 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size)
            
            // Set circle drawable programmatically
            val strokeColor = if (autoBuyActive) "#4CAF50" else "#448AFF"
            val shape = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#121212")) // Slate dark grey
                setStroke((2 * resources.displayMetrics.density).toInt(), Color.parseColor(strokeColor)) // Green if active, Blue if idle
                cornerRadius = size / 2f
            }
            background = shape

            // Inner Icon
            val icon = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_dialog_info)
                setColorFilter(Color.WHITE)
                val iconSize = (24 * resources.displayMetrics.density).toInt()
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            }
            addView(icon)
        }

        // Make draggable & clickable
        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoving = false
            private var downTime = 0L
            private val handler = android.os.Handler(android.os.Looper.getMainLooper())
            private var isLongPressed = false

            private val longPressRunnable = Runnable {
                isLongPressed = true
                showControlPanel()
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        downTime = System.currentTimeMillis()
                        isLongPressed = false
                        handler.postDelayed(longPressRunnable, 1000)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            handler.removeCallbacks(longPressRunnable)
                            params.x = (initialX + dx).toInt()
                            params.y = (initialY + dy).toInt()
                            windowManager.updateViewLayout(bubble, params)
                            isMoving = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(longPressRunnable)
                        if (event.action == MotionEvent.ACTION_UP && !isMoving && !isLongPressed) {
                            val config = currentConfig
                            if (config != null && config.isCalibrationEnabled) {
                                if (calibrationOverlay == null) {
                                    startCalibrationFlow()
                                }
                            } else {
                                toggleAutoBuy()
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        floatingBubble = bubble
        windowManager.addView(bubble, params)
        updateBubbleColor()
    }

    private fun removeFloatingBubble() {
        floatingBubble?.let {
            windowManager.removeView(it)
            floatingBubble = null
        }
    }

    /**
     * Spawns the central control panel overlay.
     */
    private fun showControlPanel() {
        if (controlPanel != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            AutoBuyerLogs.addLogBlocking("Overlay permission not granted. Control panel will not be shown.")
            return
        }
        removeFloatingBubble()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val width = (280 * resources.displayMetrics.density).toInt()
        val height = (360 * resources.displayMetrics.density).toInt()

        val params = WindowManager.LayoutParams(
            width,
            height,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, // Allow touches outside
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val panel = FrameLayout(this).apply {
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#1C1B1F")) // Material M3 dark background
                cornerRadius = 16 * resources.displayMetrics.density
                setStroke((1 * resources.displayMetrics.density).toInt(), Color.parseColor("#49454F"))
            }
            background = bg
            setPadding(16, 16, 16, 16)
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header View
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val title = TextView(this).apply {
            text = "Lot Monitor Panel"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)

        val closeBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            setPadding(8, 8, 8, 8)
            setOnClickListener {
                removeControlPanel()
                createFloatingBubble()
            }
        }
        header.addView(closeBtn)

        // Make control panel draggable via header touch
        header.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        windowManager.updateViewLayout(panel, params)
                        return true
                    }
                }
                return false
            }
        })

        rootLayout.addView(header)

        // Configuration State Text
        val configText = TextView(this).apply {
            text = "Config: Loading..."
            setTextColor(Color.parseColor("#D0BCFF"))
            textSize = 12f
            setPadding(0, 8, 0, 8)
        }
        rootLayout.addView(configText)

        // Horizontal Row for Buttons
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 4, 0, 4)
            }
        }

        // Start / Stop Toggle Button
        val toggleButton = Button(this).apply {
            text = "START MONITORING"
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                rightMargin = (8 * resources.displayMetrics.density).toInt()
            }
            setOnClickListener {
                toggleAutoBuy()
            }
        }
        buttonsRow.addView(toggleButton)

        // Settings Button to launch Main App
        val settingsBtn = Button(this).apply {
            text = "⚙️ SETTINGS"
            setBackgroundColor(Color.parseColor("#444349"))
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val intent = Intent(this@OverlayControlService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                // Collapse panel to bubble when opening settings to not obscure the screen
                removeControlPanel()
                createFloatingBubble()
            }
        }
        buttonsRow.addView(settingsBtn)

        rootLayout.addView(buttonsRow)

        // Live Log Viewer
        val logHeaderLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 4)
        }

        val logHeader = TextView(this).apply {
            text = "Live Log Console:"
            setTextColor(Color.parseColor("#9E9E9E"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        logHeaderLayout.addView(logHeader)

        val copyBtn = TextView(this).apply {
            text = "COPY ALL"
            setTextColor(Color.parseColor("#BB86FC")) // Elegant purple accent color
            setPadding(12, 6, 12, 6)
            textSize = 12f
            setOnClickListener {
                val configHeader = currentConfig?.let { config ->
                    buildString {
                        appendLine("=== НАСТРОЙКИ БОТА (OVERLAY CONFIG) ===")
                        appendLine("Целевой предмет: ${config.targetItemName}")
                        appendLine("Порог цены: ${config.priceThreshold}")
                        appendLine("Оператор: ${if (config.isLessThanOperator) "< (Меньше)" else "> (Больше)"}")
                        appendLine("Задержка сканирования: ${config.scanIntervalMs} ms")
                        appendLine("Задержка вкладок OCR: ${config.tabSwitchIntervalMs} ms")
                        appendLine("Режим сканирования: ${if (config.useViewScanning) "View Node Scanning" else "Coordinate/OCR Scanning"}")
                        appendLine("Реальная покупка: ${if (config.enableActualBuying) "ВКЛЮЧЕНА" else "ВЫКЛЮЧЕНА (Только логи)"}")
                        appendLine("====================================\n")
                    }
                } ?: "=== НАСТРОЙКИ БОТА: НЕ ЗАГРУЖЕНЫ ===\n"

                val logsText = AutoBuyerLogs.getAllLogsText(configHeader)
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("AutoBuyer Logs & Config", logsText)
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(this@OverlayControlService, "Настройки и логи скопированы!", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@OverlayControlService, "Ошибка копирования: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        logHeaderLayout.addView(copyBtn)
        rootLayout.addView(logHeaderLayout)

        val logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = 4
            }
            val container = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#0F0E11"))
                cornerRadius = 8 * resources.displayMetrics.density
            }
            background = container
            setPadding(8, 8, 8, 8)
        }

        val logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        logScroll.addView(logContainer)
        rootLayout.addView(logScroll)

        panel.addView(rootLayout)
        controlPanel = panel
        windowManager.addView(panel, params)

        // Listen for live logs flow
        logsJob = serviceScope.launch {
            AutoBuyerLogs.logsFlow.collect { logLine ->
                val tv = TextView(this@OverlayControlService).apply {
                    text = logLine
                    setTextColor(Color.GREEN)
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                logContainer.addView(tv)
                logScroll.post {
                    logScroll.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        updatePanelUi()
    }

    private fun removeControlPanel() {
        controlPanel?.let {
            windowManager.removeView(it)
            controlPanel = null
        }
        logsJob?.cancel()
        logsJob = null
    }

    /**
     * Update the visual content of the control panel based on active configuration.
     */
    private fun updatePanelUi() {
        val panel = controlPanel ?: return
        val layout = panel.getChildAt(0) as? LinearLayout ?: return
        
        // Find text at index 1
        val configText = layout.getChildAt(1) as? TextView
        val toggleBtn = if (layout.getChildAt(2) is Button) {
            layout.getChildAt(2) as? Button
        } else {
            val buttonsRow = layout.getChildAt(2) as? LinearLayout
            buttonsRow?.getChildAt(0) as? Button
        }

        currentConfig?.let { config ->
            val scanMode = if (config.useViewScanning) {
                if (MediaProjectionHelper.hasProjection()) "OCR Mode" else "Native View Tree"
            } else {
                "Coordinate Macro"
            }
            configText?.text = "Item: ${config.targetItemName} | Threshold: ${config.priceThreshold}\nMode: $scanMode"
            
            if (config.autoBuyEnabled) {
                toggleBtn?.text = "PAUSE MONITORING"
                toggleBtn?.setBackgroundColor(Color.parseColor("#E91E63")) // Pink pause button
            } else {
                toggleBtn?.text = "START MONITORING"
                toggleBtn?.setBackgroundColor(Color.parseColor("#2196F3")) // Blue start button
            }
        }
    }

    private fun toggleAutoBuy() {
        val config = currentConfig ?: return
        val service = LootBuyerAccessibilityService.instance
        if (service == null) {
            AutoBuyerLogs.addLogBlocking("ERROR: Enable Accessibility Service first!")
            android.widget.Toast.makeText(this, "Enable 'Auto-Buyer' Accessibility Service first!", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        val nextState = !config.autoBuyEnabled

        serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            db.configurationDao().saveConfiguration(config.copy(autoBuyEnabled = nextState))
            
            withContext(Dispatchers.Main) {
                if (nextState) {
                    service.startAutomation()
                    // Automatically collapse control panel to floating bubble when starting monitoring!
                    // This prevents the large panel from blocking the OCR elements on the screen.
                    if (controlPanel != null) {
                        removeControlPanel()
                        createFloatingBubble()
                    }
                } else {
                    service.stopAutomation()
                }
            }
        }
    }

    private var calibrationOverlay: FrameLayout? = null
    private var calibrationStep = 1
    
    // Store temporary coordinates during calibration
    private var tempTargetX = -1f
    private var tempTargetY = -1f
    private var tempAlternateX = -1f
    private var tempAlternateY = -1f
    private var tempConfirmX = -1f
    private var tempConfirmY = -1f
    private var lastTapTime = 0L

    private fun startCalibrationFlow() {
        if (calibrationOverlay != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            AutoBuyerLogs.addLogBlocking("Overlay permission not granted. Calibration overlay will not be shown.")
            return
        }
        calibrationStep = 1
        lastTapTime = 0L // Reset touch debounce timer
        
        val config = currentConfig ?: AppConfiguration()
        val targetItemName = config.targetItemName
        
        val targetCategoryTab = when {
            targetItemName.lowercase().contains("руда") || targetItemName.lowercase().contains("ore") -> "Ore"
            targetItemName.lowercase().contains("медь") || targetItemName.lowercase().contains("copper") -> "Copper"
            targetItemName.lowercase().contains("серебро") || targetItemName.lowercase().contains("silver") -> "Silver"
            targetItemName.lowercase().contains("золото") || targetItemName.lowercase().contains("gold") -> "Gold"
            targetItemName.lowercase().contains("сапфир") || targetItemName.lowercase().contains("sapphire") || targetItemName.lowercase().contains("sap") -> "Sap"
            else -> "Ore"
        }
        val alternateTab = if (targetCategoryTab.equals("Ore", ignoreCase = true)) "Copper" else "Ore"

        fun getTabNameRussian(tab: String): String = when (tab) {
            "Ore" -> "Руда (Ore)"
            "Copper" -> "Медь (Copper)"
            "Silver" -> "Серебро (Silver)"
            "Gold" -> "Золото (Gold)"
            "Sap" -> "Сапфир (Sapphire)"
            else -> "Руда (Ore)"
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#4D000000")) // 30% semi-transparent black
        }

        // Add instruction panel at the top
        val instructionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E24"))
            setPadding(24, 24, 24, 24)
            gravity = Gravity.CENTER_HORIZONTAL
            val stroke = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E24"))
                cornerRadius = 16f
                setStroke(2, Color.parseColor("#FF9800"))
            }
            background = stroke
        }

        val titleView = TextView(this).apply {
            text = "РЕЖИМ КАЛИБРОВКИ ВКЛАДОК"
            setTextColor(Color.parseColor("#FF9800"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        instructionPanel.addView(titleView)

        val stepView = TextView(this).apply {
            id = View.generateViewId()
            text = "Шаг 1 из 2: Нажмите на вкладку '${getTabNameRussian(targetCategoryTab)}'"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 12, 0, 12)
            gravity = Gravity.CENTER
        }
        instructionPanel.addView(stepView)

        val subtext = TextView(this).apply {
            text = "Нажмите точно по центру указанной вкладки в игре.\nБот запомнит точку и нажмет её в игре."
            setTextColor(Color.LTGRAY)
            textSize = 10f
            gravity = Gravity.CENTER
        }
        instructionPanel.addView(subtext)

        // Cancel button
        val cancelButton = Button(this).apply {
            text = "Отмена"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C62828"))
            setOnClickListener {
                disableCalibrationInDb()
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 16
        }
        instructionPanel.addView(cancelButton, btnParams)

        val panelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            topMargin = 100
            leftMargin = 50
            rightMargin = 50
        }

        overlay.addView(instructionPanel, panelParams)

        // Handle touch events on the calibration overlay
        overlay.isClickable = true
        overlay.isFocusable = true
        overlay.setOnTouchListener { _, event ->
            val action = event.action
            val x = event.rawX
            val y = event.rawY
            if (action == MotionEvent.ACTION_DOWN) {
                AutoBuyerLogs.addLogBlocking("Touch on overlay: ACTION_DOWN at ($x, $y)")
                handleCalibrationTap(x, y, stepView)
            }
            true
        }

        windowManager.addView(overlay, params)
        calibrationOverlay = overlay
        AutoBuyerLogs.addLogBlocking("Calibration overlay added to window manager. Step 1 of 2 active.")
    }

    private fun handleCalibrationTap(x: Float, y: Float, stepView: TextView) {
        val now = System.currentTimeMillis()
        if (now - lastTapTime < 500) {
            AutoBuyerLogs.addLogBlocking("Ignored simulated/rapid tap at ($x, $y) to prevent recursion.")
            return
        }
        lastTapTime = now

        AutoBuyerLogs.addLogBlocking("handleCalibrationTap: step=$calibrationStep, coords=($x, $y)")
        // Delegate tap to accessibility service so the game responds to the tap!
        val service = LootBuyerAccessibilityService.instance
        if (service != null) {
            AutoBuyerLogs.addLogBlocking("Delegating tap to AccessibilityService.clickAt($x, $y)")
            service.clickAt(x, y)
        } else {
            AutoBuyerLogs.addLogBlocking("Warning: LootBuyerAccessibilityService.instance is null! Cannot delegate click.")
        }

        val config = currentConfig ?: AppConfiguration()
        val targetItemName = config.targetItemName
        
        val targetCategoryTab = when {
            targetItemName.lowercase().contains("руда") || targetItemName.lowercase().contains("ore") -> "Ore"
            targetItemName.lowercase().contains("медь") || targetItemName.lowercase().contains("copper") -> "Copper"
            targetItemName.lowercase().contains("серебро") || targetItemName.lowercase().contains("silver") -> "Silver"
            targetItemName.lowercase().contains("золото") || targetItemName.lowercase().contains("gold") -> "Gold"
            targetItemName.lowercase().contains("сапфир") || targetItemName.lowercase().contains("sapphire") || targetItemName.lowercase().contains("sap") -> "Sap"
            else -> "Ore"
        }
        val alternateTab = if (targetCategoryTab.equals("Ore", ignoreCase = true)) "Copper" else "Ore"

        fun getTabNameRussian(tab: String): String = when (tab) {
            "Ore" -> "Руда (Ore)"
            "Copper" -> "Медь (Copper)"
            "Silver" -> "Серебро (Silver)"
            "Gold" -> "Золото (Gold)"
            "Sap" -> "Сапфир (Sapphire)"
            else -> "Руда (Ore)"
        }

        when (calibrationStep) {
            1 -> {
                tempTargetX = x
                tempTargetY = y
                calibrationStep = 2
                stepView.text = "Шаг 2 из 2: Нажмите на вкладку '${getTabNameRussian(alternateTab)}'"
                AutoBuyerLogs.addLogBlocking("Saved Target Tab ($targetCategoryTab) coordinates. Next step: Alternate Tab.")
            }
            2 -> {
                tempAlternateX = x
                tempAlternateY = y
                AutoBuyerLogs.addLogBlocking("Saved Alternate Tab ($alternateTab) coordinates. Finishing calibration...")
                
                // Save all calibrated coordinates and disable calibration mode!
                serviceScope.launch {
                    val db = AppDatabase.getDatabase(applicationContext)
                    val current = db.configurationDao().getConfiguration() ?: AppConfiguration()
                    var updated = current.copy(
                        isCalibrationEnabled = false,
                        calibratedConfirmX = -1f,
                        calibratedConfirmY = -1f
                    )
                    
                    // Save target tab coordinates
                    updated = when (targetCategoryTab) {
                        "Ore" -> updated.copy(calibratedOreX = tempTargetX, calibratedOreY = tempTargetY)
                        "Copper" -> updated.copy(calibratedCopperX = tempTargetX, calibratedCopperY = tempTargetY)
                        "Silver" -> updated.copy(calibratedSilverX = tempTargetX, calibratedSilverY = tempTargetY)
                        "Gold" -> updated.copy(calibratedGoldX = tempTargetX, calibratedGoldY = tempTargetY)
                        "Sap" -> updated.copy(calibratedSapX = tempTargetX, calibratedSapY = tempTargetY)
                        else -> updated
                    }
                    
                    // Save alternate tab coordinates
                    updated = when (alternateTab) {
                        "Ore" -> updated.copy(calibratedOreX = tempAlternateX, calibratedOreY = tempAlternateY)
                        "Copper" -> updated.copy(calibratedCopperX = tempAlternateX, calibratedCopperY = tempAlternateY)
                        "Silver" -> updated.copy(calibratedSilverX = tempAlternateX, calibratedSilverY = tempAlternateY)
                        "Gold" -> updated.copy(calibratedGoldX = tempAlternateX, calibratedGoldY = tempAlternateY)
                        "Sap" -> updated.copy(calibratedSapX = tempAlternateX, calibratedSapY = tempAlternateY)
                        else -> updated
                    }
                    
                    db.configurationDao().saveConfiguration(updated)
                    AutoBuyerLogs.addLog("🎯 Калибровка завершена и сохранена в базу данных!")
                    
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            applicationContext,
                            "Калибровка успешно сохранена!",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun disableCalibrationInDb() {
        serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val current = db.configurationDao().getConfiguration() ?: AppConfiguration()
            db.configurationDao().saveConfiguration(current.copy(isCalibrationEnabled = false))
        }
    }

    private fun stopCalibrationFlow() {
        val overlay = calibrationOverlay
        if (overlay != null) {
            try {
                windowManager.removeView(overlay)
            } catch (e: Exception) {
                // Ignore
            }
            calibrationOverlay = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "OverlayServiceChannel",
                "Overlay Control Service Channel",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val action = intent.action
            if (action == "ACTION_START_PROJECTION") {
                if (MediaProjectionHelper.hasProjection()) {
                    AutoBuyerLogs.addLogBlocking("Screen Capture already initialized from Activity.")
                    return START_STICKY
                }
                val resultCode = intent.getIntExtra("EXTRA_RESULT_CODE", -1)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("EXTRA_DATA", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("EXTRA_DATA")
                }
                
                if (resultCode != -1 && data != null) {
                    try {
                        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                        val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
                        if (projection != null) {
                            MediaProjectionHelper.initProjection(projection, applicationContext)
                            AutoBuyerLogs.addLogBlocking("Screen Capture authorized inside OverlayControlService!")
                        } else {
                            AutoBuyerLogs.addLogBlocking("Failed to get MediaProjection in Service.")
                        }
                    } catch (e: Exception) {
                        AutoBuyerLogs.addLogBlocking("Error initializing MediaProjection in Service: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        }
        return START_STICKY
    }
}
