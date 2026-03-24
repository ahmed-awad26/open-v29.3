package com.opencontacts.core.crypto

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appLockDataStore by preferencesDataStore(name = "app_lock_settings")

object SettingsDefaults {
    const val THEME_MODE = "SYSTEM"
    const val DEFAULT_EXPORT_PATH = "vault_exports"
    const val DEFAULT_BACKUP_PATH = "vault_backups"
    const val SORT_ORDER = "FIRST_NAME"
    const val LIST_DENSITY = "COMFORTABLE"
    const val DEFAULT_START_TAB = "CONTACTS"
    const val LOCK_SCREEN_VISIBILITY = "HIDE_SENSITIVE"
    const val OVERLAY_POPUP_MODE = "HEADS_UP"
    const val GROUP_TAG_SORT_ORDER = "MOST_USED"
    const val INCOMING_CALL_WINDOW_SIZE = "COMPACT"
    const val LOCK_AFTER_INACTIVITY_SECONDS = 30
    const val TRASH_RETENTION_DAYS = 30
}

@Singleton
class AppLockRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val settings: Flow<AppLockSettings> = context.appLockDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map(::mapSettings)

    suspend fun setPin(pin: CharArray) {
        val salt = ByteArray(SALT_LENGTH).also(SecureRandom()::nextBytes)
        val hash = hashPin(pin, salt)
        context.appLockDataStore.edit { prefs ->
            prefs[PIN_HASH] = encode(hash)
            prefs[PIN_SALT] = encode(salt)
        }
        pin.fill('\u0000')
    }

    suspend fun clearPin() {
        context.appLockDataStore.edit { prefs ->
            prefs.remove(PIN_HASH)
            prefs.remove(PIN_SALT)
        }
    }

    suspend fun verifyPin(pin: CharArray): Boolean {
        val snapshot = context.appLockDataStore.data.first()
        val storedHash = snapshot[PIN_HASH] ?: return false
        val storedSalt = snapshot[PIN_SALT] ?: return false
        val computed = hashPin(pin, decode(storedSalt))
        pin.fill('\u0000')
        return encode(computed) == storedHash
    }

    suspend fun setBiometricEnabled(enabled: Boolean) = edit { it[BIOMETRIC_ENABLED] = enabled }
    suspend fun setAllowDeviceCredential(enabled: Boolean) = edit { it[ALLOW_DEVICE_CREDENTIAL] = enabled }
    suspend fun setLockOnAppResume(enabled: Boolean) = edit { it[LOCK_ON_APP_RESUME] = enabled }
    suspend fun setLockAfterInactivitySeconds(seconds: Int) = edit { it[LOCK_AFTER_INACTIVITY_SECONDS] = seconds.coerceIn(0, 3600) }
    suspend fun setTrashRetentionDays(days: Int) = edit { it[TRASH_RETENTION_DAYS] = days.coerceIn(7, 3650) }
    suspend fun setThemeMode(mode: String) = edit { it[THEME_MODE] = mode.uppercase() }
    suspend fun setExportPath(path: String) = edit { it[EXPORT_PATH] = path.ifBlank { SettingsDefaults.DEFAULT_EXPORT_PATH } }
    suspend fun setBackupPath(path: String) = edit { it[BACKUP_PATH] = path.ifBlank { SettingsDefaults.DEFAULT_BACKUP_PATH } }

    suspend fun setExportFolder(uri: String?, displayName: String?) {
        edit { prefs ->
            if (uri.isNullOrBlank()) prefs.remove(EXPORT_FOLDER_URI) else prefs[EXPORT_FOLDER_URI] = uri
            if (displayName.isNullOrBlank()) prefs.remove(EXPORT_FOLDER_NAME) else prefs[EXPORT_FOLDER_NAME] = displayName
        }
    }

    suspend fun setBackupFolder(uri: String?, displayName: String?) {
        edit { prefs ->
            if (uri.isNullOrBlank()) prefs.remove(BACKUP_FOLDER_URI) else prefs[BACKUP_FOLDER_URI] = uri
            if (displayName.isNullOrBlank()) prefs.remove(BACKUP_FOLDER_NAME) else prefs[BACKUP_FOLDER_NAME] = displayName
        }
    }

    suspend fun setDefaultContactSortOrder(value: String) = edit { it[DEFAULT_CONTACT_SORT_ORDER] = value.uppercase() }
    suspend fun setContactListDensity(value: String) = edit { it[CONTACT_LIST_DENSITY] = value.uppercase() }
    suspend fun setShowContactPhotosInList(enabled: Boolean) = edit { it[SHOW_CONTACT_PHOTOS_IN_LIST] = enabled }
    suspend fun setDefaultStartTab(value: String) = edit { it[DEFAULT_START_TAB] = value.uppercase() }
    suspend fun setConfirmBeforeDelete(enabled: Boolean) = edit { it[CONFIRM_BEFORE_DELETE] = enabled }
    suspend fun setConfirmBeforeBlockUnblock(enabled: Boolean) = edit { it[CONFIRM_BEFORE_BLOCK_UNBLOCK] = enabled }
    suspend fun setShowRecentCallsPreview(enabled: Boolean) = edit { it[SHOW_RECENT_CALLS_PREVIEW] = enabled }
    suspend fun setAutoCollapseCallGroups(enabled: Boolean) = edit { it[AUTO_COLLAPSE_CALL_GROUPS] = enabled }
    suspend fun setShowBlockedContactsInSearch(enabled: Boolean) = edit { it[SHOW_BLOCKED_CONTACTS_IN_SEARCH] = enabled }
    suspend fun setIncludeTimestampInExportFileName(enabled: Boolean) = edit { it[INCLUDE_TIMESTAMP_IN_EXPORT_FILENAME] = enabled }
    suspend fun setHideEmptyFoldersAndTags(enabled: Boolean) = edit { it[HIDE_EMPTY_FOLDERS_AND_TAGS] = enabled }
    suspend fun setOpenContactDirectlyOnTap(enabled: Boolean) = edit { it[OPEN_CONTACT_DIRECTLY_ON_TAP] = enabled }
    suspend fun setShowFavoritesFirst(enabled: Boolean) = edit { it[SHOW_FAVORITES_FIRST] = enabled }
    suspend fun setEnableIncomingCallerPopup(enabled: Boolean) = edit { it[ENABLE_INCOMING_CALLER_POPUP] = enabled }
    suspend fun setEnableMissedCallNotification(enabled: Boolean) = edit { it[ENABLE_MISSED_CALL_NOTIFICATION] = enabled }
    suspend fun setShowPhotoInNotifications(enabled: Boolean) = edit { it[SHOW_PHOTO_IN_NOTIFICATIONS] = enabled }
    suspend fun setShowFolderTagsInNotifications(enabled: Boolean) = edit { it[SHOW_FOLDER_TAGS_IN_NOTIFICATIONS] = enabled }
    suspend fun setLockScreenNotificationVisibility(value: String) = edit { it[LOCK_SCREEN_NOTIFICATION_VISIBILITY] = value.uppercase() }
    suspend fun setHeadsUpNotifications(enabled: Boolean) = edit { it[HEADS_UP_NOTIFICATIONS] = enabled }
    suspend fun setOverlayPopupMode(value: String) = edit { it[OVERLAY_POPUP_MODE] = value.uppercase() }
    suspend fun setVibrationEnabled(enabled: Boolean) = edit { it[VIBRATION_ENABLED] = enabled }
    suspend fun setSoundEnabled(enabled: Boolean) = edit { it[SOUND_ENABLED] = enabled }
    suspend fun setHapticFeedbackEnabled(enabled: Boolean) = edit { it[HAPTIC_FEEDBACK_ENABLED] = enabled }
    suspend fun setDialPadShowLetters(enabled: Boolean) = edit { it[DIAL_PAD_SHOW_LETTERS] = enabled }
    suspend fun setDialPadAutoFormat(enabled: Boolean) = edit { it[DIAL_PAD_AUTO_FORMAT] = enabled }
    suspend fun setDialPadShowT9Suggestions(enabled: Boolean) = edit { it[DIAL_PAD_SHOW_T9_SUGGESTIONS] = enabled }
    suspend fun setDialPadLongPressBackspaceClears(enabled: Boolean) = edit { it[DIAL_PAD_LONG_PRESS_BACKSPACE_CLEARS] = enabled }
    suspend fun setGroupTagSortOrder(value: String) = edit { it[GROUP_TAG_SORT_ORDER] = value.uppercase() }
    suspend fun setIncomingCallCompactMode(enabled: Boolean) = edit { it[INCOMING_CALL_COMPACT_MODE] = enabled }
    suspend fun setIncomingCallShowNumber(enabled: Boolean) = edit { it[INCOMING_CALL_SHOW_NUMBER] = enabled }
    suspend fun setIncomingCallShowTag(enabled: Boolean) = edit { it[INCOMING_CALL_SHOW_TAG] = enabled }
    suspend fun setIncomingCallShowGroup(enabled: Boolean) = edit { it[INCOMING_CALL_SHOW_GROUP] = enabled }
    suspend fun setIncomingCallWindowTransparency(value: Int) = edit { it[INCOMING_CALL_WINDOW_TRANSPARENCY] = value.coerceIn(55, 100) }
    suspend fun setIncomingCallWindowSize(value: String) = edit { it[INCOMING_CALL_WINDOW_SIZE] = value.uppercase() }

    private suspend fun edit(block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.appLockDataStore.edit { prefs -> block(prefs) }
    }

    private fun mapSettings(prefs: Preferences): AppLockSettings {
        return AppLockSettings(
            hasPin = prefs[PIN_HASH] != null,
            biometricEnabled = prefs[BIOMETRIC_ENABLED] ?: false,
            allowDeviceCredential = prefs[ALLOW_DEVICE_CREDENTIAL] ?: true,
            lockOnAppResume = prefs[LOCK_ON_APP_RESUME] ?: true,
            lockAfterInactivitySeconds = prefs[LOCK_AFTER_INACTIVITY_SECONDS] ?: SettingsDefaults.LOCK_AFTER_INACTIVITY_SECONDS,
            trashRetentionDays = prefs[TRASH_RETENTION_DAYS] ?: SettingsDefaults.TRASH_RETENTION_DAYS,
            themeMode = prefs[THEME_MODE] ?: SettingsDefaults.THEME_MODE,
            exportPath = prefs[EXPORT_PATH] ?: SettingsDefaults.DEFAULT_EXPORT_PATH,
            backupPath = prefs[BACKUP_PATH] ?: SettingsDefaults.DEFAULT_BACKUP_PATH,
            exportFolderUri = prefs[EXPORT_FOLDER_URI],
            exportFolderName = prefs[EXPORT_FOLDER_NAME],
            backupFolderUri = prefs[BACKUP_FOLDER_URI],
            backupFolderName = prefs[BACKUP_FOLDER_NAME],
            defaultContactSortOrder = prefs[DEFAULT_CONTACT_SORT_ORDER] ?: SettingsDefaults.SORT_ORDER,
            contactListDensity = prefs[CONTACT_LIST_DENSITY] ?: SettingsDefaults.LIST_DENSITY,
            showContactPhotosInList = prefs[SHOW_CONTACT_PHOTOS_IN_LIST] ?: true,
            defaultStartTab = prefs[DEFAULT_START_TAB] ?: SettingsDefaults.DEFAULT_START_TAB,
            confirmBeforeDelete = prefs[CONFIRM_BEFORE_DELETE] ?: true,
            confirmBeforeBlockUnblock = prefs[CONFIRM_BEFORE_BLOCK_UNBLOCK] ?: true,
            showRecentCallsPreview = prefs[SHOW_RECENT_CALLS_PREVIEW] ?: true,
            autoCollapseCallGroups = prefs[AUTO_COLLAPSE_CALL_GROUPS] ?: false,
            showBlockedContactsInSearch = prefs[SHOW_BLOCKED_CONTACTS_IN_SEARCH] ?: true,
            includeTimestampInExportFileName = prefs[INCLUDE_TIMESTAMP_IN_EXPORT_FILENAME] ?: true,
            hideEmptyFoldersAndTags = prefs[HIDE_EMPTY_FOLDERS_AND_TAGS] ?: false,
            openContactDirectlyOnTap = prefs[OPEN_CONTACT_DIRECTLY_ON_TAP] ?: true,
            showFavoritesFirst = prefs[SHOW_FAVORITES_FIRST] ?: false,
            enableIncomingCallerPopup = prefs[ENABLE_INCOMING_CALLER_POPUP] ?: true,
            enableMissedCallNotification = prefs[ENABLE_MISSED_CALL_NOTIFICATION] ?: true,
            showPhotoInNotifications = prefs[SHOW_PHOTO_IN_NOTIFICATIONS] ?: true,
            showFolderTagsInNotifications = prefs[SHOW_FOLDER_TAGS_IN_NOTIFICATIONS] ?: true,
            lockScreenNotificationVisibility = prefs[LOCK_SCREEN_NOTIFICATION_VISIBILITY] ?: SettingsDefaults.LOCK_SCREEN_VISIBILITY,
            headsUpNotifications = prefs[HEADS_UP_NOTIFICATIONS] ?: true,
            overlayPopupMode = prefs[OVERLAY_POPUP_MODE] ?: SettingsDefaults.OVERLAY_POPUP_MODE,
            vibrationEnabled = prefs[VIBRATION_ENABLED] ?: true,
            soundEnabled = prefs[SOUND_ENABLED] ?: true,
            hapticFeedbackEnabled = prefs[HAPTIC_FEEDBACK_ENABLED] ?: true,
            dialPadShowLetters = prefs[DIAL_PAD_SHOW_LETTERS] ?: true,
            dialPadAutoFormat = prefs[DIAL_PAD_AUTO_FORMAT] ?: true,
            dialPadShowT9Suggestions = prefs[DIAL_PAD_SHOW_T9_SUGGESTIONS] ?: true,
            dialPadLongPressBackspaceClears = prefs[DIAL_PAD_LONG_PRESS_BACKSPACE_CLEARS] ?: true,
            groupTagSortOrder = prefs[GROUP_TAG_SORT_ORDER] ?: SettingsDefaults.GROUP_TAG_SORT_ORDER,
            incomingCallCompactMode = prefs[INCOMING_CALL_COMPACT_MODE] ?: true,
            incomingCallShowNumber = prefs[INCOMING_CALL_SHOW_NUMBER] ?: true,
            incomingCallShowTag = prefs[INCOMING_CALL_SHOW_TAG] ?: true,
            incomingCallShowGroup = prefs[INCOMING_CALL_SHOW_GROUP] ?: true,
            incomingCallWindowTransparency = prefs[INCOMING_CALL_WINDOW_TRANSPARENCY] ?: 88,
            incomingCallWindowSize = prefs[INCOMING_CALL_WINDOW_SIZE] ?: SettingsDefaults.INCOMING_CALL_WINDOW_SIZE,
        )
    }

    private fun hashPin(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, HASH_ITERATIONS, DERIVED_KEY_LENGTH_BITS)
        return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val ALLOW_DEVICE_CREDENTIAL = booleanPreferencesKey("allow_device_credential")
        val LOCK_ON_APP_RESUME = booleanPreferencesKey("lock_on_app_resume")
        val LOCK_AFTER_INACTIVITY_SECONDS = intPreferencesKey("lock_after_inactivity_seconds")
        val TRASH_RETENTION_DAYS = intPreferencesKey("trash_retention_days")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val EXPORT_PATH = stringPreferencesKey("export_path")
        val BACKUP_PATH = stringPreferencesKey("backup_path")
        val EXPORT_FOLDER_URI = stringPreferencesKey("export_folder_uri")
        val EXPORT_FOLDER_NAME = stringPreferencesKey("export_folder_name")
        val BACKUP_FOLDER_URI = stringPreferencesKey("backup_folder_uri")
        val BACKUP_FOLDER_NAME = stringPreferencesKey("backup_folder_name")
        val DEFAULT_CONTACT_SORT_ORDER = stringPreferencesKey("default_contact_sort_order")
        val CONTACT_LIST_DENSITY = stringPreferencesKey("contact_list_density")
        val SHOW_CONTACT_PHOTOS_IN_LIST = booleanPreferencesKey("show_contact_photos_in_list")
        val DEFAULT_START_TAB = stringPreferencesKey("default_start_tab")
        val CONFIRM_BEFORE_DELETE = booleanPreferencesKey("confirm_before_delete")
        val CONFIRM_BEFORE_BLOCK_UNBLOCK = booleanPreferencesKey("confirm_before_block_unblock")
        val SHOW_RECENT_CALLS_PREVIEW = booleanPreferencesKey("show_recent_calls_preview")
        val AUTO_COLLAPSE_CALL_GROUPS = booleanPreferencesKey("auto_collapse_call_groups")
        val SHOW_BLOCKED_CONTACTS_IN_SEARCH = booleanPreferencesKey("show_blocked_contacts_in_search")
        val INCLUDE_TIMESTAMP_IN_EXPORT_FILENAME = booleanPreferencesKey("include_timestamp_in_export_filename")
        val HIDE_EMPTY_FOLDERS_AND_TAGS = booleanPreferencesKey("hide_empty_folders_and_tags")
        val OPEN_CONTACT_DIRECTLY_ON_TAP = booleanPreferencesKey("open_contact_directly_on_tap")
        val SHOW_FAVORITES_FIRST = booleanPreferencesKey("show_favorites_first")
        val ENABLE_INCOMING_CALLER_POPUP = booleanPreferencesKey("enable_incoming_caller_popup")
        val ENABLE_MISSED_CALL_NOTIFICATION = booleanPreferencesKey("enable_missed_call_notification")
        val SHOW_PHOTO_IN_NOTIFICATIONS = booleanPreferencesKey("show_photo_in_notifications")
        val SHOW_FOLDER_TAGS_IN_NOTIFICATIONS = booleanPreferencesKey("show_folder_tags_in_notifications")
        val LOCK_SCREEN_NOTIFICATION_VISIBILITY = stringPreferencesKey("lock_screen_notification_visibility")
        val HEADS_UP_NOTIFICATIONS = booleanPreferencesKey("heads_up_notifications")
        val OVERLAY_POPUP_MODE = stringPreferencesKey("overlay_popup_mode")
        val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val HAPTIC_FEEDBACK_ENABLED = booleanPreferencesKey("haptic_feedback_enabled")
        val DIAL_PAD_SHOW_LETTERS = booleanPreferencesKey("dial_pad_show_letters")
        val DIAL_PAD_AUTO_FORMAT = booleanPreferencesKey("dial_pad_auto_format")
        val DIAL_PAD_SHOW_T9_SUGGESTIONS = booleanPreferencesKey("dial_pad_show_t9_suggestions")
        val DIAL_PAD_LONG_PRESS_BACKSPACE_CLEARS = booleanPreferencesKey("dial_pad_long_press_backspace_clears")
        val GROUP_TAG_SORT_ORDER = stringPreferencesKey("group_tag_sort_order")
        val INCOMING_CALL_COMPACT_MODE = booleanPreferencesKey("incoming_call_compact_mode")
        val INCOMING_CALL_SHOW_NUMBER = booleanPreferencesKey("incoming_call_show_number")
        val INCOMING_CALL_SHOW_TAG = booleanPreferencesKey("incoming_call_show_tag")
        val INCOMING_CALL_SHOW_GROUP = booleanPreferencesKey("incoming_call_show_group")
        val INCOMING_CALL_WINDOW_TRANSPARENCY = intPreferencesKey("incoming_call_window_transparency")
        val INCOMING_CALL_WINDOW_SIZE = stringPreferencesKey("incoming_call_window_size")

        const val SALT_LENGTH = 16
        const val HASH_ITERATIONS = 120_000
        const val DERIVED_KEY_LENGTH_BITS = 256
        const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    }
}

