package com.opencontacts.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.opencontacts.core.model.ContactSummary
import com.opencontacts.core.vault.VaultSessionManager
import com.opencontacts.domain.contacts.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Composable
fun TrashRoute(
    onBack: () -> Unit,
    appViewModel: AppViewModel,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val settings by appViewModel.appLockSettings.collectAsStateWithLifecycle()
    SettingsScaffold(title = "Trash", onBack = onBack) { modifier ->
        LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SettingsSection(title = "Retention policy", subtitle = "Current automatic purge window") {
                    Text(
                        "Deleted contacts are currently kept for ${settings.trashRetentionDays} day(s). Change this from Preferences.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (items.isEmpty()) {
                item {
                    SettingsSection(title = "Trash is empty") {
                        Text("Deleted contacts will appear here until they are restored or permanently removed.")
                    }
                }
            } else {
                items(items, key = { it.id }) { contact ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(contact.primaryPhone ?: "No phone", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            androidx.compose.foundation.layout.Row {
                                IconButton(onClick = { viewModel.restore(contact.id) }) { Icon(Icons.Default.RestoreFromTrash, contentDescription = "Restore") }
                                IconButton(onClick = { viewModel.deleteForever(contact.id) }) { Icon(Icons.Default.DeleteForever, contentDescription = "Delete forever") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val sessionManager: VaultSessionManager,
    private val contactRepository: ContactRepository,
) : ViewModel() {
    val items: StateFlow<List<ContactSummary>> = sessionManager.activeVaultId
        .combine(sessionManager.isLocked) { vaultId, locked -> vaultId to locked }
        .flatMapLatest { (vaultId, locked) ->
            if (vaultId == null || locked) flowOf(emptyList()) else contactRepository.observeTrash(vaultId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(contactId: String) {
        val vaultId = sessionManager.activeVaultId.value ?: return
        viewModelScope.launch { contactRepository.restoreContact(vaultId, contactId) }
    }

    fun deleteForever(contactId: String) {
        val vaultId = sessionManager.activeVaultId.value ?: return
        viewModelScope.launch { contactRepository.permanentlyDeleteContact(vaultId, contactId) }
    }
}
