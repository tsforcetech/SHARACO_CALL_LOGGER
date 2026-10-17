package com.sharaco.callwebhook.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import androidx.navigation.NavGraph.Companion.findStartDestination

@Composable fun CallTrackRoot(model: AppViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    AppTheme(state.theme) {
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(state.error) { if (state.error.isNotEmpty()) { snack.showSnackbar(state.error, duration = SnackbarDuration.Long); model.clearMessage() } }
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route ?: "dashboard"
        val s = state.session
        val ready = s != null && s.companyId.isNotBlank()
        val tabs = if (s?.manager == true) listOf("dashboard" to "Dashboard", "team" to "Team", "reports" to "Reports", "calls" to "Calls", "settings" to "More") else listOf("dashboard" to "Home", "calls" to "Calls", "sync" to "Sync", "settings" to "Profile")
        fun navigate(destination: String) { nav.navigate(destination) { launchSingleTop = true } }
        Scaffold(snackbarHost = { SnackbarHost(snack) }, bottomBar = {
            if (ready) NavigationBar { tabs.forEach { (path, title) ->
                NavigationBarItem(selected = route == path, onClick = { nav.navigate(path) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } }, label = { Text(title) }, icon = {
                    Icon(when (path) { "dashboard" -> Icons.Default.Dashboard; "team" -> Icons.Default.Group; "reports" -> Icons.Default.BarChart; "calls" -> Icons.Default.Call; "sync" -> Icons.Default.Sync; else -> Icons.Default.MoreHoriz }, title)
                })
            } }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.busy > 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (ready && route !in tabs.map { it.first }) TextButton({ nav.popBackStack() }) { Text("‹ Back") }
                Box(Modifier.weight(1f)) {
                    when {
                        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        s == null -> SignInScreen(model)
                        s.companyId.isBlank() -> WorkspaceScreen(state, model)
                        else -> key(s.uid, s.companyId, s.role) {
                            NavHost(navController = nav, startDestination = "dashboard") {
                                composable("dashboard") { Dashboard(state, model, ::navigate) }
                                composable("team") { if (s.manager) TeamScreen(state, model, ::navigate) }
                                composable("reports") { if (s.manager) ReportsScreen(state, model, ::navigate) }
                                composable("calls") { CallsScreen(state, model) }
                                composable("sync") { SyncScreen(state, model) }
                                composable("settings") { SettingsScreen(state, model, ::navigate) }
                                composable("devices") { DevicesScreen(state) }
                                composable("audit") { if (s.role == "OWNER") EventScreen(state, true) }
                                composable("notifications") { if (s.manager) EventScreen(state, false) }
                            }
                        }
                    }
                }
            }
        }
        if (state.invite.isNotEmpty()) InvitationDialog(state.invite, s?.zone ?: "UTC", model::clearInvite)
    }
}
