package com.sharaco.callwebhook.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.sharaco.callwebhook.data.humanError
import com.sharaco.callwebhook.domain.*
import kotlinx.coroutines.launch

@Composable
fun CallsScreen(state: AppState, model: AppViewModel) {
    val s = state.session ?: return
    var tab by remember { mutableStateOf("Synced history") }
    var selected by remember { mutableStateOf<Row?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filter = state.filters.payload(s.zone)
    val local =
        state.local
            .filter { call ->
                call.timestamp >= filter.num("from") &&
                    call.timestamp < filter.num("to") &&
                    (state.filters.types.isEmpty() || call.type in state.filters.types) &&
                    (state.filters.phone.isBlank() || call.phone.contains(state.filters.phone)) &&
                    (state.filters.connection.isBlank() ||
                        (connected(call.type, call.seconds) ==
                            (state.filters.connection == "CONNECTED"))) &&
                    call.seconds >= (state.filters.minDuration.toLongOrNull() ?: 0) &&
                    call.seconds <= (state.filters.maxDuration.toLongOrNull() ?: 604800)
            }
            .map { call ->
                mapOf<String, Any?>(
                    "id" to call.id,
                    "employeeName" to s.member.str("name"),
                    "phoneNumber" to call.phone,
                    "callType" to call.type,
                    "callTimestamp" to call.timestamp,
                    "durationSeconds" to call.seconds,
                    "syncStatus" to
                        if (call.synced) "✓ Synced"
                        else if (call.error.isNotEmpty()) "! Failed" else "◷ Pending",
                    "error" to call.error,
                )
            }
    val rows =
        if (tab == "Synced history") state.calls.map { it + ("syncStatus" to "✓ Synced") }
        else
            when (state.sort) {
                "OLDEST" -> local.sortedBy { it.num("callTimestamp") }
                "LONGEST" -> local.sortedByDescending { it.num("durationSeconds") }
                "SHORTEST" -> local.sortedBy { it.num("durationSeconds") }
                else -> local
            }
    val first =
        TableColumn("Phone number", "phoneNumber") { it.str("phoneNumber").ifBlank { "Withheld" } }
    val columns =
        listOf(
            TableColumn("Employee", "employeeName") { it.str("employeeName") },
            TableColumn("Type", "callType") { it.str("callType").replace('_', ' ') },
            TableColumn("Date & time", "callTimestamp") {
                dateTime(it.num("callTimestamp"), s.zone)
            },
            TableColumn("Duration", "durationSeconds") { duration(it.num("durationSeconds")) },
            TableColumn("Status", "syncStatus") { it.str("syncStatus") },
        )
    Page("Call history", "Inspect individual calls and their synchronization status.") {
        if (s.role != "OWNER")
            Choices(listOf("Synced history", "Device history"), tab, { tab = it })
        ReportFilters(state, model)
        Choices(listOf("NEWEST", "OLDEST", "LONGEST", "SHORTEST"), state.sort, model::setSort)
        if (tab == "Device history")
            Hint(
                "Device history contains the monitoring period collected on this installation. Search applies to the ${state.local.size} loaded device records."
            )
        if (rows.isEmpty())
            Hint(if (state.busy > 0) "Loading calls…" else "No calls recorded for this period.")
        else DataTable(rows, first, columns) { selected = it }
        if (tab == "Synced history" && state.hasMore)
            Button({ model.loadCalls(true) }, enabled = state.busy == 0) {
                Text("Load next 50 calls")
            }
        if (tab == "Device history" && state.local.size >= state.localLimit)
            Button(model::moreLocal) { Text("Load more device history") }
        Row {
            listOf(false, true).forEach { pdf ->
                TextButton(
                    {
                        scope.launch {
                            try {
                                ReportExport.share(
                                    context,
                                    "Calls_${state.filters.start}_${state.filters.end}",
                                    (listOf(first) + columns).map { it.label },
                                    rows.map { row ->
                                        (listOf(first) + columns).map { it.format(row) }
                                    },
                                    pdf,
                                )
                            } catch (e: Exception) {
                                model.message(humanError(e))
                            }
                        }
                    },
                    enabled = rows.isNotEmpty(),
                ) {
                    Text(if (pdf) "PDF · loaded rows" else "CSV · loaded rows")
                }
            }
        }
        OutlinedButton({ model.loadCalls() }) { Text("Refresh synced history") }
    }
    selected?.let { row ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(row.str("phoneNumber").ifBlank { "Withheld number" }) },
            text = {
                Column {
                    Text(row.str("employeeName"))
                    Text(row.str("callType").replace('_', ' '))
                    Text(dateTime(row.num("callTimestamp"), s.zone))
                    Text(duration(row.num("durationSeconds")))
                    Text(row.str("syncStatus"))
                    if (row.str("error").isNotEmpty()) Text(row.str("error"))
                }
            },
            confirmButton = {
                TextButton(
                    {
                        try {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_DIAL,
                                    Uri.fromParts("tel", row.str("phoneNumber"), null),
                                )
                            )
                        } catch (e: Exception) {
                            model.message("No phone dialer is available on this device.")
                        }
                    },
                    enabled = row.str("phoneNumber").isNotBlank(),
                ) {
                    Text("Open dialer")
                }
            },
            dismissButton = { TextButton({ selected = null }) { Text("Close") } },
        )
    }
}
