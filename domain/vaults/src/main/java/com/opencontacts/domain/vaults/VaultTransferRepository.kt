package com.opencontacts.domain.vaults

import com.opencontacts.core.model.BackupRecordSummary
import com.opencontacts.core.model.ImportExportHistorySummary
import kotlinx.coroutines.flow.Flow

interface VaultTransferRepository {
    fun observeBackupRecords(vaultId: String): Flow<List<BackupRecordSummary>>
    fun observeImportExportHistory(vaultId: String): Flow<List<ImportExportHistorySummary>>
    suspend fun createLocalBackup(vaultId: String): BackupRecordSummary
    suspend fun restoreLatestLocalBackup(vaultId: String): Boolean
    suspend fun stageLatestBackupToGoogleDrive(vaultId: String): BackupRecordSummary
    suspend fun stageLatestBackupToOneDrive(vaultId: String): BackupRecordSummary
    suspend fun exportContactsJson(vaultId: String): ImportExportHistorySummary
    suspend fun importLatestContactsJson(vaultId: String): ImportExportHistorySummary
    suspend fun exportContactsCsv(vaultId: String): ImportExportHistorySummary
    suspend fun importLatestContactsCsv(vaultId: String): ImportExportHistorySummary
    suspend fun exportContactsVcf(vaultId: String): ImportExportHistorySummary
    suspend fun exportContactsExcel(vaultId: String): ImportExportHistorySummary
    suspend fun importLatestContactsVcf(vaultId: String): ImportExportHistorySummary
    suspend fun importFromPhoneContacts(vaultId: String): ImportExportHistorySummary
    suspend fun exportAllContactsToPhone(vaultId: String): ImportExportHistorySummary
}
