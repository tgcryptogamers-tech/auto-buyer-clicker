package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "purchase_history")
data class PurchaseRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val itemName: String,
    val price: Double,
    val quantity: Double,
    val details: String
)
