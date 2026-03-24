package com.opencontacts.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opencontacts.core.crypto.AppLockRepository
import com.opencontacts.core.crypto.AppLockSettings
import com.opencontacts.core.model.ContactSummary
import com.opencontacts.core.vault.VaultSessionManager
import com.opencontacts.domain.contacts.ContactRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val CALLS_CHANNEL_ID = "incoming_calls"
private const val MISSED_CHANNEL_ID = "missed_calls"
private const val INCOMING_NOTIFICATION_ID = 33001
private const val MISSED_NOTIFICATION_ID = 33002
private const val ACTION_ANSWER_CALL = "com.opencontacts.app.action.ANSWER_CALL"
private const val ACTION_DECLINE_CALL = "com.opencontacts.app.action.DECLINE_CALL"
private const val ACTION_DISMISS_CALL = "com.opencontacts.app.action.DISMISS_CALL"
private const val ACTION_CALL_BACK = "com.opencontacts.app.action.CALL_BACK"
private const val ACTION_OPEN_CONTACT = "com.opencontacts.app.action.OPEN_CONTACT"

private val incomingCallScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

data class IncomingCallUiState(
    val displayName: String,
    val number: String,
    val photoUri: String? = null,
    val folderName: String? = null,
    val tags: List<String> = emptyList(),
    val contactId: String? = null,
)

object IncomingCallOverlayController {
    val state = MutableStateFlow<IncomingCallUiState?>(null)

    fun show(call: IncomingCallUiState) {
        state.value = call
    }

    fun clear() {
        state.value = null
    }
}

private object IncomingCallTracker {
    var lastState: String? = null
    var lastRinging: IncomingCallUiState? = null
    var answered: Boolean = false
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface IncomingCallEntryPoint {
    fun contactRepository(): ContactRepository
    fun appLockRepository(): AppLockRepository
    fun vaultSessionManager(): VaultSessionManager
}

class IncomingCallBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val pendingResult = goAsync()
        incomingCallScope.launch {
            runCatching {
                handlePhoneStateChanged(context, intent)
            }
            pendingResult.finish()
        }
    }
}

class IncomingCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER_CALL -> answerIncomingCall(context)
            ACTION_DECLINE_CALL -> declineIncomingCall(context)
            ACTION_DISMISS_CALL -> dismissIncomingUi(context)
            ACTION_CALL_BACK -> {
                val number = intent.getStringExtra("number").orEmpty()
                if (number.isNotBlank()) {
                    context.startActivity(
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                dismissIncomingUi(context)
            }
            ACTION_OPEN_CONTACT -> {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                dismissIncomingUi(context)
            }
        }
    }
}

private suspend fun handlePhoneStateChanged(context: Context, intent: Intent) {
    val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE).orEmpty()
    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
    val matched = lookupCurrentContact(context, number)
    val settings = entryPoint(context).appLockRepository().settings.first()
    val uiState = matched.toIncomingCallUiState(number)

    when (state) {
        TelephonyManager.EXTRA_STATE_RINGING -> {
            IncomingCallTracker.lastState = state
            IncomingCallTracker.lastRinging = uiState
            IncomingCallTracker.answered = false
            if (settings.enableIncomingCallerPopup) {
                IncomingCallOverlayController.show(uiState)
            }
            postIncomingCallNotification(context, uiState, settings)
        }
        TelephonyManager.EXTRA_STATE_OFFHOOK -> {
            IncomingCallTracker.lastState = state
            IncomingCallTracker.answered = true
            dismissIncomingUi(context)
        }
        TelephonyManager.EXTRA_STATE_IDLE -> {
            val wasRinging = IncomingCallTracker.lastState == TelephonyManager.EXTRA_STATE_RINGING || IncomingCallTracker.lastRinging != null
            val ringingState = IncomingCallTracker.lastRinging
            dismissIncomingUi(context)
            if (wasRinging && !IncomingCallTracker.answered && settings.enableMissedCallNotification && ringingState != null) {
                postMissedCallNotification(context, ringingState, settings)
            }
            IncomingCallTracker.lastState = state
            IncomingCallTracker.lastRinging = null
            IncomingCallTracker.answered = false
        }
    }
}

