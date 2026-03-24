package com.opencontacts.feature.contacts

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opencontacts.core.crypto.AppLockSettings
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.opencontacts.core.model.ContactSummary
import com.opencontacts.core.model.VaultSummary
import com.opencontacts.feature.contacts.fastscroll.AlphabetFastScroller
import com.opencontacts.feature.contacts.fastscroll.buildAlphabetSectionIndex
import com.opencontacts.feature.contacts.fastscroll.normalizeSectionLetter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ContactsRoute(
    activeVaultName: String = "Active vault",
    vaults: List<VaultSummary> = emptyList(),
    onOpenDetails: (String) -> Unit,
    onOpenWorkspace: (() -> Unit)? = null,
    onOpenImportExport: (() -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
    onOpenSecurity: (() -> Unit)? = null,
    onOpenBackup: (() -> Unit)? = null,
    onOpenTrash: (() -> Unit)? = null,
    onOpenVaults: (() -> Unit)? = null,
    onSwitchVault: ((String) -> Unit)? = null,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val callLogGroups by viewModel.callLogGroups.collectAsStateWithLifecycle()
    val editing by viewModel.editingContact.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var defaultTabApplied by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var qrPayload by remember { mutableStateOf<String?>(null) }
    var permissionPromptDismissed by rememberSaveable { mutableStateOf(false) }
    var dialPadVisible by remember { mutableStateOf(false) }
    var dialNumber by rememberSaveable { mutableStateOf("") }
    var bulkFolderEditor by remember { mutableStateOf<String?>(null) }
    var bulkTagEditor by remember { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var pendingDeleteIds by remember { mutableStateOf<Set<String>?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (selectedTab == 1) viewModel.refreshCallLogs()
    }

    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.WRITE_CONTACTS)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(Manifest.permission.ANSWER_PHONE_CALLS)
            if (Build.VERSION.SDK_INT <= 32) add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty() && !permissionPromptDismissed) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    LaunchedEffect(settings.defaultStartTab, defaultTabApplied) {
        if (!defaultTabApplied) {
            selectedTab = if (settings.defaultStartTab.equals("CALL_LOG", ignoreCase = true)) 1 else 0
            defaultTabApplied = true
        }
    }

    BackHandler(enabled = searchQuery.isNotBlank()) {
        searchQuery = ""
    }

    val callWeightByContactId = remember(callLogGroups) {
        callLogGroups.filter { it.contactId != null }.associate { it.contactId!! to it.totalCalls }
    }
    val callWeightByNumber = remember(callLogGroups) {
        callLogGroups.filter { it.number.isNotBlank() }.associate { normalizePhoneForMatching(it.number) to it.totalCalls }
    }

    val filteredContacts = remember(contacts, favoritesOnly, settings, searchQuery, callWeightByContactId, callWeightByNumber) {
        val normalizedQuery = searchQuery.trim().lowercase()
        contacts
            .asSequence()
            .filter { contact -> !favoritesOnly || contact.isFavorite }
            .filter { contact -> normalizedQuery.isBlank() || settings.showBlockedContactsInSearch || !contact.isBlocked }
            .filter { contact ->
                if (normalizedQuery.isBlank()) true else {
                    contact.displayName.lowercase().contains(normalizedQuery) ||
                        (contact.primaryPhone?.lowercase()?.contains(normalizedQuery) == true) ||
                        contact.tags.any { it.lowercase().contains(normalizedQuery) } ||
                        (contact.folderName?.lowercase()?.contains(normalizedQuery) == true) ||
                        contact.folderNames.any { it.lowercase().contains(normalizedQuery) }
                }
            }
            .sortedWith { left, right ->
                if (settings.showFavoritesFirst && left.isFavorite != right.isFavorite) {
                    return@sortedWith if (left.isFavorite) -1 else 1
                }
                val primaryCompare = when (settings.defaultContactSortOrder.uppercase()) {
                    "LAST_NAME" -> left.displayName.substringAfterLast(' ', left.displayName).compareTo(right.displayName.substringAfterLast(' ', right.displayName), ignoreCase = true)
                    "RECENTLY_ADDED" -> right.createdAt.compareTo(left.createdAt)
                    "MOST_CONTACTED" -> {
                        val leftCount = callWeightByContactId[left.id] ?: callWeightByNumber[normalizePhoneForMatching(left.primaryPhone)] ?: 0
                        val rightCount = callWeightByContactId[right.id] ?: callWeightByNumber[normalizePhoneForMatching(right.primaryPhone)] ?: 0
                        rightCount.compareTo(leftCount)
                    }
                    else -> left.displayName.compareTo(right.displayName, ignoreCase = true)
                }
                if (primaryCompare != 0) primaryCompare else left.displayName.compareTo(right.displayName, ignoreCase = true)
            }
            .toList()
    }

    val selectedContacts = remember(selectedIds, contacts) { contacts.filter { it.id in selectedIds } }
    val sectionIndex = remember(filteredContacts) { buildAlphabetSectionIndex(filteredContacts) { it.displayName } }
    val listState = rememberLazyListState()
    val currentScrollerLetter by remember(filteredContacts, listState) {
        derivedStateOf {
            filteredContacts
                .getOrNull(listState.firstVisibleItemIndex)
                ?.let { normalizeSectionLetter(it.displayName) }
                ?: "#"
        }
    }

    fun openDial(phone: String?) {
        val raw = phone?.trim().orEmpty()
        if (raw.isBlank()) return
        val uri = Uri.parse("tel:$raw")
        val hasCallPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val intent = if (hasCallPermission) Intent(Intent.ACTION_CALL, uri) else Intent(Intent.ACTION_DIAL, uri)
        context.startActivity(intent)
    }

    fun shareSelectedAsText() {
        if (selectedContacts.isEmpty()) return
        val payload = selectedContacts.joinToString("\n\n") { contact ->
            buildString {
                append(contact.displayName)
                contact.primaryPhone?.takeIf(String::isNotBlank)?.let { append("\n$it") }
                if (contact.tags.isNotEmpty()) append("\nTags: ${contact.tags.joinToString()}")
                contact.folderName?.let { append("\nFolder: $it") }
            }
        }
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, payload)
                },
                "Share contact(s)",
            ),
        )
    }

    fun shareQr(contact: ContactSummary) {
        qrPayload = buildString {
            append("BEGIN:VCARD\n")
            append("VERSION:3.0\n")
            append("FN:${contact.displayName}\n")
            contact.primaryPhone?.takeIf(String::isNotBlank)?.let { append("TEL:$it\n") }
            if (contact.tags.isNotEmpty()) append("NOTE:Tags=${contact.tags.joinToString()}\n")
            contact.folderName?.let { append("ORG:$it\n") }
            append("END:VCARD")
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("Vaults", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                vaults.forEach { vault ->
                    DrawerItem(
                        title = vault.displayName,
                        subtitle = if (vault.displayName == activeVaultName) "Current vault" else if (vault.isLocked) "Locked" else "Switch vault",
                        icon = Icons.Default.Folder,
                        selected = vault.displayName == activeVaultName,
                    ) {
                        onSwitchVault?.invoke(vault.id)
                        scope.launch { drawerState.close() }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                DrawerItem("Add / change vault", "Create or rename vaults", Icons.Default.Add) { onOpenVaults?.invoke(); scope.launch { drawerState.close() } }
                DrawerItem("Groups", "Tags & folders in current vault", Icons.Default.Groups) { onOpenWorkspace?.invoke(); scope.launch { drawerState.close() } }
                DrawerItem("Import / Export", "CSV, VCF, Excel", Icons.Default.Sync) { onOpenImportExport?.invoke(); scope.launch { drawerState.close() } }
                DrawerItem("Backup", "Local and Drive staging", Icons.Default.Storage) { onOpenBackup?.invoke(); scope.launch { drawerState.close() } }
                DrawerItem("Trash", "Restore or permanently delete", Icons.Default.DeleteSweep) { onOpenTrash?.invoke(); scope.launch { drawerState.close() } }
                DrawerItem("Settings", "Security, blocked contacts, retention", Icons.Default.Settings) { onOpenSecurity?.invoke(); scope.launch { drawerState.close() } }
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(26.dp),
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HomeAction("Favorites", Icons.Default.Star, selected = favoritesOnly) { favoritesOnly = !favoritesOnly }
                        HomeAction("Groups", Icons.Default.Groups) { onOpenWorkspace?.invoke() }
                        HomeAction("Add", Icons.Default.Add) { viewModel.startCreate() }
                        HomeAction("Dial", Icons.Default.Dialpad) { dialPadVisible = true }
                    }
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 6.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, contentDescription = "Menu") }
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp),
                            placeholder = { Text("Search contacts, numbers, tags, folders") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Backspace, contentDescription = "Clear search")
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    }
                }

                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Contacts") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Call log") })
                }

                LaunchedEffect(selectedTab) {
                    if (selectedTab == 1) viewModel.refreshCallLogs()
                }

                if (selectedTab == 0) {
                    if (selectedIds.isNotEmpty()) {
                        SelectionBar(
                            count = selectedIds.size,
                            allSelected = selectedIds.size == filteredContacts.size && filteredContacts.isNotEmpty(),
                            onSelectAll = { selectedIds = if (selectedIds.size == filteredContacts.size) emptySet() else filteredContacts.map { it.id }.toSet() },
                            onShare = ::shareSelectedAsText,
                            onDelete = { if (settings.confirmBeforeDelete) pendingDeleteIds = selectedIds else { viewModel.deleteMany(selectedIds); selectedIds = emptySet() } },
                            onEdit = { selectedContacts.singleOrNull()?.let(viewModel::startEdit) },
                            onQr = { selectedContacts.singleOrNull()?.let(::shareQr) },
                            onAssignFolder = { bulkFolderEditor = selectedContacts.firstOrNull()?.folderName.orEmpty() },
                            onAssignTag = { bulkTagEditor = selectedContacts.firstOrNull()?.tags?.firstOrNull().orEmpty() },
                            qrEnabled = selectedIds.size == 1,
                            editEnabled = selectedIds.size == 1,
                        )
                    }
                    if (filteredContacts.isEmpty()) {
                        EmptyStateCard("No contacts yet", "Add a private contact, import a CSV or VCF file, or bring in all your phone contacts.")
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            var lastJumpPosition by remember(sectionIndex) { mutableIntStateOf(-1) }
                            LazyColumn(
                                state = listState,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize().padding(end = 16.dp),
                            ) {
                                items(filteredContacts, key = { it.id }, contentType = { "contact" }) { contact ->
                                    val selected = contact.id in selectedIds
                                    ContactModernCard(
                                        contact = contact,
                                        selected = selected,
                                        selectionMode = selectedIds.isNotEmpty(),
                                        onToggleSelection = { selectedIds = if (selected) selectedIds - contact.id else selectedIds + contact.id },
                                        onLongPress = { selectedIds = if (selected) selectedIds - contact.id else selectedIds + contact.id },
                                        onOpen = {
                                            if (selectedIds.isNotEmpty()) {
                                                selectedIds = if (selected) selectedIds - contact.id else selectedIds + contact.id
                                            } else if (settings.openContactDirectlyOnTap) {
                                                onOpenDetails(contact.id)
                                            } else {
                                                selectedIds = setOf(contact.id)
                                            }
                                        },
                                        onCall = { openDial(contact.primaryPhone) },
                                        compact = settings.contactListDensity.equals("COMPACT", ignoreCase = true),
                                        showPhoto = settings.showContactPhotosInList,
                                    )
                                }
                                item { Spacer(Modifier.height(96.dp)) }
                            }
                            AlphabetFastScroller(
                                letters = sectionIndex.letters,
                                availableLetters = sectionIndex.availableLetters,
                                activeLetter = currentScrollerLetter,
                                modifier = Modifier.align(Alignment.CenterEnd),
                                onLetterChanged = { selectedLetter ->
                                    sectionIndex.resolveTargetPosition(selectedLetter)
                                        ?.takeIf { it != lastJumpPosition }
                                        ?.let { targetPosition ->
                                            lastJumpPosition = targetPosition
                                            scope.launch { listState.scrollToItem(targetPosition) }
                                        }
                                },
                                onInteractionEnd = { lastJumpPosition = -1 },
                            )
                        }
                    }
                } else {
                    if (callLogGroups.isEmpty()) {
                        EmptyStateCard("No recent calls", "Grant call-log permission to show the full device call history, grouped by contact or normalized number.")
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(callLogGroups, key = { it.key }, contentType = { "call_group" }) { group ->
                                CallLogGroupCard(
                                    group = group,
                                    onDial = { openDial(group.number) },
                                    onOpenDetails = group.contactId?.let { contactId -> { onOpenDetails(contactId) } },
                                )
                            }
                            item { Spacer(Modifier.height(84.dp)) }
                        }
                    }
                }
            }

            editing?.let { editor ->
                FullScreenContactEditor(
                    state = editor,
                    onStateChange = viewModel::updateEditor,
                    onDismiss = viewModel::dismissEditor,
                    onConfirm = viewModel::saveEditor,
                )
            }
            bulkFolderEditor?.let { value ->
                ClassificationPickerDialog(
                    title = "Assign folder",
                    value = value,
                    existingNames = folders.map { it.name },
                    label = "Folder name",
                    onValueChange = { bulkFolderEditor = it },
                    onDismiss = { bulkFolderEditor = null },
                    onConfirm = {
                        val clean = bulkFolderEditor?.trim().orEmpty()
                        if (clean.isNotBlank()) viewModel.assignFolderToMany(selectedIds, clean)
                        bulkFolderEditor = null
                        selectedIds = emptySet()
                    },
                )
            }
            bulkTagEditor?.let { value ->
                ClassificationPickerDialog(
                    title = "Assign tag",
                    value = value,
                    existingNames = tags.map { it.name },
                    label = "Tag name",
                    onValueChange = { bulkTagEditor = it },
                    onDismiss = { bulkTagEditor = null },
                    onConfirm = {
                        val clean = bulkTagEditor?.trim().orEmpty().removePrefix("#")
                        if (clean.isNotBlank()) viewModel.assignTagToMany(selectedIds, clean)
                        bulkTagEditor = null
                        selectedIds = emptySet()
                    },
                )
            }
            qrPayload?.let { payload -> QrCodeDialog(payload = payload, onDismiss = { qrPayload = null }) }
                        pendingDeleteIds?.let { ids ->
                AlertDialog(
                    onDismissRequest = { pendingDeleteIds = null },
                    title = { Text("Delete selected contacts?") },
                    text = { Text("${ids.size} selected contact(s) will be moved to trash.") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.deleteMany(ids)
                            selectedIds = emptySet()
                            pendingDeleteIds = null
                        }) { Text("Delete") }
                    },
                    dismissButton = { TextButton(onClick = { pendingDeleteIds = null }) { Text("Cancel") } },
                )
            }
            if (dialPadVisible) {
                DialPadBottomSheet(
                    number = dialNumber,
                    contacts = filteredContacts,
                    settings = settings,
                    onNumberChange = { dialNumber = it },
                    onDismiss = { dialPadVisible = false },
                    onCall = { openDial(dialNumber) },
                    onSaveContact = { value ->
                        dialPadVisible = false
                        viewModel.startCreate(prefilledPhone = value)
                    },
                )
            }
        }
    }
}

