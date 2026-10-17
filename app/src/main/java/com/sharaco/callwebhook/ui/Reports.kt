package com.sharaco.callwebhook.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.sharaco.callwebhook.data.humanError
import com.sharaco.callwebhook.domain.*
import kotlinx.coroutines.launch

fun reportColumns(zone: String) =
    listOf(
        TableColumn("Total", "total") { number(it.num("total")) },
        TableColumn("Outgoing", "outgoing") { number(it.num("outgoing")) },
        TableColumn("Incoming", "incoming") { number(it.num("incoming")) },
        TableColumn("Connected", "connected") { number(it.num("connected")) },
        TableColumn("Missed", "missed") { number(it.num("missed")) },
        TableColumn("Rejected", "rejected") { number(it.num("rejected")) },
        TableColumn("Unique nos.", "unique") { number(it.num("unique")) },
        TableColumn("Connection %", "outgoingRate") { "%.1f%%".format(it.dec("outgoingRate")) },
        TableColumn("Total talk", "talk") { duration(it.num("talk")) },
        TableColumn("Avg. duration", "average") { duration(it.num("average")) },
        TableColumn("Last call", "lastCallAt") { dateTime(it.num("lastCallAt"), zone) },
        TableColumn("Last sync", "lastSyncAt") { dateTime(it.num("lastSyncAt"), zone) },
    )

@Composable
fun ReportsScreen(state: AppState, model: AppViewModel, navigate: (String) -> Unit) {
    val s = state.session ?: return
    val rows = state.report.rows("staff")
    val columns = reportColumns(s.zone)
    val first = TableColumn("Employee", "name") { it.str("name") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Row?>(null) }
    Page("Staff performance", "Tap a heading to sort. Tap a person to inspect their performance.") {
        ReportFilters(state, model)
        if (rows.isEmpty())
            Hint(if (state.busy > 0) "Loading report…" else "No employees match this report.")
        else DataTable(rows, first, columns) { selected = it }
        Row {
            listOf(false, true).forEach { pdf ->
                TextButton(
                    {
                        scope.launch {
                            try {
                                ReportExport.share(
                                    context,
                                    "Staff_${state.filters.start}_${state.filters.end}",
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
                    enabled = state.report.isNotEmpty(),
                ) {
                    Text(if (pdf) "Export PDF" else "Export CSV")
                }
            }
        }
        OutlinedButton({ model.refreshReport() }) { Text("Refresh report") }
    }
    selected?.let { row ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(row.str("name")) },
            text = {
                Column {
                    Text("${row.str("code")} · ${row.str("department")} · ${row.str("status")}")
                    Text("${number(row.num("total"))} calls · ${duration(row.num("talk"))} talk")
                    Text("Last call ${dateTime(row.num("lastCallAt"), s.zone)}")
                    Text("Last sync ${dateTime(row.num("lastSyncAt"), s.zone)}")
                    Text(
                        "Device health: ${state.devices.firstOrNull { it.str("employeeUid") == row.str("id") && it.flag("active") }?.let(::deviceHealth) ?: "No active device"}"
                    )
                }
            },
            confirmButton = {
                TextButton({
                    model.setFilters(state.filters.copy(employees = setOf(row.str("id"))))
                    selected = null
                    navigate("dashboard")
                }) {
                    Text("Performance charts")
                }
            },
            dismissButton = {
                TextButton({
                    model.setFilters(state.filters.copy(employees = setOf(row.str("id"))))
                    selected = null
                    navigate("calls")
                }) {
                    Text("View calls")
                }
            },
        )
    }
}
