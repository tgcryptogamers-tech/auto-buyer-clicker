package com.example.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConfigurationDao {
    @Query("SELECT * FROM app_configuration WHERE id = 1")
    fun getConfigurationFlow(): Flow<AppConfiguration?>

    @Query("SELECT * FROM app_configuration WHERE id = 1")
    suspend fun getConfiguration(): AppConfiguration?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveConfiguration(config: AppConfiguration)
}

@Dao
interface PurchaseDao {
    @Query("SELECT * FROM purchase_history ORDER BY timestamp DESC")
    fun getAllPurchasesFlow(): Flow<List<PurchaseRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPurchase(purchase: PurchaseRecord)

    @Query("DELETE FROM purchase_history")
    suspend fun clearAllPurchases()

    @Query("DELETE FROM purchase_history WHERE id = (SELECT MAX(id) FROM purchase_history)")
    suspend fun deleteLatestPurchase()
}

