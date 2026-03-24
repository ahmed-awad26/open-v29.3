package com.opencontacts.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AppearanceRoute(
    onBack: () -> Unit,
    appViewModel: AppViewModel,
) {
    val settings by appViewModel.appLockSettings.collectAsStateWithLifecycle()
    SettingsScaffold(title = "Appearance", onBack = onBack) { modifier ->
        LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SettingsSection(
                    title = "Theme",
                    subtitle = "Use a calmer dark theme and consistent containers across the app.",
                ) {
                    SettingsChoiceRow(
                        title = "Theme mode",
                        subtitle = "Choose how the app adapts to light and dark mode.",
                        selected = settings.themeMode,
                        choices = listOf("LIGHT", "DARK", "SYSTEM"),
                        onSelect = appViewModel::setThemeMode,
                    )
                }
            }
            item {
                SettingsSection(title = "Preview") {
                    Text(
                        "Dark mode now avoids the bright blue details header and uses calmer container colors for better contrast.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
