package com.opencontacts.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencontacts.core.crypto.AppLockRepository
import com.opencontacts.core.crypto.AppLockSettings
import com.opencontacts.core.crypto.BiometricAuthManager
import com.opencontacts.core.crypto.BiometricAvailability
import com.opencontacts.core.vault.VaultSessionManager
import com.opencontacts.data.repository.TransferDestinationKind
import com.opencontacts.data.repository.TransferDestinationManager
import com.opencontacts.domain.contacts.ContactRepository
import com.opencontacts.domain.vaults.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRepository: VaultRepository,
    private val contactRepository: ContactRepository,
    private val appLockRepository: AppLockRepository,
    private val transferDestinationManager: TransferDestinationManager,
    private val biometricAuthManager: BiometricAuthManager,
    private val sessionManager: VaultSessionManager,
) : ViewModel() {
    val appLockSettings: StateFlow<AppLockSettings> = appLockRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLockSettings.DEFAULT)

    val activeVaultId: StateFlow<String?> = sessionManager.activeVaultId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isLocked: StateFlow<Boolean> = sessionManager.isLocked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val shouldShowUnlock: StateFlow<Boolean> = combine(activeVaultId, isLocked, appLockSettings) { vaultId, locked, settings ->
        vaultId != null && locked && (settings.hasPin || settings.biometricEnabled)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val vaults = vaultRepository.observeVaults()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeVaultName: StateFlow<String> = combine(activeVaultId, vaults) { vaultId, allVaults ->
        allVaults.firstOrNull { it.id == vaultId }?.displayName ?: "No active vault"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "No active vault")

    val activeContactCount: StateFlow<Int> = activeVaultId
        .combine(isLocked) { vaultId, locked -> vaultId to locked }
        .flatMapLatest { (vaultId, locked) ->
            if (vaultId == null || locked) kotlinx.coroutines.flow.flowOf(emptyList()) else contactRepository.observeContacts(vaultId)
        }
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val blockedContacts = activeVaultId
        .combine(isLocked) { vaultId, locked -> vaultId to locked }
        .flatMapLatest { (vaultId, locked) ->
            if (vaultId == null || locked) kotlinx.coroutines.flow.flowOf(emptyList()) else contactRepository.observeBlockedContacts(vaultId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError

    private val _storageMessage = MutableStateFlow<String?>(null)
    val storageMessage: StateFlow<String?> = _storageMessage

    val appVersion: String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "v${info.versionName}"
    }.getOrDefault("v0.0.28")

    private var lastBackgroundElapsedRealtime: Long = 0L

    init {
        viewModelScope.launch {
            val defaultVault = vaultRepository.ensureDefaultVault()
            val settings = appLockRepository.settings.first()
            val shouldLock = settings.hasPin || settings.biometricEnabled
            vaultRepository.setLocked(defaultVault.id, shouldLock)
            sessionManager.setVault(defaultVault.id, locked = shouldLock)
            purgeTrashIfNeeded(defaultVault.id, settings)
        }
    }

    fun setPin(pin: String) {
        if (pin.length < 4 || !pin.all(Char::isDigit)) {
            _pinError.value = "PIN must contain at least 4 digits"
            return
        }
        viewModelScope.launch {
            appLockRepository.setPin(pin.toCharArray())
            _pinError.value = null
        }
    }

    fun clearPin() {
        viewModelScope.launch {
            appLockRepository.clearPin()
            _pinError.value = null
            activeVaultId.value?.let { vaultRepository.setLocked(it, false) }
            sessionManager.unlock()
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appLockRepository.setBiometricEnabled(enabled)
            if (!enabled && !appLockSettings.value.hasPin) {
                activeVaultId.value?.let { vaultRepository.setLocked(it, false) }
                sessionManager.unlock()
            }
        }
    }

    fun setAllowDeviceCredential(enabled: Boolean) = launchSetting { appLockRepository.setAllowDeviceCredential(enabled) }
    fun setLockOnAppResume(enabled: Boolean) = launchSetting { appLockRepository.setLockOnAppResume(enabled) }
    fun setLockAfterInactivitySeconds(seconds: Int) = launchSetting { appLockRepository.setLockAfterInactivitySeconds(seconds) }
    fun setTrashRetentionDays(days: Int) = launchSetting { appLockRepository.setTrashRetentionDays(days) }
    fun setThemeMode(mode: String) = launchSetting { appLockRepository.setThemeMode(mode) }
    fun setDefaultContactSortOrder(value: String) = launchSetting { appLockRepository.setDefaultContactSortOrder(value) }
    fun setContactListDensity(value: String) = launchSetting { appLockRepository.setContactListDensity(value) }
    fun setShowContactPhotosInList(enabled: Boolean) = launchSetting { appLockRepository.setShowContactPhotosInList(enabled) }
    fun setDefaultStartTab(value: String) = launchSetting { appLockRepository.setDefaultStartTab(value) }
    fun setConfirmBeforeDelete(enabled: Boolean) = launchSetting { appLockRepository.setConfirmBeforeDelete(enabled) }
    fun setConfirmBeforeBlockUnblock(enabled: Boolean) = launchSetting { appLockRepository.setConfirmBeforeBlockUnblock(enabled) }
    fun setShowRecentCallsPreview(enabled: Boolean) = launchSetting { appLockRepository.setShowRecentCallsPreview(enabled) }
    fun setAutoCollapseCallGroups(enabled: Boolean) = launchSetting { appLockRepository.setAutoCollapseCallGroups(enabled) }
    fun setShowBlockedContactsInSearch(enabled: Boolean) = launchSetting { appLockRepository.setShowBlockedContactsInSearch(enabled) }
    fun setIncludeTimestampInExportFileName(enabled: Boolean) = launchSetting { appLockRepository.setIncludeTimestampInExportFileName(enabled) }
    fun setHideEmptyFoldersAndTags(enabled: Boolean) = launchSetting { appLockRepository.setHideEmptyFoldersAndTags(enabled) }
    fun setOpenContactDirectlyOnTap(enabled: Boolean) = launchSetting { appLockRepository.setOpenContactDirectlyOnTap(enabled) }
    fun setShowFavoritesFirst(enabled: Boolean) = launchSetting { appLockRepository.setShowFavoritesFirst(enabled) }
    fun setEnableIncomingCallerPopup(enabled: Boolean) = launchSetting { appLockRepository.setEnableIncomingCallerPopup(enabled) }
    fun setEnableMissedCallNotification(enabled: Boolean) = launchSetting { appLockRepository.setEnableMissedCallNotification(enabled) }
    fun setShowPhotoInNotifications(enabled: Boolean) = launchSetting { appLockRepository.setShowPhotoInNotifications(enabled) }
    fun setShowFolderTagsInNotifications(enabled: Boolean) = launchSetting { appLockRepository.setShowFolderTagsInNotifications(enabled) }
    fun setLockScreenNotificationVisibility(value: String) = launchSetting { appLockRepository.setLockScreenNotificationVisibility(value) }
    fun setHeadsUpNotifications(enabled: Boolean) = launchSetting { appLockRepository.setHeadsUpNotifications(enabled) }
    fun setOverlayPopupMode(value: String) = launchSetting { appLockRepository.setOverlayPopupMode(value) }
    fun setVibrationEnabled(enabled: Boolean) = launchSetting { appLockRepository.setVibrationEnabled(enabled) }
    fun setSoundEnabled(enabled: Boolean) = launchSetting { appLockRepository.setSoundEnabled(enabled) }
    fun setHapticFeedbackEnabled(enabled: Boolean) = launchSetting { appLockRepository.setHapticFeedbackEnabled(enabled) }
    fun setDialPadShowLetters(enabled: Boolean) = launchSetting { appLockRepository.setDialPadShowLetters(enabled) }
    fun setDialPadAutoFormat(enabled: Boolean) = launchSetting { appLockRepository.setDialPadAutoFormat(enabled) }
    fun setDialPadShowT9Suggestions(enabled: Boolean) = launchSetting { appLockRepository.setDialPadShowT9Suggestions(enabled) }
    fun setDialPadLongPressBackspaceClears(enabled: Boolean) = launchSetting { appLockRepository.setDialPadLongPressBackspaceClears(enabled) }
    fun setGroupTagSortOrder(value: String) = launchSetting { appLockRepository.setGroupTagSortOrder(value) }
    fun setIncomingCallCompactMode(enabled: Boolean) = launchSetting { appLockRepository.setIncomingCallCompactMode(enabled) }
    fun setIncomingCallShowNumber(enabled: Boolean) = launchSetting { appLockRepository.setIncomingCallShowNumber(enabled) }
    fun setIncomingCallShowTag(enabled: Boolean) = launchSetting { appLockRepository.setIncomingCallShowTag(enabled) }
    fun setIncomingCallShowGroup(enabled: Boolean) = launchSetting { appLockRepository.setIncomingCallShowGroup(enabled) }
    fun setIncomingCallWindowTransparency(value: Int) = launchSetting { appLockRepository.setIncomingCallWindowTransparency(value) }
    fun setIncomingCallWindowSize(value: String) = launchSetting { appLockRepository.setIncomingCallWindowSize(value) }

    fun showUiError(message: String) {
        _pinError.value = message
    }

    fun unlockWithPin(pin: String) {
        viewModelScope.launch {
            val valid = appLockRepository.verifyPin(pin.toCharArray())
            if (valid) {
                _pinError.value = null
                activeVaultId.value?.let { vaultRepository.setLocked(it, false) }
                sessionManager.unlock()
            } else {
                _pinError.value = "Incorrect PIN"
            }
        }
    }

    fun unlockWithBiometricSuccess() {
        viewModelScope.launch {
            _pinError.value = null
            activeVaultId.value?.let { vaultRepository.setLocked(it, false) }
            sessionManager.unlock()
        }
    }

    fun lockNow() {
        viewModelScope.launch {
            activeVaultId.value?.let { vaultRepository.setLocked(it, true) }
            sessionManager.lock()
        }
    }

    fun clearError() {
        _pinError.value = null
    }

    fun switchVault(vaultId: String) {
        viewModelScope.launch {
            val target = vaults.value.firstOrNull { it.id == vaultId } ?: return@launch
            sessionManager.setVault(vaultId, locked = target.isLocked)
            purgeTrashIfNeeded(vaultId, appLockSettings.value)
        }
    }

    fun setBackupFolder(uri: Uri) {
        viewModelScope.launch {
            try {
                val result = transferDestinationManager.setFolder(TransferDestinationKind.BACKUP, uri)
                _storageMessage.value = "Backup folder set to ${result.displayName}"
            } catch (error: Exception) {
                _storageMessage.value = error.message ?: "Unable to use the selected backup folder."
            }
        }
    }

    fun setExportFolder(uri: Uri) {
        viewModelScope.launch {
            try {
                val result = transferDestinationManager.setFolder(TransferDestinationKind.EXPORT, uri)
                _storageMessage.value = "Export folder set to ${result.displayName}"
            } catch (error: Exception) {
                _storageMessage.value = error.message ?: "Unable to use the selected export folder."
            }
        }
    }

    fun resetBackupFolder() {
        viewModelScope.launch {
            transferDestinationManager.resetFolder(TransferDestinationKind.BACKUP)
            _storageMessage.value = "Backup folder reset to the default app storage location."
        }
    }

    fun resetExportFolder() {
        viewModelScope.launch {
            transferDestinationManager.resetFolder(TransferDestinationKind.EXPORT)
            _storageMessage.value = "Export folder reset to the default app storage location."
        }
    }

    fun showStorageMessage(message: String) { _storageMessage.value = message }
    fun clearStorageMessage() { _storageMessage.value = null }

    fun createVault(displayName: String) {
        viewModelScope.launch {
            val created = vaultRepository.createVault(displayName)
            sessionManager.setVault(created.id, locked = created.isLocked)
        }
    }

    fun unblockContact(contactId: String) {
        val vaultId = activeVaultId.value ?: return
        viewModelScope.launch { contactRepository.setContactBlocked(vaultId, contactId, false) }
    }

    fun deleteVault(vaultId: String) {
        viewModelScope.launch {
            val current = activeVaultId.value
            val items = vaults.value
            if (items.size <= 1) return@launch
            vaultRepository.deleteVault(vaultId)
            if (current == vaultId) {
                val fallback = vaultRepository.ensureDefaultVault()
                sessionManager.setVault(fallback.id, locked = fallback.isLocked)
            }
        }
    }

    fun onAppBackgrounded() {
        lastBackgroundElapsedRealtime = SystemClock.elapsedRealtime()
    }

    fun onAppForegrounded() {
        val settings = appLockSettings.value
        if (!settings.lockOnAppResume) return
        val vaultId = activeVaultId.value ?: return
        val secured = settings.hasPin || settings.biometricEnabled
        if (!secured) return
        val idleMillis = if (lastBackgroundElapsedRealtime == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - lastBackgroundElapsedRealtime
        val timeoutMillis = settings.lockAfterInactivitySeconds * 1000L
        if (idleMillis >= timeoutMillis) {
            viewModelScope.launch {
                vaultRepository.setLocked(vaultId, true)
                sessionManager.lock()
            }
        }
    }

    fun canUseBiometric(): Boolean = biometricAuthManager.canAuthenticate(appLockSettings.value.allowDeviceCredential)
    fun biometricAvailability(): BiometricAvailability = biometricAuthManager.availability(appLockSettings.value.allowDeviceCredential)
    fun biometricPromptInfo(title: String) = biometricAuthManager.createPromptInfo(title, appLockSettings.value.allowDeviceCredential)

    private fun launchSetting(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private suspend fun purgeTrashIfNeeded(vaultId: String, settings: AppLockSettings) {
        val retentionMillis = settings.trashRetentionDays * 24L * 60L * 60L * 1000L
        if (retentionMillis > 0) {
            contactRepository.purgeDeletedOlderThan(vaultId, System.currentTimeMillis() - retentionMillis)
        }
    }
}
