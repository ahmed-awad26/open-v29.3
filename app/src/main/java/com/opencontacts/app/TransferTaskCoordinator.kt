package com.opencontacts.app

import com.opencontacts.core.model.BackupRecordSummary
import com.opencontacts.core.model.ImportExportHistorySummary
import com.opencontacts.domain.vaults.VaultTransferRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

@Singleton
class TransferTaskCoordinator @Inject constructor(
    private val transferRepository: VaultTransferRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val importExportMutex = Mutex()
    private val backupMutex = Mutex()

    private val _importExportProgress = MutableStateFlow(ImportExportProgressUiState.idle())
    val importExportProgress: StateFlow<ImportExportProgressUiState> = _importExportProgress

    private val _backupProgress = MutableStateFlow(TransferProgressUiState.idle("Backup is idle"))
    val backupProgress: StateFlow<TransferProgressUiState> = _backupProgress

    fun exportJson(vaultId: String) = launchImportExport("Preparing JSON export…", "Exporting JSON…", "JSON export completed") { transferRepository.exportContactsJson(vaultId) }
    fun exportCsv(vaultId: String) = launchImportExport("Preparing CSV export…", "Exporting CSV…", "CSV export completed") { transferRepository.exportContactsCsv(vaultId) }
    fun exportVcf(vaultId: String) = launchImportExport("Preparing VCF export…", "Exporting VCF…", "VCF export completed") { transferRepository.exportContactsVcf(vaultId) }
    fun exportExcel(vaultId: String) = launchImportExport("Preparing Excel export…", "Exporting Excel…", "Excel export completed") { transferRepository.exportContactsExcel(vaultId) }
    fun importFromPhone(vaultId: String) = launchImportExport("Preparing phone import…", "Importing phone contacts…", "Phone import completed") { transferRepository.importFromPhoneContacts(vaultId) }
    fun exportToPhone(vaultId: String) = launchImportExport("Preparing phone export…", "Exporting to phone contacts…", "Phone export completed") { transferRepository.exportAllContactsToPhone(vaultId) }
    fun importCsv(vaultId: String) = launchImportExport("Preparing CSV import…", "Importing CSV…", "CSV import completed") { transferRepository.importLatestContactsCsv(vaultId) }
    fun importVcf(vaultId: String) = launchImportExport("Preparing VCF import…", "Importing VCF…", "VCF import completed") { transferRepository.importLatestContactsVcf(vaultId) }

    fun createBackup(vaultId: String) = launchBackup("Preparing backup…", "Creating encrypted backup…", "Backup completed") { transferRepository.createLocalBackup(vaultId) }
    fun restoreLatest(vaultId: String) = launchBackup("Preparing restore…", "Restoring latest backup…", "Restore completed") { transferRepository.restoreLatestLocalBackup(vaultId) }
    fun stageGoogleDrive(vaultId: String) = launchBackup("Preparing Google Drive staging…", "Staging encrypted backup for Google Drive…", "Google Drive staging completed") { transferRepository.stageLatestBackupToGoogleDrive(vaultId) }
    fun stageOneDrive(vaultId: String) = launchBackup("Preparing OneDrive staging…", "Staging encrypted backup for OneDrive…", "OneDrive staging completed") { transferRepository.stageLatestBackupToOneDrive(vaultId) }

    private fun launchImportExport(
        preparing: String,
        running: String,
        completed: String,
        block: suspend () -> ImportExportHistorySummary,
    ) {
        scope.launch {
            importExportMutex.withLock {
                runCatching {
                    _importExportProgress.value = ImportExportProgressUiState(indeterminate = false, progress = 0.05f, label = preparing, message = "Preparing destination and validating source…")
                    _importExportProgress.value = ImportExportProgressUiState(indeterminate = false, progress = 0.18f, label = preparing, message = "Task queued and running even if you leave this screen.")
                    _importExportProgress.value = ImportExportProgressUiState(indeterminate = false, progress = 0.32f, label = running, message = "Processing contacts and writing the active document…")
                    val result = block()
                    _importExportProgress.value = ImportExportProgressUiState(indeterminate = false, progress = 1f, label = completed, message = "${result.status} • ${result.itemCount} item(s)")
                }.onFailure { error ->
                    _importExportProgress.value = ImportExportProgressUiState.failed(error.message ?: "Import/export failed")
                }
            }
        }
    }

    private fun launchBackup(
        preparing: String,
        running: String,
        completed: String,
        block: suspend () -> Any,
    ) {
        scope.launch {
            backupMutex.withLock {
                runCatching {
                    _backupProgress.value = TransferProgressUiState(indeterminate = false, progress = 0.05f, label = preparing, message = "Preparing destination and vault snapshot…")
                    _backupProgress.value = TransferProgressUiState(indeterminate = false, progress = 0.2f, label = preparing, message = "Task queued and running even if you leave this screen.")
                    _backupProgress.value = TransferProgressUiState(indeterminate = false, progress = 0.38f, label = running, message = "Encrypting, packaging, and writing the current backup artifact…")
                    val result = block()
                    val finalMessage = when (result) {
                        is BackupRecordSummary -> "${result.status} • ${result.filePath}"
                        is Boolean -> if (result) "Restore completed successfully" else "No backup file was found"
                        else -> result.toString()
                    }
                    _backupProgress.value = TransferProgressUiState(indeterminate = false, progress = 1f, label = completed, message = finalMessage)
                }.onFailure { error ->
                    _backupProgress.value = TransferProgressUiState.failed(error.message ?: "Backup flow failed")
                }
            }
        }
    }
}

data class TransferProgressUiState(
    val indeterminate: Boolean,
    val progress: Float,
    val label: String,
    val message: String,
) {
    companion object {
        fun idle(message: String) = TransferProgressUiState(indeterminate = false, progress = 0f, label = "Idle", message = message)
        fun failed(message: String) = TransferProgressUiState(indeterminate = false, progress = 1f, label = "Failed", message = message)
    }
}

data class ImportExportProgressUiState(
    val indeterminate: Boolean,
    val progress: Float,
    val label: String,
    val message: String,
) {
    companion object {
        fun idle() = ImportExportProgressUiState(indeterminate = false, progress = 0f, label = "Idle", message = "No transfer is running.")
        fun failed(message: String) = ImportExportProgressUiState(indeterminate = false, progress = 1f, label = "Failed", message = message)
    }
}