data class AppLockSettings(
    val hasPin: Boolean,
    val biometricEnabled: Boolean,
    val allowDeviceCredential: Boolean,
    val lockOnAppResume: Boolean,
    val lockAfterInactivitySeconds: Int,
    val trashRetentionDays: Int,
    val themeMode: String,
    val exportPath: String,
    val backupPath: String,
    val exportFolderUri: String? = null,
    val exportFolderName: String? = null,
    val backupFolderUri: String? = null,
    val backupFolderName: String? = null,
    val defaultContactSortOrder: String,
    val contactListDensity: String,
    val showContactPhotosInList: Boolean,
    val defaultStartTab: String,
    val confirmBeforeDelete: Boolean,
    val confirmBeforeBlockUnblock: Boolean,
    val showRecentCallsPreview: Boolean,
    val autoCollapseCallGroups: Boolean,
    val showBlockedContactsInSearch: Boolean,
    val includeTimestampInExportFileName: Boolean,
    val hideEmptyFoldersAndTags: Boolean,
    val openContactDirectlyOnTap: Boolean,
    val showFavoritesFirst: Boolean,
    val enableIncomingCallerPopup: Boolean,
    val enableMissedCallNotification: Boolean,
    val showPhotoInNotifications: Boolean,
    val showFolderTagsInNotifications: Boolean,
    val lockScreenNotificationVisibility: String,
    val headsUpNotifications: Boolean,
    val overlayPopupMode: String,
    val vibrationEnabled: Boolean,
    val soundEnabled: Boolean,
    val hapticFeedbackEnabled: Boolean,
    val dialPadShowLetters: Boolean,
    val dialPadAutoFormat: Boolean,
    val dialPadShowT9Suggestions: Boolean,
    val dialPadLongPressBackspaceClears: Boolean,
    val groupTagSortOrder: String,
    val incomingCallCompactMode: Boolean,
    val incomingCallShowNumber: Boolean,
    val incomingCallShowTag: Boolean,
    val incomingCallShowGroup: Boolean,
    val incomingCallWindowTransparency: Int,
    val incomingCallWindowSize: String,
) {
    companion object {
        val DEFAULT = AppLockSettings(
            hasPin = false,
            biometricEnabled = false,
            allowDeviceCredential = true,
            lockOnAppResume = true,
            lockAfterInactivitySeconds = SettingsDefaults.LOCK_AFTER_INACTIVITY_SECONDS,
            trashRetentionDays = SettingsDefaults.TRASH_RETENTION_DAYS,
            themeMode = SettingsDefaults.THEME_MODE,
            exportPath = SettingsDefaults.DEFAULT_EXPORT_PATH,
            backupPath = SettingsDefaults.DEFAULT_BACKUP_PATH,
            defaultContactSortOrder = SettingsDefaults.SORT_ORDER,
            contactListDensity = SettingsDefaults.LIST_DENSITY,
            showContactPhotosInList = true,
            defaultStartTab = SettingsDefaults.DEFAULT_START_TAB,
            confirmBeforeDelete = true,
            confirmBeforeBlockUnblock = true,
            showRecentCallsPreview = true,
            autoCollapseCallGroups = false,
            showBlockedContactsInSearch = true,
            includeTimestampInExportFileName = true,
            hideEmptyFoldersAndTags = false,
            openContactDirectlyOnTap = true,
            showFavoritesFirst = false,
            enableIncomingCallerPopup = true,
            enableMissedCallNotification = true,
            showPhotoInNotifications = true,
            showFolderTagsInNotifications = true,
            lockScreenNotificationVisibility = SettingsDefaults.LOCK_SCREEN_VISIBILITY,
            headsUpNotifications = true,
            overlayPopupMode = SettingsDefaults.OVERLAY_POPUP_MODE,
            vibrationEnabled = true,
            soundEnabled = true,
            hapticFeedbackEnabled = true,
            dialPadShowLetters = true,
            dialPadAutoFormat = true,
            dialPadShowT9Suggestions = true,
            dialPadLongPressBackspaceClears = true,
            groupTagSortOrder = SettingsDefaults.GROUP_TAG_SORT_ORDER,
            incomingCallCompactMode = true,
            incomingCallShowNumber = true,
            incomingCallShowTag = true,
            incomingCallShowGroup = true,
            incomingCallWindowTransparency = 88,
            incomingCallWindowSize = SettingsDefaults.INCOMING_CALL_WINDOW_SIZE,
        )
    }
}
