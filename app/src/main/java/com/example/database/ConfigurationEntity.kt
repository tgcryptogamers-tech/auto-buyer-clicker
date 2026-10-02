package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_configuration")
data class AppConfiguration(
    @PrimaryKey val id: Int = 1,
    val targetItemName: String = "Loot",
    val priceThreshold: Double = 100.0,
    val isLessThanOperator: Boolean = true, // True: buy if price < threshold. False: buy if price > threshold.
    val scanIntervalMs: Long = 350,
    val useViewScanning: Boolean = true, // True: inspect View tree nodes. False: use tap coordinates.
    
    // Coordinates for Coordinate-based tap mode
    val checkAreaX: Float = 500f,
    val checkAreaY: Float = 400f,
    val buyButtonX: Float = 500f,
    val buyButtonY: Float = 800f,
    val refreshButtonX: Float = 800f,
    val refreshButtonY: Float = 200f,
    
    val autoBuyEnabled: Boolean = false,
    val tabSwitchIntervalMs: Long = 150L, // Configurable interval in ms for alternating tabs
    val enableActualBuying: Boolean = false, // If true, make actual clicks to purchase. If false, log only.
    val usePriceThreshold: Boolean = true, // If true, filter by price threshold. If false, buy unconditionally.
    val isCalibrationEnabled: Boolean = false, // If true, calibration mode is active
    val calibratedOreX: Float = -1f,
    val calibratedOreY: Float = -1f,
    val calibratedCopperX: Float = -1f,
    val calibratedCopperY: Float = -1f,
    val calibratedSilverX: Float = -1f,
    val calibratedSilverY: Float = -1f,
    val calibratedGoldX: Float = -1f,
    val calibratedGoldY: Float = -1f,
    val calibratedSapX: Float = -1f,
    val calibratedSapY: Float = -1f,
    val calibratedConfirmX: Float = -1f,
    val calibratedConfirmY: Float = -1f,
    val verboseOcrLogging: Boolean = false,
    val useSearchCycles: Boolean = false,
    val cycle1DurationMin: Int = 2,
    val cycle2DurationMin: Int = 1,
    val cycle3DurationMin: Int = 1,
    val cycle1RandomRangeSec: Int = 30,
    val cycle2RandomRangeSec: Int = 30,
    val cycle3RandomRangeSec: Int = 30,
    val tabSwitchRandomizationMs: Int = 50,
    val clickRandomizationRadiusPx: Int = 10
)
