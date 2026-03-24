package com.opencontacts.data.repository

import android.content.Context
import android.util.Base64
import com.opencontacts.core.crypto.AppLockRepository
import com.opencontacts.core.crypto.VaultPassphraseManager
import com.opencontacts.core.model.BackupRecordSummary
import com.opencontacts.core.model.ContactSummary
import com.opencontacts.core.model.ImportExportHistorySummary
import com.opencontacts.data.db.database.VaultDatabaseFactory
import com.opencontacts.data.db.entity.BackupRecordEntity
import com.opencontacts.data.db.entity.ContactEntity
import com.opencontacts.data.db.entity.ContactFolderCrossRef
import com.opencontacts.data.db.entity.ContactTagCrossRef
import com.opencontacts.data.db.entity.FolderEntity
import com.opencontacts.data.db.entity.ImportExportHistoryEntity
import com.opencontacts.data.db.entity.NoteEntity
import com.opencontacts.data.db.entity.ReminderEntity
import com.opencontacts.data.db.entity.TagEntity
import com.opencontacts.data.db.entity.TimelineEntity
import com.opencontacts.data.db.mapper.toModel
import com.opencontacts.domain.vaults.VaultTransferRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class VaultTransferRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultDatabaseFactory: VaultDatabaseFactory,
    private val vaultPassphraseManager: VaultPassphraseManager,
    private val googleDriveBackupAdapter: GoogleDriveBackupAdapter,
    private val oneDriveBackupAdapter: OneDriveBackupAdapter,
    private val phoneContactsBridge: PhoneContactsBridge,
    private val backupFileCodec: BackupFileCodec,
    private val vcfHandler: VcfHandler,
    private val csvHandler: CsvHandler,
    private val transferDestinationManager: TransferDestinationManager,
    private val appLockRepository: AppLockRepository,
) : VaultTransferRepository {
    override fun observeBackupRecords(vaultId: String): Flow<List<BackupRecordSummary>> = flow {
        val db = vaultDatabaseFactory.getDatabase(vaultId)
        emitAll(db.contactsDao().observeBackupRecords().map { it.map { entity -> entity.toModel() } })
    }

    override fun observeImportExportHistory(vaultId: String): Flow<List<ImportExportHistorySummary>> = flow {
        val db = vaultDatabaseFactory.getDatabase(vaultId)
        emitAll(db.contactsDao().observeImportExportHistory().map { it.map { entity -> entity.toModel() } })
    }

    override suspend fun createLocalBackup(vaultId: String): BackupRecordSummary {
        val dao = vaultDatabaseFactory.getDatabase(vaultId).contactsDao()
        val artifact = buildBackupArtifact(vaultId)
        val saved = transferDestinationManager.writeBackupDocument(
            fileName = artifact.fileName,
            mimeType = "application/octet-stream",
        ) { output ->
            output.write(artifact.bytes)
        }
        return persistBackupRecord(
            dao = dao,
            provider = "LOCAL",
            vaultId = vaultId,
            status = "SUCCESS",
            filePath = saved.path,
            fileSizeBytes = saved.sizeBytes,
            createdAt = artifact.createdAt,
        )
    }

    override suspend fun restoreLatestLocalBackup(vaultId: String): Boolean {
        val db = vaultDatabaseFactory.getDatabase(vaultId)
        val dao = db.contactsDao()
        val latest = transferDestinationManager.findLatestBackupDocument(
            prefix = "vault-$vaultId",
            extension = "ocbak",
        ) ?: return false

        val wrappedPayload = latest.readBytes()
        val encryptedPayload = backupFileCodec.unwrap(wrappedPayload).decodeToString()
        val root = JSONObject(decryptForVault(vaultId, encryptedPayload).decodeToString())

        dao.clearTimeline()
        dao.clearNotes()
        dao.clearReminders()
        dao.clearAll()
        dao.clearBackupRecords()
        dao.clearImportExportHistory()
        dao.upsertFolders(root.optJSONArray("folders")?.toFolderEntities().orEmpty())
        dao.upsertTags(root.optJSONArray("tags")?.toTagEntities().orEmpty())
        dao.upsertAll(root.optJSONArray("contacts")?.toContactEntities().orEmpty())
        dao.insertContactFolderCrossRefs(root.optJSONArray("folderCrossRefs")?.toFolderCrossRefs().orEmpty())
        dao.insertContactTagCrossRefs(root.optJSONArray("crossRefs")?.toCrossRefs().orEmpty())
        dao.upsertNotes(root.optJSONArray("notes")?.toNoteEntities().orEmpty())
        dao.upsertReminders(root.optJSONArray("reminders")?.toReminderEntities().orEmpty())
        dao.insertTimelineItems(root.optJSONArray("timeline")?.toTimelineEntities().orEmpty())
        dao.upsertBackupRecord(
            BackupRecordEntity(
                backupId = UUID.randomUUID().toString(),
                provider = "LOCAL",
                vaultId = vaultId,
                createdAt = System.currentTimeMillis(),
                status = "RESTORED",
                filePath = latest.path,
                fileSizeBytes = latest.sizeBytes,
            ),
        )
        return true
    }

    override suspend fun stageLatestBackupToGoogleDrive(vaultId: String): BackupRecordSummary {
        val dao = vaultDatabaseFactory.getDatabase(vaultId).contactsDao()
        val artifact = buildBackupArtifact(vaultId)
        transferDestinationManager.writeBackupDocument(
            fileName = artifact.fileName,
            mimeType = "application/octet-stream",
        ) { output ->
            output.write(artifact.bytes)
        }
        val staged = googleDriveBackupAdapter.stageEncryptedBackup(artifact.fileName, artifact.bytes)
        return persistCloudRecord(vaultId, "GOOGLE_DRIVE_STAGED", staged, dao)
    }

    override suspend fun stageLatestBackupToOneDrive(vaultId: String): BackupRecordSummary {
        val dao = vaultDatabaseFactory.getDatabase(vaultId).contactsDao()
        val artifact = buildBackupArtifact(vaultId)
        transferDestinationManager.writeBackupDocument(
            fileName = artifact.fileName,
            mimeType = "application/octet-stream",
        ) { output ->
            output.write(artifact.bytes)
        }
        val staged = oneDriveBackupAdapter.stageEncryptedBackup(artifact.fileName, artifact.bytes)
        return persistCloudRecord(vaultId, "ONEDRIVE_STAGED", staged, dao)
    }

    override suspend fun exportContactsJson(vaultId: String): ImportExportHistorySummary {
        val dao = vaultDatabaseFactory.getDatabase(vaultId).contactsDao()
        val now = System.currentTimeMillis()
        val contacts = dao.getAllDetailed().map { it.toModel() }
        val fileName = stamped("contacts", vaultId, "json")
        val payload = JSONObject().apply {
            put("vaultId", vaultId)
            put("createdAt", now)
            put("contacts", JSONArray(contacts.map { it.toJson() }))
        }
        val saved = transferDestinationManager.writeExportDocument(
            fileName = fileName,
            mimeType = "application/json",
        ) { output ->
            output.write(payload.toString(2).encodeToByteArray())
        }
        return persistHistory(dao, "EXPORT_JSON", vaultId, now, "SUCCESS", saved.path, contacts.size)
    }

    override suspend fun importLatestContactsJson(vaultId: String): ImportExportHistorySummary {
        val latest = transferDestinationManager.findLatestExportDocument(
            prefix = "contacts-$vaultId",
            extension = "json",
        ) ?: return persistHistory(
            dao = vaultDatabaseFactory.getDatabase(vaultId).contactsDao(),
            operationType = "IMPORT_JSON",
            vaultId = vaultId,
            createdAt = System.currentTimeMillis(),
            status = "NO_FILE",
            filePath = "",
            itemCount = 0,
        )
        val root = JSONObject(latest.readText())
        val contacts = root.getJSONArray("contacts").toContactSummaries()
        return importSummaries(vaultId, contacts, "IMPORT_JSON", latest.path)
    }

    override suspend fun exportContactsCsv(vaultId: String): ImportExportHistorySummary {
        val dao = vaultDatabaseFactory.getDatabase(vaultId).contactsDao()
        val now = System.currentTimeMillis()
        val contacts = dao.getAllDetailed().map { it.toModel() }
        val fileName = stamped("contacts", vaultId, "csv")
        val saved = transferDestinationManager.writeExportDocument(
            fileName = fileName,
            mimeType = "text/csv",
        ) { output ->
            csvHandler.write(contacts, output)
        }
        return persistHistory(dao, "EXPORT_CSV", vaultId, now, "SUCCESS", saved.path, contacts.size)
    }

    override suspend fun importLatestContactsCsv(vaultId: String): ImportExportHistorySummary {
        val latest = transferDestinationManager.findLatestExportDocument(
            prefix = "contacts-$vaultId",
            extension = "csv",
        ) ?: importFallbackFile("contacts.csv")
            ?: return persistHistory(
                dao = vaultDatabaseFactory.getDatabase(vaultId).contactsDao(),
                operationType = "IMPORT_CSV",
                vaultId = vaultId,
                createdAt = System.currentTimeMillis(),
                status = "NO_FILE",
                filePath = "",
                itemCount = 0,
            )
        val contacts = latest.readBytes().inputStream().use { csvHandler.parse(it) }
        return importSummaries(vaultId, contacts, "IMPORT_CSV", latest.path)
    }

    override suspend fun exportContactsVcf(vaultId: String): ImportExportHistorySummary {
        val dao = vaultDatabaseFactory.getDatabase(vaultId).contactsDao()
        val now = System.currentTimeMillis()
        val contacts = dao.getAllDetailed().map { it.toModel() }
        val fileName = stamped("contacts", vaultId, "vcf")
        val saved = transferDestinationManager.writeExportDocument(
            fileName = fileName,
            mimeType = "text/x-vcard",
        ) { output ->
            vcfHandler.write(contacts, output)
        }
        return persistHistory(dao, "EXPORT_VCF", vaultId, now, "SUCCESS", saved.path, contacts.size)
    }

    override suspend fun exportContactsExcel(vaultId: String): ImportExportHistorySummary {
        val dao = vaultDatabaseFactory.getDatabase(vaultId).contactsDao()
        val now = System.currentTimeMillis()
        val contacts = dao.getAllDetailed().map { it.toModel() }
        val fileName = stamped("contacts", vaultId, "xls")
        val excelHtml = buildString {
            append("<html><head><meta charset=\"utf-8\"></head><body><table border=\"1\">")
            append("<tr><th>Name</th><th>Phone</th><th>Tags</th><th>Folder</th><th>Favorite</th></tr>")
            contacts.forEach { contact ->
                append("<tr>")
                append("<td>${escapeHtml(contact.displayName)}</td>")
                append("<td>${escapeHtml(contact.primaryPhone.orEmpty())}</td>")
                append("<td>${escapeHtml(contact.tags.joinToString(" | "))}</td>")
                append("<td>${escapeHtml(contact.folderName.orEmpty())}</td>")
                append("<td>${if (contact.isFavorite) "Yes" else "No"}</td>")
                append("</tr>")
            }
            append("</table></body></html>")
        }
        val saved = transferDestinationManager.writeExportDocument(
            fileName = fileName,
            mimeType = "application/vnd.ms-excel",
        ) { output ->
            output.write(excelHtml.encodeToByteArray())
        }
        return persistHistory(dao, "EXPORT_EXCEL", vaultId, now, "SUCCESS", saved.path, contacts.size)
    }

    override suspend fun importLatestContactsVcf(vaultId: String): ImportExportHistorySummary {
        val latest = transferDestinationManager.findLatestExportDocument(
            prefix = "contacts-$vaultId",
            extension = "vcf",
        ) ?: importFallbackFile("contacts.vcf")
            ?: return persistHistory(
                dao = vaultDatabaseFactory.getDatabase(vaultId).contactsDao(),
                operationType = "IMPORT_VCF",
                vaultId = vaultId,
                createdAt = System.currentTimeMillis(),
                status = "NO_FILE",
                filePath = "",
                itemCount = 0,
            )
        val contacts = latest.readBytes().inputStream().use { vcfHandler.parse(it) }
        return importSummaries(vaultId, contacts, "IMPORT_VCF", latest.path)
    }

    override suspend fun importFromPhoneContacts(vaultId: String): ImportExportHistorySummary {
        val contacts = phoneContactsBridge.importContacts()
        return importSummaries(vaultId, contacts, "IMPORT_PHONE", "content://contacts")
    }

    override suspend fun exportAllContactsToPhone(vaultId: String): ImportExportHistorySummary {
        val dao = vaultDatabaseFactory.getDatabase(vaultId).contactsDao()
        val contacts = dao.getAllDetailed().map { it.toModel() }
        val inserted = phoneContactsBridge.exportContacts(contacts)
        return persistHistory(dao, "EXPORT_PHONE", vaultId, System.currentTimeMillis(), "SUCCESS", "content://contacts", inserted)
    }

    private suspend fun buildBackupArtifact(vaultId: String): BackupArtifact {
        val dao = vaultDatabaseFactory.getDatabase(vaultId).contactsDao()
        val now = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("vaultId", vaultId)
            put("createdAt", now)
            put("contacts", JSONArray(dao.getAll().map { it.toJson() }))
            put("folderCrossRefs", JSONArray(dao.getAllFolderCrossRefs().map { it.toJson() }))
            put("notes", JSONArray(dao.getAllNotes().map { it.toJson() }))
            put("reminders", JSONArray(dao.getAllReminders().map { it.toJson() }))
            put("timeline", JSONArray(dao.getAllTimelineItems().map { it.toJson() }))
            put("tags", JSONArray(dao.getTags().map { it.toJson() }))
            put("folders", JSONArray(dao.getFolders().map { it.toJson() }))
            put("crossRefs", JSONArray(dao.getAllCrossRefs().map { it.toJson() }))
        }
        val encryptedPayload = encryptForVault(vaultId, payload.toString(2).encodeToByteArray())
        return BackupArtifact(
            fileName = stamped("vault", vaultId, "ocbak"),
            createdAt = now,
            itemCount = dao.count(),
            bytes = backupFileCodec.wrap(encryptedPayload.encodeToByteArray(), now, dao.count()),
        )
    }

    private suspend fun importSummaries(
        vaultId: String,
        contacts: List<ContactSummary>,
        operation: String,
        filePath: String,
    ): ImportExportHistorySummary {
        val dao = vaultDatabaseFactory.getDatabase(vaultId).contactsDao()
        val now = System.currentTimeMillis()
        contacts.forEach { summary ->
            val id = summary.id.ifBlank { UUID.randomUUID().toString() }
            dao.upsert(
                ContactEntity(
                    contactId = id,
                    displayName = summary.displayName,
                    sortKey = summary.displayName.lowercase(),
                    primaryPhone = summary.primaryPhone,
                    tagCsv = summary.tags.joinToString(","),
                    isFavorite = summary.isFavorite,
                    folderName = summary.folderName,
                    createdAt = now,
                    updatedAt = now,
                    isDeleted = false,
                    deletedAt = null,
                    photoUri = summary.photoUri,
                    isBlocked = summary.isBlocked,
                    externalLinksJson = org.json.JSONArray(summary.socialLinks.map { link -> org.json.JSONObject().apply { put("type", link.type); put("value", link.value); put("label", link.label) } }).toString(),
                ),
            )
            val normalizedFolders = (summary.folderNames.ifEmpty { listOfNotNull(summary.folderName) })
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
            normalizedFolders.forEach { folderName ->
                dao.upsertFolder(FolderEntity(folderName, folderName, "folder", "blue", now))
            }
            dao.deleteFolderCrossRefsForContact(id)
            dao.insertContactFolderCrossRefs(normalizedFolders.map { ContactFolderCrossRef(id, it) })
            dao.deleteCrossRefsForContact(id)
            val tags = summary.tags.map { it.trim().removePrefix("#") }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
            dao.upsertTags(tags.map { TagEntity(it, it, "default", now) })
            dao.insertContactTagCrossRefs(tags.map { ContactTagCrossRef(id, it) })
        }
        return persistHistory(dao, operation, vaultId, now, "SUCCESS", filePath, contacts.size)
    }

    private suspend fun persistBackupRecord(
        dao: com.opencontacts.data.db.dao.ContactsDao,
        provider: String,
        vaultId: String,
        status: String,
        filePath: String,
        fileSizeBytes: Long,
        createdAt: Long = System.currentTimeMillis(),
    ): BackupRecordSummary {
        val entity = BackupRecordEntity(
            backupId = UUID.randomUUID().toString(),
            provider = provider,
            vaultId = vaultId,
            createdAt = createdAt,
            status = status,
            filePath = filePath,
            fileSizeBytes = fileSizeBytes,
        )
        dao.upsertBackupRecord(entity)
        return entity.toModel()
    }

    private suspend fun persistCloudRecord(
        vaultId: String,
        provider: String,
        stagedFile: File,
        dao: com.opencontacts.data.db.dao.ContactsDao,
    ): BackupRecordSummary {
        return persistBackupRecord(
            dao = dao,
            provider = provider,
            vaultId = vaultId,
            status = "STAGED",
            filePath = stagedFile.absolutePath,
            fileSizeBytes = stagedFile.length(),
        )
    }

    private suspend fun persistHistory(
        dao: com.opencontacts.data.db.dao.ContactsDao,
        operationType: String,
        vaultId: String,
        createdAt: Long,
        status: String,
        filePath: String,
        itemCount: Int,
    ): ImportExportHistorySummary {
        val entity = ImportExportHistoryEntity(UUID.randomUUID().toString(), operationType, vaultId, createdAt, status, filePath, itemCount)
        dao.upsertImportExportHistory(entity)
        return entity.toModel()
    }

    private fun importDir(): File = File(context.filesDir, "vault_imports").apply { mkdirs() }

    private fun importFallbackFile(fileName: String): ReadableTransferDocument? {
        val file = File(importDir(), fileName).takeIf { it.exists() } ?: return null
        return ReadableTransferDocument(
            path = "vault_imports/${file.name}",
            sizeBytes = file.length(),
            modifiedAt = file.lastModified(),
            byteReader = file::readBytes,
        )
    }

    private suspend fun stamped(prefix: String, vaultId: String, extension: String): String {
        val includeTimestamp = appLockRepository.settings.first().includeTimestampInExportFileName
        return if (includeTimestamp) {
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault()).format(java.util.Date())
            "${prefix}-${vaultId}-${stamp}.${extension}"
        } else {
            "${prefix}-${vaultId}.${extension}"
        }
    }

    private suspend fun encryptForVault(vaultId: String, payload: ByteArray): String {
        val keyBytes = vaultPassphraseManager.getOrCreatePassphrase(vaultId)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val key = SecretKeySpec(keyBytes.copyOf(32), "AES")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(payload)
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } finally {
            keyBytes.fill(0)
        }
    }

    private suspend fun decryptForVault(vaultId: String, payload: String): ByteArray {
        val keyBytes = vaultPassphraseManager.getOrCreatePassphrase(vaultId)
        return try {
            val parts = payload.split(':', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val key = SecretKeySpec(keyBytes.copyOf(32), "AES")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            cipher.doFinal(encrypted)
        } finally {
            keyBytes.fill(0)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}

private data class BackupArtifact(
    val fileName: String,
    val createdAt: Long,
    val itemCount: Int,
    val bytes: ByteArray,
)

private fun ContactEntity.toJson(): JSONObject = JSONObject().apply {
    put("contactId", contactId)
    put("displayName", displayName)
    put("sortKey", sortKey)
    put("primaryPhone", primaryPhone)
    put("tagCsv", tagCsv)
    put("isFavorite", isFavorite)
    put("folderName", folderName)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
    put("isDeleted", isDeleted)
    put("deletedAt", deletedAt)
    put("photoUri", photoUri)
    put("isBlocked", isBlocked)
    put("externalLinksJson", externalLinksJson)
}
private fun TagEntity.toJson(): JSONObject = JSONObject().apply { put("tagName", tagName); put("displayName", displayName); put("colorToken", colorToken); put("createdAt", createdAt) }
private fun FolderEntity.toJson(): JSONObject = JSONObject().apply { put("folderName", folderName); put("displayName", displayName); put("iconToken", iconToken); put("colorToken", colorToken); put("createdAt", createdAt); put("imageUri", imageUri); put("description", description); put("sortOrder", sortOrder); put("isPinned", isPinned) }
private fun ContactFolderCrossRef.toJson(): JSONObject = JSONObject().apply { put("contactId", contactId); put("folderName", folderName) }
private fun ContactTagCrossRef.toJson(): JSONObject = JSONObject().apply { put("contactId", contactId); put("tagName", tagName) }
private fun NoteEntity.toJson(): JSONObject = JSONObject().apply { put("noteId", noteId); put("contactId", contactId); put("body", body); put("createdAt", createdAt) }
private fun ReminderEntity.toJson(): JSONObject = JSONObject().apply { put("reminderId", reminderId); put("contactId", contactId); put("title", title); put("dueAt", dueAt); put("isDone", isDone); put("createdAt", createdAt) }
private fun TimelineEntity.toJson(): JSONObject = JSONObject().apply { put("timelineId", timelineId); put("contactId", contactId); put("type", type); put("title", title); put("subtitle", subtitle); put("createdAt", createdAt) }
private fun ContactSummary.toJson(): JSONObject = JSONObject().apply { put("id", id); put("displayName", displayName); put("primaryPhone", primaryPhone); put("tags", JSONArray(tags)); put("isFavorite", isFavorite); put("folderName", folderName); put("folderNames", JSONArray(folderNames)); put("photoUri", photoUri); put("isBlocked", isBlocked); put("socialLinks", JSONArray(socialLinks.map { link -> JSONObject().apply { put("type", link.type); put("value", link.value); put("label", link.label) } })) }

private fun JSONArray.toContactEntities(): List<ContactEntity> = (0 until length()).map { index ->
    val obj = getJSONObject(index)
    ContactEntity(
        obj.getString("contactId"),
        obj.getString("displayName"),
        obj.getString("sortKey"),
        obj.optString("primaryPhone").takeIf { it.isNotBlank() },
        obj.optString("tagCsv"),
        obj.optBoolean("isFavorite", false),
        obj.optString("folderName").takeIf { it.isNotBlank() },
        obj.optLong("createdAt"),
        obj.optLong("updatedAt"),
        obj.optBoolean("isDeleted", false),
        obj.optLong("deletedAt").takeIf { it > 0L },
        obj.optString("photoUri").takeIf { it.isNotBlank() },
        obj.optBoolean("isBlocked", false),
        obj.optString("externalLinksJson", "[]"),
    )
}
private fun JSONArray.toTagEntities(): List<TagEntity> = (0 until length()).map { i -> getJSONObject(i).let { TagEntity(it.getString("tagName"), it.getString("displayName"), it.optString("colorToken", "default"), it.optLong("createdAt")) } }
private fun JSONArray.toFolderEntities(): List<FolderEntity> = (0 until length()).map { i -> getJSONObject(i).let { FolderEntity(it.getString("folderName"), it.getString("displayName"), it.optString("iconToken", "folder"), it.optString("colorToken", "blue"), it.optLong("createdAt"), it.optString("imageUri").takeIf { value -> value.isNotBlank() }, it.optString("description").takeIf { value -> value.isNotBlank() }, it.optInt("sortOrder", 0), it.optBoolean("isPinned", false)) } }
private fun JSONArray.toFolderCrossRefs(): List<ContactFolderCrossRef> = (0 until length()).map { i -> getJSONObject(i).let { ContactFolderCrossRef(it.getString("contactId"), it.getString("folderName")) } }
private fun JSONArray.toCrossRefs(): List<ContactTagCrossRef> = (0 until length()).map { i -> getJSONObject(i).let { ContactTagCrossRef(it.getString("contactId"), it.getString("tagName")) } }
private fun JSONArray.toNoteEntities(): List<NoteEntity> = (0 until length()).map { i -> getJSONObject(i).let { NoteEntity(it.getString("noteId"), it.getString("contactId"), it.getString("body"), it.optLong("createdAt")) } }
private fun JSONArray.toReminderEntities(): List<ReminderEntity> = (0 until length()).map { i -> getJSONObject(i).let { ReminderEntity(it.getString("reminderId"), it.getString("contactId"), it.getString("title"), it.optLong("dueAt"), it.optBoolean("isDone", false), it.optLong("createdAt")) } }
private fun JSONArray.toTimelineEntities(): List<TimelineEntity> = (0 until length()).map { i -> getJSONObject(i).let { TimelineEntity(it.getString("timelineId"), it.getString("contactId"), it.getString("type"), it.getString("title"), it.optString("subtitle").takeIf { s -> s.isNotBlank() }, it.optLong("createdAt")) } }
private fun JSONArray.toContactSummaries(): List<ContactSummary> = (0 until length()).map { i ->
    val obj = getJSONObject(i)
    ContactSummary(
        id = obj.optString("id", UUID.randomUUID().toString()),
        displayName = obj.getString("displayName"),
        primaryPhone = obj.optString("primaryPhone").takeIf { it.isNotBlank() },
        tags = obj.optJSONArray("tags")?.let { arr -> (0 until arr.length()).map { idx -> arr.getString(idx) } }.orEmpty(),
        isFavorite = obj.optBoolean("isFavorite", false),
        folderName = obj.optString("folderName").takeIf { it.isNotBlank() },
        folderNames = obj.optJSONArray("folderNames")?.let { arr -> (0 until arr.length()).map { idx -> arr.getString(idx) } }.orEmpty(),
        deletedAt = null,
        photoUri = obj.optString("photoUri").takeIf { it.isNotBlank() },
        isBlocked = obj.optBoolean("isBlocked", false),
        socialLinks = obj.optJSONArray("socialLinks")?.let { arr -> (0 until arr.length()).mapNotNull { idx -> arr.optJSONObject(idx)?.let { link -> com.opencontacts.core.model.ContactSocialLink(link.optString("type"), link.optString("value"), link.optString("label").takeIf { label -> label.isNotBlank() }) } } }.orEmpty(),
    )
}

private fun escapeHtml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
