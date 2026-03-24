package com.opencontacts.feature.vaults

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencontacts.core.model.VaultSummary
import com.opencontacts.core.vault.VaultSessionManager
import com.opencontacts.domain.vaults.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class VaultsViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val sessionManager: VaultSessionManager,
) : ViewModel() {
    val vaults: StateFlow<List<VaultSummary>> = repository.observeVaults()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _editingVault = MutableStateFlow<VaultEditorState?>(null)
    val editingVault: StateFlow<VaultEditorState?> = _editingVault.asStateFlow()

    init {
        viewModelScope.launch {
            val default = repository.ensureDefaultVault()
            if (sessionManager.activeVaultId.value == null) {
                sessionManager.switchTo(default.id)
            }
        }
    }

    fun activate(vaultId: String) {
        viewModelScope.launch {
            repository.setLocked(vaultId, false)
            sessionManager.switchTo(vaultId)
        }
    }

    fun startCreate() {
        _editingVault.value = VaultEditorState()
    }

    fun startRename(vault: VaultSummary) {
        _editingVault.value = VaultEditorState(id = vault.id, displayName = vault.displayName)
    }

    fun updateEditor(state: VaultEditorState) {
        _editingVault.value = state
    }

    fun dismissEditor() {
        _editingVault.value = null
    }

    fun saveEditor() {
        val editor = _editingVault.value ?: return
        viewModelScope.launch {
            if (editor.id == null) {
                val created = repository.createVault(editor.displayName)
                repository.setLocked(created.id, false)
                sessionManager.switchTo(created.id)
            } else {
                repository.renameVault(editor.id, editor.displayName)
            }
            _editingVault.value = null
        }
    }

    fun deleteVault(vaultId: String) {
        viewModelScope.launch {
            val deletingActive = sessionManager.activeVaultId.value == vaultId
            repository.deleteVault(vaultId)
            val fallback = repository.ensureDefaultVault()
            if (deletingActive) sessionManager.switchTo(fallback.id)
        }
    }

    fun lockVault(vaultId: String) {
        viewModelScope.launch {
            repository.setLocked(vaultId, true)
            if (sessionManager.activeVaultId.value == vaultId) {
                sessionManager.lock()
            }
        }
    }
}

data class VaultEditorState(
    val id: String? = null,
    val displayName: String = "",
)
