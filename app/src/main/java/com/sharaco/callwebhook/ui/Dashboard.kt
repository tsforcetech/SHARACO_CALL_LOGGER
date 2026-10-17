package com.sharaco.callwebhook.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.sharaco.callwebhook.domain.*

@Composable
fun Dashboard(state: AppState, model: AppViewModel, navigate: (String) -> Unit) {
    val s = state.session ?: return
    Page(
        if (s.manager) s.company.str("name")
        else "Hello, ${s.member.str("name").substringBefore(' ')}",
        "Your business activity at a glance",
    ) {
        ReportFilters(state, model)
        if (!s.manager && !state.permission)
            Hint("Call history access disabled. Open Sync to enable permission.", true)
        if (s.manager && state.employees.isEmpty())
            Panel("Ready for your first employee") {
                Text("Add an employee, then share their single-use invitation.")
                Button({ navigate("team") }) { Text("Add first employee") }
            }
        if (state.report.isEmpty()) {
            Hint(
                if (state.busy > 0) "Loading activity…"
                else "No report loaded. Connect to the internet and refresh."
            )
        } else {
            Metrics(state.report.row("metrics")) { key ->
                val types =
                    when (key) {
                        "outgoing" -> setOf("OUTGOING")
                        "incoming" -> setOf("INCOMING")
                        "missed" -> setOf("MISSED")
                        else -> emptySet()
                    }
                model.setFilters(
                    state.filters.copy(
                        types = types,
                        connection = if (key in listOf("connected", "average")) "CONNECTED" else "",
                    )
                )
                navigate("calls")
            }
            Panel("Call activity") { ActivityChart(state.report.rows("timeline")) }
            Panel("Call distribution") { Distribution(state.report.row("metrics")) }
            Panel("Connection rate") {
                val m = state.report.row("metrics")
                Text(
                    "%.1f%% outgoing  ·  %.1f%% incoming"
                        .format(m.dec("outgoingRate"), m.dec("incomingRate")),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "Connection status is derived from the call information available from the Android device. Incoming and outgoing calls with duration > 0 are treated as connected.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (s.manager)
                Panel("Staff performance") {
                    StaffChart(state.report.rows("staff")) { row ->
                        model.setFilters(state.filters.copy(employees = setOf(row.str("id"))))
                        navigate("reports")
                    }
                }
            else
                Panel("Recent calls") {
                    if (state.calls.isEmpty()) Text("No calls recorded for this period.")
                    state.calls.take(5).forEach {
                        Text(
                            "${it.str("phoneNumber").ifBlank { "Withheld" }} · ${it.str("callType")} · ${duration(it.num("durationSeconds"))}"
                        )
                    }
                    TextButton({ navigate("calls") }) { Text("View all calls") }
                }
            Text(
                "Updated ${dateTime(state.report.num("generatedAt"), s.zone)}. Daily summaries can take a few moments to reflect a new upload.",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (!s.manager)
            Panel("Monitoring & sync") {
                Text(state.syncPhase)
                Text(
                    "${state.pending} pending · Last success ${dateTime(state.lastSuccess, s.zone)}"
                )
                Button({ navigate("sync") }) { Text("Open sync status") }
            }
        else if (state.devices.any { deviceHealth(it) != "HEALTHY" }) {
            OutlinedButton({ navigate("devices") }) { Text("Review devices needing attention") }
        }
        OutlinedButton(
            {
                model.refreshReport()
                model.loadCalls()
            },
            Modifier.fillMaxWidth(),
            enabled = state.busy == 0,
        ) {
            Text("Refresh activity")
        }
    }
}
