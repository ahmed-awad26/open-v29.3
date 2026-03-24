package com.opencontacts.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.opencontacts.data.db.entity.VaultRegistryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultRegistryDao {
    @Query("SELECT * FROM vault_registry ORDER BY created_at ASC")
    fun observeAll(): Flow<List<VaultRegistryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: VaultRegistryEntity)

    @Query("SELECT * FROM vault_registry LIMIT 1")
    suspend fun firstOrNull(): VaultRegistryEntity?

    @Query("SELECT * FROM vault_registry WHERE vault_id = :vaultId LIMIT 1")
    suspend fun getById(vaultId: String): VaultRegistryEntity?

    @Query("SELECT COUNT(*) FROM vault_registry")
    suspend fun count(): Int

    @Query("DELETE FROM vault_registry WHERE vault_id = :vaultId")
    suspend fun deleteById(vaultId: String)
}
