package com.opencontacts.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.opencontacts.core.crypto.AppLockRepository
import com.opencontacts.core.crypto.AppLockSettings
import com.opencontacts.core.model.ContactDraft
import com.opencontacts.core.model.ContactSummary
import com.opencontacts.core.model.FolderSummary
import com.opencontacts.core.model.TagSummary
import com.opencontacts.core.vault.VaultSessionManager
import com.opencontacts.domain.contacts.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val folderColorPalette = listOf("blue", "green", "violet", "amber", "rose", "slate")
private val folderIconPalette = listOf("folder", "work", "home", "star", "archive", "group")

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun WorkspaceRoute(
    onBack: () -> Unit,
    onOpenDetails: (String) -> Unit,
    viewModel: WorkspaceViewModel = hiltViewModel(),
) {
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var folderEditor by remember { mutableStateOf<FolderEditorState?>(null) }
    var tagEditor by remember { mutableStateOf<TagEditorState?>(null) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var selectedContactIds by remember { mutableStateOf(setOf<String>()) }
    var addContactsDialog by remember { mutableStateOf(false) }
    var pendingDeleteFolder by remember { mutableStateOf<FolderSummary?>(null) }
    var pendingDeleteTag by remember { mutableStateOf<TagSummary?>(null) }
    var tagSearchQuery by remember { mutableStateOf("") }

    val activeFolder = folders.firstOrNull { it.name == selectedFolder }
    val activeTag = tags.firstOrNull { it.name == selectedTag }
    val filteredContacts = remember(contacts, selectedTag, selectedFolder) {
        when {
            selectedTag != null -> contacts.filter { selectedTag in it.tags }
            selectedFolder != null -> contacts.filter { it.folderName == selectedFolder }
            else -> emptyList()
        }
    }
    val activeContainerTitle = activeFolder?.name ?: activeTag?.name
    val selectionMode = selectedContactIds.isNotEmpty()

    val displayedFolders = remember(folders, settings.hideEmptyFoldersAndTags) {
        if (settings.hideEmptyFoldersAndTags) folders.filter { it.usageCount > 0 } else folders
    }
    val displayedTags = remember(tags, settings.hideEmptyFoldersAndTags, settings.groupTagSortOrder, tagSearchQuery) {
        val source = if (settings.hideEmptyFoldersAndTags) tags.filter { it.usageCount > 0 } else tags
        source.filter { tagSearchQuery.isBlank() || it.name.contains(tagSearchQuery.trim(), ignoreCase = true) }
            .sortedWith(
                when (settings.groupTagSortOrder.uppercase()) {
                    "ALPHABETICAL" -> compareBy<TagSummary> { it.name.lowercase() }
                    "RECENT" -> compareByDescending<TagSummary> { it.usageCount }.thenByDescending { it.name.length }
                    else -> compareByDescending<TagSummary> { it.usageCount }.thenBy { it.name.lowercase() }
                }
            )
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            WorkspaceHeader(
                title = activeContainerTitle ?: "Groups, folders & tags",
                subtitle = when {
                    activeFolder != null -> "${filteredContacts.size} contact(s) inside this folder"
                    activeTag != null -> "${filteredContacts.size} contact(s) with this tag"
                    else -> "Keep folders visual, tags lightweight, and organization fast."
                },
                accent = activeFolder?.colorToken ?: activeTag?.colorToken ?: "blue",
                onBack = if (activeContainerTitle == null) onBack else {
                    {
                        selectedFolder = null
                        selectedTag = null
                        selectedContactIds = emptySet()
                    }
                },
                onEdit = {
                    when {
                        activeFolder != null -> folderEditor = activeFolder.toEditorState()
                        activeTag != null -> tagEditor = TagEditorState(originalName = activeTag.name, name = activeTag.name)
                    }
                },
                onDelete = {
                    activeFolder?.let { pendingDeleteFolder = it }
                    activeTag?.let { pendingDeleteTag = it }
                },
                canEdit = activeContainerTitle != null,
            )

            if (activeContainerTitle == null) {
                FolderLane(
                    folders = displayedFolders,
                    selectedFolder = selectedFolder,
                    onSelect = {
                        selectedFolder = it
                        selectedTag = null
                        selectedContactIds = emptySet()
                    },
                    onAdd = { folderEditor = FolderEditorState() },
                    onEdit = { folderEditor = it.toEditorState() },
                )

                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Label, null)
                                Text("Tags", style = MaterialTheme.typography.titleLarge)
                            }
                            IconButton(onClick = { tagEditor = TagEditorState() }) { Icon(Icons.Default.Add, contentDescription = "Add tag") }
                        }
                        OutlinedTextField(
                            value = tagSearchQuery,
                            onValueChange = { tagSearchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Search tags") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            displayedTags.take(24).forEach { tag ->
                                FilterChip(
                                    selected = tag.name == selectedTag,
                                    onClick = {
                                        selectedTag = tag.name
                                        selectedFolder = null
                                        selectedContactIds = emptySet()
                                    },
                                    label = { Text(if (tag.usageCount > 0) "${tag.name} · ${tag.usageCount}" else tag.name) },
                                    leadingIcon = { Icon(Icons.Default.Label, null) },
                                )
                            }
                        }
                    }
                }
            } else {
                if (selectionMode) {
                    WorkspaceSelectionBar(
                        count = selectedContactIds.size,
                        onAssign = { addContactsDialog = true },
                        onClear = { selectedContactIds = emptySet() },
                        onRemove = {
                            if (selectedFolder != null) viewModel.removeFolderFromContacts(selectedContactIds)
                            if (selectedTag != null) viewModel.removeTagFromContacts(selectedContactIds, selectedTag!!)
                            selectedContactIds = emptySet()
                        },
                    )
                }

                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (selectedFolder != null) "Folder contents" else "Tagged contacts", style = MaterialTheme.typography.titleLarge)
                            Row {
                                IconButton(onClick = { addContactsDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add contacts") }
                                if (selectedContactIds.isNotEmpty()) {
                                    TextButton(onClick = { selectedContactIds = emptySet() }) { Text("Clear") }
                                }
                            }
                        }
                        if (filteredContacts.isEmpty()) {
                            Text(
                                if (selectedFolder != null) "No contacts are linked to this folder yet." else "No contacts are linked to this tag yet.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                items(filteredContacts, key = { it.id }) { contact ->
                                    ContactMiniCard(
                                        contact = contact,
                                        selected = contact.id in selectedContactIds,
                                        selectionMode = selectionMode,
                                        onOpen = {
                                            if (selectionMode) {
                                                selectedContactIds = if (contact.id in selectedContactIds) selectedContactIds - contact.id else selectedContactIds + contact.id
                                            } else {
                                                onOpenDetails(contact.id)
                                            }
                                        },
                                        onLongPress = {
                                            selectedContactIds = if (contact.id in selectedContactIds) selectedContactIds - contact.id else selectedContactIds + contact.id
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    folderEditor?.let { state ->
        val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            folderEditor = state.copy(imageUri = uri?.toString())
        }
        FolderEditorDialog(
            state = state,
            onDismiss = { folderEditor = null },
            onStateChange = { folderEditor = it },
            onPickPhoto = { photoPicker.launch("image/*") },
            onConfirm = {
                viewModel.saveFolder(folderEditor!!.toSummary())
                if (state.originalName != null && state.originalName != state.name.trim()) {
                    viewModel.renameFolder(state.originalName, state.name.trim())
                }
                folderEditor = null
            },
        )
    }

    tagEditor?.let { state ->
        TagEditorDialog(
            state = state,
            onDismiss = { tagEditor = null },
            onStateChange = { tagEditor = it },
            onConfirm = {
                val cleanName = state.name.trim().removePrefix("#")
                if (cleanName.isNotBlank()) {
                    if (state.originalName != null && state.originalName != cleanName) {
                        viewModel.renameTag(state.originalName, cleanName)
                    } else {
                        viewModel.saveTag(cleanName)
                    }
                }
                tagEditor = null
            },
        )
    }

    pendingDeleteFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingDeleteFolder = null },
            title = { Text("Delete folder") },
            text = { Text("This deletes the folder only. Contacts inside it will stay محفوظة وسيتم فقط فك الربط من هذا الفولدر دون حذف أي جهة اتصال.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(folder.name)
                    if (selectedFolder == folder.name) {
                        selectedFolder = null
                        selectedContactIds = emptySet()
                    }
                    pendingDeleteFolder = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteFolder = null }) { Text("Cancel") } },
        )
    }

    pendingDeleteTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { pendingDeleteTag = null },
            title = { Text("Delete tag") },
            text = { Text("This removes the tag classification from linked contacts and deletes the tag itself. Contacts will not be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTag(tag.name)
                    if (selectedTag == tag.name) {
                        selectedTag = null
                        selectedContactIds = emptySet()
                    }
                    pendingDeleteTag = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteTag = null }) { Text("Cancel") } },
        )
    }

    if (addContactsDialog && activeContainerTitle != null) {
        AddContactsDialog(
            title = if (selectedFolder != null) "Add contacts to folder" else "Add contacts to tag",
            contacts = contacts,
            alreadyIncludedIds = filteredContacts.map { it.id }.toSet(),
            onDismiss = { addContactsDialog = false },
            onConfirm = { ids ->
                if (selectedFolder != null) viewModel.assignFolderToContacts(ids, selectedFolder!!)
                if (selectedTag != null) viewModel.assignTagToContacts(ids, selectedTag!!)
                selectedContactIds = emptySet()
                addContactsDialog = false
            },
        )
    }
}