@Composable
private fun DrawerItem(title: String, subtitle: String, icon: ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HomeAction(label: String, icon: ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.combinedClickable(onClick = onClick)) {
        Surface(shape = CircleShape, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Icon(icon, contentDescription = label, modifier = Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    qrEnabled: Boolean,
    editEnabled: Boolean,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onQr: () -> Unit,
    onAssignFolder: () -> Unit,
    onAssignTag: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("$count selected", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onSelectAll) { Icon(if (allSelected) Icons.Default.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, contentDescription = "Select all") }
                IconButton(onClick = onAssignFolder) { Icon(Icons.Default.CreateNewFolder, contentDescription = "Assign folder") }
                IconButton(onClick = onAssignTag) { Icon(Icons.Default.Groups, contentDescription = "Assign tag") }
                IconButton(onClick = onEdit, enabled = editEnabled) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = "Share") }
                IconButton(onClick = onQr, enabled = qrEnabled) { Icon(Icons.Default.QrCode2, contentDescription = "QR") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
        }
    }
}

@Composable
private fun ContactModernCard(
    contact: ContactSummary,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onLongPress: () -> Unit,
    onOpen: () -> Unit,
    onCall: () -> Unit,
    compact: Boolean,
    showPhoto: Boolean,
) {
    val context = LocalContext.current
    val padding = if (compact) 12.dp else 16.dp
    val avatarSize = if (compact) 44.dp else 52.dp
    val avatarBitmapState by rememberBitmapFromUri(context = context, uri = contact.photoUri, maxSizePx = 192)
    val avatarBitmap = avatarBitmapState
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> MaterialTheme.colorScheme.secondaryContainer
                contact.isBlocked -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.surface
            },
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(modifier = Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Top) {
                    if (selectionMode) {
                        Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
                        Spacer(Modifier.width(8.dp))
                    }
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(avatarSize)) {
                        Box(contentAlignment = Alignment.Center) {
                            if (showPhoto && avatarBitmap != null) {
                                Image(bitmap = avatarBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                Text(contact.displayName.take(1).uppercase(), style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(contact.displayName, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (contact.isFavorite) Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(contact.primaryPhone ?: "No phone number", style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            contact.folderName?.let { AssistChip(onClick = {}, label = { Text(it) }, leadingIcon = { Icon(Icons.Default.Folder, null) }) }
                            if (contact.isBlocked) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text("Blocked", color = MaterialTheme.colorScheme.onErrorContainer) },
                                    leadingIcon = { Icon(Icons.Default.Block, null, tint = MaterialTheme.colorScheme.onErrorContainer) },
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = onCall, enabled = !contact.primaryPhone.isNullOrBlank()) { Icon(Icons.Default.Phone, contentDescription = "Call") }
            }
            if (contact.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    contact.tags.take(8).forEach { tag -> AssistChip(onClick = {}, label = { Text(tag) }) }
                }
            }
        }
    }
}

