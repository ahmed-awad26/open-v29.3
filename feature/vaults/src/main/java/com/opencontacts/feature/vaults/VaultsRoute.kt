package com.opencontacts.feature.vaults

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultsRoute(
    onBack: () -> Unit,
    viewModel: VaultsViewModel = hiltViewModel(),
) {
    val vaults by viewModel.vaults.collectAsStateWithLifecycle()
    val editing by viewModel.editingVault.collectAsStateWithLifecycle()

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = viewModel::startCreate, text = { Text("New vault") }, icon = { Icon(Icons.Default.Add, contentDescription = null) })
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer, shape = CardDefaults.elevatedShape) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Vaults", style = MaterialTheme.typography.headlineMedium)
                    Text("Create, rename, lock, and switch private vaults. Delete remains available from Settings only.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VaultStat("Total", vaults.size.toString())
                        VaultStat("Locked", vaults.count { it.isLocked }.toString())
                    }
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(vaults, key = { it.id }) { vault ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(vault.displayName, style = MaterialTheme.typography.titleLarge)
                                        Icon(if (vault.isLocked) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                    Text("Color: ${vault.colorToken} • Icon: ${vault.iconToken}")
                                    Text(if (vault.isLocked) "Locked" else "Ready to use")
                                }
                                Row {
                                    IconButton(onClick = { viewModel.startRename(vault) }) { Icon(Icons.Default.Edit, contentDescription = "Rename") }
                                    IconButton(onClick = { viewModel.lockVault(vault.id) }) { Icon(Icons.Default.Lock, contentDescription = "Lock") }
                                }
                            }
                            OutlinedButton(onClick = { viewModel.activate(vault.id) }) {
                                Icon(Icons.Default.RadioButtonChecked, contentDescription = null)
                                Text("Use this vault")
                            }
                        }
                    }
                }
            }
        }

        editing?.let { editor ->
            VaultEditorDialog(
                state = editor,
                onStateChange = viewModel::updateEditor,
                onDismiss = viewModel::dismissEditor,
                onConfirm = viewModel::saveEditor,
            )
        }
    }
}

@Composable
private fun VaultStat(label: String, value: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = CardDefaults.shape) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun VaultEditorDialog(state: VaultEditorState, onStateChange: (VaultEditorState) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.id == null) "Create vault" else "Rename vault") },
        text = {
            OutlinedTextField(value = state.displayName, onValueChange = { onStateChange(state.copy(displayName = it)) }, label = { Text("Vault name") }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
