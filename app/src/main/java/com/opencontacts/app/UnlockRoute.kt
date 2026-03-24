package com.opencontacts.app

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun UnlockRoute(
    viewModel: AppViewModel = hiltViewModel(),
) {
    val settings by viewModel.appLockSettings.collectAsStateWithLifecycle()
    val error by viewModel.pinError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }

    Surface {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Unlock vault", style = MaterialTheme.typography.headlineMedium)
            Text("Authenticate to access your private contacts workspace.")
            if (settings.hasPin) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = pin,
                    onValueChange = {
                        pin = it
                        viewModel.clearError()
                    },
                    singleLine = true,
                    label = { Text("PIN") },
                    supportingText = { if (error != null) Text(error ?: "") },
                )
                Button(onClick = { viewModel.unlockWithPin(pin) }) {
                    Text("Unlock with PIN")
                }
            }
            if (settings.biometricEnabled && viewModel.canUseBiometric()) {
                Button(onClick = {
                    val activity = context.findFragmentActivity() ?: run {
                        viewModel.showUiError("Biometric unlock is unavailable on this screen.")
                        return@Button
                    }
                    runCatching {
                        val prompt = BiometricPrompt(
                            activity,
                            ContextCompat.getMainExecutor(activity),
                            object : BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                    super.onAuthenticationSucceeded(result)
                                    viewModel.unlockWithBiometricSuccess()
                                }

                                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                    super.onAuthenticationError(errorCode, errString)
                                    viewModel.showUiError(errString.toString())
                                }
                            },
                        )
                        prompt.authenticate(viewModel.biometricPromptInfo("OpenContacts"))
                    }.onFailure {
                        viewModel.showUiError(it.message ?: "Unable to start biometric prompt")
                    }
                }) {
                    Text("Unlock with biometrics")
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