@Composable
private fun WorkspaceHeader(
    title: String,
    subtitle: String,
    accent: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    canEdit: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = accentColor(accent).copy(alpha = 0.16f),
        border = BorderStroke(1.dp, accentColor(accent).copy(alpha = 0.22f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (canEdit) {
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
        }
    }
}

@Composable
private fun FolderLane(
    folders: List<FolderSummary>,
    selectedFolder: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (FolderSummary) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp)) {
        Column(modifier = Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null)
                    Text("Folders", style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    "Visual organization",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                items(folders, key = { it.name }) { folder ->
                    FolderTile(
                        folder = folder,
                        selected = folder.name == selectedFolder,
                        onClick = { onSelect(folder.name) },
                        onLongPress = { onEdit(folder) },
                    )
                }
                item("add-folder") {
                    AddFolderTile(onClick = onAdd)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(
    folder: FolderSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val bitmap by rememberFolderBitmap(folder.imageUri)
    Surface(
        modifier = Modifier
            .size(width = 110.dp, height = 122.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        shape = RoundedCornerShape(24.dp),
        color = if (selected) accentColor(folder.colorToken).copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) accentColor(folder.colorToken).copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(shape = RoundedCornerShape(18.dp), color = accentColor(folder.colorToken).copy(alpha = 0.18f), modifier = Modifier.size(54.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(folderIcon(folder.iconToken), contentDescription = null, tint = accentColor(folder.colorToken))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${folder.usageCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (folder.isPinned) {
                Icon(Icons.Default.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun AddFolderTile(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(width = 110.dp, height = 122.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("New folder", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun WorkspaceSelectionBar(
    count: Int,
    onAssign: () -> Unit,
    onRemove: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$count selected", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAssign) { Text("Assign") }
                TextButton(onClick = onRemove) { Text("Remove") }
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun ContactMiniCard(contact: ContactSummary, selected: Boolean, selectionMode: Boolean, onOpen: () -> Unit, onLongPress: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Box(
                    modifier = Modifier.size(22.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(contact.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(contact.primaryPhone ?: "No phone", style = MaterialTheme.typography.bodyMedium)
                if (contact.tags.isNotEmpty()) Text(contact.tags.joinToString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AddContactsDialog(
    title: String,
    contacts: List<ContactSummary>,
    alreadyIncludedIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(contacts.filterNot { it.id in alreadyIncludedIds }, key = { it.id }) { contact ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                            selected = if (contact.id in selected) selected - contact.id else selected + contact.id
                        },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(20.dp).clip(CircleShape).background(if (contact.id in selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        )
                        Column {
                            Text(contact.displayName)
                            Text(contact.primaryPhone ?: "No phone", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FolderEditorDialog(
    state: FolderEditorState,
    onDismiss: () -> Unit,
    onStateChange: (FolderEditorState) -> Unit,
    onPickPhoto: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.originalName == null) "New folder" else "Edit folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = state.name, onValueChange = { onStateChange(state.copy(name = it)) }, label = { Text("Folder name") }, singleLine = true)
                OutlinedTextField(value = state.description.orEmpty(), onValueChange = { onStateChange(state.copy(description = it)) }, label = { Text("Short description") }, maxLines = 2)
                Text("Color", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    folderColorPalette.forEach { color ->
                        Surface(
                            modifier = Modifier.size(34.dp),
                            shape = CircleShape,
                            color = accentColor(color),
                            border = BorderStroke(2.dp, if (state.colorToken == color) MaterialTheme.colorScheme.onSurface else Color.Transparent),
                            onClick = { onStateChange(state.copy(colorToken = color)) },
                        ) {}
                    }
                }
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    folderIconPalette.forEach { icon ->
                        AssistChip(
                            onClick = { onStateChange(state.copy(iconToken = icon)) },
                            label = { Text(icon) },
                            leadingIcon = { Icon(folderIcon(icon), null) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPickPhoto) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.imageUri.isNullOrBlank()) "Pick image" else "Change image")
                    }
                    if (!state.imageUri.isNullOrBlank()) {
                        TextButton(onClick = { onStateChange(state.copy(imageUri = null)) }) { Text("Remove") }
                    }
                }
                FilterChip(selected = state.isPinned, onClick = { onStateChange(state.copy(isPinned = !state.isPinned)) }, label = { Text("Pinned") }, leadingIcon = { Icon(Icons.Default.PushPin, null) })
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = state.name.trim().isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TagEditorDialog(
    state: TagEditorState,
    onDismiss: () -> Unit,
    onStateChange: (TagEditorState) -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.originalName == null) "New tag" else "Edit tag") },
        text = { OutlinedTextField(value = state.name, onValueChange = { onStateChange(state.copy(name = it)) }, label = { Text("Tag name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = state.name.trim().isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun accentColor(token: String): Color = when (token.lowercase()) {
    "green" -> Color(0xFF16A34A)
    "violet" -> Color(0xFF7C3AED)
    "amber" -> Color(0xFFD97706)
    "rose" -> Color(0xFFE11D48)
    "slate" -> Color(0xFF475569)
    else -> Color(0xFF2563EB)
}

private fun folderIcon(token: String) = when (token.lowercase()) {
    "star" -> Icons.Default.PushPin
    "group" -> Icons.Default.Label
    else -> Icons.Default.Folder
}

private data class FolderEditorState(
    val originalName: String? = null,
    val name: String = "",
    val iconToken: String = "folder",
    val colorToken: String = "blue",
    val imageUri: String? = null,
    val description: String? = null,
    val isPinned: Boolean = false,
) {
    fun toSummary(): FolderSummary = FolderSummary(
        name = name.trim(),
        iconToken = iconToken,
        colorToken = colorToken,
        imageUri = imageUri,
        description = description?.trim()?.takeIf { it.isNotBlank() },
        isPinned = isPinned,
    )
}

private data class TagEditorState(
    val originalName: String? = null,
    val name: String = "",
)

private fun FolderSummary.toEditorState() = FolderEditorState(
    originalName = name,
    name = name,
    iconToken = iconToken,
    colorToken = colorToken,
    imageUri = imageUri,
    description = description,
    isPinned = isPinned,
)


@Composable
private fun rememberFolderBitmap(uri: String?): androidx.compose.runtime.State<Bitmap?> {
    val context = LocalContext.current
    return produceState<Bitmap?>(initialValue = null, key1 = context, key2 = uri) {
        value = withContext(Dispatchers.IO) {
            if (uri.isNullOrBlank()) null else runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val vaultSessionManager: VaultSessionManager,
    private val contactRepository: ContactRepository,
    appLockRepository: AppLockRepository,
) : ViewModel() {
    val settings = appLockRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLockSettings.DEFAULT)

    val tags = vaultSessionManager.activeVaultId
        .combine(vaultSessionManager.isLocked) { vaultId, isLocked -> vaultId to isLocked }
        .flatMapLatest { (vaultId, isLocked) ->
            if (vaultId == null || isLocked) flowOf(emptyList()) else contactRepository.observeTags(vaultId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders = vaultSessionManager.activeVaultId
        .combine(vaultSessionManager.isLocked) { vaultId, isLocked -> vaultId to isLocked }
        .flatMapLatest { (vaultId, isLocked) ->
            if (vaultId == null || isLocked) flowOf(emptyList()) else contactRepository.observeFolders(vaultId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val contacts = vaultSessionManager.activeVaultId
        .combine(vaultSessionManager.isLocked) { vaultId, isLocked -> vaultId to isLocked }
        .flatMapLatest { (vaultId, isLocked) ->
            if (vaultId == null || isLocked) flowOf(emptyList()) else contactRepository.observeContacts(vaultId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveTag(name: String) {
        val vaultId = vaultSessionManager.activeVaultId.value ?: return
        viewModelScope.launch { contactRepository.upsertTag(vaultId, TagSummary(name = name)) }
    }

    fun saveFolder(folder: FolderSummary) {
        val vaultId = vaultSessionManager.activeVaultId.value ?: return
        viewModelScope.launch { contactRepository.upsertFolder(vaultId, folder) }
    }

    fun deleteTag(name: String) {
        val vaultId = vaultSessionManager.activeVaultId.value ?: return
        val affectedContacts = contacts.value.filter { name in it.tags }
        viewModelScope.launch {
            affectedContacts.forEach { current ->
                contactRepository.saveContactDraft(
                    vaultId,
                    ContactDraft(
                        id = current.id,
                        displayName = current.displayName,
                        primaryPhone = current.primaryPhone,
                        tags = current.tags.filterNot { it == name },
                        isFavorite = current.isFavorite,
                        folderName = current.folderName,
                        photoUri = current.photoUri,
                        isBlocked = current.isBlocked,
                    ),
                )
            }
            contactRepository.deleteTag(vaultId, name)
        }
    }

    fun deleteFolder(name: String) {
        val vaultId = vaultSessionManager.activeVaultId.value ?: return
        val affectedContacts = contacts.value.filter { it.folderName == name }
        viewModelScope.launch {
            affectedContacts.forEach { current ->
                contactRepository.saveContactDraft(
                    vaultId,
                    ContactDraft(
                        id = current.id,
                        displayName = current.displayName,
                        primaryPhone = current.primaryPhone,
                        tags = current.tags,
                        isFavorite = current.isFavorite,
                        folderName = null,
                        photoUri = current.photoUri,
                        isBlocked = current.isBlocked,
                    ),
                )
            }
            contactRepository.deleteFolder(vaultId, name)
        }
    }

    fun renameTag(oldName: String, newName: String) {
        val vaultId = vaultSessionManager.activeVaultId.value ?: return
        viewModelScope.launch {
            if (oldName != newName) {
                contactRepository.upsertTag(vaultId, TagSummary(name = newName))
                contacts.value.filter { oldName in it.tags }.forEach { current ->
                    contactRepository.saveContactDraft(vaultId, ContactDraft(id = current.id, displayName = current.displayName, primaryPhone = current.primaryPhone, tags = current.tags.map { if (it == oldName) newName else it }, isFavorite = current.isFavorite, folderName = current.folderName, photoUri = current.photoUri, isBlocked = current.isBlocked))
                }
                contactRepository.deleteTag(vaultId, oldName)
            }
        }
    }

    fun renameFolder(oldName: String, newName: String) {
        val vaultId = vaultSessionManager.activeVaultId.value ?: return
        viewModelScope.launch {
            if (oldName != newName) {
                val oldFolder = folders.value.firstOrNull { it.name == oldName }
                val existingTarget = folders.value.firstOrNull { it.name == newName }
                val mergedFolder = when {
                    oldFolder != null && existingTarget != null -> existingTarget.copy(
                        usageCount = maxOf(existingTarget.usageCount, oldFolder.usageCount),
                    )
                    oldFolder != null -> oldFolder.copy(name = newName)
                    existingTarget != null -> existingTarget
                    else -> FolderSummary(name = newName)
                }
                contactRepository.upsertFolder(vaultId, mergedFolder)
                contacts.value.filter { it.folderName == oldName }.forEach { current ->
                    contactRepository.saveContactDraft(vaultId, ContactDraft(id = current.id, displayName = current.displayName, primaryPhone = current.primaryPhone, tags = current.tags, isFavorite = current.isFavorite, folderName = newName, photoUri = current.photoUri, isBlocked = current.isBlocked))
                }
                contactRepository.deleteFolder(vaultId, oldName)
            }
        }
    }

    fun removeTagFromContacts(contactIds: Set<String>, tag: String) {
        val vaultId = vaultSessionManager.activeVaultId.value ?: return
        val currentMap = contacts.value.associateBy { it.id }
        viewModelScope.launch {
            contactIds.forEach { id ->
                val current = currentMap[id] ?: return@forEach
                contactRepository.saveContactDraft(vaultId, ContactDraft(id = current.id, displayName = current.displayName, primaryPhone = current.primaryPhone, tags = current.tags.filterNot { it == tag }, isFavorite = current.isFavorite, folderName = current.folderName, photoUri = current.photoUri, isBlocked = current.isBlocked))
            }
        }
    }

    fun removeFolderFromContacts(contactIds: Set<String>) {
        val vaultId = vaultSessionManager.activeVaultId.value ?: return
        val currentMap = contacts.value.associateBy { it.id }
        viewModelScope.launch {
            contactIds.forEach { id ->
                val current = currentMap[id] ?: return@forEach
                contactRepository.saveContactDraft(vaultId, ContactDraft(id = current.id, displayName = current.displayName, primaryPhone = current.primaryPhone, tags = current.tags, isFavorite = current.isFavorite, folderName = null, photoUri = current.photoUri, isBlocked = current.isBlocked))
            }
        }
    }

    fun assignFolderToContacts(contactIds: Set<String>, folder: String) {
        val vaultId = vaultSessionManager.activeVaultId.value ?: return
        val cleanFolder = folder.trim().ifBlank { return }
        val currentMap = contacts.value.associateBy { it.id }
        viewModelScope.launch {
            val existingFolder = folders.value.firstOrNull { it.name == cleanFolder } ?: FolderSummary(name = cleanFolder)
            contactRepository.upsertFolder(vaultId, existingFolder)
            contactIds.forEach { id ->
                val current = currentMap[id] ?: return@forEach
                contactRepository.saveContactDraft(vaultId, ContactDraft(id = current.id, displayName = current.displayName, primaryPhone = current.primaryPhone, tags = current.tags, isFavorite = current.isFavorite, folderName = cleanFolder, photoUri = current.photoUri, isBlocked = current.isBlocked))
            }
        }
    }

    fun assignTagToContacts(contactIds: Set<String>, tag: String) {
        val vaultId = vaultSessionManager.activeVaultId.value ?: return
        val cleanTag = tag.trim().removePrefix("#").ifBlank { return }
        val currentMap = contacts.value.associateBy { it.id }
        viewModelScope.launch {
            contactRepository.upsertTag(vaultId, TagSummary(name = cleanTag))
            contactIds.forEach { id ->
                val current = currentMap[id] ?: return@forEach
                contactRepository.saveContactDraft(vaultId, ContactDraft(id = current.id, displayName = current.displayName, primaryPhone = current.primaryPhone, tags = (current.tags + cleanTag).distinct(), isFavorite = current.isFavorite, folderName = current.folderName, photoUri = current.photoUri, isBlocked = current.isBlocked))
            }
        }
    }
}
