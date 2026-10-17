package com.sharaco.callwebhook.ui

import android.Manifest
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.work.WorkManager
import com.sharaco.callwebhook.BuildConfig
import com.sharaco.callwebhook.domain.*
import kotlinx.coroutines.flow.collectLatest

@Composable
fun SyncScreen(state: AppState, model: AppViewModel) {
    val context = LocalContext.current
    val s = state.session ?: return
    var consent by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    var workers by remember { mutableStateOf("Checking…") }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            model.permissionsChanged()
        }
    val device = state.devices.firstOrNull { it.str("id") == state.installation }
    val active = device?.flag("active") == true
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val online =
        manager
            .getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    LaunchedEffect(Unit) {
        WorkManager.getInstance(context).getWorkInfosByTagFlow("calltrack-sync").collectLatest {
            info ->
            workers =
                info.map { it.state.name }.distinct().joinToString().ifBlank { "Not scheduled" }
        }
    }
    Page(
        "Device setup & sync",
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
    ) {
        Hint(
            if (active && state.permission)
                "Monitoring active · company call metadata synchronization is enabled."
            else "Monitoring inactive · complete permission and device registration.",
            !active || !state.permission,
        )
        Panel("1 · Understand monitoring") {
            Text(MONITORING)
            Row {
                Checkbox(consent, { consent = it })
                Text(
                    "I understand this device's call history will be synchronized.",
                    Modifier.weight(1f),
                )
            }
        }
        Panel("2 · Permissions") {
            Text("Call history: ${if (state.permission) "Granted" else "Required / denied"}")
            Button(
                { permission.launch(Manifest.permission.READ_CALL_LOG) },
                enabled = consent && !state.permission,
            ) {
                Text("Enable call history")
            }
            Text("Notifications: ${if (state.notificationPermission) "Granted" else "Not granted"}")
            if (Build.VERSION.SDK_INT >= 33)
                OutlinedButton(
                    { permission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    enabled = !state.notificationPermission,
                ) {
                    Text("Enable notifications")
                }
            TextButton({
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    )
                )
            }) {
                Text("Open app settings")
            }
            Text(
                "If Android no longer shows a permission prompt, enable Call logs in App settings → Permissions.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Panel("3 · Register this device") {
            Text(
                "One primary device per employee. Replacement immediately revokes the previous device's upload access."
            )
            Button({ model.register(false) }, enabled = state.permission && consent) {
                Text(if (active) "Update device registration" else "Register this device")
            }
            OutlinedButton({ replacing = true }, enabled = state.permission && consent) {
                Text("Replace existing device")
            }
        }
        Panel("Sync health") {
            Text(
                device?.let { deviceHealth(it) } ?: "NOT REGISTERED",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(state.syncPhase)
            Text("Pending calls: ${number(state.pending.toLong())}")
            Text("Last successful sync: ${dateTime(state.lastSuccess, s.zone)}")
            Text("Network: ${if (online) "Online" else "Offline — calls remain queued"}")
            Text("WorkManager: $workers")
            Text("App version: ${BuildConfig.VERSION_NAME}")
            if (state.syncError.isNotBlank()) Hint(state.syncError, true)
            Button({ model.syncNow() }, enabled = active && state.permission && state.busy == 0) {
                Text("Sync now")
            }
        }
        Text(
            "Android schedules periodic work at intervals of at least 15 minutes and may delay it for battery restrictions. Force-stopping the app prevents work until you reopen it. Calls are reconciled on launch, resume, manual sync and periodic work; device manufacturers may require battery settings changes.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (replacing)
        Confirm(
            "Replace existing device?",
            "The previous device will no longer be allowed to upload. Historical company records remain.",
            "REPLACE",
            { replacing = false },
        ) {
            model.register(true)
        }
}

@Composable
fun DevicesScreen(state: AppState) {
    val s = state.session ?: return
    var filter by remember { mutableStateOf("ALL") }
    var selected by remember { mutableStateOf<Row?>(null) }
    val now = System.currentTimeMillis()
    val rows =
        state.devices
            .map { it + ("health" to deviceHealth(it)) }
            .filter {
                when (filter) {
                    "HEALTHY",
                    "DELAYED" -> it.str("health") == filter
                    "PERMISSION ISSUE" -> !it.flag("callLogPermission")
                    "OFFLINE" -> now - it.num("lastSeenAt") > 3600000
                    "OLD APP" -> it.str("appVersion") != BuildConfig.VERSION_NAME
                    else -> true
                }
            }
    Page("Device health", "Measured permissions, synchronization and last contact") {
        Choices(
            listOf("ALL", "HEALTHY", "DELAYED", "PERMISSION ISSUE", "OFFLINE", "OLD APP"),
            filter,
            { filter = it },
        )
        if (rows.isEmpty()) Hint("No devices match this filter.")
        else
            DataTable(
                rows,
                TableColumn("Employee", "employeeName") { it.str("employeeName") },
                listOf(
                    TableColumn("Device", "model") {
                        "${it.str("manufacturer")} ${it.str("model")}"
                    },
                    TableColumn("Android", "androidVersion") { it.str("androidVersion") },
                    TableColumn("App", "appVersion") { it.str("appVersion") },
                    TableColumn("Permission", "callLogPermission") {
                        if (it.flag("callLogPermission")) "Granted" else "Disabled"
                    },
                    TableColumn("Last seen", "lastSeenAt") {
                        dateTime(it.num("lastSeenAt"), s.zone)
                    },
                    TableColumn("Last sync", "lastSuccessfulSyncAt") {
                        dateTime(it.num("lastSuccessfulSyncAt"), s.zone)
                    },
                    TableColumn("Pending", "pendingRecordCount") {
                        number(it.num("pendingRecordCount"))
                    },
                    TableColumn("Health", "health") { it.str("health") },
                ),
            ) {
                selected = it
            }
        Text(
            "Offline means no recent contact, not a live network probe. Device values reflect the last successful heartbeat.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    selected?.let { d ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(d.str("employeeName")) },
            text = {
                Column {
                    Text("${d.str("manufacturer")} ${d.str("model")}")
                    Text("Android ${d.str("androidVersion")} · App ${d.str("appVersion")}")
                    Text("Active: ${d.flag("active")}")
                    Text("Registered ${dateTime(d.num("registeredAt"), s.zone)}")
                    Text("Last seen ${dateTime(d.num("lastSeenAt"), s.zone)}")
                    Text("Last success ${dateTime(d.num("lastSuccessfulSyncAt"), s.zone)}")
                    Text("Pending ${d.num("pendingRecordCount")}")
                    Text("Call permission: ${d.flag("callLogPermission")}")
                    Text("Notifications: ${d.flag("notificationPermission")}")
                    Text("Last worker phase: ${d.str("lastSyncAttemptStatus")}")
                    Text("Last error: ${d.str("lastSyncError").ifBlank { "None reported" }}")
                }
            },
            confirmButton = { TextButton({ selected = null }) { Text("Close") } },
        )
    }
}
