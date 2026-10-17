package com.sharaco.callwebhook

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.sharaco.callwebhook.domain.*
import com.sharaco.callwebhook.ui.*
import java.time.LocalDate

// Deterministic visual fixtures live only in screenshotTest; they never enter an APK or Firebase.
private val metrics: Row =
    mapOf(
        "total" to 427,
        "outgoing" to 302,
        "incoming" to 78,
        "connected" to 286,
        "missed" to 31,
        "rejected" to 16,
        "talk" to 45240,
        "average" to 158,
        "unique" to 193,
        "outgoingRate" to 74.6,
        "incomingRate" to 81.2,
    )
private val session =
    Session(
        "owner",
        "company",
        mapOf("name" to "Sharaco Technologies", "timeZone" to "Asia/Dubai"),
        mapOf("name" to "Company owner", "role" to "OWNER", "status" to "ACTIVE"),
    )
private val staff =
    listOf("Ahmed Hassan", "Jasim Mohammed", "Rashid Ali").mapIndexed { i, name ->
        metrics +
            mapOf(
                "name" to name,
                "id" to "employee$i",
                "total" to 92 - i * 11,
                "outgoing" to 62 - i * 7,
                "incoming" to 20 - i * 2,
                "missed" to 7 - i,
                "rejected" to 3 - i,
                "connected" to 65 - i * 8,
                "talk" to 10270 - i * 1264,
                "unique" to 52 - i * 6,
                "department" to "Sales",
                "status" to "ACTIVE",
            )
    }
private val state =
    AppState(
        session = session,
        loading = false,
        filters = Filters(LocalDate.of(2026, 10, 16)),
        employees = staff,
        report =
            mapOf(
                "metrics" to metrics,
                "staff" to staff,
                "generatedAt" to 1792152000000L,
                "timeline" to
                    listOf(5, 12, 8, 22, 31, 25, 18, 29).mapIndexed { i, value ->
                        mapOf("label" to "${9+i}:00", "value" to value)
                    },
            ),
    )

@Composable
private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
    AppTheme(if (dark) "DARK" else "LIGHT") {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("Home", "Team", "Reports", "Calls", "More").forEachIndexed { i, title ->
                        NavigationBarItem(
                            selected = i == 0,
                            onClick = {},
                            icon = {
                                Icon(
                                    when (i) {
                                        0 -> Icons.Default.Dashboard
                                        1 -> Icons.Default.Group
                                        2 -> Icons.Default.BarChart
                                        3 -> Icons.Default.Call
                                        else -> Icons.Default.MoreHoriz
                                    },
                                    title,
                                )
                            },
                            label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors =
                                NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) { content() }
        }
    }
}

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showBackground = true)
@Composable
fun DashboardLight() {
    Frame(false) { DashboardContent(state, {}, {}, {}) }
}

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showBackground = true)
@Composable
fun DashboardDark() {
    Frame(true) { DashboardContent(state, {}, {}, {}) }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 800, fontScale = 1.3f, showBackground = true)
@Composable
fun LargeTextDashboard() {
    Frame(false) { DashboardContent(state, {}, {}, {}) }
}

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showBackground = true)
@Composable
fun SignInLight() {
    AppTheme("LIGHT") { Surface { SignInContent {} } }
}

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showBackground = true)
@Composable
fun ReportsLight() {
    Frame(false) {
        Page("Staff performance", "Tap a heading to sort. Swipe across for more metrics.") {
            ReportFilters(state) {}
            DataTable(
                staff,
                TableColumn("Employee", "name") { it.str("name") },
                reportColumns("Asia/Dubai"),
            ) {}
            Panel("Call activity") { ActivityChart(state.report.rows("timeline")) }
            Panel("Call distribution") { Distribution(metrics) }
        }
    }
}

@PreviewTest
@Preview(widthDp = 360, heightDp = 800, fontScale = 1.3f, showBackground = true)
@Composable
fun SignInErrorLargeText() {
    AppTheme("LIGHT") {
        Surface {
            SignInContent(
                error =
                    "Google sign-in is not enabled for this app. Ask your administrator to enable the Google provider in Firebase Authentication."
            ) {}
        }
    }
}