@Composable
private fun CallLogGroupCard(group: CallLogGroup, onDial: () -> Unit, onOpenDetails: (() -> Unit)?) {
    val missedColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFFF7A7A) else Color(0xFFB3261E)
    val isMissed = group.lastType == "Missed"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpenDetails != null) Modifier.clickable(onClick = onOpenDetails) else Modifier),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = if (isMissed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f) else MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(group.displayName, style = MaterialTheme.typography.titleMedium)
                    if (isMissed) AssistChip(onClick = {}, label = { Text("Missed", color = missedColor) })
                }
                Text(
                    text = "${group.lastType} • ${formatTime(group.lastTimestamp)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isMissed) missedColor else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${group.totalCalls} call(s) • ${group.number.ifBlank { "No number" }}${if (group.totalDurationSeconds > 0) " • ${group.totalDurationSeconds}s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isMissed) missedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDial, enabled = group.number.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Filled.CallMade,
                    contentDescription = "Dial",
                    tint = if (isMissed) missedColor else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun EmptyStateCard(title: String, subtitle: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle)
        }
    }
}

@Composable
private fun FullScreenContactEditor(state: ContactEditorState, onStateChange: (ContactEditorState) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        onStateChange(state.copy(photoUri = uri?.toString().orEmpty()))
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.id == null) "Add contact" else "Edit contact", style = MaterialTheme.typography.headlineMedium)
                Row {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = onConfirm) { Text("Save") }
                }
            }
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Main details", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.material3.OutlinedTextField(value = state.displayName, onValueChange = { onStateChange(state.copy(displayName = it)) }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    androidx.compose.material3.OutlinedTextField(value = state.phone, onValueChange = { onStateChange(state.copy(phone = it)) }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    androidx.compose.material3.OutlinedTextField(value = state.folderName, onValueChange = { onStateChange(state.copy(folderName = it)) }, label = { Text("Folder") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    androidx.compose.material3.OutlinedTextField(value = state.tags, onValueChange = { onStateChange(state.copy(tags = it)) }, label = { Text("Tags (comma separated)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.isFavorite, onCheckedChange = { onStateChange(state.copy(isFavorite = it)) })
                        Text("Favorite")
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Contact photo", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.photoUri.isBlank()) "No photo selected" else "Photo attached")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { photoPicker.launch("image/*") }) { Text(if (state.photoUri.isBlank()) "Add photo" else "Change photo") }
                        if (state.photoUri.isNotBlank()) {
                            TextButton(onClick = { onStateChange(state.copy(photoUri = "")) }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialPadBottomSheet(
    number: String,
    contacts: List<ContactSummary>,
    settings: AppLockSettings,
    onNumberChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onCall: () -> Unit,
    onSaveContact: (String) -> Unit,
) {
    val keys = listOf(
        "1" to "", "2" to "ABC", "3" to "DEF",
        "4" to "GHI", "5" to "JKL", "6" to "MNO",
        "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
        "*" to "", "0" to "+", "#" to "",
    )
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val formattedNumber = remember(number, settings.dialPadAutoFormat) {
        if (settings.dialPadAutoFormat) formatDialPadNumber(number) else number
    }
    val suggestions = remember(number, contacts, settings.dialPadShowT9Suggestions) {
        if (!settings.dialPadShowT9Suggestions) emptyList() else computeDialPadSuggestions(number, contacts)
    }
    val stateLabel = remember(number, suggestions) {
        when {
            number.isBlank() -> "Idle"
            suggestions.isNotEmpty() -> "Matched contact"
            else -> "Typing"
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stateLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                val clip = clipboardManager.getText()?.text.orEmpty()
                                if (clip.isNotBlank()) onNumberChange(number + clip.filter { it.isDigit() || it == '+' || it == '*' || it == '#' })
                            }) { Text("Paste") }
                            TextButton(onClick = { if (number.isNotBlank()) onSaveContact(number) }, enabled = number.isNotBlank()) { Text("Save") }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formattedNumber.ifBlank { "Enter number" },
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (number.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { if (number.isNotEmpty()) onNumberChange(number.dropLast(1)) },
                            enabled = number.isNotEmpty(),
                            modifier = Modifier.combinedClickable(
                                onClick = { if (number.isNotEmpty()) onNumberChange(number.dropLast(1)) },
                                onLongClick = { if (settings.dialPadLongPressBackspaceClears && number.isNotEmpty()) onNumberChange("") },
                            ),
                        ) {
                            Icon(Icons.Default.Backspace, contentDescription = "Delete digit")
                        }
                    }
                    if (suggestions.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(suggestions.take(4), key = { it.id }) { contact ->
                                DialPadSuggestionChip(
                                    contact = contact,
                                    onSelect = { onNumberChange(contact.primaryPhone.orEmpty()) },
                                )
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                keys.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        row.forEach { (digit, letters) ->
                            Box(
                                modifier = Modifier
                                    .size(width = 92.dp, height = 72.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .combinedClickable(
                                        onClick = {
                                            if (settings.hapticFeedbackEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onNumberChange(number + digit)
                                        },
                                        onLongClick = {
                                            if (settings.hapticFeedbackEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            when (digit) {
                                                "0" -> onNumberChange(number + "+")
                                                else -> onNumberChange(number + digit)
                                            }
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(digit, style = MaterialTheme.typography.headlineMedium)
                                    if (settings.dialPadShowLetters && letters.isNotBlank()) {
                                        Text(letters, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else if (digit == "0") {
                                        Spacer(Modifier.height(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Surface(shape = CircleShape, color = Color(0xFF16A34A)) {
                    IconButton(onClick = onCall, modifier = Modifier.size(74.dp), enabled = number.isNotBlank()) {
                        Icon(Icons.Default.Phone, contentDescription = "Call", tint = Color.White)
                    }
                }
            }
        }
    }
}


@Composable
private fun DialPadSuggestionChip(contact: ContactSummary, onSelect: () -> Unit) {
    val phone = contact.primaryPhone.orEmpty()
    val folderLabel = contact.folderNames.firstOrNull() ?: contact.folderName
    AssistChip(
        onClick = onSelect,
        label = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(contact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text(phone, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
        trailingIcon = if (!folderLabel.isNullOrBlank()) {
            { Text(folderLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        } else null,
    )
}

private fun computeDialPadSuggestions(number: String, contacts: List<ContactSummary>): List<ContactSummary> {
    val digits = number.filter(Char::isDigit)
    if (digits.isBlank()) return emptyList()
    return contacts.filter { contact ->
        val phoneDigits = contact.primaryPhone.orEmpty().filter(Char::isDigit)
        phoneDigits.contains(digits) || contact.displayName.toT9Digits().startsWith(digits)
    }.sortedWith(compareByDescending<ContactSummary> { it.isFavorite }.thenBy { it.displayName.lowercase() }).take(3)
}

private fun String.toT9Digits(): String = uppercase().mapNotNull { char ->
    when (char) {
        in 'A'..'C' -> '2'
        in 'D'..'F' -> '3'
        in 'G'..'I' -> '4'
        in 'J'..'L' -> '5'
        in 'M'..'O' -> '6'
        in 'P'..'S' -> '7'
        in 'T'..'V' -> '8'
        in 'W'..'Z' -> '9'
        else -> null
    }
}.joinToString("")

private fun formatDialPadNumber(number: String): String {
    if (number.isBlank()) return ""
    val prefix = if (number.startsWith('+')) "+" else ""
    val digits = number.removePrefix("+")
    return prefix + digits.chunked(3).joinToString(" ")
}

@Composable
private fun QrCodeDialog(payload: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val bitmap = remember(payload) { generateQrBitmap(payload, if (expanded) 1200 else 720) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share as QR") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "QR code",
                        modifier = Modifier.size(if (expanded) 300.dp else 220.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).padding(12.dp),
                    )
                }
                Text("Scan to import contact data quickly.")
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    val ok = bitmap?.let { shareBitmap(context, it) } ?: false
                    if (!ok) Toast.makeText(context, "Unable to share QR", Toast.LENGTH_SHORT).show()
                }) { Text("Share") }
                TextButton(onClick = {
                    val ok = bitmap?.let { saveBitmapToGallery(context, it) } ?: false
                    Toast.makeText(context, if (ok) "QR saved to gallery" else "Unable to save QR", Toast.LENGTH_SHORT).show()
                }) { Text("Save") }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Collapse" else "Expand") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun generateQrBitmap(payload: String, size: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until size) for (y in 0 until size) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}.getOrNull()

private fun saveBitmapToGallery(context: android.content.Context, bitmap: Bitmap): Boolean {
    return runCatching {
        val resolver = context.contentResolver
        val name = "opencontacts_qr_${System.currentTimeMillis()}.png"
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OpenContacts")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) } ?: return false
        true
    }.getOrDefault(false)
}

private fun shareBitmap(context: android.content.Context, bitmap: Bitmap): Boolean {
    return runCatching {
        val file = File(context.cacheDir, "qr_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        val uri = FileProvider.getUriForFile(context, "com.opencontacts.app.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("QR", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share QR"))
        true
    }.getOrDefault(false)
}

@Composable
private fun ClassificationPickerDialog(
    title: String,
    value: String,
    existingNames: List<String>,
    label: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true)
                if (existingNames.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        existingNames.forEach { name ->
                            AssistChip(onClick = { onValueChange(name) }, label = { Text(name) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun rememberBitmapFromUri(
    context: android.content.Context,
    uri: String?,
    maxSizePx: Int,
) = produceState<Bitmap?>(initialValue = null, key1 = context, key2 = uri, key3 = maxSizePx) {
    value = withContext(Dispatchers.IO) {
        loadBitmapFromUri(context = context, uri = uri, maxSizePx = maxSizePx)
    }
}

private fun loadBitmapFromUri(
    context: android.content.Context,
    uri: String?,
    maxSizePx: Int,
): Bitmap? {
    if (uri.isNullOrBlank()) return null
    return runCatching {
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
        val sampleSize = generateSequence(1) { it * 2 }
            .takeWhile { largestSide / it > maxSizePx }
            .lastOrNull() ?: 1
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
    }.getOrNull()
}

private fun formatTime(value: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(value))
