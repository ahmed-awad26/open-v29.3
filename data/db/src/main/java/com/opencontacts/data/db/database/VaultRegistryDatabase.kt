package com.opencontacts.data.db.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.opencontacts.data.db.dao.VaultRegistryDao
import com.opencontacts.data.db.entity.VaultRegistryEntity

@Database(entities = [VaultRegistryEntity::class], version = 2, exportSchema = true)
abstract class VaultRegistryDatabase : RoomDatabase() {
    abstract fun vaultRegistryDao(): VaultRegistryDao
}
