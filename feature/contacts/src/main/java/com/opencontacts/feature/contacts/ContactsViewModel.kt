package com.opencontacts.feature.contacts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencontacts.core.crypto.AppLockRepository
import com.opencontacts.core.crypto.AppLockSettings
import com.opencontacts.core.model.ContactDraft
import com.opencontacts.core.model.ContactSocialLink
import com.opencontacts.core.model.ContactSummary
import com.opencontacts.core.model.FolderSummary
import com.opencontacts.core.model.TagSummary
import com.opencontacts.core.vault.VaultSessionManager
import com.opencontacts.domain.contacts.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultSessionManager: VaultSessionManager,
    private val contactRepository: ContactRepository,
    appLockRepository: AppLockRepository,
) : ViewModel() {
    val settings: StateFlow<AppLockSettings> = appLockRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLockSettings.DEFAULT)

    val contacts: StateFlow<List<ContactSummary>> = vaultSessionManager.activeVaultId
        .combine(vaultSessionManager.isLocked) { vaultId, isLocked -> vaultId to isLocked }
        .flatMapLatest { (vaultId, isLocked) ->
            if (vaultId == null || isLocked) flowOf(emptyList()) else contactRepository.observeContacts(vaultId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tags: StateFlow<List<TagSummary>> = vaultSessionManager.activeVaultId
        .combine(vaultSessionManager.isLocked) { vaultId, isLocked -> vaultId to isLocked }
        .flatMapLatest { (vaultId, isLocked) ->
            if (vaultId == null || isLocked) flowOf(emptyList()) else contactRepository.observeTags(vaultId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<FolderSummary>> = vaultSessionManager.activeVaultId
        .combine(vaultSessionManager.isLocked) { vaultId, isLocked -> vaultId to isLocked }
        .flatMapLatest { (vaultId, isLocked) ->
            if (vaultId == null || isLocked) flowOf(emptyList()) else contactRepository.observeFolders(vaultId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _editingContact = MutableStateFlow<ContactEditorState?>(null)
    val editingContact: StateFlow<ContactEditorState?> = _editingContact

    private val _callLogs = MutableStateFlow<List<CallLogItem>>(emptyList())
    val callLogs: StateFlow<List<CallLogItem>> = _callLogs

    val callLogGroups: StateFlow<List<CallLogGroup>> = combine(_callLogs, contacts) { logs, contactList -> logs to contactList }
        .mapLatest { (logs, contactList) ->
            withContext(Dispatchers.Default) {
                val contactsById = contactList.associateBy { it.id }
                val contactsByNumber = contactList
                    .mapNotNull { contact ->
                        normalizePhoneForMatching(contact.primaryPhone)
                            .takeIf { it.isNotBlank() }
                            ?.let { normalized -> normalized to contact }
                    }
                    .toMap()

                groupCallLogs(
                    logs.map { log ->
                        val matched = log.matchedContactId?.let(contactsById::get) ?: contactsByNumber[log.normalizedNumber]
                        if (matched != null) log.copy(matchedContactId = matched.id, matchedDisplayName = matched.displayName) else log
                    },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var callLogsLoaded = false
    private var callLogsRefreshing = false

    fun refreshCallLogs(force: Boolean = false) {
        if ((callLogsLoaded || callLogsRefreshing) && !force) return
        callLogsRefreshing = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = queryDeviceCallLogs(context, contacts.value)
            _callLogs.value = result
            callLogsLoaded = true
            callLogsRefreshing = false
        }
    }

    fun startCreate(prefilledPhone: String = "") {
        _editingContact.value = ContactEditorState(phone = prefilledPhone)
    }

    fun startEdit(contact: ContactSummary) {
        _editingContact.value = ContactEditorState(
            id = contact.id,
            displayName = contact.displayName,
            phone = contact.primaryPhone.orEmpty(),
            tags = contact.tags.joinToString(", "),
            isFavorite = contact.isFavorite,
            folderName = contact.folderName.orEmpty(),
            folderNames = contact.folderNames,
            photoUri = contact.photoUri.orEmpty(),
            isBlocked = contact.isBlocked,
            socialLinks = contact.socialLinks,
        )
    }

    fun updateEditor(state: ContactEditorState) {
        _editingContact.value = state
    }

    fun dismissEditor() {
        _editingContact.value = null
    }

    fun saveEditor() {
        val editor = _editingContact.value ?: return
        val vaultId = vaultSessionManager.activeVaultId.value ?: return
        viewModelScope.launch {
            contactRepository.saveContactDraft(
                vaultId = vaultId,
                draft = ContactDraft(
                    id = editor.id,
                    displayName = editor.displayName.ifBlank { "Unnamed contact" },
                    primaryPhone = editor.phone.ifBlank { null },
                    tags = editor.tags.split(',').mapNotNull { it.trim().takeIf(String::isNotBlank) },
                    isFavorite = editor.isFavorite,
                    folderName = editor.folderName.ifBlank { null },
                    folderNames = editor.folderNames.ifEmpty { listOfNotNull(editor.folderName.ifBlank { null }) },
                    photoUri = editor.photoUri.ifBlank { null },
                    isBlocked = editor.isBlocked,
                    socialLinks = editor.socialLinks,
                ),
            )
            _editingContact.value = null
        }
    }

    fun delete(contactId: String) {
        val vaultId = vaultSessionManager.activeVaultId.value ?: return
        viewModelScope.launch { contactRepository.deleteContact(vaultId, contactId) }
    }

    fun deleteMany(contactIds: Collection<String>) {
        val vaultId = vaultSessionManager.activeVaultId.value ?: return
        viewModelScope.launch { contactIds.forEach { contactRepository.deleteContact(vaultId, it) } }
    }

    fun assignFolderToMany(contactIds: Collection<String>, folderName: String) {
        val vaultId = vaultSessionManager.activeVaultId.value ?: return
        val cleanFolder = folderName.trim().ifBlank { return }
        val currentContacts = contacts.value.associateBy { it.id }
        viewModelScope.launch {
            contactRepository.upsertFolder(vaultId, FolderSummary(name = cleanFolder))
            contactIds.forEach { id ->
                val current = currentContacts[id] ?: return@forEach
                contactRepository.saveContactDraft(
                    vaultId,
                    ContactDraft(
                        id = current.id,
                        displayName = current.displayName,
                        primaryPhone = current.primaryPhone,
                        tags = current.tags,
                        isFavorite = current.isFavorite,
                        folderName = cleanFolder,
                        folderNames = listOf(cleanFolder),
                        photoUri = current.photoUri,
                        isBlocked = current.isBlocked,
                        socialLinks = current.socialLinks,
                    ),
                )
            }
        }
    }

    fun assignTagToMany(contactIds: Collection<String>, tagName: String) {
        val vaultId = vaultSessionManager.activeVaultId.value ?: return
        val cleanTag = tagName.trim().removePrefix("#").ifBlank { return }
        val currentContacts = contacts.value.associateBy { it.id }
        viewModelScope.launch {
            contactRepository.upsertTag(vaultId, TagSummary(name = cleanTag))
            contactIds.forEach { id ->
                val current = currentContacts[id] ?: return@forEach
                contactRepository.saveContactDraft(
                    vaultId,
                    ContactDraft(
                        id = current.id,
                        displayName = current.displayName,
                        primaryPhone = current.primaryPhone,
                        tags = (current.tags + cleanTag).distinct(),
                        isFavorite = current.isFavorite,
                        folderName = current.folderName,
                        folderNames = current.folderNames,
                        photoUri = current.photoUri,
                        isBlocked = current.isBlocked,
                        socialLinks = current.socialLinks,
                    ),
                )
            }
        }
    }

    fun removeFolderFromMany(contactIds: Collection<String>) {
        val vaultId = vaultSessionManager.activeVaultId.value ?: return
        val currentContacts = contacts.value.associateBy { it.id }
        viewModelScope.launch {
            contactIds.forEach { id ->
                val current = currentContacts[id] ?: return@forEach
                contactRepository.saveContactDraft(
                    vaultId,
                    ContactDraft(
                        id = current.id,
                        displayName = current.displayName,
                        primaryPhone = current.primaryPhone,
                        tags = current.tags,
                        isFavorite = current.isFavorite,
                        folderName = null,
                        folderNames = emptyList(),
                        photoUri = current.photoUri,
                        isBlocked = current.isBlocked,
                        socialLinks = current.socialLinks,
                    ),
                )
            }
        }
    }
}

data class ContactEditorState(
    val id: String? = null,
    val displayName: String = "",
    val phone: String = "",
    val tags: String = "",
    val isFavorite: Boolean = false,
    val folderName: String = "",
    val folderNames: List<String> = emptyList(),
    val photoUri: String = "",
    val isBlocked: Boolean = false,
    val socialLinks: List<ContactSocialLink> = emptyList(),
)