private suspend fun lookupCurrentContact(context: Context, incomingNumber: String): ContactSummary? {
    val entryPoint = entryPoint(context)
    val vaultId = entryPoint.vaultSessionManager().activeVaultId.value ?: return null
    val contacts = entryPoint.contactRepository().observeContacts(vaultId).first()
    val normalized = normalizeIncomingNumber(incomingNumber)
    return contacts.firstOrNull { normalizeIncomingNumber(it.primaryPhone) == normalized }
}

private fun ContactSummary?.toIncomingCallUiState(rawNumber: String): IncomingCallUiState {
    return IncomingCallUiState(
        displayName = this?.displayName ?: if (rawNumber.isBlank()) "Unknown caller" else rawNumber,
        number = rawNumber.ifBlank { this?.primaryPhone.orEmpty() },
        photoUri = this?.photoUri,
        folderName = this?.folderName,
        tags = this?.tags.orEmpty(),
        contactId = this?.id,
    )
}

private fun postIncomingCallNotification(context: Context, call: IncomingCallUiState, settings: AppLockSettings) {
    ensureChannels(context, settings)
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return

    val contentIntent = PendingIntent.getActivity(
        context,
        6001,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
    )

    val builder = NotificationCompat.Builder(context, CALLS_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.sym_call_incoming)
        .setContentTitle(call.displayName)
        .setContentText(call.number.ifBlank { "Incoming call" })
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setPriority(if (settings.headsUpNotifications) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
        .setOngoing(true)
        .setAutoCancel(false)
        .setVisibility(lockScreenVisibility(settings))
        .setContentIntent(contentIntent)
        .addAction(0, "Answer", broadcastPendingIntent(context, ACTION_ANSWER_CALL))
        .addAction(0, "Decline", broadcastPendingIntent(context, ACTION_DECLINE_CALL))
    loadNotificationBitmap(context, settings, call.photoUri)?.let(builder::setLargeIcon)
    if (settings.showFolderTagsInNotifications) {
        val extras = buildList {
            call.folderName?.takeIf(String::isNotBlank)?.let { add(it) }
            addAll(call.tags.take(2))
        }
        if (extras.isNotEmpty()) builder.setSubText(extras.joinToString(" • "))
    }
    manager.notify(INCOMING_NOTIFICATION_ID, builder.build())
}

private fun postMissedCallNotification(context: Context, call: IncomingCallUiState, settings: AppLockSettings) {
    ensureChannels(context, settings)
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return

    val builder = NotificationCompat.Builder(context, MISSED_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.sym_call_missed)
        .setContentTitle("Missed call")
        .setContentText(
            buildString {
                append(call.displayName)
                if (call.number.isNotBlank()) append(" • ${call.number}")
            },
        )
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(lockScreenVisibility(settings))
        .setAutoCancel(true)
        .addAction(0, "Call back", broadcastPendingIntent(context, ACTION_CALL_BACK, call.number))
        .addAction(0, "Open contact", broadcastPendingIntent(context, ACTION_OPEN_CONTACT, call.contactId.orEmpty()))
        .addAction(0, "Dismiss", broadcastPendingIntent(context, ACTION_DISMISS_CALL))

    loadNotificationBitmap(context, settings, call.photoUri)?.let(builder::setLargeIcon)
    if (settings.showFolderTagsInNotifications) {
        val extras = buildList {
            call.folderName?.takeIf(String::isNotBlank)?.let { add(it) }
            addAll(call.tags.take(2))
        }
        if (extras.isNotEmpty()) builder.setSubText(extras.joinToString(" • "))
    }
    manager.notify(MISSED_NOTIFICATION_ID, builder.build())
}

private fun dismissIncomingUi(context: Context) {
    IncomingCallOverlayController.clear()
    NotificationManagerCompat.from(context).cancel(INCOMING_NOTIFICATION_ID)
}

private fun answerIncomingCall(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.acceptRingingCall()
        }
    } catch (_: Throwable) {
    } finally {
        dismissIncomingUi(context)
    }
}

private fun declineIncomingCall(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.endCall()
        }
    } catch (_: Throwable) {
    } finally {
        dismissIncomingUi(context)
    }
}

private fun broadcastPendingIntent(context: Context, action: String, payload: String? = null): PendingIntent {
    val intent = Intent(context, IncomingCallActionReceiver::class.java).setAction(action)
    payload?.let {
        if (action == ACTION_CALL_BACK) intent.putExtra("number", it)
        if (action == ACTION_OPEN_CONTACT) intent.putExtra("contactId", it)
    }
    return PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
    )
}

