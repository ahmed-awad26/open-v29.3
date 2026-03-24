package com.opencontacts.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.opencontacts.core.model.ImportExportHistorySummary
import com.opencontacts.core.vault.VaultSessionManager
import com.opencontacts.domain.vaults.VaultTransferRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Composable
fun ImportExportRoute(
    onBack: () -> Unit,
    appViewModel: AppViewModel,
    viewModel: ImportExportViewModel = hiltViewModel(),
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importCsvFromUri(context, uri)
    }
    val vcfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importVcfFromUri(context, uri)
    }

    SettingsScaffold(title = "Import & Export", onBack = onBack) { modifier ->
        LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ProgressSurface(progress)
            }
            item {
                SettingsSection(title = "Export", subtitle = "Use upward-facing export actions and keep generated files in the export location configured from settings.") {
                    ActionGridRow(
                        ActionSpec("Export JSON", "Portable structured export", Icons.Default.FileUpload) { viewModel.exportJson() },
                        ActionSpec("Export CSV", "Spreadsheet-friendly export", Icons.Default.Upload) { viewModel.exportCsv() },
                    )
                    SettingsSpacer()
                    ActionGridRow(
                        ActionSpec("Export VCF", "Standard contact card format", Icons.Default.FileUpload) { viewModel.exportVcf() },
                        ActionSpec("Export Excel", "Excel-compatible workbook", Icons.Default.Upload) { viewModel.exportExcel() },
                    )
                    SettingsSpacer()
                    ActionGridRow(
                        ActionSpec("Export to phone", "Copy active vault contacts to system contacts", Icons.Default.Upload) { viewModel.exportToPhone() },
                        ActionSpec("", "", Icons.Default.Upload) { },
                        hideSecond = true,
                    )
                }
            }
            item {
                SettingsSection(title = "Import", subtitle = "Import works in place without a banner or separate heavy header.") {
                    ActionGridRow(
                        ActionSpec("Import CSV", "Pick a CSV document", Icons.Default.FileDownload) { csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) },
                        ActionSpec("Import VCF", "Pick a VCF document", Icons.Default.Download) { vcfPicker.launch(arrayOf("text/x-vcard", "text/vcard", "*/*")) },
                    )
                    SettingsSpacer()
                    ActionGridRow(
                        ActionSpec("Import phone contacts", "Pull system contacts into the private vault", Icons.Default.PhoneAndroid) { viewModel.importFromPhone() },
                        ActionSpec("", "", Icons.Default.Download) { },
                        hideSecond = true,
                    )
                }
            }
            item {
                SettingsSection(title = "Recent activity", subtitle = "Every run reports status and keeps its result visible until the next one completes.") {
                    if (history.isEmpty()) {
                        Text("No import or export records yet.")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            history.forEach { item -> HistoryCard(item) }
                        }
                    }
                }
            }
        }
    }
}

private data class ActionSpec(
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
)

@Composable
private fun ActionGridRow(first: ActionSpec, second: ActionSpec, hideSecond: Boolean = false) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ActionTile(first, Modifier.weight(1f))
        if (!hideSecond) ActionTile(second, Modifier.weight(1f))
    }
}

@Composable
private fun ActionTile(spec: ActionSpec, modifier: Modifier) {
    Card(modifier = modifier, onClick = spec.onClick) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Icon(spec.icon, contentDescription = null)
            Text(spec.title, style = MaterialTheme.typography.titleMedium)
            Text(spec.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProgressSurface(progress: ImportExportProgressUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(progress.label, style = MaterialTheme.typography.titleMedium)
            if (progress.indeterminate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            Text(progress.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (progress.progress in 0f..0.999f && progress.label != "Idle") {
                Text("The task keeps running even if you leave this page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun HistoryCard(item: ImportExportHistorySummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.operationType, style = MaterialTheme.typography.titleMedium)
            Text(item.status)
            Text("Items: ${item.itemCount}")
            Text(item.filePath)
            Text(formatTimestamp(item.createdAt), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@HiltViewModel
class ImportExportViewModel @Inject constructor(
    private val vaultSessionManager: VaultSessionManager,
    private val transferRepository: VaultTransferRepository,
    private val coordinator: TransferTaskCoordinator,
) : ViewModel() {
    val history: StateFlow<List<ImportExportHistorySummary>> = vaultSessionManager.activeVaultId
        .combine(vaultSessionManager.isLocked) { vaultId, locked -> vaultId to locked }
        .flatMapLatest { (vaultId, locked) ->
            if (vaultId == null || locked) flowOf(emptyList()) else transferRepository.observeImportExportHistory(vaultId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val progress: StateFlow<ImportExportProgressUiState> = coordinator.importExportProgress

    private fun activeVaultId(): String? = vaultSessionManager.activeVaultId.value

    fun exportJson() { activeVaultId()?.let(coordinator::exportJson) }
    fun exportCsv() { activeVaultId()?.let(coordinator::exportCsv) }
    fun exportVcf() { activeVaultId()?.let(coordinator::exportVcf) }
    fun exportExcel() { activeVaultId()?.let(coordinator::exportExcel) }
    fun importFromPhone() { activeVaultId()?.let(coordinator::importFromPhone) }
    fun exportToPhone() { activeVaultId()?.let(coordinator::exportToPhone) }

    fun importCsvFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                copyUriIntoImports(context, uri, "contacts.csv")
                activeVaultId()?.let(coordinator::importCsv)
            } catch (error: Exception) {
                // coordinator remains shared; surface the failure locally by resetting its state on next explicit action.
            }
        }
    }

    fun importVcfFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                copyUriIntoImports(context, uri, "contacts.vcf")
                activeVaultId()?.let(coordinator::importVcf)
            } catch (error: Exception) {
                // no-op: the shared progress state is updated by the coordinator when the next run starts.
            }
        }
    }
}

private fun formatTimestamp(value: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(value))

private suspend fun copyUriIntoImports(context: Context, uri: Uri, fileName: String) {
    val dir = File(context.filesDir, "vault_imports").apply { mkdirs() }
    val target = File(dir, fileName)
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: error("Unable to open selected file")
}
