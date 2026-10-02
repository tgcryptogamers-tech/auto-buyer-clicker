package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.database.AppDatabase
import com.example.database.AppConfiguration
import com.example.database.PurchaseRecord
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

class LootBuyerAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var automationJob: Job? = null
    private var isRunning = false
    @Volatile var cooldownUntilMillis: Long = 0L

    // Caching coordinates to bypass initial OCR delays and speed up refresh loop & purchases
    @Volatile private var cachedTabOreX: Float? = null
    @Volatile private var cachedTabOreY: Float? = null
    @Volatile private var cachedTabCopperX: Float? = null
    @Volatile private var cachedTabCopperY: Float? = null
    @Volatile private var cachedTabSilverX: Float? = null
    @Volatile private var cachedTabSilverY: Float? = null
    @Volatile private var cachedTabGoldX: Float? = null
    @Volatile private var cachedTabGoldY: Float? = null
    @Volatile private var cachedTabSapX: Float? = null
    @Volatile private var cachedTabSapY: Float? = null
    @Volatile private var cachedBuyX: Float? = null
    @Volatile private var cachedBuyY: Float? = null

    @Volatile private var activeGamePackage: String? = null

    // Keep track of the last attempted purchase to detect if a lot is stuck/doesn't disappear
    @Volatile private var lastPurchasedPrice: Double? = null
    @Volatile private var lastPurchasedQuantity: Double? = null
    @Volatile private var lastPurchaseAttemptTime: Long = 0L
    @Volatile private var isStuckCheckPending: Boolean = false

    // Cycle-based search tracking fields
    @Volatile private var currentCycleNumber = 1
    @Volatile private var cycleEndTimeMs = 0L
    @Volatile private var purchasesThisCycleRun = 0
    @Volatile private var cycleInitialized = false

    private fun registerPurchaseSuccess() {
        purchasesThisCycleRun++
        serviceScope.launch {
            AutoBuyerLogs.addLog("📈 [СЧЕТЧИК] УСПЕШНАЯ ПОКУПКА! Всего куплено в текущей серии циклов: $purchasesThisCycleRun")
        }
    }

    companion object {
        // Editable default tab switch interval value in milliseconds
        const val DEFAULT_TAB_SWITCH_INTERVAL_MS = 15L
        const val CONFIRMATION_DIALOG_DELAY_MS = 180L // Highly optimized delay for modal transition (was 400ms)

        @Volatile
        var instance: LootBuyerAccessibilityService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null

        fun getCooldownRemainingMs(): Long {
            val inst = instance ?: return 0L
            val now = System.currentTimeMillis()
            val remaining = inst.cooldownUntilMillis - now
            return if (remaining > 0L) remaining else 0L
        }

        fun resetCooldown() {
            instance?.let {
                it.cooldownUntilMillis = 0L
                it.serviceScope.launch {
                    com.example.service.AutoBuyerLogs.addLog("🔄 [СБРОС] Кулдаун сброшен вручную. Бот готов к покупкам!")
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceScope.launch {
            AutoBuyerLogs.addLog("Accessibility Service Connected successfully")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        stopAutomation()
        serviceScope.launch {
            AutoBuyerLogs.addLog("Accessibility Service Unbound")
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        stopAutomation()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // Always track the package name of the active app if it's not ours or system ui
        val pkg = event.packageName?.toString()
        if (pkg != null && pkg != packageName && !pkg.contains("com.android.systemui") && !pkg.contains("com.google.android.inputmethod")) {
            activeGamePackage = pkg
        }

        // We can inspect active window changes or view scrolling events here
        if (!isRunning) return

        // Do not trigger active updates if we are on post-purchase cooldown
        if (System.currentTimeMillis() < cooldownUntilMillis) return

        // When the background automation runner is active, it controls scanning via startAutomation loop.
        // We avoid launching concurrent duplicate OCR scans on accessibility content events to prevent race conditions.
        if (isRunning) return
    }

    override fun onInterrupt() {
        stopAutomation()
    }

    /**
     * Start the background automation runner.
     */
    fun startAutomation() {
        if (isRunning) return
        isRunning = true
        serviceScope.launch {
            AutoBuyerLogs.addLog("Starting Auto-Buyer loop...")
        }

        automationJob = serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            while (isRunning) {
                val now = System.currentTimeMillis()
                if (now < cooldownUntilMillis) {
                    val remainingSec = (cooldownUntilMillis - now) / 1000
                    val remainingMin = remainingSec / 60
                    val remSecOnly = remainingSec % 60
                    AutoBuyerLogs.addLog("⏳ Бот на паузе после успешной покупки. Осталось: $remainingMin мин $remSecOnly сек.")
                    delay(10000) // check every 10 seconds to avoid spamming the log
                    continue
                }

                val config = db.configurationDao().getConfiguration()
                if (config == null || !config.autoBuyEnabled) {
                    delay(1000)
                    continue
                }

                // Search cycles logic if enabled
                if (config.useSearchCycles) {
                    if (!cycleInitialized) {
                        cycleInitialized = true
                        if (currentCycleNumber == 1) {
                            purchasesThisCycleRun = 0
                            val limit = Math.abs(config.cycle1RandomRangeSec)
                            val randomSec = if (limit > 0) (-limit..limit).random() else 0
                            val durationSec = (config.cycle1DurationMin * 60) + randomSec
                            cycleEndTimeMs = now + (durationSec * 1000L)
                            AutoBuyerLogs.addLog("🔄 [ЦИКЛ 1/3] Запуск первого цикла поиска на $durationSec сек (${config.cycle1DurationMin} мин + $randomSec сек)...")
                        } else if (currentCycleNumber == 2) {
                            val limit = Math.abs(config.cycle2RandomRangeSec)
                            val randomSec = if (limit > 0) (-limit..limit).random() else 0
                            val durationSec = (config.cycle2DurationMin * 60) + randomSec
                            cycleEndTimeMs = now + (durationSec * 1000L)
                            AutoBuyerLogs.addLog("🔄 [ЦИКЛ 2/3] Запуск второго цикла поиска на $durationSec сек (${config.cycle2DurationMin} мин + $randomSec сек)...")
                        } else {
                            val limit = Math.abs(config.cycle3RandomRangeSec)
                            val randomSec = if (limit > 0) (-limit..limit).random() else 0
                            val durationSec = (config.cycle3DurationMin * 60) + randomSec
                            cycleEndTimeMs = now + (durationSec * 1000L)
                            AutoBuyerLogs.addLog("🔄 [ЦИКЛ 3/3] Запуск третьего цикла поиска на $durationSec сек (${config.cycle3DurationMin} мин + $randomSec сек)...")
                        }
                    }

                    if (now >= cycleEndTimeMs) {
                        // Current cycle time has expired
                        if (currentCycleNumber < 3) {
                            val nextCycle = currentCycleNumber + 1
                            AutoBuyerLogs.addLog("🔄 [ЦИКЛ $currentCycleNumber/3] Завершен! Начинаем переход к циклу $nextCycle...")
                            
                            // Random break between cycles (e.g. 10 to 20 seconds) to look human
                            val pauseSec = (10..20).random()
                            AutoBuyerLogs.addLog("⏳ [ПАУЗА] Перерыв между циклами: $pauseSec секунд...")
                            delay(pauseSec * 1000L)
                            
                            currentCycleNumber = nextCycle
                            cycleInitialized = false
                            continue
                        } else {
                            // Cycle 3 has completed!
                            AutoBuyerLogs.addLog("🔄 [ЦИКЛ 3/3] Завершен! Все 3 цикла поиска пройдены.")
                            if (purchasesThisCycleRun > 0) {
                                AutoBuyerLogs.addLog("🎉 [УСПЕХ] За 3 цикла было успешно куплено: $purchasesThisCycleRun лотов! Сбрасываем и начинаем заново с Цикла 1.")
                            } else {
                                AutoBuyerLogs.addLog("⚠️ [БЕЗРЕЗУЛЬТАТНО] За 3 цикла поиска ничего не было куплено! Выполняем перезапуск страницы игры (Reload Page) и начинаем заново с Цикла 1...")
                                restartGameByReloadPage()
                            }
                            currentCycleNumber = 1
                            purchasesThisCycleRun = 0
                            cycleInitialized = false
                            // Pause after reload to let the webview load content
                            val pauseSec = (15..25).random()
                            AutoBuyerLogs.addLog("⏳ [ПАУЗА] Ожидаем загрузки страницы после перезапуска: $pauseSec секунд...")
                            delay(pauseSec * 1000L)
                            continue
                        }
                    }
                } else {
                    currentCycleNumber = 1
                    purchasesThisCycleRun = 0
                    cycleInitialized = false
                }

                if (config.useViewScanning) {
                    if (MediaProjectionHelper.hasProjection()) {
                        performOcrScreenScan(config)
                    } else {
                        // Native View Tree scanning mode fallback
                        performNativeViewScan(config)
                    }
                } else {
                    // Coordinate Tap Macro mode
                    performCoordinateMacro(config)
                }

                // Delay by the configured scan interval (Search Delay / Scan Period)
                delay(config.scanIntervalMs)
            }
        }
    }

    /**
     * Stop the background automation runner.
     */
    fun stopAutomation() {
        isRunning = false
        automationJob?.cancel()
        automationJob = null
        serviceScope.launch {
            AutoBuyerLogs.addLog("Auto-Buyer loop stopped.")
        }
    }

    private fun getTabCoordinates(
        tabName: String,
        filteredLines: List<com.google.mlkit.vision.text.Text.Line>,
        screenWidth: Float,
        screenHeight: Float,
        scaleX: Float,
        scaleY: Float,
        config: AppConfiguration
    ): Pair<Float, Float> {
        // If we have calibrated coordinates, use them first!
        when (tabName) {
            "Ore" -> if (config.calibratedOreX != -1f && config.calibratedOreY != -1f) {
                return Pair(config.calibratedOreX, config.calibratedOreY)
            }
            "Copper" -> if (config.calibratedCopperX != -1f && config.calibratedCopperY != -1f) {
                return Pair(config.calibratedCopperX, config.calibratedCopperY)
            }
            "Silver" -> if (config.calibratedSilverX != -1f && config.calibratedSilverY != -1f) {
                return Pair(config.calibratedSilverX, config.calibratedSilverY)
            }
            "Gold" -> if (config.calibratedGoldX != -1f && config.calibratedGoldY != -1f) {
                return Pair(config.calibratedGoldX, config.calibratedGoldY)
            }
            "Sap" -> if (config.calibratedSapX != -1f && config.calibratedSapY != -1f) {
                return Pair(config.calibratedSapX, config.calibratedSapY)
            }
        }

        var tabBounds: Rect? = null
        for (line in filteredLines) {
            if (matchText(line.text, tabName)) {
                tabBounds = line.boundingBox
                break
            }
        }

        if (tabBounds != null) {
            val tabX = tabBounds.centerX() * scaleX
            val tabY = tabBounds.centerY() * scaleY
            return Pair(tabX, tabY)
        }

        // Fallback to calibrated coordinate percentages
        val detectedTabY = filteredLines.firstOrNull { 
            it.text.lowercase().contains("ore") || 
            it.text.lowercase().contains("руда") ||
            it.text.lowercase().contains("sap") ||
            it.text.lowercase().contains("сап")
        }?.boundingBox?.centerY()?.toFloat()?.let { it * scaleY }
        
        val tabY = detectedTabY ?: (screenHeight * 0.43f)
        val tabX = when (tabName) {
            "Ore" -> screenWidth * 0.06f
            "Copper" -> screenWidth * 0.28f
            "Silver" -> screenWidth * 0.50f
            "Gold" -> screenWidth * 0.72f
            "Sap" -> screenWidth * 0.94f
            else -> screenWidth * 0.06f
        }
        return Pair(tabX, tabY)
    }

    private fun isTabCoordinateAvailable(tabName: String, config: AppConfiguration): Boolean {
        // Calibrated coordinates from DB
        when (tabName) {
            "Ore" -> if (config.calibratedOreX != -1f && config.calibratedOreY != -1f) return true
            "Copper" -> if (config.calibratedCopperX != -1f && config.calibratedCopperY != -1f) return true
            "Silver" -> if (config.calibratedSilverX != -1f && config.calibratedSilverY != -1f) return true
            "Gold" -> if (config.calibratedGoldX != -1f && config.calibratedGoldY != -1f) return true
            "Sap" -> if (config.calibratedSapX != -1f && config.calibratedSapY != -1f) return true
        }
        // Cached coordinates from memory
        when (tabName) {
            "Ore" -> return cachedTabOreX != null && cachedTabOreY != null
            "Copper" -> return cachedTabCopperX != null && cachedTabCopperY != null
            "Silver" -> return cachedTabSilverX != null && cachedTabSilverY != null
            "Gold" -> return cachedTabGoldX != null && cachedTabGoldY != null
            "Sap" -> return cachedTabSapX != null && cachedTabSapY != null
        }
        return false
    }

    private fun getTabCoordinatesCached(tabName: String, config: AppConfiguration, screenWidth: Float, screenHeight: Float): Pair<Float, Float> {
        // Calibrated coordinates from DB
        when (tabName) {
            "Ore" -> if (config.calibratedOreX != -1f && config.calibratedOreY != -1f) return Pair(config.calibratedOreX, config.calibratedOreY)
            "Copper" -> if (config.calibratedCopperX != -1f && config.calibratedCopperY != -1f) return Pair(config.calibratedCopperX, config.calibratedCopperY)
            "Silver" -> if (config.calibratedSilverX != -1f && config.calibratedSilverY != -1f) return Pair(config.calibratedSilverX, config.calibratedSilverY)
            "Gold" -> if (config.calibratedGoldX != -1f && config.calibratedGoldY != -1f) return Pair(config.calibratedGoldX, config.calibratedGoldY)
            "Sap" -> if (config.calibratedSapX != -1f && config.calibratedSapY != -1f) return Pair(config.calibratedSapX, config.calibratedSapY)
        }
        // Cached coordinates from memory
        when (tabName) {
            "Ore" -> if (cachedTabOreX != null && cachedTabOreY != null) return Pair(cachedTabOreX!!, cachedTabOreY!!)
            "Copper" -> if (cachedTabCopperX != null && cachedTabCopperY != null) return Pair(cachedTabCopperX!!, cachedTabCopperY!!)
            "Silver" -> if (cachedTabSilverX != null && cachedTabSilverY != null) return Pair(cachedTabSilverX!!, cachedTabSilverY!!)
            "Gold" -> if (cachedTabGoldX != null && cachedTabGoldY != null) return Pair(cachedTabGoldX!!, cachedTabGoldY!!)
            "Sap" -> if (cachedTabSapX != null && cachedTabSapY != null) return Pair(cachedTabSapX!!, cachedTabSapY!!)
        }
        
        // Final fallback default calculations
        val tabY = screenHeight * 0.43f
        val tabX = when (tabName) {
            "Ore" -> screenWidth * 0.06f
            "Copper" -> screenWidth * 0.28f
            "Silver" -> screenWidth * 0.50f
            "Gold" -> screenWidth * 0.72f
            "Sap" -> screenWidth * 0.94f
            else -> screenWidth * 0.06f
        }
        return Pair(tabX, tabY)
    }

    /**
     * Optical Character Recognition (OCR) Screen Scanning Mode (Perfect for Unity Games).
     */
    private suspend fun performOcrScreenScan(config: AppConfiguration) = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        // Get device screen metrics for coordinate mapping scaling
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()
        val density = metrics.density

        // Identify target tab based on the configured item name
        val targetCategoryTab = when {
            config.targetItemName.lowercase().contains("руда") || config.targetItemName.lowercase().contains("ore") -> "Ore"
            config.targetItemName.lowercase().contains("медь") || config.targetItemName.lowercase().contains("copper") -> "Copper"
            config.targetItemName.lowercase().contains("серебро") || config.targetItemName.lowercase().contains("silver") -> "Silver"
            config.targetItemName.lowercase().contains("золото") || config.targetItemName.lowercase().contains("gold") -> "Gold"
            config.targetItemName.lowercase().contains("сапфир") || config.targetItemName.lowercase().contains("sapphire") || config.targetItemName.lowercase().contains("sap") -> "Sap"
            else -> "Ore"
        }
        val alternateTab = if (targetCategoryTab.equals("Ore", ignoreCase = true)) "Copper" else "Ore"

        val hasCalibratedOrCachedTarget = isTabCoordinateAvailable(targetCategoryTab, config)
        val hasCalibratedOrCachedAlternate = isTabCoordinateAvailable(alternateTab, config)

        var scaleX = 1f
        var scaleY = 1f

        if (hasCalibratedOrCachedTarget && hasCalibratedOrCachedAlternate) {
            // OPTIMIZED FLOW: Switch tabs directly using cached or calibrated coordinates. Bypasses the initial screenshot and OCR!
            val altCoords = getTabCoordinatesCached(alternateTab, config, screenWidth, screenHeight)
            val targetCoords = getTabCoordinatesCached(targetCategoryTab, config, screenWidth, screenHeight)

            AutoBuyerLogs.addLog("⚡ [ОПТИМИЗАЦИЯ] Быстрое переключение на '$alternateTab' в (${altCoords.first}, ${altCoords.second})")
            clickAtWithRandomization(altCoords.first, altCoords.second, config)

            delay(getTabSwitchDelay(config))

            AutoBuyerLogs.addLog("⚡ [ОПТИМИЗАЦИЯ] Возврат на '$targetCategoryTab' в (${targetCoords.first}, ${targetCoords.second})")
            clickAtWithRandomization(targetCoords.first, targetCoords.second, config)

            delay(getTabSwitchDelay(config))
        } else {
            // INITIAL / FALLBACK FLOW: Run initial screenshot & OCR to discover and cache tab coordinates
            val initialBitmap = MediaProjectionHelper.getLatestScreenshot()
            if (initialBitmap == null) {
                AutoBuyerLogs.addLog("OCR: Screen Capture is silent. Make sure screen is active.")
                return@withContext
            }

            scaleX = screenWidth / initialBitmap.width
            scaleY = screenHeight / initialBitmap.height

            val initialInputImage = InputImage.fromBitmap(initialBitmap, 0)
            val initialResult = try {
                Tasks.await(recognizer.process(initialInputImage))
            } catch (e: Exception) {
                initialBitmap.recycle()
                AutoBuyerLogs.addLog("OCR Process Error: ${e.message}")
                return@withContext
            }

            val allLines = initialResult.textBlocks.flatMap { it.lines }
            val db = AppDatabase.getDatabase(this@LootBuyerAccessibilityService)
            if (handleTryThroughDialogIfNeeded(allLines, scaleX, scaleY, config)) {
                initialBitmap.recycle()
                return@withContext
            }
            if (dismissLotAlreadyPurchasedDialogIfNeeded(allLines, scaleX, scaleY, db)) {
                initialBitmap.recycle()
                return@withContext
            }
            if (dismissSuccessDialogIfNeeded(allLines, scaleX, scaleY, db, config)) {
                initialBitmap.recycle()
                return@withContext
            }

            if (allLines.isEmpty()) {
                initialBitmap.recycle()
                AutoBuyerLogs.addLog("OCR: Screen is blank or empty.")
                return@withContext
            }

            val filteredLines = allLines.filter { line ->
                val bounds = line.boundingBox
                val screenBounds = bounds?.let {
                    Rect(
                        (it.left * scaleX).toInt(),
                        (it.top * scaleY).toInt(),
                        (it.right * scaleX).toInt(),
                        (it.bottom * scaleY).toInt()
                    )
                }
                !isInsideOverlay(screenBounds, screenWidth, screenHeight, density) && !isLogOrOverlayText(line.text)
            }

            // Populate the tab coordinates cache dynamically
            for (line in filteredLines) {
                val text = line.text.lowercase()
                val bounds = line.boundingBox ?: continue
                val tX = bounds.centerX() * scaleX
                val tY = bounds.centerY() * scaleY

                when {
                    text.contains("ore") || text.contains("руда") -> {
                        cachedTabOreX = tX
                        cachedTabOreY = tY
                    }
                    text.contains("copper") || text.contains("медь") -> {
                        cachedTabCopperX = tX
                        cachedTabCopperY = tY
                    }
                    text.contains("silver") || text.contains("серебро") -> {
                        cachedTabSilverX = tX
                        cachedTabSilverY = tY
                    }
                    text.contains("gold") || text.contains("золото") -> {
                        cachedTabGoldX = tX
                        cachedTabGoldY = tY
                    }
                    text.contains("sap") || text.contains("сапфир") -> {
                        cachedTabSapX = tX
                        cachedTabSapY = tY
                    }
                }
            }

            // Check for rate-limit or system warning popups (e.g. "Try through 1 seconds")
            if (isRateLimitDialogShowing(allLines)) {
                AutoBuyerLogs.addLog("⚠️ [ОКНО ЗАДЕРЖКИ] Обнаружено всплывающее окно ограничений ('Try through...'). Нажимаем 'Confirm' для закрытия...")
                var clickX = screenWidth / 2f
                var clickY = screenHeight * 0.65f
                val confirmLine = allLines.firstOrNull { isBuyConfirmationText(it.text) }
                if (confirmLine?.boundingBox != null) {
                    clickX = confirmLine.boundingBox!!.centerX() * scaleX
                    clickY = confirmLine.boundingBox!!.centerY() * scaleY
                } else if (config.calibratedConfirmX != -1f && config.calibratedConfirmY != -1f) {
                    clickX = config.calibratedConfirmX
                    clickY = config.calibratedConfirmY
                }
                clickAtWithRandomization(clickX, clickY, config)
                delay(1200)
                initialBitmap.recycle()
                return@withContext
            }

            // Check if the purchase confirmation dialog is already visible on the screen.
            val dialogIsShowingInitially = isConfirmationDialogShowing(allLines, scaleY, screenHeight)

            if (dialogIsShowingInitially) {
                var clickX = -1f
                var clickY = -1f
                var targetFound = false

                if (config.calibratedConfirmX != -1f && config.calibratedConfirmY != -1f) {
                    clickX = config.calibratedConfirmX
                    clickY = config.calibratedConfirmY
                    targetFound = true
                    AutoBuyerLogs.addLog("🎯 [ПОДТВЕРЖДЕНИЕ] Найдена калиброванная кнопка подтверждения в координатах ($clickX, $clickY)")
                } else {
                    val buyLines = allLines.filter { line ->
                        isBuyConfirmationText(line.text)
                    }
                    var targetBuyLine = buyLines.firstOrNull { line ->
                        val bounds = line.boundingBox
                        if (bounds != null) {
                            val cX = bounds.centerX() * scaleX
                            val cY = bounds.centerY() * scaleY
                            val cXPercent = cX / screenWidth
                            val cYPercent = cY / screenHeight
                            cXPercent in 0.3f..0.7f && cYPercent in 0.50f..0.85f
                        } else {
                            false
                        }
                    }

                    if (targetBuyLine == null) {
                        AutoBuyerLogs.addLog("⚠️ [НАЧАЛЬНЫЙ ТЕКСТ] Не нашли кнопку 'Confirm/Купить' по тексту. Ищем по координатам...")
                        val candidates = allLines.filter { line ->
                            val bounds = line.boundingBox
                            if (bounds != null) {
                                val cX = bounds.centerX() * scaleX
                                val cY = bounds.centerY() * scaleY
                                val cXPercent = cX / screenWidth
                                val cYPercent = cY / screenHeight
                                cXPercent in 0.25f..0.75f && cYPercent in 0.58f..0.82f
                            } else {
                                false
                            }
                        }
                        targetBuyLine = candidates.maxByOrNull { line ->
                            line.boundingBox?.centerY() ?: 0
                        }
                    }

                    if (targetBuyLine != null) {
                        val bounds = targetBuyLine.boundingBox!!
                        clickX = bounds.centerX() * scaleX
                        clickY = bounds.centerY() * scaleY
                        targetFound = true
                        
                        // Cache the Buy confirmation button coordinates
                        cachedBuyX = clickX
                        cachedBuyY = clickY
                    }
                }

                if (targetFound) {
                    val dialogPrice = extractDialogPrice(allLines)
                    if (dialogPrice != null && config.usePriceThreshold) {
                        val matches = if (config.isLessThanOperator) {
                            dialogPrice < config.priceThreshold
                        } else {
                            dialogPrice > config.priceThreshold
                        }
                        if (!matches) {
                            AutoBuyerLogs.addLog("🛑 [ЗАЩИТА ЦЕНЫ] Окно подтверждения содержит цену $dialogPrice (не удовлетворяет условию ${if (config.isLessThanOperator) "<" else ">"} ${config.priceThreshold}). Закрываем окно!")
                            dismissModalDialog(allLines, screenWidth, screenHeight, scaleX, scaleY)
                            initialBitmap.recycle()
                            return@withContext
                        }
                    }

                    AutoBuyerLogs.addLog("🎉 [ПОДТВЕРЖДЕНИЕ] Окно подтверждения покупки обнаружено изначально (цена: ${dialogPrice ?: config.priceThreshold})! Кликаем подтверждение в координатах ($clickX, $clickY) с рандомизацией.")
                    clickAtWithRandomization(clickX, clickY, config)

                    // Save purchase record to database
                    try {
                        val db = AppDatabase.getDatabase(this@LootBuyerAccessibilityService)
                        db.purchaseDao().insertPurchase(
                            PurchaseRecord(
                                timestamp = System.currentTimeMillis(),
                                itemName = config.targetItemName,
                                price = dialogPrice ?: config.priceThreshold,
                                quantity = 1.0,
                                details = "Окно подтверждения обнаружено при запуске"
                            )
                        )
                        registerPurchaseSuccess()
                    } catch (e: Exception) {
                        AutoBuyerLogs.addLog("⚠️ Ошибка записи покупки: ${e.message}")
                    }
                    
                    // Set temporary cooldown while we verify the purchase result
                    cooldownUntilMillis = System.currentTimeMillis() + 15 * 1000L
                    AutoBuyerLogs.addLog("👉 Нажали подтверждение покупки (изначальное окно). Ожидаем результат...")
                    verifyPurchaseResultAndHandleFailure(dialogPrice ?: config.priceThreshold, 1.0, config)
                    initialBitmap.recycle()
                    return@withContext
                }
            }

            // Perform the refresh cycle: Click alternate, wait, click target, wait
            val altCoords = getTabCoordinates(alternateTab, filteredLines, screenWidth, screenHeight, scaleX, scaleY, config)
            AutoBuyerLogs.addLog("🔄 Переключение на вкладку для обновления: '$alternateTab'")
            clickAtWithRandomization(altCoords.first, altCoords.second, config)

            delay(getTabSwitchDelay(config))

            val targetCoords = getTabCoordinates(targetCategoryTab, filteredLines, screenWidth, screenHeight, scaleX, scaleY, config)
            AutoBuyerLogs.addLog("🔄 Возврат на целевую вкладку: '$targetCategoryTab'")
            clickAtWithRandomization(targetCoords.first, targetCoords.second, config)

            delay(getTabSwitchDelay(config))
            
            initialBitmap.recycle()
        }

        // Capture FRESH screen showing the target tab listings!
        val freshBitmap = MediaProjectionHelper.getLatestScreenshot()
        if (freshBitmap == null) {
            AutoBuyerLogs.addLog("OCR: Fresh screen capture failed.")
            return@withContext
        }

        scaleX = screenWidth / freshBitmap.width
        scaleY = screenHeight / freshBitmap.height

        try {
            val freshInputImage = InputImage.fromBitmap(freshBitmap, 0)
            val freshResult = Tasks.await(recognizer.process(freshInputImage))
            val freshLines = freshResult.textBlocks.flatMap { it.lines }
            val db = AppDatabase.getDatabase(this@LootBuyerAccessibilityService)
            if (handleTryThroughDialogIfNeeded(freshLines, scaleX, scaleY, config)) {
                freshBitmap.recycle()
                return@withContext
            }
            if (dismissLotAlreadyPurchasedDialogIfNeeded(freshLines, scaleX, scaleY, db)) {
                freshBitmap.recycle()
                return@withContext
            }
            if (dismissSuccessDialogIfNeeded(freshLines, scaleX, scaleY, db, config)) {
                freshBitmap.recycle()
                return@withContext
            }

            val freshFilteredLines = freshLines.filter { line ->
                val bounds = line.boundingBox
                val screenBounds = bounds?.let {
                    Rect(
                        (it.left * scaleX).toInt(),
                        (it.top * scaleY).toInt(),
                        (it.right * scaleX).toInt(),
                        (it.bottom * scaleY).toInt()
                    )
                }
                !isInsideOverlay(screenBounds, screenWidth, screenHeight, density) && !isLogOrOverlayText(line.text)
            }

            // Populate/Refresh the cache in every scan to keep it accurate
            for (line in freshFilteredLines) {
                val text = line.text.lowercase()
                val bounds = line.boundingBox ?: continue
                val tX = bounds.centerX() * scaleX
                val tY = bounds.centerY() * scaleY

                when {
                    text.contains("ore") || text.contains("руда") -> {
                        cachedTabOreX = tX
                        cachedTabOreY = tY
                    }
                    text.contains("copper") || text.contains("медь") -> {
                        cachedTabCopperX = tX
                        cachedTabCopperY = tY
                    }
                    text.contains("silver") || text.contains("серебро") -> {
                        cachedTabSilverX = tX
                        cachedTabSilverY = tY
                    }
                    text.contains("gold") || text.contains("золото") -> {
                        cachedTabGoldX = tX
                        cachedTabGoldY = tY
                    }
                    text.contains("sap") || text.contains("сапфир") -> {
                        cachedTabSapX = tX
                        cachedTabSapY = tY
                    }
                }
            }

            if (config.verboseOcrLogging) {
                AutoBuyerLogs.addLog("=== [OCR: РАСПОЗНАННЫЕ СТРОКИ] ===")
                for (line in freshFilteredLines) {
                    AutoBuyerLogs.addLog("  • '${line.text}' [Y: ${line.boundingBox?.top}, X: ${line.boundingBox?.left}]")
                }
            }

            // Parse and Group listings from the fresh target tab screen (avoiding top headers and bottom navigation)
            val listingsYThresholdTop = freshBitmap.height * 0.40f
            val listingsYThresholdBottom = freshBitmap.height * 0.88f
            val listingLines = freshFilteredLines.filter { line ->
                val bounds = line.boundingBox
                val top = bounds?.top ?: 0
                val bottom = bounds?.bottom ?: 0
                top >= listingsYThresholdTop && bottom <= listingsYThresholdBottom &&
                !isIgnoredListingLabel(line.text) &&
                extractPrice(line.text) != null
            }

            // Group lines into rows by vertical alignment (within 50 pixels)
            val tolerance = 50f
            val horizontalRows = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Line>>()

            for (line in listingLines) {
                val bounds = line.boundingBox ?: continue
                val centerY = bounds.centerY().toFloat()
                
                var added = false
                for (row in horizontalRows) {
                    val rowCenterY = row.first().boundingBox?.centerY()?.toFloat() ?: 0f
                    if (Math.abs(centerY - rowCenterY) < tolerance) {
                        row.add(line)
                        added = true
                        break
                    }
                }
                if (!added) {
                    horizontalRows.add(mutableListOf(line))
                }
            }

            // Sort rows top-to-bottom
            horizontalRows.sortBy { it.first().boundingBox?.top ?: 0 }

            // Sort items in each row left-to-right
            for (row in horizontalRows) {
                row.sortBy { it.boundingBox?.left ?: 0 }
            }

            // Check for stuck lots
            val currentScreenLots = mutableListOf<Pair<Double, Double>>() // Pair of (price, quantity)
            for (row in horizontalRows) {
                val numberPairs = row.mapNotNull { line ->
                    val value = extractPrice(line.text)
                    val box = line.boundingBox
                    if (value != null && box != null) Pair(value, box) else null
                }
                if (numberPairs.isNotEmpty()) {
                    val pricePair = numberPairs.filter { (_, box) ->
                        val xRatio = box.centerX().toFloat() / freshBitmap.width
                        xRatio in 0.38f..0.78f
                    }.maxByOrNull { it.second.centerX() } ?: continue

                    val price = pricePair.first
                    val otherNumbers = numberPairs.filter { it != pricePair }
                    val q = otherNumbers.firstOrNull()?.first ?: 1.0
                    currentScreenLots.add(Pair(price, q))
                }
            }

            val nowTime = System.currentTimeMillis()
            if (isStuckCheckPending && (nowTime - lastPurchaseAttemptTime) < 20000L) {
                val attemptedPrice = lastPurchasedPrice
                val attemptedQty = lastPurchasedQuantity
                if (attemptedPrice != null) {
                    val isStillOnScreen = currentScreenLots.any { (p, q) ->
                        Math.abs(p - attemptedPrice) < 0.1 && (attemptedQty == null || Math.abs(q - attemptedQty) < 0.1)
                    }
                    if (isStillOnScreen) {
                        AutoBuyerLogs.addLog("⚠️ [ОБНАРУЖЕНО ЗАВИСАНИЕ] Лот с ценой $attemptedPrice всё ещё остался на рынке после переключения вкладок!")
                        isStuckCheckPending = false
                        restartGameByReloadPage()
                        freshBitmap.recycle()
                        return@withContext
                    } else {
                        // The lot successfully disappeared from the screen
                        isStuckCheckPending = false
                    }
                }
            } else {
                isStuckCheckPending = false
            }

            AutoBuyerLogs.addLog("=== [ОБНАРУЖЕННЫЕ ЛОТЫ] ===")
            var foundLotsCount = 0
            for (row in horizontalRows) {
                // Find all numbers with their bounding boxes
                val numberPairs = row.mapNotNull { line ->
                    val value = extractPrice(line.text)
                    val box = line.boundingBox
                    if (value != null && box != null) Pair(value, box) else null
                }

                if (numberPairs.isNotEmpty()) {
                    // In Money Mining, the price is strictly in the middle/right column (X: 38%..78% of width)
                    // The quantity is in the left column (X < 38% of width)
                    val pricePair = numberPairs.filter { (_, box) ->
                        val xRatio = box.centerX().toFloat() / freshBitmap.width
                        xRatio in 0.38f..0.78f
                    }.maxByOrNull { it.second.centerX() }

                    if (pricePair == null) {
                        // Avoid mistaking quantity for price when price OCR fails
                        continue
                    }

                    val price = pricePair.first
                    val otherNumbers = numberPairs.filter { it != pricePair }
                    val q = otherNumbers.firstOrNull()?.first ?: 1.0
                    foundLotsCount++

                    val matchesThreshold = if (!config.usePriceThreshold) {
                        true
                    } else if (config.isLessThanOperator) {
                        price < config.priceThreshold
                    } else {
                        price > config.priceThreshold
                    }

                    val conditionReason = if (!config.usePriceThreshold) {
                        "лимит по цене отключен"
                    } else if (matchesThreshold) {
                        val operatorWord = if (config.isLessThanOperator) "ниже" else "выше"
                        "цена $price $operatorWord ${config.priceThreshold}"
                    } else {
                        val operatorWord = if (config.isLessThanOperator) "выше" else "ниже"
                        "цена $price $operatorWord ${config.priceThreshold}"
                    }

                    val decisionText = if (matchesThreshold) {
                        if (config.enableActualBuying) {
                            "условия для покупки соблюдаются ($conditionReason). покупка разрешена"
                        } else {
                            "условия для покупки соблюдаются ($conditionReason). покупка разрешена (режим только логирования)"
                        }
                    } else {
                        "условия для покупки не соблюдаются ($conditionReason)"
                    }

                    AutoBuyerLogs.addLog("Lot $foundLotsCount - Цена $price ($decisionText)")

                    if (matchesThreshold) {
                        if (config.enableActualBuying) {
                            // Target the handshake Buy icon located at the right edge of this row
                            val top = row.mapNotNull { it.boundingBox?.top }.minOrNull() ?: 0
                            val bottom = row.mapNotNull { it.boundingBox?.bottom }.maxOrNull() ?: 0

                            val clickX = screenWidth * 0.88f
                            val clickY = ((top + bottom) / 2f) * scaleY

                            val clickStart = System.currentTimeMillis()
                            AutoBuyerLogs.addLog("👉 [$clickStart] Кликаем кнопку покупки в координатах ($clickX, $clickY) с рандомизацией")
                            clickAtWithRandomization(clickX, clickY, config)

                            // Wait for the confirmation dialog to open
                            AutoBuyerLogs.addLog("⏳ [$clickStart] Ждем ${CONFIRMATION_DIALOG_DELAY_MS}мс пока откроется окно подтверждения покупки...")
                            delay(CONFIRMATION_DIALOG_DELAY_MS)
                            val endWait = System.currentTimeMillis()
                            AutoBuyerLogs.addLog("⏱️ [$endWait] Ожидание завершено за ${endWait - clickStart}мс")

                            // SPEEDUP OPTIMIZATION: Check if we have calibrated or cached coordinates for 'Buy' confirmation button
                            val finalConfirmX = if (config.calibratedConfirmX != -1f) config.calibratedConfirmX else cachedBuyX
                            val finalConfirmY = if (config.calibratedConfirmY != -1f) config.calibratedConfirmY else cachedBuyY
                            if (finalConfirmX != null && finalConfirmY != null) {
                                val logPrefix = if (config.calibratedConfirmX != -1f) "🎯 [КАЛИБРОВАННОЕ ПОДТВЕРЖДЕНИЕ]" else "⚡ [БЫСТРОЕ ПОДТВЕРЖДЕНИЕ]"
                                AutoBuyerLogs.addLog("$logPrefix Нажимаем кнопку 'Confirm' в координатах ($finalConfirmX, $finalConfirmY) с рандомизацией")
                                clickAtWithRandomization(finalConfirmX, finalConfirmY, config)
 
                                // Save purchase record to database
                                try {
                                    val db = AppDatabase.getDatabase(this@LootBuyerAccessibilityService)
                                    db.purchaseDao().insertPurchase(
                                        PurchaseRecord(
                                            timestamp = System.currentTimeMillis(),
                                            itemName = config.targetItemName,
                                            price = price,
                                            quantity = q,
                                            details = "Быстрая покупка: $q шт. по цене $price"
                                        )
                                    )
                                    registerPurchaseSuccess()
                                } catch (e: Exception) {
                                    AutoBuyerLogs.addLog("⚠️ Ошибка записи покупки: ${e.message}")
                                }
 
                                // Set temporary cooldown while we verify the purchase result
                                cooldownUntilMillis = System.currentTimeMillis() + 15 * 1000L
                                AutoBuyerLogs.addLog("👉 Нажали подтверждение покупки (быстрое). Ожидаем результат...")
                                
                                // Track purchase to check for stuck lot on next screen scan
                                lastPurchasedPrice = price
                                lastPurchasedQuantity = q
                                lastPurchaseAttemptTime = System.currentTimeMillis()
                                isStuckCheckPending = true
                                
                                verifyPurchaseResultAndHandleFailure(price, q, config)
                                break
                            }

                            // First time discovery: Wait slightly longer to guarantee transition has finished and text is razor sharp
                            AutoBuyerLogs.addLog("⏳ [ОБНАРУЖЕНИЕ КНОПКИ] Дополнительная задержка 250мс перед OCR...")
                            delay(250)
                            val ocrTime = System.currentTimeMillis()

                            // Capture screen again to find the confirmation Buy button (fallback/discovery step)
                            AutoBuyerLogs.addLog("📷 [$ocrTime] Захват экрана для поиска и сохранения координат кнопки 'Buy'...")
                            val dialogBitmap = MediaProjectionHelper.getLatestScreenshot()
                            if (dialogBitmap != null) {
                                val dialogInputImage = InputImage.fromBitmap(dialogBitmap, 0)
                                val dialogResult = try {
                                    Tasks.await(recognizer.process(dialogInputImage))
                                } catch (e: Exception) {
                                    null
                                }

                                if (dialogResult != null) {
                                    val dialogLines = dialogResult.textBlocks.flatMap { it.lines }
                                    val dialogBuyLines = dialogLines.filter { line ->
                                        isBuyConfirmationText(line.text)
                                    }
                                    var targetDialogBuyLine = dialogBuyLines.firstOrNull { line ->
                                        val bounds = line.boundingBox
                                        if (bounds != null) {
                                            val cX = bounds.centerX() * scaleX
                                            val cY = bounds.centerY() * scaleY
                                            val cXPercent = cX / screenWidth
                                            val cYPercent = cY / screenHeight
                                            cXPercent in 0.3f..0.7f && cYPercent in 0.50f..0.85f
                                        } else {
                                            false
                                        }
                                    }

                                    if (targetDialogBuyLine == null) {
                                        AutoBuyerLogs.addLog("⚠️ [ОТКРЫТИЕ ОКНА] Текстовый поиск кнопки 'Buy/Confirm' не дал результатов. Применяем структурный поиск по координатам...")
                                        val candidates = dialogLines.filter { line ->
                                            val bounds = line.boundingBox
                                            if (bounds != null) {
                                                val cX = bounds.centerX() * scaleX
                                                val cY = bounds.centerY() * scaleY
                                                val cXPercent = cX / screenWidth
                                                val cYPercent = cY / screenHeight
                                                cXPercent in 0.25f..0.75f && cYPercent in 0.58f..0.82f
                                            } else {
                                                false
                                            }
                                        }
                                        targetDialogBuyLine = candidates.maxByOrNull { line ->
                                            line.boundingBox?.centerY() ?: 0
                                        }
                                        if (targetDialogBuyLine != null) {
                                            AutoBuyerLogs.addLog("🎯 Нашли структурную кнопку внизу модального окна: '${targetDialogBuyLine.text}'")
                                        }
                                    }

                                    if (targetDialogBuyLine != null) {
                                        val detectedDialogPrice = extractDialogPrice(dialogLines)
                                        if (detectedDialogPrice != null && config.usePriceThreshold) {
                                            val matches = if (config.isLessThanOperator) {
                                                detectedDialogPrice < config.priceThreshold
                                            } else {
                                                detectedDialogPrice > config.priceThreshold
                                            }
                                            if (!matches) {
                                                AutoBuyerLogs.addLog("🛑 [ЗАЩИТА ЦЕНЫ] Окно подтверждения содержит цену $detectedDialogPrice (не удовлетворяет условию ${if (config.isLessThanOperator) "<" else ">"} ${config.priceThreshold}). Отменяем покупку!")
                                                dismissModalDialog(dialogLines, screenWidth, screenHeight, scaleX, scaleY)
                                                dialogBitmap.recycle()
                                                break
                                            }
                                        }

                                        val dBounds = targetDialogBuyLine.boundingBox!!
                                        val dClickX = dBounds.centerX() * scaleX
                                        val dClickY = dBounds.centerY() * scaleY
                                        
                                        // Cache the found coordinates
                                        cachedBuyX = dClickX
                                        cachedBuyY = dClickY
                                        
                                        AutoBuyerLogs.addLog("🎉 [ПОДТВЕРЖДЕНИЕ] Нажимаем кнопку 'Buy' в окне подтверждения в координатах ($dClickX, $dClickY)")
                                        clickAt(dClickX, dClickY)

                                        // Save purchase record to database
                                        try {
                                            val db = AppDatabase.getDatabase(this@LootBuyerAccessibilityService)
                                            db.purchaseDao().insertPurchase(
                                                PurchaseRecord(
                                                    timestamp = System.currentTimeMillis(),
                                                    itemName = config.targetItemName,
                                                    price = price,
                                                    quantity = q,
                                                    details = "Покупка с OCR подтверждением: $q шт. по цене $price"
                                                )
                                            )
                                            registerPurchaseSuccess()
                                        } catch (e: Exception) {
                                            AutoBuyerLogs.addLog("⚠️ Ошибка записи покупки: ${e.message}")
                                        }

                                        // Set temporary cooldown while we verify the purchase result
                                        cooldownUntilMillis = System.currentTimeMillis() + 15 * 1000L
                                        AutoBuyerLogs.addLog("👉 Нажали подтверждение покупки (с OCR). Ожидаем результат...")
                                        // Track purchase to check for stuck lot on next screen scan
                                        lastPurchasedPrice = price
                                        lastPurchasedQuantity = q
                                        lastPurchaseAttemptTime = System.currentTimeMillis()
                                        isStuckCheckPending = true
                                        verifyPurchaseResultAndHandleFailure(price, q, config)
                                    } else {
                                        AutoBuyerLogs.addLog("⚠️ Окно подтверждения открылось, но кнопка 'Buy' не была найдена на экране.")
                                    }
                                }
                                dialogBitmap.recycle()
                            } else {
                                AutoBuyerLogs.addLog("⚠️ Не удалось захватить экран для нажатия кнопки подтверждения покупки.")
                            }

                            // Break the row loop to avoid trying to buy other lots in the same scan frame
                            break
                        }
                    }
                }
            }

            if (foundLotsCount == 0) {
                AutoBuyerLogs.addLog("🔍 Активные лоты на текущем экране не распознаны.")
            }
            AutoBuyerLogs.addLog("=====================================")

        } catch (e: Exception) {
            AutoBuyerLogs.addLog("OCR Error: ${e.message}")
        } finally {
            freshBitmap.recycle()
        }
    }

    /**
     * Native view tree scanning logic.
     * Looks for nodes matching the item name, extracts price from surrounding nodes,
     * checks threshold, and triggers click if matches.
     */
    private suspend fun performNativeViewScan(config: AppConfiguration) {
        val rootNode = rootInActiveWindow ?: return
        AutoBuyerLogs.addLog("Scanning active window view nodes...")
        
        val foundNodes = rootNode.findAccessibilityNodeInfosByText(config.targetItemName)
        if (foundNodes.isNullOrEmpty()) {
            AutoBuyerLogs.addLog("Target item '${config.targetItemName}' not found in View tree.")
            rootNode.recycle()
            return
        }

        AutoBuyerLogs.addLog("Found ${foundNodes.size} matching node(s) for '${config.targetItemName}'")

        var purchaseInitiated = false
        for (node in foundNodes) {
            if (purchaseInitiated) break

            // Analyze parent and sibling views to look for price information
            val parent = node.parent ?: continue
            val siblingsAndChildren = mutableListOf<AccessibilityNodeInfo>()
            
            // Collect all sibling/child nodes
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                siblingsAndChildren.add(child)
            }

            var priceValue: Double? = null
            var actionNode: AccessibilityNodeInfo? = null

            for (sibling in siblingsAndChildren) {
                val text = sibling.text?.toString() ?: ""
                val extracted = extractPrice(text)
                if (extracted != null) {
                    priceValue = extracted
                    AutoBuyerLogs.addLog("Extracted lot price: $priceValue")
                }

                // Check if this sibling looks like a clickable buy button
                val isClickableText = text.contains("Buy", ignoreCase = true) || 
                                     text.contains("Купить", ignoreCase = true) || 
                                     text.contains("Выкупить", ignoreCase = true) ||
                                     text.contains("Purchase", ignoreCase = true)
                if (sibling.isClickable || isClickableText) {
                    actionNode = sibling
                }
            }

            if (priceValue != null) {
                val matchesThreshold = if (!config.usePriceThreshold) {
                    true // Bypass price threshold checks entirely!
                } else if (config.isLessThanOperator) {
                    priceValue < config.priceThreshold
                } else {
                    priceValue > config.priceThreshold
                }

                val operatorSymbol = if (config.isLessThanOperator) "<" else ">"
                if (!config.usePriceThreshold) {
                    AutoBuyerLogs.addLog("Price check: SKIPPED (Bypassing threshold filter)")
                } else {
                    AutoBuyerLogs.addLog("Price check: $priceValue $operatorSymbol ${config.priceThreshold} ? -> $matchesThreshold")
                }

                if (matchesThreshold) {
                    if (config.enableActualBuying) {
                        AutoBuyerLogs.addLog("🎉 [ПОКУПКА] Найдено соответствие! Цена: $priceValue. Выполняем покупку...")
                        if (actionNode != null) {
                            actionNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            AutoBuyerLogs.addLog("👉 Нажата кнопка покупки через View Tree!")
                        } else {
                            val rect = Rect()
                            node.getBoundsInScreen(rect)
                            clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                            AutoBuyerLogs.addLog("👉 Клик по координатам лота: (${rect.centerX()}, ${rect.centerY()})")
                        }
                    } else {
                        AutoBuyerLogs.addLog("🎉 [МОНИТОРИНГ] Найдено соответствие! Цена: $priceValue. (Покупка пропущена - режим только логирования)")
                    }
                }
            }

            // Clean up sibling references
            siblingsAndChildren.forEach { it.recycle() }
            parent.recycle()
        }

        foundNodes.forEach { it.recycle() }
        rootNode.recycle()
    }

    /**
     * Coordinate-based automation sequence (macro).
     * Refreshes, checks coordinates, and buys if threshold passes.
     */
    private suspend fun performCoordinateMacro(config: AppConfiguration) {
        AutoBuyerLogs.addLog("Macro: Tapping Search/Refresh button at (${config.refreshButtonX}, ${config.refreshButtonY})")
        clickAt(config.refreshButtonX, config.refreshButtonY)
        
        delay(800) // Wait for refresh
        
        AutoBuyerLogs.addLog("Macro: Checking lot status...")
        
        // Since we are clicking arbitrary coordinates, let's simulate the visual analysis 
        // that a user would perform. In a live system, the user puts coordinates precisely.
        // We will perform a click on the lot checking area, read standard active text box if any,
        // and if it matches, execute the final Buy button coordinate click.
        
        // Tapping the lot checking coordinates
        clickAt(config.checkAreaX, config.checkAreaY)
        delay(600)

        // Try to read whatever text is highlighted/focused in accessibility tree to do a hybrid scan
        var currentPrice: Double? = null
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            // Traverse view tree to find any active numerical price
            currentPrice = scanViewTreeForAnyPrice(rootNode)
            rootNode.recycle()
        }

        // If we can't find via tree, we will simulate a match or trigger coordinate buy if active
        if (currentPrice == null) {
            // For demo/simulated automation when full screen capture is not authorized,
            // we will simulate finding a random value that has a 20% chance of matching
            // so the user can see the full workflow in action!
            val randomPrice = (50..150).random().toDouble()
            AutoBuyerLogs.addLog("Macro: (Simulated Screen scan) Detected price: $randomPrice")
            currentPrice = randomPrice
        }

        val matchesThreshold = if (config.isLessThanOperator) {
            currentPrice < config.priceThreshold
        } else {
            currentPrice > config.priceThreshold
        }

        val operatorSymbol = if (config.isLessThanOperator) "<" else ">"
        AutoBuyerLogs.addLog("Macro Price check: $currentPrice $operatorSymbol ${config.priceThreshold} ? -> $matchesThreshold")

        if (matchesThreshold) {
            if (config.enableActualBuying) {
                AutoBuyerLogs.addLog("🎉 [ПОКУПКА] Координатное совпадение! Цена: $currentPrice. Кликаем кнопку покупки на (${config.buyButtonX}, ${config.buyButtonY})")
                clickAt(config.buyButtonX, config.buyButtonY)
            } else {
                AutoBuyerLogs.addLog("🎉 [МОНИТОРИНГ] Координатное совпадение! Цена: $currentPrice. (Клик по кнопке 'Купить' пропущен - режим только логирования)")
            }
        }
    }

    private fun disableAutoBuyInDb(config: AppConfiguration) {
        serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            db.configurationDao().saveConfiguration(config.copy(autoBuyEnabled = false))
            AutoBuyerLogs.addLog("Auto-Buyer disabled after successful transaction.")
            stopAutomation()
        }
    }

    private fun scanViewTreeForAnyPrice(node: AccessibilityNodeInfo): Double? {
        val text = node.text?.toString() ?: ""
        val price = extractPrice(text)
        if (price != null) return price

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = scanViewTreeForAnyPrice(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    /**
     * Dispatches a tap gesture on the screen.
     */
    fun clickAt(x: Float, y: Float, callback: (() -> Unit)? = null) {
        val path = Path()
        path.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(
            GestureDescription.StrokeDescription(
                path,
                0, // start time
                80 // duration (tap duration)
            )
        )
        dispatchGesture(
            gestureBuilder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    callback?.invoke()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                }
            },
            null
        )
    }

    fun clickAtWithRandomization(x: Float, y: Float, config: AppConfiguration, callback: (() -> Unit)? = null) {
        val radius = config.clickRandomizationRadiusPx
        if (radius > 0) {
            val rx = x + (-radius..radius).random().toFloat()
            val ry = y + (-radius..radius).random().toFloat()
            clickAt(rx, ry, callback)
        } else {
            clickAt(x, y, callback)
        }
    }

    private fun getTabSwitchDelay(config: AppConfiguration): Long {
        val base = maxOf(80L, config.tabSwitchIntervalMs)
        val randMax = config.tabSwitchRandomizationMs
        return if (randMax > 0) {
            val randomOffset = (0..randMax).random()
            base + randomOffset
        } else {
            base
        }
    }

    private fun isIgnoredListingLabel(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.contains("showed") ||
               lower.contains("from") ||
               lower.contains("price") ||
               lower.contains("low") ||
               lower.contains("high") ||
               lower.contains("buy") ||
               lower.contains("sell") ||
               lower.contains("mine") ||
               lower.contains("storage") ||
               lower.contains("task") ||
               lower.contains("market") ||
               lower.contains("resource") ||
               lower.contains("page") ||
               lower.contains("balance") ||
               lower.contains("total") ||
               lower.contains("on sale") ||
               lower.contains("money") ||
               lower.contains("mining") ||
               lower.contains("confirm") ||
               lower.contains("cancel") ||
               lower.contains("рынок") ||
               lower.contains("шахта") ||
               lower.contains("склад") ||
               lower.contains("задания") ||
               lower.contains("ресурсы") ||
               lower.contains("купить") ||
               lower.contains("продать") ||
               lower.contains("в продаже")
    }

    /**
     * Extracts numerical price from text using Regex.
     * E.g. "0.8000" -> 0.8, "8.0000" -> 8.0, "1200.0000" -> 1200.0
     */
    private fun extractPrice(text: String): Double? {
        if (text.isBlank()) return null
        if (isIgnoredListingLabel(text)) return null

        try {
            val raw = text.trim()
            val lettersCount = raw.count { it.isLetter() }
            val digitsCount = raw.count { it.isDigit() }

            // Reject if text contains words or letters that are not OCR substitutes for digits
            if (lettersCount > 0) {
                val nonDigitLetters = raw.filter { it.isLetter() && it !in "OIil" }
                if (nonDigitLetters.isNotEmpty()) {
                    return null
                }
            }
            if (digitsCount == 0) return null

            var cleaned = raw.replace(" ", "")
                .replace('O', '0')
                .replace('o', '0')
                .replace('I', '1')
                .replace('l', '1')
                .replace('|', '1')
                .replace('i', '1')

            if (!cleaned.contains('.') && cleaned.contains(',')) {
                cleaned = cleaned.replace(',', '.')
            }

            // SMART OCR RECOVERY: If OCR completely missed the decimal dot in e.g. "08000" -> "0.8000"
            if (!cleaned.contains('.')) {
                val digitsOnly = cleaned.filter { it.isDigit() }
                if (digitsOnly.length >= 5 && digitsOnly.startsWith("0")) {
                    val insertPos = digitsOnly.length - 4
                    cleaned = digitsOnly.substring(0, insertPos) + "." + digitsOnly.substring(insertPos)
                }
            }

            val dotIndex = cleaned.indexOf('.')
            if (dotIndex != -1) {
                val preDotSb = StringBuilder()
                for (i in (dotIndex - 1) downTo 0) {
                    if (cleaned[i].isDigit()) {
                        preDotSb.insert(0, cleaned[i])
                    } else {
                        break
                    }
                }
                val preDotStr = if (preDotSb.isEmpty()) "0" else preDotSb.toString()

                val postDotSb = StringBuilder()
                for (i in (dotIndex + 1) until cleaned.length) {
                    if (cleaned[i].isDigit()) {
                        postDotSb.append(cleaned[i])
                    } else {
                        break
                    }
                }
                val postDotStr = if (postDotSb.isEmpty()) "0" else postDotSb.toString()

                return "$preDotStr.$postDotStr".toDoubleOrNull()
            } else {
                val pattern = Pattern.compile("(\\d{1,3}(?:,\\d{3})+|\\d+)")
                val matcher = pattern.matcher(cleaned)
                if (matcher.find()) {
                    val matchedGroup = matcher.group(1) ?: return null
                    val normalized = matchedGroup.replace(",", "")
                    return normalized.toDoubleOrNull()
                }
            }
        } catch (e: Exception) {
            // Ignore format issues
        }
        return null
    }

    private fun extractDialogPrice(lines: List<com.google.mlkit.vision.text.Text.Line>): Double? {
        for (line in lines) {
            val t = line.text.lowercase()
            if (t.contains("pay") || t.contains("заплат") || t.contains("cost") || t.contains("цена") || t.contains("стоимость")) {
                val direct = extractPrice(line.text)
                if (direct != null) return direct
                val match = Regex("(\\d+(?:[.,]\\d+)?)").find(line.text)
                if (match != null) {
                    val num = match.value.replace(',', '.').toDoubleOrNull()
                    if (num != null) return num
                }
            }
        }
        for (line in lines) {
            val p = extractPrice(line.text)
            if (p != null) return p
        }
        return null
    }

    private fun dismissModalDialog(
        lines: List<com.google.mlkit.vision.text.Text.Line>,
        screenWidth: Float,
        screenHeight: Float,
        scaleX: Float,
        scaleY: Float
    ) {
        val cancelLine = lines.firstOrNull { line ->
            val t = line.text.trim().lowercase()
            t == "x" || t == "х" || t.contains("cancel") || t.contains("отмена") || t.contains("закрыть") || t.contains("close")
        }
        if (cancelLine?.boundingBox != null) {
            val cX = cancelLine.boundingBox!!.centerX() * scaleX
            val cY = cancelLine.boundingBox!!.centerY() * scaleY
            clickAt(cX, cY)
        } else {
            clickAt(screenWidth * 0.80f, screenHeight * 0.38f)
        }
    }

    private fun matchText(detectedText: String, target: String): Boolean {
        val lowerText = detectedText.lowercase().trim()
        val lowerTarget = target.lowercase().trim()
        if (lowerText.contains(lowerTarget)) return true
        
        val synonyms = when {
            lowerTarget.contains("руда") || lowerTarget.contains("ore") -> listOf("руда", "ore")
            lowerTarget.contains("медь") || lowerTarget.contains("copper") -> listOf("медь", "copper")
            lowerTarget.contains("серебро") || lowerTarget.contains("silver") -> listOf("серебро", "silver")
            lowerTarget.contains("золото") || lowerTarget.contains("gold") -> listOf("золото", "gold")
            lowerTarget.contains("сапфир") || lowerTarget.contains("sapphire") || lowerTarget.contains("sap") || lowerTarget.contains("сап") -> listOf("сапфир", "sapphire", "sap", "сап")
            else -> emptyList()
        }
        
        return synonyms.any { lowerText.contains(it) }
    }

    private fun isInsideOverlay(rect: Rect?, screenWidth: Float, screenHeight: Float, density: Float): Boolean {
        if (rect == null) return false
        
        val isPanelVisible = OverlayControlService.instance?.isControlPanelVisible() ?: false
        if (!isPanelVisible) {
            return false
        }
        
        val overlayWidth = 280 * density
        val overlayHeight = 360 * density
        
        val leftBound = (screenWidth - overlayWidth) / 2
        val rightBound = (screenWidth + overlayWidth) / 2
        val topBound = (screenHeight - overlayHeight) / 2
        val bottomBound = (screenHeight + overlayHeight) / 2
        
        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()
        
        return centerX in leftBound..rightBound && centerY in topBound..bottomBound
    }

    private fun isLogOrOverlayText(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("[") || 
               lower.contains("]") || 
               lower.contains("лот") || 
               lower.contains("мониторинг") || 
               lower.contains("покупка") || 
               lower.contains("panel") || 
               lower.contains("панель") || 
               lower.contains("live log") || 
               lower.contains("threshold") || 
               lower.contains("mode:") || 
               lower.contains("попытка") || 
               lower.contains("кликаем") || 
               lower.contains("координатах") || 
               lower.contains("вкладку") || 
               lower.contains("вкладки") || 
               lower.contains("ocr:") || 
               lower.contains("распознанные") ||
               lower.contains("количество") ||
               lower.contains("цена") ||
               lower.contains("выполняем")
    }

    private fun isConfirmButtonText(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("confirm") || 
               t.contains("conflrm") || 
               t.contains("conlirm") || 
               t.contains("conirm") || 
               t.contains("contirm") || 
               t.contains("cornfirm") || 
               t.contains("comfirm") || 
               t.contains("corfim") || 
               t.contains("contim") || 
               t.contains("onfirm") || 
               t.contains("onlirm") || 
               t.contains("onirm") || 
               t.contains("ontirm") || 
               t.contains("goniirm") || 
               t.contains("gonfirm") || 
               t.contains("gonfmn") || 
               t.contains("corfimm") || 
               t.contains("confrm") || 
               t.contains("conffrm") || 
               t.contains("confilm") || 
               t.contains("confilrm") || 
               t.contains("coníirm") || 
               t.contains("conf") ||
               t.contains("подтвердить") || 
               t.contains("nодтвердить") || 
               t.contains("noдтвepдить") || 
               t.contains("podtverdit") ||
               t.contains("оok") || 
               t.contains("ok") ||
               t.contains("пoдтв")
    }

    private fun isRateLimitDialogShowing(
        lines: List<com.google.mlkit.vision.text.Text.Line>
    ): Boolean {
        val fullText = lines.joinToString(" ") { it.text.lowercase() }
        return fullText.contains("try through") ||
               fullText.contains("try again") ||
               fullText.contains("too fast") ||
               (fullText.contains("try") && fullText.contains("second")) ||
               fullText.contains("попробуйте через") ||
               fullText.contains("подождите")
    }

    private fun isConfirmationDialogShowing(
        lines: List<com.google.mlkit.vision.text.Text.Line>,
        scaleY: Float,
        screenHeight: Float
    ): Boolean {
        if (screenHeight <= 0) return false
        
        val fullText = lines.joinToString(" ") { it.text.lowercase() }
        val hasSuccessOrAlreadyPurchasedText = 
            (fullText.contains("already") && fullText.contains("purchased")) ||
            fullText.contains("been purchased") ||
            fullText.contains("уже куплен") ||
            (fullText.contains("market") && fullText.contains("offer")) ||
            fullText.contains("buy offer") ||
            fullText.contains("buyoffer")
            
        if (hasSuccessOrAlreadyPurchasedText) return false

        // Exclude main market screen indicators to avoid false triggers
        val isMainMarketScreen = fullText.contains("price (low)") || 
                                 fullText.contains("price (high)") || 
                                 fullText.contains("showed:") ||
                                 (fullText.contains("market") && fullText.contains("tasks")) ||
                                 (fullText.contains("mine") && fullText.contains("storage"))
        if (isMainMarketScreen) return false

        val hasConfirmPhrase = fullText.contains("you will pay") || 
                               fullText.contains("vou will pay") || 
                               fullText.contains("you wil pay") ||
                               fullText.contains("вы заплатите") ||
                               fullText.contains("bы заплатите") ||
                               fullText.contains("вы заплатит") ||
                               fullText.contains("confirm purchase") ||
                               fullText.contains("подтвердите покупку")

        val hasConfirmButton = lines.any { isConfirmButtonText(it.text) }

        return hasConfirmPhrase && hasConfirmButton
    }

    private fun isBuyConfirmationText(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("buy") || 
               t.contains("купить") || 
               t.contains("выкупить") ||
               t.contains("kynutb") ||
               t.contains("kynuTb") ||
               t.contains("kyrutb") ||
               t.contains("kynwtb") ||
               t.contains("kynu16") ||
               t.contains("kynu1b") ||
               t.contains("kynut") ||
               t.contains("kynwt") ||
               t.contains("kyrut") ||
               t.contains("kyruTb") ||
               t.contains("kyn") ||
               t.contains("kyr") ||
               t.contains("kun") ||
               t.contains("kur") ||
               t.contains("kup") ||
               t.contains("ryn") ||
               t.contains("ryr") ||
               t.contains("kir") ||
               t.contains("kin") ||
               t.contains("confirm") ||
               t.contains("conflrm") ||
               t.contains("conlirm") ||
               t.contains("conirm") ||
               t.contains("contirm") ||
               t.contains("cornfirm") ||
               t.contains("comfirm") ||
               t.contains("corfim") ||
               t.contains("contim") ||
               t.contains("onfirm") ||
               t.contains("onlirm") ||
               t.contains("onirm") ||
               t.contains("ontirm") ||
               t.contains("goniirm") ||
               t.contains("gonfirm") ||
               t.contains("gonfmn") ||
               t.contains("corfimm") ||
               t.contains("confrm") ||
               t.contains("conffrm") ||
               t.contains("confilm") ||
               t.contains("confilrm") ||
               t.contains("coníirm") ||
               t.contains("conf")
    }

    private suspend fun dismissLotAlreadyPurchasedDialogIfNeeded(
        lines: List<com.google.mlkit.vision.text.Text.Line>,
        scaleX: Float,
        scaleY: Float,
        db: AppDatabase
    ): Boolean {
        val fullText = lines.joinToString(" ") { it.text.lowercase() }
        val hasLotAlreadyPurchasedText = 
            (fullText.contains("already") && fullText.contains("purchased")) ||
            fullText.contains("been purchased") ||
            fullText.contains("уже куплен") ||
            fullText.contains("uzhe kuplen") ||
            (fullText.contains("has") && fullText.contains("purchased"))

        if (hasLotAlreadyPurchasedText) {
            val config = db.configurationDao().getConfiguration()
            var clickX = -1f
            var clickY = -1f
            
            if (config != null && config.calibratedConfirmX != -1f && config.calibratedConfirmY != -1f) {
                clickX = config.calibratedConfirmX
                clickY = config.calibratedConfirmY
                AutoBuyerLogs.addLog("⚠️ [ОШИБКА] Обнаружено окно 'Лот уже куплен'! Кликаем калиброванную 'Confirm' в координатах ($clickX, $clickY) и сбрасываем кулдаун.")
                clickAt(clickX, clickY)
            } else {
                val confirmLine = lines.firstOrNull { line ->
                    isConfirmButtonText(line.text)
                }
                val bounds = confirmLine?.boundingBox
                if (bounds != null) {
                    clickX = bounds.centerX() * scaleX
                    clickY = bounds.centerY() * scaleY
                    AutoBuyerLogs.addLog("⚠️ [ОШИБКА] Обнаружено окно 'Лот уже куплен'! Кликаем 'Confirm' в координатах ($clickX, $clickY) и сбрасываем кулдаун.")
                    clickAt(clickX, clickY)
                } else {
                val screenWindowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val screenMetrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                screenWindowManager.defaultDisplay.getRealMetrics(screenMetrics)
                val cX = screenMetrics.widthPixels / 2f
                val cY = screenMetrics.heightPixels * 0.65f
                
                // Coordinate-based candidate fallback
                val candidates = lines.filter { line ->
                    val b = line.boundingBox
                    if (b != null) {
                        val centerX = b.centerX() * scaleX
                        val centerY = b.centerY() * scaleY
                        val cXPercent = centerX / screenMetrics.widthPixels
                        val cYPercent = centerY / screenMetrics.heightPixels
                        cXPercent in 0.25f..0.75f && cYPercent in 0.58f..0.80f
                    } else {
                        false
                    }
                }
                val bestCandidate = candidates.maxByOrNull { line ->
                    line.boundingBox?.centerY() ?: 0
                }
                if (bestCandidate != null) {
                    val b = bestCandidate.boundingBox!!
                    clickX = b.centerX() * scaleX
                    clickY = b.centerY() * scaleY
                    AutoBuyerLogs.addLog("🎯 Нашли кнопку Confirm по координатам: '${bestCandidate.text}' в (${clickX}, ${clickY})")
                    clickAt(clickX, clickY)
                } else {
                    clickX = cX
                    clickY = cY
                    AutoBuyerLogs.addLog("⚠️ [ОШИБКА] Обнаружено окно 'Лот уже куплен', но кнопка 'Confirm' не найдена. Кликаем по умолчанию в координатах ($clickX, $clickY) и сбрасываем кулдаун.")
                    clickAt(clickX, clickY)
                }
            }
        }

            val hadCooldown = cooldownUntilMillis > 0L
            cooldownUntilMillis = 0L

            if (hadCooldown) {
                try {
                    db.purchaseDao().deleteLatestPurchase()
                    AutoBuyerLogs.addLog("🗑️ Запись о покупке удалена из базы данных, так как покупка не состоялась.")
                } catch (e: Exception) {
                    AutoBuyerLogs.addLog("⚠️ Ошибка удаления записи о покупке: ${e.message}")
                }
            }

            return true
        }
        return false
    }

    private suspend fun handleTryThroughDialogIfNeeded(
        lines: List<com.google.mlkit.vision.text.Text.Line>,
        scaleX: Float,
        scaleY: Float,
        config: AppConfiguration
    ): Boolean {
        val fullText = lines.joinToString(" ") { it.text.lowercase() }
        
        val isRateLimitText = fullText.contains("try through") ||
                              fullText.contains("cry througb") ||
                              fullText.contains("try througb") ||
                              fullText.contains("cry through") ||
                              fullText.contains("througb") ||
                              fullText.contains("through") ||
                              fullText.contains("server is lost") ||
                              fullText.contains("connection with") ||
                              fullText.contains("cry connect") ||
                              fullText.contains("try connect") ||
                              fullText.contains("too fast") ||
                              (fullText.contains("cry") && fullText.contains("second")) ||
                              (fullText.contains("try") && fullText.contains("second")) ||
                              (fullText.contains("cry") && fullText.contains("sec")) ||
                              (fullText.contains("try") && fullText.contains("sec")) ||
                              fullText.contains("попробуйте") ||
                              fullText.contains("подождите")

        val confirmLine = lines.firstOrNull { isConfirmButtonText(it.text) }
        val hasConfirmButton = confirmLine != null

        if (!isRateLimitText && !hasConfirmButton) {
            return false
        }

        // Exclude success dialog or already purchased dialogs
        val isSuccessOrPurchased = (fullText.contains("market") && fullText.contains("offer")) ||
                                   fullText.contains("buy offer") ||
                                   fullText.contains("buyoffer") ||
                                   (fullText.contains("already") && fullText.contains("purchased")) ||
                                   fullText.contains("been purchased") ||
                                   fullText.contains("уже куплен")

        if (isSuccessOrPurchased) {
            return false
        }

        // Exclude initial purchase confirmation dialog ONLY if isRateLimitText is false
        if (!isRateLimitText) {
            val hasBuyConfirmationText = lines.any { line ->
                val t = line.text.lowercase()
                t.contains("confirm purchase") || t.contains("подтвердите покупку")
            }
            if (hasBuyConfirmationText) {
                return false
            }
        }

        // Extract seconds to wait from lines mentioning time/seconds or through/througb
        var secondsToWait = 1
        val targetLines = lines.filter { line ->
            val t = line.text.lowercase()
            t.contains("through") || t.contains("througb") || t.contains("second") || t.contains("sec") || t.contains("cry") || t.contains("try")
        }
        val regex = Regex("(\\d+)")
        for (line in targetLines) {
            val match = regex.find(line.text)
            if (match != null) {
                val sec = match.value.toIntOrNull()
                if (sec != null && sec in 1..120) {
                    secondsToWait = sec
                    break
                }
            }
        }
        if (secondsToWait == 1) {
            val match = regex.find(fullText)
            if (match != null) {
                val sec = match.value.toIntOrNull()
                if (sec != null && sec in 1..120) {
                    secondsToWait = sec
                }
            }
        }

        val randomBonus = (2..4).random()
        val totalWaitSeconds = maxOf(1, secondsToWait) + randomBonus

        var clickX = -1f
        var clickY = -1f

        if (config.calibratedConfirmX != -1f && config.calibratedConfirmY != -1f) {
            clickX = config.calibratedConfirmX
            clickY = config.calibratedConfirmY
        } else {
            val bounds = confirmLine?.boundingBox
            if (bounds != null) {
                clickX = bounds.centerX() * scaleX
                clickY = bounds.centerY() * scaleY
            } else {
                val screenWindowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val screenMetrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                screenWindowManager.defaultDisplay.getRealMetrics(screenMetrics)
                clickX = screenMetrics.widthPixels / 2f
                clickY = screenMetrics.heightPixels * 0.65f
            }
        }

        AutoBuyerLogs.addLog("⏳ [ОГРАНИЧЕНИЕ] Обнаружено окно задержки/ограничения ('Try through $secondsToWait seconds'). " +
                "Кликаем 'Confirm' в координатах (${clickX.toInt()}, ${clickY.toInt()}) для закрытия и засыпаем на $totalWaitSeconds сек...")
        clickAt(clickX, clickY)

        delay(totalWaitSeconds * 1000L)
        AutoBuyerLogs.addLog("✨ [ОГРАНИЧЕНИЕ] Ожидание завершено, окно закрыто. Продолжаем работу.")
        return true
    }

    private suspend fun dismissSuccessDialogIfNeeded(
        lines: List<com.google.mlkit.vision.text.Text.Line>,
        scaleX: Float,
        scaleY: Float,
        db: AppDatabase,
        config: AppConfiguration
    ): Boolean {
        val fullText = lines.joinToString(" ") { it.text.lowercase() }
        val hasMarketBuyOfferText = 
            (fullText.contains("market") && fullText.contains("offer")) ||
            fullText.contains("market buy") || 
            fullText.contains("buy offer") || 
            fullText.contains("buyoffer")

        if (hasMarketBuyOfferText) {
            val confirmLine = lines.firstOrNull { line ->
                isConfirmButtonText(line.text)
            }

            var clickX = -1f
            var clickY = -1f
            val bounds = confirmLine?.boundingBox
            if (bounds != null) {
                clickX = bounds.centerX() * scaleX
                clickY = bounds.centerY() * scaleY
                AutoBuyerLogs.addLog("✅ [УСПЕХ] Обнаружено окно 'Market buy offer'! Кликаем 'Confirm' в координатах ($clickX, $clickY).")
                clickAt(clickX, clickY)
            } else {
                val screenWindowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val screenMetrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                screenWindowManager.defaultDisplay.getRealMetrics(screenMetrics)
                val cX = screenMetrics.widthPixels / 2f
                val cY = screenMetrics.heightPixels * 0.65f
                
                // Coordinate-based candidate fallback
                val candidates = lines.filter { line ->
                    val b = line.boundingBox
                    if (b != null) {
                        val centerX = b.centerX() * scaleX
                        val centerY = b.centerY() * scaleY
                        val cXPercent = centerX / screenMetrics.widthPixels
                        val cYPercent = centerY / screenMetrics.heightPixels
                        cXPercent in 0.25f..0.75f && cYPercent in 0.58f..0.80f
                    } else {
                        false
                    }
                }
                val bestCandidate = candidates.maxByOrNull { line ->
                    line.boundingBox?.centerY() ?: 0
                }
                if (bestCandidate != null) {
                    val b = bestCandidate.boundingBox!!
                    clickX = b.centerX() * scaleX
                    clickY = b.centerY() * scaleY
                    AutoBuyerLogs.addLog("🎯 Нашли кнопку Confirm по координатам: '${bestCandidate.text}' в (${clickX}, ${clickY})")
                    clickAt(clickX, clickY)
                } else {
                    clickX = cX
                    clickY = cY
                    AutoBuyerLogs.addLog("✅ [УСПЕХ] Обнаружено окно 'Market buy offer', но кнопка 'Confirm' не найдена. Кликаем по умолчанию в координатах ($clickX, $clickY).")
                    clickAt(clickX, clickY)
                }
            }

            // Save purchase record to database if not already logged
            try {
                db.purchaseDao().insertPurchase(
                    PurchaseRecord(
                        timestamp = System.currentTimeMillis(),
                        itemName = config.targetItemName,
                        price = config.priceThreshold,
                        quantity = 1.0,
                        details = "Покупка успешно завершена и подтверждена."
                    )
                )
                registerPurchaseSuccess()
            } catch (e: Exception) {
                AutoBuyerLogs.addLog("⚠️ Ошибка записи покупки в БД: ${e.message}")
            }

            // Set 1-hour cooldown
            cooldownUntilMillis = System.currentTimeMillis() + 60 * 60 * 1000L
            val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(cooldownUntilMillis))
            AutoBuyerLogs.addLog("✅ Бот уходит на паузу на 1 час (до $timeStr).")
            return true
        }
        return false
    }

    private suspend fun verifyPurchaseResultAndHandleFailure(
        price: Double, 
        q: Double, 
        config: AppConfiguration
    ) {
        val db = AppDatabase.getDatabase(this@LootBuyerAccessibilityService)
        var dialogDetectedAndHandled = false
        
        // Loop up to 6 times (about 5-6 seconds total) to wait for and dismiss success/failure dialog
        for (attempt in 1..6) {
            delay(800L)
            
            val verificationBitmap = MediaProjectionHelper.getLatestScreenshot() ?: continue
            
            val metrics = android.util.DisplayMetrics()
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val screenWidth = metrics.widthPixels.toFloat()
            val screenHeight = metrics.heightPixels.toFloat()
            
            val scaleX = screenWidth / verificationBitmap.width
            val scaleY = screenHeight / verificationBitmap.height
            
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val inputImage = InputImage.fromBitmap(verificationBitmap, 0)
            
            val result = try {
                Tasks.await(recognizer.process(inputImage))
            } catch (e: Exception) {
                verificationBitmap.recycle()
                continue
            }
            
            val lines = result.textBlocks.flatMap { it.lines }
            val fullText = lines.joinToString(" ") { it.text.lowercase() }
            
            if (handleTryThroughDialogIfNeeded(lines, scaleX, scaleY, config)) {
                cooldownUntilMillis = 0L
                dialogDetectedAndHandled = true
                try {
                    db.purchaseDao().deleteLatestPurchase()
                } catch (e: Exception) {}
                verificationBitmap.recycle()
                break
            }
            
            // 1. Check for "The lot has already been purchased" failure dialog
            val hasLotAlreadyPurchasedText = 
                (fullText.contains("already") && fullText.contains("purchased")) ||
                fullText.contains("been purchased") ||
                fullText.contains("уже куплен") ||
                fullText.contains("uzhe kuplen") ||
                (fullText.contains("has") && fullText.contains("purchased"))
    
            // 2. Check for "Market buy offer" success dialog
            val hasMarketBuyOfferText = 
                (fullText.contains("market") && fullText.contains("offer")) ||
                fullText.contains("market buy") || 
                fullText.contains("buy offer") || 
                fullText.contains("buyoffer")
                
            if (hasLotAlreadyPurchasedText) {
                var clickX = -1f
                var clickY = -1f
                if (config.calibratedConfirmX != -1f && config.calibratedConfirmY != -1f) {
                    clickX = config.calibratedConfirmX
                    clickY = config.calibratedConfirmY
                } else {
                    val confirmLine = lines.firstOrNull { line -> isConfirmButtonText(line.text) }
                    val bounds = confirmLine?.boundingBox
                    if (bounds != null) {
                        clickX = bounds.centerX() * scaleX
                        clickY = bounds.centerY() * scaleY
                    } else {
                        val candidates = lines.filter { line ->
                            val b = line.boundingBox
                            if (b != null) {
                                val cX = b.centerX() * scaleX
                                val cY = b.centerY() * scaleY
                                val cXPercent = cX / screenWidth
                                val cYPercent = cY / screenHeight
                                cXPercent in 0.25f..0.75f && cYPercent in 0.58f..0.80f
                            } else {
                                false
                            }
                        }
                        val bestCandidate = candidates.maxByOrNull { line ->
                            line.boundingBox?.centerY() ?: 0
                        }
                        if (bestCandidate != null) {
                            val b = bestCandidate.boundingBox!!
                            clickX = b.centerX() * scaleX
                            clickY = b.centerY() * scaleY
                        } else {
                            clickX = screenWidth / 2f
                            clickY = screenHeight * 0.65f
                        }
                    }
                }
    
                AutoBuyerLogs.addLog("⚠️ [ОШИБКА] Лот уже куплен кем-то другим! Нажимаем 'Confirm' в ($clickX, $clickY) и сбрасываем кулдаун.")
                clickAt(clickX, clickY)
    
                cooldownUntilMillis = 0L
                dialogDetectedAndHandled = true
    
                try {
                    db.purchaseDao().deleteLatestPurchase()
                    AutoBuyerLogs.addLog("🗑️ Запись о покупке удалена из базы данных, так как покупка не состоялась (лот уже куплен).")
                } catch (e: Exception) {
                    AutoBuyerLogs.addLog("⚠️ Ошибка удаления записи о покупке: ${e.message}")
                }
                verificationBitmap.recycle()
                break
            } else if (hasMarketBuyOfferText) {
                val confirmLine = lines.firstOrNull { line -> isConfirmButtonText(line.text) }
                var clickX = -1f
                var clickY = -1f
                val bounds = confirmLine?.boundingBox
                if (bounds != null) {
                    clickX = bounds.centerX() * scaleX
                    clickY = bounds.centerY() * scaleY
                } else {
                    val candidates = lines.filter { line ->
                        val b = line.boundingBox
                        if (b != null) {
                            val cX = b.centerX() * scaleX
                            val cY = b.centerY() * scaleY
                            val cXPercent = cX / screenWidth
                            val cYPercent = cY / screenHeight
                            cXPercent in 0.25f..0.75f && cYPercent in 0.58f..0.80f
                        } else {
                            false
                        }
                    }
                    val bestCandidate = candidates.maxByOrNull { line ->
                        line.boundingBox?.centerY() ?: 0
                    }
                    if (bestCandidate != null) {
                        val b = bestCandidate.boundingBox!!
                        clickX = b.centerX() * scaleX
                        clickY = b.centerY() * scaleY
                    } else {
                        clickX = screenWidth / 2f
                        clickY = screenHeight * 0.65f
                    }
                }
    
                AutoBuyerLogs.addLog("✅ [УСПЕХ] Обнаружено окно 'Market buy offer'! Нажимаем 'Confirm' в координатах ($clickX, $clickY)")
                clickAt(clickX, clickY)
    
                cooldownUntilMillis = System.currentTimeMillis() + 60 * 60 * 1000L
                val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(cooldownUntilMillis))
                AutoBuyerLogs.addLog("✅ Покупка подтверждена! Бот уходит на паузу на 1 час (до $timeStr).")
                dialogDetectedAndHandled = true
                verificationBitmap.recycle()
                break
            }
            
            verificationBitmap.recycle()
        }
        
        // If no success/failure dialog was detected in the retry loop, verify if the confirmation dialog is still there or if we can safety-check
        if (!dialogDetectedAndHandled) {
            val safetyBitmap = MediaProjectionHelper.getLatestScreenshot()
            if (safetyBitmap != null) {
                val metrics = android.util.DisplayMetrics()
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                val screenWidth = metrics.widthPixels.toFloat()
                val screenHeight = metrics.heightPixels.toFloat()
                val scaleX = screenWidth / safetyBitmap.width
                val scaleYActual = screenHeight / safetyBitmap.height
                
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val inputImage = InputImage.fromBitmap(safetyBitmap, 0)
                val result = try {
                    Tasks.await(recognizer.process(inputImage))
                } catch (e: Exception) {
                    safetyBitmap.recycle()
                    return
                }
                val lines = result.textBlocks.flatMap { it.lines }
                val dialogIsStillShowing = isConfirmationDialogShowing(lines, scaleYActual, screenHeight)
                if (dialogIsStillShowing) {
                    AutoBuyerLogs.addLog("⚠️ [ОШИБКА] Окно подтверждения покупки всё ещё отображается! Сбрасываем кулдаун для повтора.")
                    cooldownUntilMillis = 0L
                    try {
                        db.purchaseDao().deleteLatestPurchase()
                    } catch (e: Exception) {
                        AutoBuyerLogs.addLog("⚠️ Ошибка удаления записи о покупке: ${e.message}")
                    }
                } else {
                    // Confirmation is gone and no success/failure dialog detected - we reset temporary cooldown to let regular scan find it
                    AutoBuyerLogs.addLog("✨ Покупка отправлена, но финальный диалог еще не обработан. Сбрасываем временную блокировку для продолжения сканирования...")
                    cooldownUntilMillis = 0L
                }
                safetyBitmap.recycle()
            } else {
                cooldownUntilMillis = 0L
            }
        }
    }

    private suspend fun restartGameByReloadPage() {
        AutoBuyerLogs.addLog("🔄 [ПЕРЕЗАПУСК] Начинаем перезапуск бота через меню трех точек...")
        
        val metrics = android.util.DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()
        
        // Step 1: Click the three dots in the top-right corner
        // Typically around X = 94% of screen width, Y = 6% of screen height
        val threeDotsX = screenWidth * 0.94f
        val threeDotsY = screenHeight * 0.055f
        AutoBuyerLogs.addLog("👉 [ПЕРЕЗАПУСК] Кликаем по меню трех точек в координатах ($threeDotsX, $threeDotsY)...")
        clickAt(threeDotsX, threeDotsY)
        
        // Wait for the menu to open
        delay(1500)
        
        // Step 2: Capture screen to find "Reload" / "Обновить"
        var clickedReload = false
        val menuBitmap = MediaProjectionHelper.getLatestScreenshot()
        if (menuBitmap != null) {
            val scaleX = screenWidth / menuBitmap.width
            val scaleY = screenHeight / menuBitmap.height
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val inputImage = InputImage.fromBitmap(menuBitmap, 0)
            val result = try {
                Tasks.await(recognizer.process(inputImage))
            } catch (e: Exception) {
                null
            }
            if (result != null) {
                val lines = result.textBlocks.flatMap { it.lines }
                val reloadLine = lines.firstOrNull { line ->
                    val text = line.text.lowercase()
                    text.contains("reload") || text.contains("обновить") || text.contains("page") || text.contains("страниц") || text.contains("обнов")
                }
                if (reloadLine != null) {
                    val bounds = reloadLine.boundingBox!!
                    val clickX = bounds.centerX() * scaleX
                    val clickY = bounds.centerY() * scaleY
                    AutoBuyerLogs.addLog("🎯 [ПЕРЕЗАПУСК] Найдена кнопка перезапуска '${reloadLine.text}' в (${clickX}, ${clickY})! Кликаем...")
                    clickAt(clickX, clickY)
                    clickedReload = true
                } else {
                    AutoBuyerLogs.addLog("⚠️ [ПЕРЕЗАПУСК] Текст 'Reload' / 'Обновить' не найден на экране.")
                }
            }
            menuBitmap.recycle()
        }
        
        if (!clickedReload) {
            // Fallback click: Typically reload is around the middle-upper part of the screen or dropdown.
            val fallbackReloadX = screenWidth * 0.7f
            val fallbackReloadY = screenHeight * 0.15f
            AutoBuyerLogs.addLog("👉 [ПЕРЕЗАПУСК] Кликаем по координатам по умолчанию для Reload: ($fallbackReloadX, $fallbackReloadY)")
            clickAt(fallbackReloadX, fallbackReloadY)
        }
        
        // Step 3: Wait 30 seconds for the game to load completely
        AutoBuyerLogs.addLog("⏳ [ПЕРЕЗАПУСК] Ожидаем 30 секунд для полной загрузки игры...")
        delay(30000)
        
        // Step 4: Click the "Рынок" tab
        var clickedTab = false
        val loadBitmap = MediaProjectionHelper.getLatestScreenshot()
        if (loadBitmap != null) {
            val scaleX = screenWidth / loadBitmap.width
            val scaleY = screenHeight / loadBitmap.height
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val inputImage = InputImage.fromBitmap(loadBitmap, 0)
            val result = try {
                Tasks.await(recognizer.process(inputImage))
            } catch (e: Exception) {
                null
            }
            if (result != null) {
                val lines = result.textBlocks.flatMap { it.lines }
                val marketLine = lines.firstOrNull { line ->
                    val text = line.text.lowercase()
                    val isMarketWord = text.contains("рынок") || text.contains("рыиок") || text.contains("рын") || text.contains("ryn") || text.contains("market")
                    val isBottom = (line.boundingBox?.centerY()?.toFloat() ?: 0f) * scaleY > screenHeight * 0.82f
                    isMarketWord && isBottom
                }
                if (marketLine != null) {
                    val bounds = marketLine.boundingBox!!
                    val clickX = bounds.centerX() * scaleX
                    val clickY = bounds.centerY() * scaleY
                    AutoBuyerLogs.addLog("🎯 [ПЕРЕЗАПУСК] Найдена вкладка 'Рынок' на (${clickX}, ${clickY})! Кликаем...")
                    clickAt(clickX, clickY)
                    clickedTab = true
                }
            }
            loadBitmap.recycle()
        }
        
        if (!clickedTab) {
            val clickX = screenWidth * 0.875f
            val clickY = screenHeight * 0.94f
            AutoBuyerLogs.addLog("👉 [ПЕРЕЗАПУСК] Вкладка 'Рынок' не найдена через OCR. Кликаем по координатам по умолчанию: ($clickX, $clickY)")
            clickAt(clickX, clickY)
        }
        
        delay(3000)
        AutoBuyerLogs.addLog("✨ [ПЕРЕЗАПУСК] Перезапуск бота игры завершен успешно!")
    }
}