private fun immutableFlag(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

private fun ensureChannels(context: Context, settings: AppLockSettings) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val callsChannel = NotificationChannel(CALLS_CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Incoming caller alerts and heads-up notifications"
        lockscreenVisibility = lockScreenVisibility(settings)
        enableVibration(settings.vibrationEnabled)
        if (!settings.soundEnabled) setSound(null, null)
    }
    val missedChannel = NotificationChannel(MISSED_CHANNEL_ID, "Missed calls", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "Missed call notifications"
        lockscreenVisibility = lockScreenVisibility(settings)
        enableVibration(settings.vibrationEnabled)
    }
    manager.createNotificationChannel(callsChannel)
    manager.createNotificationChannel(missedChannel)
}

private fun lockScreenVisibility(settings: AppLockSettings): Int {
    return if (settings.lockScreenNotificationVisibility.equals("SHOW_FULL", ignoreCase = true)) {
        NotificationCompat.VISIBILITY_PUBLIC
    } else {
        NotificationCompat.VISIBILITY_PRIVATE
    }
}

private fun loadNotificationBitmap(context: Context, settings: AppLockSettings, photoUri: String?): Bitmap? {
    if (!settings.showPhotoInNotifications || photoUri.isNullOrBlank()) return null
    return runCatching {
        context.contentResolver.openInputStream(photoUri.toUri())?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}

private fun normalizeIncomingNumber(number: String?): String {
    return number.orEmpty().filter(Char::isDigit).takeLast(10)
}

private fun entryPoint(context: Context): IncomingCallEntryPoint {
    return EntryPointAccessors.fromApplication(context.applicationContext, IncomingCallEntryPoint::class.java)
}

@Composable
fun IncomingCallInAppHost() {
    val call by IncomingCallOverlayController.state.collectAsStateWithLifecycle()
    if (call != null) {
        IncomingCallDialog(call = call!!)
    }
}

@Composable
private fun IncomingCallDialog(call: IncomingCallUiState) {
    val context = LocalContext.current
    val settings by entryPoint(context).appLockRepository().settings.collectAsStateWithLifecycle(initialValue = AppLockSettings.DEFAULT)
    val avatarBitmap = remember(call.photoUri) {
        runCatching {
            call.photoUri?.let { uri ->
                context.contentResolver.openInputStream(uri.toUri())?.use { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
    }
    val cardAlpha = (settings.incomingCallWindowTransparency.coerceIn(55, 100) / 100f)
    val maxTags = if (settings.incomingCallCompactMode) 2 else 4

    Dialog(
        onDismissRequest = { dismissIncomingUi(context) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (settings.incomingCallWindowSize.equals("EXPANDED", ignoreCase = true)) 22.dp else 34.dp)
                .background(Color.Black.copy(alpha = 0.10f), RoundedCornerShape(32.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                shape = RoundedCornerShape(32.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = cardAlpha)),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier.size(if (settings.incomingCallCompactMode) 60.dp else 72.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (settings.showPhotoInNotifications && avatarBitmap != null) {
                                Image(
                                    bitmap = avatarBitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(if (settings.incomingCallCompactMode) 60.dp else 72.dp),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Text(call.displayName.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Incoming call", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(call.displayName, style = if (settings.incomingCallCompactMode) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall)
                            if (settings.incomingCallShowNumber && call.number.isNotBlank()) {
                                Text(call.number, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    if (settings.incomingCallShowGroup && !call.folderName.isNullOrBlank()) {
                        AssistChip(onClick = {}, label = { Text(call.folderName!!) })
                    }
                    if (settings.incomingCallShowTag && call.tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            call.tags.take(maxTags).forEach { tag ->
                                AssistChip(onClick = {}, label = { Text(tag) })
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { declineIncomingCall(context) }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("Decline")
                        }
                        TextButton(onClick = { dismissIncomingUi(context) }) {
                            Icon(Icons.Default.PhoneMissed, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("Dismiss")
                        }
                        TextButton(onClick = { answerIncomingCall(context) }) {
                            Icon(Icons.Default.Call, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("Answer")
                        }
                    }
                }
            }
        }
    }
}
