package com.opencontacts.app

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SecurityRoute(
    onBack: () -> Unit,
    appViewModel: AppViewModel,
) {
    val settings by appViewModel.appLockSettings.collectAsStateWithLifecycle()
    val pinError by appViewModel.pinError.collectAsStateWithLifecycle()
    val availability = appViewModel.biometricAvailability()
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }

    SettingsScaffold(title = "Security", onBack = onBack) { modifier ->
        LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SettingsSection(title = "App lock", subtitle = "Lock the app on launch or when returning from background without creating resume loops.") {
                    SettingsSwitchRow(
                        title = "Enable biometric lock",
                        subtitle = if (availability.canAuthenticate) "Use fingerprint, face, or device credentials depending on your settings." else availability.message,
                        checked = settings.biometricEnabled,
                        enabled = availability.canAuthenticate,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                appViewModel.setBiometricEnabled(false)
                            } else {
                                val activity = context.findFragmentActivity() ?: return@SettingsSwitchRow
                                val prompt = BiometricPrompt(
                                    activity,
                                    ContextCompat.getMainExecutor(activity),
                                    object : BiometricPrompt.AuthenticationCallback() {
                                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                            appViewModel.setBiometricEnabled(true)
                                        }

                                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                            appViewModel.showUiError(errString.toString())
                                        }
                                    },
                                )
                                prompt.authenticate(appViewModel.biometricPromptInfo("Enable app lock"))
                            }
                        },
                    )
                    SettingsSpacer()
                    SettingsSwitchRow(
                        title = "Allow device credentials fallback",
                        subtitle = "Use device PIN, pattern, or password when biometrics are unavailable.",
                        checked = settings.allowDeviceCredential,
                        onCheckedChange = appViewModel::setAllowDeviceCredential,
                    )
                    SettingsSpacer()
                    SettingsSwitchRow(
                        title = "Lock on app resume",
                        subtitle = "Require authentication again after returning from background.",
                        checked = settings.lockOnAppResume,
                        onCheckedChange = appViewModel::setLockOnAppResume,
                    )
                    SettingsSpacer()
                    SettingsChoiceRow(
                        title = "Lock after inactivity",
                        subtitle = "Choose how quickly the app locks after it leaves the foreground.",
                        selected = when (settings.lockAfterInactivitySeconds) { 0 -> "Immediately"; 15 -> "15 sec"; 30 -> "30 sec"; else -> "60 sec" },
                        choices = listOf("Immediately", "15 sec", "30 sec", "60 sec"),
                        onSelect = { label ->
                            appViewModel.setLockAfterInactivitySeconds(
                                when (label) {
                                    "Immediately" -> 0
                                    "15 sec" -> 15
                                    "30 sec" -> 30
                                    else -> 60
                                },
                            )
                        },
                    )
                }
            }
            item {
                SettingsSection(title = "PIN", subtitle = "Use a local PIN as an additional fallback when biometrics are disabled or unavailable.") {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            pin = it.filter(Char::isDigit)
                            appViewModel.clearError()
                        },
                        label = { Text(if (settings.hasPin) "Change PIN" else "Set PIN") },
                        singleLine = true,
                    )
                    pinError?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    SettingsSpacer()
                    Button(onClick = { appViewModel.setPin(pin) }) {
                        Text(if (settings.hasPin) "Update PIN" else "Save PIN")
                    }
                    if (settings.hasPin) {
                        androidx.compose.material3.TextButton(onClick = appViewModel::clearPin) { Text("Clear PIN") }
                    }
                }
            }
            item {
                SettingsSection(title = "Immediate action") {
                    Button(onClick = appViewModel::lockNow) {
                        Text("Lock app now")
                    }
                }
            }
        }
    }
}

private fun android.content.Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is android.content.ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
