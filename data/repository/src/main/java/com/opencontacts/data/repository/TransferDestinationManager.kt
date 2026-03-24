package com.opencontacts.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.opencontacts.core.crypto.AppLockRepository
import com.opencontacts.core.crypto.AppLockSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private const val DEFAULT_BACKUP_FOLDER = "vault_backups"
private const val DEFAULT_EXPORT_FOLDER = "vault_exports"

enum class TransferDestinationKind {
    BACKUP,
    EXPORT,
}

data class FolderSelectionSummary(
    val uriString: String,
    val displayName: String,
)

data class SavedTransferDocument(
    val path: String,
    val sizeBytes: Long,
)

data class ReadableTransferDocument(
    val path: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    private val byteReader: () -> ByteArray,
) {
    fun readBytes(): ByteArray = byteReader()
    fun readText(): String = readBytes().decodeToString()
}

class TransferDestinationException(message: String, cause: Throwable? = null) : IOException(message, cause)

@Singleton
class TransferDestinationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLockRepository: AppLockRepository,
) {
    suspend fun setFolder(kind: TransferDestinationKind, uri: Uri): FolderSelectionSummary {
        val normalized = uri.normalizeScheme()
        try {
            context.contentResolver.takePersistableUriPermission(
                normalized,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Some providers already persist permission or do not expose it back; continue and validate the tree below.
        }

        val tree = requireTree(normalized)
        val displayName = tree.name?.takeIf(String::isNotBlank) ?: inferTreeName(normalized)
        when (kind) {
            TransferDestinationKind.BACKUP -> appLockRepository.setBackupFolder(normalized.toString(), displayName)
            TransferDestinationKind.EXPORT -> appLockRepository.setExportFolder(normalized.toString(), displayName)
        }
        return FolderSelectionSummary(normalized.toString(), displayName)
    }

    suspend fun resetFolder(kind: TransferDestinationKind) {
        val settings = appLockRepository.settings.first()
        val uriString = when (kind) {
            TransferDestinationKind.BACKUP -> settings.backupFolderUri
            TransferDestinationKind.EXPORT -> settings.exportFolderUri
        }
        uriString?.let(::releasePersistedPermission)
        when (kind) {
            TransferDestinationKind.BACKUP -> appLockRepository.setBackupFolder(null, null)
            TransferDestinationKind.EXPORT -> appLockRepository.setExportFolder(null, null)
        }
    }

    suspend fun writeBackupDocument(
        fileName: String,
        mimeType: String,
        writeBlock: (OutputStream) -> Unit,
    ): SavedTransferDocument {
        val settings = appLockRepository.settings.first()
        return writeDocument(
            selectedFolderUri = settings.backupFolderUri,
            fallbackRelativePath = settings.backupPath.ifBlank { DEFAULT_BACKUP_FOLDER },
            fileName = fileName,
            mimeType = mimeType,
            writeBlock = writeBlock,
        )
    }

    suspend fun writeExportDocument(
        fileName: String,
        mimeType: String,
        writeBlock: (OutputStream) -> Unit,
    ): SavedTransferDocument {
        val settings = appLockRepository.settings.first()
        return writeDocument(
            selectedFolderUri = settings.exportFolderUri,
            fallbackRelativePath = settings.exportPath.ifBlank { DEFAULT_EXPORT_FOLDER },
            fileName = fileName,
            mimeType = mimeType,
            writeBlock = writeBlock,
        )
    }

    suspend fun findLatestBackupDocument(prefix: String, extension: String): ReadableTransferDocument? {
        val settings = appLockRepository.settings.first()
        return findLatestDocument(
            selectedFolderUri = settings.backupFolderUri,
            fallbackRelativePath = settings.backupPath.ifBlank { DEFAULT_BACKUP_FOLDER },
            prefix = prefix,
            extension = extension,
        )
    }

    suspend fun findLatestExportDocument(prefix: String, extension: String): ReadableTransferDocument? {
        val settings = appLockRepository.settings.first()
        return findLatestDocument(
            selectedFolderUri = settings.exportFolderUri,
            fallbackRelativePath = settings.exportPath.ifBlank { DEFAULT_EXPORT_FOLDER },
            prefix = prefix,
            extension = extension,
        )
    }

    fun describeBackupLocation(settings: AppLockSettings): String = describeFolder(
        selectedName = settings.backupFolderName,
        selectedUri = settings.backupFolderUri,
        fallbackRelativePath = settings.backupPath,
        fallbackLabel = "App storage",
    )

    fun describeExportLocation(settings: AppLockSettings): String = describeFolder(
        selectedName = settings.exportFolderName,
        selectedUri = settings.exportFolderUri,
        fallbackRelativePath = settings.exportPath,
        fallbackLabel = "App storage",
    )

    private fun writeDocument(
        selectedFolderUri: String?,
        fallbackRelativePath: String,
        fileName: String,
        mimeType: String,
        writeBlock: (OutputStream) -> Unit,
    ): SavedTransferDocument {
        return if (selectedFolderUri.isNullOrBlank()) {
            val directory = File(context.filesDir, fallbackRelativePath.ifBlank { DEFAULT_EXPORT_FOLDER }).apply { mkdirs() }
            val file = File(directory, fileName)
            file.outputStream().use(writeBlock)
            SavedTransferDocument(path = "${directory.name}/$fileName", sizeBytes = file.length())
        } else {
            val treeUri = Uri.parse(selectedFolderUri)
            val tree = requireTree(treeUri)
            val existing = tree.findFile(fileName)
            existing?.delete()
            val document = tree.createFile(mimeType, fileName)
                ?: throw TransferDestinationException("Could not create $fileName in the selected folder.")
            context.contentResolver.openOutputStream(document.uri, "w")?.use(writeBlock)
                ?: throw TransferDestinationException("Could not write $fileName to the selected folder.")
            SavedTransferDocument(
                path = "${tree.name ?: inferTreeName(treeUri)}/$fileName",
                sizeBytes = document.length(),
            )
        }
    }

    private fun findLatestDocument(
        selectedFolderUri: String?,
        fallbackRelativePath: String,
        prefix: String,
        extension: String,
    ): ReadableTransferDocument? {
        return if (selectedFolderUri.isNullOrBlank()) {
            val directory = File(context.filesDir, fallbackRelativePath.ifBlank { DEFAULT_EXPORT_FOLDER }).apply { mkdirs() }
            val latest = directory.listFiles()
                ?.filter { it.name.startsWith(prefix) && it.extension.equals(extension, ignoreCase = true) }
                ?.maxByOrNull(File::lastModified)
                ?: return null
            ReadableTransferDocument(
                path = "${directory.name}/${latest.name}",
                sizeBytes = latest.length(),
                modifiedAt = latest.lastModified(),
                byteReader = latest::readBytes,
            )
        } else {
            val treeUri = Uri.parse(selectedFolderUri)
            val tree = requireTree(treeUri)
            val latest = tree.listFiles()
                .filter { document ->
                    val name = document.name.orEmpty()
                    document.isFile && name.startsWith(prefix) && name.endsWith(".$extension", ignoreCase = true)
                }
                .maxByOrNull { it.lastModified() }
                ?: return null
            ReadableTransferDocument(
                path = "${tree.name ?: inferTreeName(treeUri)}/${latest.name.orEmpty()}",
                sizeBytes = latest.length(),
                modifiedAt = latest.lastModified(),
                byteReader = {
                    context.contentResolver.openInputStream(latest.uri)?.use { it.readBytes() }
                        ?: throw TransferDestinationException("Could not read ${latest.name.orEmpty()} from the selected folder.")
                },
            )
        }
    }

    private fun requireTree(uri: Uri): DocumentFile {
        val tree = DocumentFile.fromTreeUri(context, uri)
            ?: throw TransferDestinationException("The selected folder is no longer available.")
        if (!tree.exists() || !tree.isDirectory) {
            throw TransferDestinationException("The selected folder is no longer available.")
        }
        return tree
    }

    private fun releasePersistedPermission(uriString: String) {
        val uri = Uri.parse(uriString)
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        val flags =
            (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        if (flags == 0) return
        try {
            context.contentResolver.releasePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Ignore stale permission entries.
        }
    }

    private fun inferTreeName(uri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
        return documentId.substringAfterLast(':').ifBlank { "Selected folder" }
    }

    private fun describeFolder(
        selectedName: String?,
        selectedUri: String?,
        fallbackRelativePath: String,
        fallbackLabel: String,
    ): String {
        return if (!selectedUri.isNullOrBlank()) {
            selectedName?.takeIf(String::isNotBlank) ?: "Selected folder"
        } else {
            "$fallbackLabel/${fallbackRelativePath.ifBlank { DEFAULT_EXPORT_FOLDER }}"
        }
    }
}
