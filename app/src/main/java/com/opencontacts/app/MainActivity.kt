package com.opencontacts.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.opencontacts.core.ui.theme.OpenContactsTheme
import com.opencontacts.feature.contacts.ContactDetailsRoute
import com.opencontacts.feature.contacts.ContactsRoute
import com.opencontacts.feature.vaults.VaultsRoute
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: AppViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            viewModel = hiltViewModel()
            ThemedApp(viewModel)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && ::viewModel.isInitialized) {
            viewModel.onAppBackgrounded()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!isChangingConfigurations && ::viewModel.isInitialized) {
            viewModel.onAppForegrounded()
        }
    }
}

@Composable
private fun ThemedApp(viewModel: AppViewModel) {
    val settings by viewModel.appLockSettings.collectAsStateWithLifecycle()
    OpenContactsTheme(themeMode = settings.themeMode) {
        Surface(color = MaterialTheme.colorScheme.background) {
            AppRoot(viewModel)
        }
    }
}

@Composable
private fun AppRoot(viewModel: AppViewModel) {
    val shouldShowUnlock by viewModel.shouldShowUnlock.collectAsStateWithLifecycle()
    if (shouldShowUnlock) UnlockRoute(viewModel = viewModel) else AppNavHost(viewModel)
}

@Composable
private fun AppNavHost(viewModel: AppViewModel) {
    val navController = rememberNavController()
    val activeVaultName by viewModel.activeVaultName.collectAsStateWithLifecycle()
    val vaults by viewModel.vaults.collectAsStateWithLifecycle()

    NavHost(navController = navController, startDestination = "contacts") {
        composable("contacts") {
            ContactsRoute(
                activeVaultName = activeVaultName,
                vaults = vaults,
                onOpenDetails = { navController.navigate("contact/$it") },
                onOpenWorkspace = { navController.navigate("workspace") },
                onOpenImportExport = { navController.navigate("settings/importexport") },
                onOpenSearch = null,
                onOpenSecurity = { navController.navigate("settings") },
                onOpenBackup = { navController.navigate("settings/backup") },
                onOpenTrash = { navController.navigate("settings/trash") },
                onOpenVaults = { navController.navigate("vaults") },
                onSwitchVault = viewModel::switchVault,
            )
            IncomingCallInAppHost()
        }
        composable(route = "contact/{contactId}", arguments = listOf(navArgument("contactId") { type = NavType.StringType })) {
            ContactDetailsRoute(onBack = { navController.popBackStack() })
            IncomingCallInAppHost()
        }
        composable("vaults") { VaultsRoute(onBack = { navController.popBackStack() }) }
        composable("workspace") {
            WorkspaceRoute(
                onBack = { navController.popBackStack() },
                onOpenDetails = { navController.navigate("contact/$it") },
            )
        }
        composable("search") { SearchRoute(onBack = { navController.popBackStack() }) }
        composable("settings") { SettingsHomeRoute(onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) }) }
        composable("settings/security") { SecurityRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/backup") { BackupRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/importexport") { ImportExportRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/preferences") { PreferencesRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/notifications") { NotificationsIncomingCallsRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/blocked") { BlockedContactsRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/trash") { TrashRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/appearance") { AppearanceRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/about") { AboutRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
    }
}
