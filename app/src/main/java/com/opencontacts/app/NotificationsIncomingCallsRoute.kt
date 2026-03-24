package com.opencontacts.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun NotificationsIncomingCallsRoute(
    onBack: () -> Unit,
    appViewModel: AppViewModel,
) {
    val settings by appViewModel.appLockSettings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var transparencySlider by remember(settings.incomingCallWindowTransparency) { mutableFloatStateOf(settings.incomingCallWindowTransparency.toFloat()) }

    SettingsScaffold(title = "Notifications & Incoming Calls", onBack = onBack) { modifier ->
        LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SettingsSection(title = "Incoming call window", subtitle = "Default behavior is a centered floating card while the app is visible, with a safer heads-up fallback in the background.") {
                    SettingsSwitchRow(
                        title = "Enable incoming caller popup",
                        subtitle = "Shows caller name, number, group, tags, and quick actions when possible.",
                        checked = settings.enableIncomingCallerPopup,
                        onCheckedChange = appViewModel::setEnableIncomingCallerPopup,
                    )
                    SettingsSpacer()
                    SettingsChoiceRow(
                        title = "Popup delivery mode",
                        subtitle = "Avoid full-screen unless Android policy forces it. Heads-up is the recommended background fallback.",
                        selected = settings.overlayPopupMode,
                        choices = listOf("IN_APP_ONLY", "HEADS_UP", "OVERLAY_WINDOW"),
                        onSelect = appViewModel::setOverlayPopupMode,
                    )
                    SettingsSpacer()
                    SettingsChoiceRow(
                        title = "Window size",
                        subtitle = "Compact keeps the floating card small and focused.",
                        selected = settings.incomingCallWindowSize,
                        choices = listOf("COMPACT", "EXPANDED"),
                        onSelect = appViewModel::setIncomingCallWindowSize,
                    )
                    SettingsSpacer()
                    SettingsSwitchRow(
                        title = "Compact content layout",
                        subtitle = "Prefer a centered concise card instead of a dense information block.",
                        checked = settings.incomingCallCompactMode,
                        onCheckedChange = appViewModel::setIncomingCallCompactMode,
                    )
                    SettingsSpacer()
                    Text("Transparency", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = transparencySlider,
                        onValueChange = { transparencySlider = it },
                        valueRange = 55f..100f,
                        onValueChangeFinished = { appViewModel.setIncomingCallWindowTransparency(transparencySlider.toInt()) },
                    )
                    Text(
                        "Current opacity: ${transparencySlider.toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SettingsSection(title = "Visible content") {
                    SettingsSwitchRow(
                        title = "Show number",
                        subtitle = "Display the phone number inside the floating incoming-call card.",
                        checked = settings.incomingCallShowNumber,
                        onCheckedChange = appViewModel::setIncomingCallShowNumber,
                    )
                    SettingsSpacer()
                    SettingsSwitchRow(
                        title = "Show group/folder",
                        subtitle = "Include the current folder classification when available.",
                        checked = settings.incomingCallShowGroup,
                        onCheckedChange = appViewModel::setIncomingCallShowGroup,
                    )
                    SettingsSpacer()
                    SettingsSwitchRow(
                        title = "Show tags",
                        subtitle = "Display a few tags without overcrowding the call surface.",
                        checked = settings.incomingCallShowTag,
                        onCheckedChange = appViewModel::setIncomingCallShowTag,
                    )
                    SettingsSpacer()
                    SettingsSwitchRow(
                        title = "Show contact photo",
                        subtitle = "Add the contact photo to notifications and the in-app incoming surface when available.",
                        checked = settings.showPhotoInNotifications,
                        onCheckedChange = appViewModel::setShowPhotoInNotifications,
                    )
                }
            }
            item {
                SettingsSection(title = "Missed calls and privacy") {
                    SettingsSwitchRow(
                        title = "Enable missed call notification",
                        subtitle = "Post a compact missed-call notification with quick actions.",
                        checked = settings.enableMissedCallNotification,
                        onCheckedChange = appViewModel::setEnableMissedCallNotification,
                    )
                    SettingsSpacer()
                    SettingsSwitchRow(
                        title = "Show folder and tags in notifications",
                        subtitle = "Keep contextual classification visible when privacy settings allow it.",
                        checked = settings.showFolderTagsInNotifications,
                        onCheckedChange = appViewModel::setShowFolderTagsInNotifications,
                    )
                    SettingsSpacer()
                    SettingsChoiceRow(
                        title = "Lock-screen visibility",
                        subtitle = "Choose whether secure lock screens show full caller details or hide sensitive information.",
                        selected = settings.lockScreenNotificationVisibility,
                        choices = listOf("SHOW_FULL", "HIDE_SENSITIVE"),
                        onSelect = appViewModel::setLockScreenNotificationVisibility,
                    )
                }
            }
            item {
                SettingsSection(title = "Sound and urgency") {
                    SettingsSwitchRow(
                        title = "Heads-up notifications",
                        subtitle = "Keep urgent incoming call notifications visible above normal notifications.",
                        checked = settings.headsUpNotifications,
                        onCheckedChange = appViewModel::setHeadsUpNotifications,
                    )
                    SettingsSpacer()
                    SettingsSwitchRow(
                        title = "Sound",
                        subtitle = "Play app notification sounds when the system allows it.",
                        checked = settings.soundEnabled,
                        onCheckedChange = appViewModel::setSoundEnabled,
                    )
                    SettingsSpacer()
                    SettingsSwitchRow(
                        title = "Vibration",
                        subtitle = "Use vibration for missed-call and incoming-call attention.",
                        checked = settings.vibrationEnabled,
                        onCheckedChange = appViewModel::setVibrationEnabled,
                    )
                }
            }
            item {
                SettingsSection(title = "Permissions and system controls") {
                    Text(
                        "Android may block true floating windows or force system UI in some versions. This screen keeps the app on the least intrusive path by default and only asks for overlay permission when you explicitly use overlay mode.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingsSpacer()
                    androidx.compose.material3.TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        })
                    }) { Text("Open notification settings") }
                    androidx.compose.material3.TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        }
                    }) { Text("Open display over apps") }
                }
            }
        }
    }
}
