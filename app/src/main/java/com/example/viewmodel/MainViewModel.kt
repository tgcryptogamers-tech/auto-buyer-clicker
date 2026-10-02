package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.database.AppDatabase
import com.example.database.AppConfiguration
import com.example.database.PurchaseRecord
import com.example.service.LootBuyerAccessibilityService
import com.example.service.OverlayControlService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val dao = db.configurationDao()
    private val purchaseDao = db.purchaseDao()

    val configuration: StateFlow<AppConfiguration?> = dao.getConfigurationFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val allPurchases: StateFlow<List<PurchaseRecord>> = purchaseDao.getAllPurchasesFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _accessibilityServiceEnabled = MutableStateFlow(false)
    val accessibilityServiceEnabled: StateFlow<Boolean> = _accessibilityServiceEnabled.asStateFlow()

    private val _overlayPermissionGranted = MutableStateFlow(false)
    val overlayPermissionGranted: StateFlow<Boolean> = _overlayPermissionGranted.asStateFlow()

    init {
        // Populate database with default row if it doesn't exist
        viewModelScope.launch {
            if (dao.getConfiguration() == null) {
                dao.saveConfiguration(AppConfiguration())
            }
        }
        checkPermissions()
    }

    fun checkPermissions() {
        val context = getApplication<Application>()
        
        // Check Accessibility Service status
        _accessibilityServiceEnabled.value = LootBuyerAccessibilityService.isServiceRunning

        // Check overlay permission
        _overlayPermissionGranted.value = Settings.canDrawOverlays(context)
    }

    fun updateSettings(
        itemName: String,
        threshold: Double,
        isLessThan: Boolean,
        intervalMs: Long,
        useViewScanning: Boolean,
        tabSwitchIntervalMs: Long = 15L,
        enableActualBuying: Boolean = false,
        verboseOcrLogging: Boolean = false
    ) {
        viewModelScope.launch {
            val current = dao.getConfiguration() ?: AppConfiguration()
            val updated = current.copy(
                targetItemName = itemName,
                priceThreshold = threshold,
                isLessThanOperator = isLessThan,
                scanIntervalMs = intervalMs,
                useViewScanning = useViewScanning,
                tabSwitchIntervalMs = tabSwitchIntervalMs,
                enableActualBuying = enableActualBuying,
                verboseOcrLogging = verboseOcrLogging
            )
            dao.saveConfiguration(updated)
        }
    }

    fun clearPurchaseHistory() {
        viewModelScope.launch {
            purchaseDao.clearAllPurchases()
        }
    }

    fun updatePriceThresholdEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = dao.getConfiguration() ?: AppConfiguration()
            dao.saveConfiguration(current.copy(usePriceThreshold = enabled))
        }
    }

    fun updateCalibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = dao.getConfiguration() ?: AppConfiguration()
            dao.saveConfiguration(current.copy(isCalibrationEnabled = enabled))
        }
    }

    fun updateSearchCyclesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = dao.getConfiguration() ?: AppConfiguration()
            dao.saveConfiguration(current.copy(useSearchCycles = enabled))
        }
    }

    fun updateSearchCyclesDurations(c1: Int, c2: Int, c3: Int) {
        viewModelScope.launch {
            val current = dao.getConfiguration() ?: AppConfiguration()
            dao.saveConfiguration(current.copy(
                cycle1DurationMin = c1,
                cycle2DurationMin = c2,
                cycle3DurationMin = c3
            ))
        }
    }

    fun updateSearchCyclesRandomRanges(r1: Int, r2: Int, r3: Int) {
        viewModelScope.launch {
            val current = dao.getConfiguration() ?: AppConfiguration()
            dao.saveConfiguration(current.copy(
                cycle1RandomRangeSec = r1,
                cycle2RandomRangeSec = r2,
                cycle3RandomRangeSec = r3
            ))
        }
    }

    fun updateTabSwitchRandomizationMs(ms: Int) {
        viewModelScope.launch {
            val current = dao.getConfiguration() ?: AppConfiguration()
            dao.saveConfiguration(current.copy(tabSwitchRandomizationMs = ms))
        }
    }

    fun updateClickRandomizationRadiusPx(px: Int) {
        viewModelScope.launch {
            val current = dao.getConfiguration() ?: AppConfiguration()
            dao.saveConfiguration(current.copy(clickRandomizationRadiusPx = px))
        }
    }

    fun updateCalibratedCoordinates(
        oreX: Float, oreY: Float,
        copperX: Float, copperY: Float,
        silverX: Float, silverY: Float,
        goldX: Float, goldY: Float,
        sapX: Float, sapY: Float,
        confirmX: Float = -1f,
        confirmY: Float = -1f
    ) {
        viewModelScope.launch {
            val current = dao.getConfiguration() ?: AppConfiguration()
            dao.saveConfiguration(current.copy(
                calibratedOreX = oreX,
                calibratedOreY = oreY,
                calibratedCopperX = copperX,
                calibratedCopperY = copperY,
                calibratedSilverX = silverX,
                calibratedSilverY = silverY,
                calibratedGoldX = goldX,
                calibratedGoldY = goldY,
                calibratedSapX = sapX,
                calibratedSapY = sapY,
                calibratedConfirmX = confirmX,
                calibratedConfirmY = confirmY
            ))
        }
    }

    fun toggleAutoBuy() {
        viewModelScope.launch {
            val current = dao.getConfiguration() ?: AppConfiguration()
            val nextState = !current.autoBuyEnabled
            val updated = current.copy(autoBuyEnabled = nextState)
            dao.saveConfiguration(updated)

            // Propagate action to running accessibility service
            val service = LootBuyerAccessibilityService.instance
            if (service != null) {
                if (nextState) {
                    service.startAutomation()
                } else {
                    service.stopAutomation()
                }
            }
        }
    }

    fun toggleOverlayService() {
        val context = getApplication<Application>()
        if (!Settings.canDrawOverlays(context)) return

        val intent = Intent(context, OverlayControlService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopOverlayService() {
        val context = getApplication<Application>()
        val intent = Intent(context, OverlayControlService::class.java)
        context.stopService(intent)
    }
}
