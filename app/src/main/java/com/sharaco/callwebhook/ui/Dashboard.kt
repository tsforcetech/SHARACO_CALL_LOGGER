package com.sharaco.callwebhook.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sharaco.callwebhook.domain.*

@Composable
fun Dashboard(state: AppState, model: AppViewModel, navigate: (String) -> Unit) =
    DashboardContent(
        state,
        model::setFilters,
        {
            model.refreshReport()
            model.loadCalls()
        },
        navigate,
    )

@Composable
fun DashboardContent(
    state: AppState,
    onFilters: (Filters) -> Unit,
    onRefresh: () -> Unit,
    navigate: (String) -> Unit,
) {
    val s = state.session ?: return
    Page(
        if (s.manager) s.company.str("name")
        else "Hello, ${s.member.str("name").substringBefore(' ')}",
        "Your business activity at a glance",
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(s.company.str("name"), s.company.str("logo"))
            Column(Modifier.weight(1f)) {
                Text(
                    if (s.manager) "COMPANY OVERVIEW" else "MY WORKDAY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(s.member.str("name"), style = MaterialTheme.typography.titleSmall)
            }
            if (s.manager)
                IconButton({ navigate("notifications") }) {
                    Icon(Icons.Default.NotificationsNone, "Notifications")
                }
        }
        if (state.report.isNotEmpty())
            SummaryHero(
                if (s.manager) "Company call summary" else "Personal call summary",
                "${number(state.report.row("metrics").num("total"))} calls",
                "${state.filters.start} · ${s.zone}",
                "Selected period",
            )
        OutlinedButton({ navigate("crmReports") }, modifier = Modifier.fillMaxWidth()) {
            Text("Targets & assigned-lead performance")
        }
        ReportFilters(state, onFilters)
        if (!s.manager && !state.permission)
            Hint("Call history access disabled. Open Sync to enable permission.", true)
        if (s.manager && state.employees.isEmpty())
            Panel("Ready for your first employee") {
                Text("Add an employee, then share their single-use invitation.")
                Button({ navigate("team") }) { Text("Add first employee") }
            }
        if (state.report.isEmpty()) {
            if (state.busy > 0) LoadingCards()
            else Hint("No report loaded. Connect to the internet and refresh.")
        } else {
            Metrics(state.report.row("metrics")) { key ->
                val types =
                    when (key) {
                        "outgoing" -> setOf("OUTGOING")
                        "incoming" -> setOf("INCOMING")
                        "missed" -> setOf("MISSED")
                        else -> emptySet()
                    }
                onFilters(
                    state.filters.copy(
                        types = types,
                        connection = if (key in listOf("connected", "average")) "CONNECTED" else "",
                    )
                )
                navigate("calls")
            }
            Panel("Performance trends") { ActivityChart(state.report.rows("timeline")) }
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
                Panel("Team performance") {
                    StaffChart(state.report.rows("staff")) { row ->
                        onFilters(state.filters.copy(employees = setOf(row.str("id"))))
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
            onRefresh,
            Modifier.fillMaxWidth(),
            enabled = state.busy == 0,
        ) {
            Text("Refresh activity")
        }
    }
}
