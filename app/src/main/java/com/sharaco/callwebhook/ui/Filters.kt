package com.sharaco.callwebhook.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sharaco.callwebhook.domain.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportFilters(state: AppState, model: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    val f = state.filters
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("Today", "Yesterday", "7 days", "30 days", "This month").forEach { label ->
            AssistChip(
                onClick = {
                    val today = LocalDate.now(ZoneId.of(state.session?.zone ?: "UTC"))
                    val end = if (label == "Yesterday") today.minusDays(1) else today
                    val start =
                        when (label) {
                            "7 days" -> today.minusDays(6)
                            "30 days" -> today.minusDays(29)
                            "This month" -> today.withDayOfMonth(1)
                            else -> end
                        }
                    model.setFilters(f.copy(start = start, end = end))
                },
                label = { Text(label) },
            )
        }
        AssistChip({ open = true }, label = { Text("Filters (${f.count})") })
    }
    Text(
        "${f.start} — ${f.end} · ${state.session?.zone}",
        style = MaterialTheme.typography.labelMedium,
    )
    if (open) {
        var draft by remember { mutableStateOf(f) }
        var from by remember { mutableStateOf(f.start.toString()) }
        var to by remember { mutableStateOf(f.end.toString()) }
        var error by remember { mutableStateOf("") }
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Refine report", style = MaterialTheme.typography.titleLarge)
                Field("Start date · YYYY-MM-DD", from, { from = it })
                Field("End date · YYYY-MM-DD", to, { to = it })
                if (state.session?.manager == true) {
                    Text("Employees · select one or more")
                    state.employees
                        .filter { it.str("uid").isNotBlank() }
                        .forEach { employee ->
                            val uid = employee.str("uid")
                            Row {
                                Checkbox(
                                    uid in draft.employees,
                                    { selected ->
                                        draft =
                                            draft.copy(
                                                employees =
                                                    if (selected) draft.employees + uid
                                                    else draft.employees - uid
                                            )
                                    },
                                )
                                Text(employee.str("name"), Modifier.padding(top = 12.dp))
                            }
                        }
                    Field("Department", draft.department, { draft = draft.copy(department = it) })
                }
                Text("Call types")
                callTypes.forEach { type ->
                    Row {
                        Checkbox(
                            type in draft.types,
                            { selected ->
                                draft =
                                    draft.copy(
                                        types =
                                            if (selected) draft.types + type else draft.types - type
                                    )
                            },
                        )
                        Text(type.replace('_', ' '), Modifier.padding(top = 12.dp))
                    }
                }
                Choices(
                    listOf("ALL", "CONNECTED", "UNCONNECTED"),
                    draft.connection.ifBlank { "ALL" },
                    { draft = draft.copy(connection = if (it == "ALL") "" else it) },
                )
                Field(
                    "Minimum duration · seconds",
                    draft.minDuration,
                    { draft = draft.copy(minDuration = it) },
                )
                Field(
                    "Maximum duration · seconds",
                    draft.maxDuration,
                    { draft = draft.copy(maxDuration = it) },
                )
                Field("Exact phone number", draft.phone, { draft = draft.copy(phone = it) })
                if (error.isNotEmpty()) Hint(error, true)
                Button(
                    onClick = {
                        runCatching {
                            val start = LocalDate.parse(from)
                            val end = LocalDate.parse(to)
                            require(
                                !end.isBefore(start) && ChronoUnit.DAYS.between(start, end) < 92
                            ) {
                                "Choose an ordered range of at most 92 days."
                            }
                            require(
                                (draft.minDuration.isBlank() ||
                                    draft.minDuration.toLongOrNull() != null) &&
                                    (draft.maxDuration.isBlank() ||
                                        draft.maxDuration.toLongOrNull() != null)
                            ) {
                                "Enter whole seconds."
                            }
                            require(
                                (draft.minDuration.toLongOrNull() ?: 0) >= 0 &&
                                    (draft.maxDuration.toLongOrNull() ?: 604800) >=
                                        (draft.minDuration.toLongOrNull() ?: 0)
                            ) {
                                "Check the duration range."
                            }
                            model.setFilters(draft.copy(start = start, end = end))
                            open = false
                        }
                            .onFailure { error = it.message ?: "Check the filters." }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Apply filters")
                }
                TextButton({
                    draft = Filters(f.start, f.end)
                    from = f.start.toString()
                    to = f.end.toString()
                }) {
                    Text("Reset filters")
                }
            }
        }
    }
}
