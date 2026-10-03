package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import com.example.service.AutoBuyerLogs
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var mediaProjectionManager: android.media.projection.MediaProjectionManager
    private var isScreenCaptureAuthorized by mutableStateOf(false)

    private val screenCaptureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, com.example.service.OverlayControlService::class.java).apply {
                action = "ACTION_START_PROJECTION"
                putExtra("EXTRA_RESULT_CODE", result.resultCode)
                putExtra("EXTRA_DATA", result.data)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            // Crucial: Initialize MediaProjection directly in the Activity process.
            // Since we've already launched the service as a foreground service with type 'mediaProjection',
            // this direct invocation in the Activity process is fully valid and bypasses the getParcelableExtra API 33+ bugs!
            try {
                val projection = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
                if (projection != null) {
                    com.example.service.MediaProjectionHelper.initProjection(projection, applicationContext)
                    isScreenCaptureAuthorized = true
                    Toast.makeText(this, "Screen Capture authorized successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    isScreenCaptureAuthorized = true // Hand off to service fallback
                }
            } catch (e: Exception) {
                isScreenCaptureAuthorized = true // Hand off to service fallback
                e.printStackTrace()
            }
        } else {
            Toast.makeText(this, "Screen Capture permission is required for OCR Unity Mode.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startScreenCaptureRequest() {
        // Start OverlayControlService as a foreground service immediately (required on Android 14+ BEFORE getMediaProjection is called)
        val serviceIntent = Intent(this, com.example.service.OverlayControlService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val intent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        isScreenCaptureAuthorized = com.example.service.MediaProjectionHelper.hasProjection()
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DashboardScreen(
                        viewModel = viewModel,
                        isScreenCaptureAuthorized = isScreenCaptureAuthorized,
                        onRequestScreenCapture = { startScreenCaptureRequest() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkPermissions()
        isScreenCaptureAuthorized = com.example.service.MediaProjectionHelper.hasProjection()
    }
}

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    isScreenCaptureAuthorized: Boolean,
    onRequestScreenCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val config by viewModel.configuration.collectAsState()
    val isAccessibilityEnabled by viewModel.accessibilityServiceEnabled.collectAsState()
    val isOverlayGranted by viewModel.overlayPermissionGranted.collectAsState()
    val allPurchases by viewModel.allPurchases.collectAsState()

    // Form inputs states
    var itemNameInput by remember { mutableStateOf("") }
    var thresholdInput by remember { mutableStateOf("") }
    var isLessThan by remember { mutableStateOf(true) }
    var scanInterval by remember { mutableStateOf(350f) }
    var tabSwitchInterval by remember { mutableStateOf(150f) }
    var useViewScanning by remember { mutableStateOf(true) }
    var enableActualBuying by remember { mutableStateOf(false) }
    var verboseOcrLogging by remember { mutableStateOf(false) }
    
    // Cycle-based search states
    var useSearchCycles by remember { mutableStateOf(false) }
    var cycle1DurationInput by remember { mutableStateOf("2") }
    var cycle2DurationInput by remember { mutableStateOf("1") }
    var cycle3DurationInput by remember { mutableStateOf("1") }
    var cycle1RandomRangeInput by remember { mutableStateOf("30") }
    var cycle2RandomRangeInput by remember { mutableStateOf("30") }
    var cycle3RandomRangeInput by remember { mutableStateOf("30") }

    // Randomization states
    var tabSwitchRandomizationInput by remember { mutableStateOf("50") }
    var clickRandomizationRadiusInput by remember { mutableStateOf("10") }

    // Live terminal log collector
    val liveLogs = remember { mutableStateListOf<String>() }
    val composeClipboard = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2500)
            isCopied = false
        }
    }

    LaunchedEffect(key1 = true) {
        AutoBuyerLogs.logsFlow.collect { logLine ->
            if (liveLogs.size > 300) liveLogs.removeAt(0)
            liveLogs.add(logLine)
        }
    }

    val prepareLogsPayload: () -> Pair<String, Int> = {
        val configHeader = buildString {
            appendLine("=== НАСТРОЙКИ БОТА (BOT CONFIG) ===")
            appendLine("Целевой предмет: $itemNameInput")
            appendLine("Порог цены: $thresholdInput")
            appendLine("Оператор: ${if (isLessThan) "< (Меньше)" else "> (Больше)"}")
            appendLine("Задержка сканирования: ${scanInterval.toInt()} ms")
            appendLine("Задержка вкладок OCR: ${tabSwitchInterval.toInt()} ms")
            appendLine("Рандомизация задержки: ${tabSwitchRandomizationInput} ms")
            appendLine("Радиус рандомизации клика: ${clickRandomizationRadiusInput} px")
            appendLine("Режим сканирования: ${if (useViewScanning) "View Node Scanning" else "Coordinate/OCR Scanning"}")
            appendLine("Реальная покупка: ${if (enableActualBuying) "ВКЛЮЧЕНА" else "ВЫКЛЮЧЕНА (Только логи)"}")
            appendLine("Использовать циклы поиска: $useSearchCycles")
            appendLine("Доступность включена: $isAccessibilityEnabled")
            appendLine("Оверлей разрешен: $isOverlayGranted")
            appendLine("====================================\n")
        }
        val memoryLogs = AutoBuyerLogs.getLogEntries()
        val finalLogsList = if (memoryLogs.isNotEmpty()) memoryLogs else liveLogs.toList()
        val logsBody = if (finalLogsList.isNotEmpty()) {
            finalLogsList.joinToString("\n")
        } else {
            "[Логи пусты / активных записей пока нет]"
        }
        Pair("$configHeader$logsBody", finalLogsList.size)
    }

    val copyAllLogs: () -> Unit = {
        val (fullText, linesCount) = prepareLogsPayload()
        try {
            composeClipboard.setText(AnnotatedString(fullText))
        } catch (e: Exception) {
            android.util.Log.e("CopyLogs", "Compose clipboard error: ${e.message}")
        }
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("AutoBuyer Logs & Config", fullText)
            clipboard?.setPrimaryClip(clip)
        } catch (e: Exception) {
            android.util.Log.e("CopyLogs", "System clipboard error: ${e.message}")
        }
        isCopied = true
        Toast.makeText(context, "Скопировано ($linesCount строк)!", Toast.LENGTH_SHORT).show()
    }

    val shareAllLogs: () -> Unit = {
        val (fullText, _) = prepareLogsPayload()
        try {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, fullText)
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, "Отправить логи бота")
            context.startActivity(shareIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Не удалось открыть меню отправки", Toast.LENGTH_SHORT).show()
        }
    }

    // Update state when database configuration loads
    LaunchedEffect(config) {
        config?.let {
            itemNameInput = it.targetItemName
            thresholdInput = it.priceThreshold.toString()
            isLessThan = it.isLessThanOperator
            scanInterval = it.scanIntervalMs.toFloat()
            tabSwitchInterval = it.tabSwitchIntervalMs.toFloat()
            useViewScanning = it.useViewScanning
            enableActualBuying = it.enableActualBuying
            verboseOcrLogging = it.verboseOcrLogging
            useSearchCycles = it.useSearchCycles
            cycle1DurationInput = it.cycle1DurationMin.toString()
            cycle2DurationInput = it.cycle2DurationMin.toString()
            cycle3DurationInput = it.cycle3DurationMin.toString()
            cycle1RandomRangeInput = it.cycle1RandomRangeSec.toString()
            cycle2RandomRangeInput = it.cycle2RandomRangeSec.toString()
            cycle3RandomRangeInput = it.cycle3RandomRangeSec.toString()
            tabSwitchRandomizationInput = it.tabSwitchRandomizationMs.toString()
            clickRandomizationRadiusInput = it.clickRandomizationRadiusPx.toString()
        }
    }

    // Save configuration parameters helper
    val saveConfig = {
        val thresh = thresholdInput.toDoubleOrNull() ?: 100.0
        viewModel.updateSettings(
            itemName = itemNameInput,
            threshold = thresh,
            isLessThan = isLessThan,
            intervalMs = scanInterval.toLong(),
            useViewScanning = useViewScanning,
            tabSwitchIntervalMs = tabSwitchInterval.toLong(),
            enableActualBuying = enableActualBuying,
            verboseOcrLogging = verboseOcrLogging
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121214)) // Dark cosmic background
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Title Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF3F51B5), Color(0xFF1A237E))
                    )
                )
                .padding(20.dp)
        ) {
            Column {
                Text(
                    text = "LOT MONITOR & SCANNER",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "Smart Game Auction Tracker & Logger",
                    color = Color(0xFFD0BCFF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Section: Required System Permissions
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Security Permissions",
                        tint = Color(0xFF2196F3)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "System Permissions Needed",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))

                // Accessibility Service status item
                PermissionRow(
                    title = "Accessibility Service (Tap Gestures)",
                    description = "Required to click targets and inspect lot values on active screen layouts.",
                    isGranted = isAccessibilityEnabled,
                    onClickSettings = {
                        try {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                            Toast.makeText(context, "Locate 'Auto-Buyer' and turn it ON", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Unable to open Accessibility settings", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // System alert overlay permission
                PermissionRow(
                    title = "Display Over Other Apps (Overlay)",
                    description = "Displays the floating control console bubble over your game's screen to toggle scanning.",
                    isGranted = isOverlayGranted,
                    onClickSettings = {
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Fallback standard settings intent
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            context.startActivity(intent)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Screen capture projection permission (Required for OCR Mode)
                PermissionRow(
                    title = "Screen Capture / Recording (OCR Mode)",
                    description = "Required to capture screens on Unity games, recognize words (e.g. Copper/Gold), and find prices.",
                    isGranted = isScreenCaptureAuthorized,
                    onClickSettings = onRequestScreenCapture
                )
            }
        }

        // Section: Overlay Widget Controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = "Overlay Widgets",
                        tint = Color(0xFF4CAF50)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Floating Controls",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Launch a floating window to toggle scan/monitoring process and watch matches logged in real time directly on top of your game/external app.",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (!isOverlayGranted) {
                                Toast.makeText(context, "Grant 'Overlay' permission first!", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.toggleOverlayService()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("launch_overlay_btn"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("LAUNCH OVERLAY", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedButton(
                        onClick = { viewModel.stopOverlayService() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("STOP OVERLAY", fontSize = 12.sp)
                    }
                }
            }
        }

        // Section: Configured Scan Settings
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search Configs",
                        tint = Color(0xFFFF9800)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Auction Scan Settings",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Item Name input
                OutlinedTextField(
                    value = itemNameInput,
                    onValueChange = {
                        itemNameInput = it
                        saveConfig()
                    },
                    label = { Text("Target Lot Name (Keywords)", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFD0BCFF),
                        unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("lot_name_input")
                )

                // Quick Predefined Russian game tabs
                Column {
                    Text("Quick Predefined Game Tabs:", color = Color.Gray, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val presetCategories = listOf("Руда", "Медь", "Серебро", "Золото", "Сапфир")
                        presetCategories.forEach { cat ->
                            val isSelected = itemNameInput.equals(cat, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF3F51B5) else Color(0xFF2B2B33))
                                    .border(1.dp, if (isSelected) Color(0xFFD0BCFF) else Color.Gray, RoundedCornerShape(8.dp))
                                    .clickable {
                                        itemNameInput = cat
                                        saveConfig()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(text = cat, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Toggle usePriceThreshold filter
                val usePriceThreshold = config?.usePriceThreshold == true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF25252E))
                        .clickable {
                            viewModel.updatePriceThresholdEnabled(!usePriceThreshold)
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Использовать лимит по цене",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (usePriceThreshold) "Проверяем цену (лимит включен)" else "Покупаем всё без проверки цены",
                            color = if (usePriceThreshold) Color(0xFF4CAF50) else Color(0xFFE91E63),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = usePriceThreshold,
                        onCheckedChange = {
                            viewModel.updatePriceThresholdEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF4CAF50),
                            checkedTrackColor = Color(0xFF4CAF50).copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.testTag("use_price_threshold_switch")
                    )
                }

                // Price/stats threshold value input
                OutlinedTextField(
                    value = thresholdInput,
                    onValueChange = {
                        thresholdInput = it
                        saveConfig()
                    },
                    label = { Text("Buyout Threshold Value", color = Color.Gray) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFD0BCFF),
                        unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("threshold_input")
                )

                // Comparison Operator selection
                Column {
                    Text("Trigger Purchase if Price/Value is:", color = Color.LightGray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = isLessThan,
                            onClick = {
                                isLessThan = true
                                saveConfig()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) {
                            Text("LESS THAN (<)", fontSize = 12.sp)
                        }
                        SegmentedButton(
                            selected = !isLessThan,
                            onClick = {
                                isLessThan = false
                                saveConfig()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) {
                            Text("GREATER THAN (>)", fontSize = 12.sp)
                        }
                    }
                }

                // Toggle Tab Calibration Mode
                val isCalibrationEnabled = config?.isCalibrationEnabled == true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF25252E))
                        .clickable {
                            viewModel.updateCalibrationEnabled(!isCalibrationEnabled)
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Калибровка координат вкладок",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isCalibrationEnabled) "РЕЖИМ КАЛИБРОВКИ АКТИВЕН" else "Обычный режим (автоопределение)",
                            color = if (isCalibrationEnabled) Color(0xFFFFEB3B) else Color.LightGray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = isCalibrationEnabled,
                        onCheckedChange = {
                            viewModel.updateCalibrationEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF4CAF50),
                            checkedTrackColor = Color(0xFF4CAF50).copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.testTag("tab_calibration_switch")
                    )
                }

                if (config != null && (config!!.calibratedOreX != -1f || config!!.calibratedCopperX != -1f)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E1E24))
                            .padding(8.dp)
                    ) {
                        Text("Сохраненные координаты калибровки:", color = Color.Gray, fontSize = 11.sp)
                        Text("• Ore: (${config!!.calibratedOreX.toInt()}, ${config!!.calibratedOreY.toInt()})", color = Color.LightGray, fontSize = 11.sp)
                        Text("• Copper: (${config!!.calibratedCopperX.toInt()}, ${config!!.calibratedCopperY.toInt()})", color = Color.LightGray, fontSize = 11.sp)
                        Text("• Silver: (${config!!.calibratedSilverX.toInt()}, ${config!!.calibratedSilverY.toInt()})", color = Color.LightGray, fontSize = 11.sp)
                        Text("• Gold: (${config!!.calibratedGoldX.toInt()}, ${config!!.calibratedGoldY.toInt()})", color = Color.LightGray, fontSize = 11.sp)
                        Text("• Sap: (${config!!.calibratedSapX.toInt()}, ${config!!.calibratedSapY.toInt()})", color = Color.LightGray, fontSize = 11.sp)
                        if (config!!.calibratedConfirmX != -1f) {
                            Text("• Confirm: (${config!!.calibratedConfirmX.toInt()}, ${config!!.calibratedConfirmY.toInt()})", color = Color.LightGray, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Сбросить координаты",
                            color = Color(0xFFE91E63),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    viewModel.updateCalibratedCoordinates(-1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f, -1f)
                                }
                                .padding(vertical = 4.dp)
                        )
                    }
                }

                // Toggle actual buying mode vs only logging/monitoring mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF25252E))
                        .clickable {
                            enableActualBuying = !enableActualBuying
                            saveConfig()
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Выполнять автоматическую покупку",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (enableActualBuying) "Режим ПОКУПКИ (клики активны)" else "Режим МОНИТОРИНГА (только логирование)",
                            color = if (enableActualBuying) Color(0xFF4CAF50) else Color(0xFFFFEB3B),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = enableActualBuying,
                        onCheckedChange = {
                            enableActualBuying = it
                            saveConfig()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF4CAF50),
                            checkedTrackColor = Color(0xFF4CAF50).copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.testTag("enable_actual_buying_switch")
                    )
                }

                // Toggle verbose OCR logging
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF25252E))
                        .clickable {
                            verboseOcrLogging = !verboseOcrLogging
                            saveConfig()
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Подробный лог OCR (Отладка)",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (verboseOcrLogging) "Показывать каждую распознанную строку экрана" else "Режим тишины (только важные действия)",
                            color = if (verboseOcrLogging) Color(0xFF4CAF50) else Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = verboseOcrLogging,
                        onCheckedChange = {
                            verboseOcrLogging = it
                            saveConfig()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF4CAF50),
                            checkedTrackColor = Color(0xFF4CAF50).copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.testTag("verbose_ocr_logging_switch")
                    )
                }

                // Interval Ms Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Search Delay / Пауза между циклами:", color = Color.LightGray, fontSize = 12.sp)
                        Text("${scanInterval.toInt()} ms", color = Color(0xFFD0BCFF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = scanInterval,
                        onValueChange = {
                            scanInterval = it
                            saveConfig()
                        },
                        valueRange = 20f..5000f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF2196F3),
                            activeTrackColor = Color(0xFF2196F3)
                        )
                    )
                    Text("💡 Пауза перед следующим циклом проверки. Для непрерывного быстрого обновления установите 20-50 ms.", color = Color.Gray, fontSize = 10.sp)
                }

                // Tab Switch Delay Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Tab Switch Delay / Скорость переключения вкладок:", color = Color.LightGray, fontSize = 12.sp)
                        Text("${tabSwitchInterval.toInt()} ms", color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = tabSwitchInterval,
                        onValueChange = {
                            tabSwitchInterval = it
                            saveConfig()
                        },
                        valueRange = 20f..2000f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF4CAF50),
                            activeTrackColor = Color(0xFF4CAF50)
                        )
                    )
                    Text("💡 Интервал между кликами по вкладкам (Руда ↔ Медь).", color = Color.Gray, fontSize = 10.sp)
                }

                // Tab Switch Delay Randomization Slider / Input
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Tab Switch Randomization (Рандомизация задержки):", color = Color.LightGray, fontSize = 12.sp)
                        Text("${tabSwitchRandomizationInput} ms", color = Color(0xFFFF9800), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = tabSwitchRandomizationInput.toFloatOrNull() ?: 50f,
                        onValueChange = {
                            tabSwitchRandomizationInput = it.toInt().toString()
                            viewModel.updateTabSwitchRandomizationMs(it.toInt())
                        },
                        valueRange = 0f..500f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFF9800),
                            activeTrackColor = Color(0xFFFF9800)
                        )
                    )
                    Text("💡 Добавляет случайную задержку от 0 до ${tabSwitchRandomizationInput} ms поверх базового интервала для имитации человека.", color = Color.Gray, fontSize = 10.sp)
                }

                // Click Randomization Radius (px)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Click Randomization Radius (Рандомизация координат клика):", color = Color.LightGray, fontSize = 12.sp)
                        Text("${clickRandomizationRadiusInput} px", color = Color(0xFF00BCD4), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = clickRandomizationRadiusInput.toFloatOrNull() ?: 10f,
                        onValueChange = {
                            clickRandomizationRadiusInput = it.toInt().toString()
                            viewModel.updateClickRandomizationRadiusPx(it.toInt())
                        },
                        valueRange = 0f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00BCD4),
                            activeTrackColor = Color(0xFF00BCD4)
                        )
                    )
                    Text("💡 Клик смещается на случайное число пикселей в пределах +- ${clickRandomizationRadiusInput} px для имитации естественных нажатий.", color = Color.Gray, fontSize = 10.sp)
                }

                val isServiceActive = isAccessibilityEnabled && config?.autoBuyEnabled == true

                // Cooldown / Pause status indicator
                var cooldownRemaining by remember { mutableStateOf(0L) }
                LaunchedEffect(isServiceActive) {
                    while (true) {
                        cooldownRemaining = com.example.service.LootBuyerAccessibilityService.getCooldownRemainingMs()
                        kotlinx.coroutines.delay(1000)
                    }
                }

                if (cooldownRemaining > 0L) {
                    val totalSec = cooldownRemaining / 1000
                    val mins = totalSec / 60
                    val secs = totalSec % 60
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF3E2723)), // Dark amber warning card
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Cooldown Active",
                                    tint = Color(0xFFFF9800)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Бот на паузе после покупки (Кулдаун)",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "До следующей покупки осталось: $mins мин $secs сек",
                                        color = Color(0xFFFFB74D),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    com.example.service.LootBuyerAccessibilityService.resetCooldown()
                                    cooldownRemaining = 0L
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD84315)),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text("СБРОС", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else if (isServiceActive) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20)), // Green active card
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Ready for purchases",
                                tint = Color.Green
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Покупки РАЗРЕШЕНЫ",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Бот в активном поиске и готов сразу выкупить лот",
                                    color = Color(0xFFA5D6A7),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                // Primary start auto-buy toggle in Main app
                Button(
                    onClick = {
                        if (!isAccessibilityEnabled) {
                            Toast.makeText(context, "Enable Accessibility Service First!", Toast.LENGTH_LONG).show()
                        } else {
                            viewModel.toggleAutoBuy()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isServiceActive) Color(0xFFE91E63) else Color(0xFF4CAF50)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("toggle_autobuy_btn"),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = if (isServiceActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = "Toggle Control"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isServiceActive) "STOP MONITORING PROCESS" else "START MONITORING PROCESS",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Section: Search Cycles (Циклы поиска)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Search Cycles",
                        tint = Color(0xFFD0BCFF)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Циклический поиск лотов (Cycles)",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Поиск разделен на 3 настраиваемых цикла с автоперезапуском страницы webapp, если ничего не найдено.",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF25252E))
                        .clickable {
                            viewModel.updateSearchCyclesEnabled(!useSearchCycles)
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Использовать циклы поиска",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (useSearchCycles) "Режим циклов АКТИВЕН" else "Обычный непрерывный режим",
                            color = if (useSearchCycles) Color(0xFF4CAF50) else Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = useSearchCycles,
                        onCheckedChange = {
                            viewModel.updateSearchCyclesEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF4CAF50),
                            checkedTrackColor = Color(0xFF4CAF50).copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.testTag("use_search_cycles_switch")
                    )
                }

                if (useSearchCycles) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Cycle 1 Duration & Random Range
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = cycle1DurationInput,
                                onValueChange = {
                                    cycle1DurationInput = it
                                    val c1 = it.toIntOrNull() ?: 2
                                    val c2 = cycle2DurationInput.toIntOrNull() ?: 1
                                    val c3 = cycle3DurationInput.toIntOrNull() ?: 1
                                    viewModel.updateSearchCyclesDurations(c1, c2, c3)
                                },
                                label = { Text("Цикл 1 (мин)", color = Color.Gray) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFFD0BCFF),
                                    unfocusedBorderColor = Color.Gray
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = cycle1RandomRangeInput,
                                onValueChange = {
                                    cycle1RandomRangeInput = it
                                    val r1 = it.toIntOrNull() ?: 30
                                    val r2 = cycle2RandomRangeInput.toIntOrNull() ?: 30
                                    val r3 = cycle3RandomRangeInput.toIntOrNull() ?: 30
                                    viewModel.updateSearchCyclesRandomRanges(r1, r2, r3)
                                },
                                label = { Text("Диапазон +- (сек)", color = Color.Gray) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFFD0BCFF),
                                    unfocusedBorderColor = Color.Gray
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            text = "💡 Реальное время: ${cycle1DurationInput.toIntOrNull() ?: 2} мин + рандом от -${Math.abs(cycle1RandomRangeInput.toIntOrNull() ?: 30)} сек до ${Math.abs(cycle1RandomRangeInput.toIntOrNull() ?: 30)} сек",
                            color = Color(0xFFD0BCFF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )

                        // Cycle 2 Duration & Random Range
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = cycle2DurationInput,
                                onValueChange = {
                                    cycle2DurationInput = it
                                    val c1 = cycle1DurationInput.toIntOrNull() ?: 2
                                    val c2 = it.toIntOrNull() ?: 1
                                    val c3 = cycle3DurationInput.toIntOrNull() ?: 1
                                    viewModel.updateSearchCyclesDurations(c1, c2, c3)
                                },
                                label = { Text("Цикл 2 (мин)", color = Color.Gray) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFFD0BCFF),
                                    unfocusedBorderColor = Color.Gray
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = cycle2RandomRangeInput,
                                onValueChange = {
                                    cycle2RandomRangeInput = it
                                    val r1 = cycle1RandomRangeInput.toIntOrNull() ?: 30
                                    val r2 = it.toIntOrNull() ?: 30
                                    val r3 = cycle3RandomRangeInput.toIntOrNull() ?: 30
                                    viewModel.updateSearchCyclesRandomRanges(r1, r2, r3)
                                },
                                label = { Text("Диапазон +- (сек)", color = Color.Gray) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFFD0BCFF),
                                    unfocusedBorderColor = Color.Gray
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            text = "💡 Реальное время: ${cycle2DurationInput.toIntOrNull() ?: 1} мин + рандом от -${Math.abs(cycle2RandomRangeInput.toIntOrNull() ?: 30)} сек до ${Math.abs(cycle2RandomRangeInput.toIntOrNull() ?: 30)} сек",
                            color = Color(0xFFD0BCFF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )

                        // Cycle 3 Duration & Random Range
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = cycle3DurationInput,
                                onValueChange = {
                                    cycle3DurationInput = it
                                    val c1 = cycle1DurationInput.toIntOrNull() ?: 2
                                    val c2 = cycle2DurationInput.toIntOrNull() ?: 1
                                    val c3 = it.toIntOrNull() ?: 1
                                    viewModel.updateSearchCyclesDurations(c1, c2, c3)
                                },
                                label = { Text("Цикл 3 (мин)", color = Color.Gray) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFFD0BCFF),
                                    unfocusedBorderColor = Color.Gray
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = cycle3RandomRangeInput,
                                onValueChange = {
                                    cycle3RandomRangeInput = it
                                    val r1 = cycle1RandomRangeInput.toIntOrNull() ?: 30
                                    val r2 = cycle2RandomRangeInput.toIntOrNull() ?: 30
                                    val r3 = it.toIntOrNull() ?: 30
                                    viewModel.updateSearchCyclesRandomRanges(r1, r2, r3)
                                },
                                label = { Text("Диапазон +- (сек)", color = Color.Gray) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFFD0BCFF),
                                    unfocusedBorderColor = Color.Gray
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            text = "💡 Реальное время: ${cycle3DurationInput.toIntOrNull() ?: 1} мин + рандом от -${Math.abs(cycle3RandomRangeInput.toIntOrNull() ?: 30)} сек до ${Math.abs(cycle3RandomRangeInput.toIntOrNull() ?: 30)} сек",
                            color = Color(0xFFD0BCFF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Section: Live Terminal Logs
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0E11)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header row with Title and log line count badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = "Code Logs",
                            tint = Color.Green
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Live Log Terminal Output",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Surface(
                        color = Color(0xFF1E1E26),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "${liveLogs.size} строк",
                            color = Color(0xFFD0BCFF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Prominent Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = copyAllLogs,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCopied) Color(0xFF2E7D32) else Color(0xFF7C4DFF),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(44.dp)
                            .testTag("copy_logs_button")
                    ) {
                        Icon(
                            imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Копировать",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isCopied) "СКОПИРОВАНО!" else "СКОПИРОВАТЬ НАСТРОЙКИ И ЛОГ",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = shareAllLogs,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFBB86FC)
                        ),
                        border = BorderStroke(1.dp, Color(0xFFBB86FC)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(0.9f)
                            .height(44.dp)
                            .testTag("share_logs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Поделиться",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "ПОДЕЛИТЬСЯ",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Terminal window: clickable directly to copy
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF070709))
                        .border(1.dp, if (isCopied) Color(0xFF4CAF50) else Color(0xFF2A2A38), RoundedCornerShape(8.dp))
                        .clickable { copyAllLogs() }
                        .padding(10.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (liveLogs.isEmpty()) {
                            Text(
                                text = "Логи пока пусты. Запустите бота или включите службу доступности для отслеживания событий.",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 40.dp),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            liveLogs.forEach { logLine ->
                                Text(
                                    text = logLine,
                                    color = when {
                                        logLine.contains("УСПЕХ") || logLine.contains("УСПЕШНАЯ") -> Color(0xFF69F0AE)
                                        logLine.contains("ОШИБКА") || logLine.contains("ЗАВИСАНИЕ") -> Color(0xFFFF5252)
                                        logLine.contains("ОГРАНИЧЕНИЕ") || logLine.contains("ПАУЗА") -> Color(0xFFFFD740)
                                        logLine.contains("ОПТИМИЗАЦИЯ") || logLine.contains("ПЕРЕКЛЮЧЕНИЕ") -> Color(0xFF40C4FF)
                                        else -> Color(0xFF81C784)
                                    },
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "💡 Нажмите на фиолетовую кнопку или прямо на терминал, чтобы скопировать все логи и настройки.",
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section: Purchase History (История Покупок)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0E11)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ShoppingCart,
                                contentDescription = "Purchase History",
                                tint = Color(0xFFD0BCFF)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "История Покупок",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (allPurchases.isNotEmpty()) {
                            Text(
                                text = "ОЧИСТИТЬ",
                                color = Color(0xFFEF5350),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable {
                                        viewModel.clearPurchaseHistory()
                                        Toast.makeText(context, "История очищена!", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(8.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 250.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (allPurchases.isEmpty()) {
                            Text(
                                text = "Нет совершенных покупок за текущую сессию.",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            allPurchases.forEach { record ->
                                val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(record.timestamp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF1E1D22))
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Success",
                                            tint = Color(0xFF4CAF50),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = "${record.itemName} (x${record.quantity.toInt()})",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "Цена: ${record.price} • $timeStr",
                                                color = Color.LightGray,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFF2E7D32))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "КУПЛЕНО",
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    onClickSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2B2B33))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = description, color = Color.LightGray, fontSize = 11.sp, lineHeight = 14.sp)
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isGranted) Color(0xFF2E7D32) else Color(0xFFC62828))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isGranted) "GRANTED" else "OFF",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = onClickSettings,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD0BCFF)),
                modifier = Modifier.height(30.dp),
                shape = RoundedCornerShape(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
            ) {
                Text("SETUP", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
