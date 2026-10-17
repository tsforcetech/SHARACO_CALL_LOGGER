package com.sharaco.callwebhook.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.sharaco.callwebhook.domain.*
import kotlinx.coroutines.delay

@Composable
fun TeamScreen(state: AppState, model: AppViewModel, navigate: (String) -> Unit) {
    val s = state.session ?: return
    var search by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("ALL") }
    var edit by remember { mutableStateOf<Row?>(null) }
    var selected by remember { mutableStateOf<Row?>(null) }
    var confirmation by remember { mutableStateOf<Pair<Row, String>?>(null) }
    LaunchedEffect(search) {
        delay(250)
        query = search
    }
    Page("Your team", "Employees, invitations and current device health") {
        Field("Search name, staff code, mobile or email", search, { search = it })
        Choices(
            listOf("ALL", "INVITED", "ACTIVE", "INACTIVE", "SUSPENDED", "REMOVED"),
            status,
            { status = it },
        )
        if (s.canManage) Button({ edit = emptyMap() }) { Text("Add employee") }
        val employees =
            state.employees.filter { e ->
                (status == "ALL" || e.str("status") == status) &&
                    listOf("name", "code", "mobile", "email").any {
                        e.str(it).contains(query, true)
                    }
            }
        if (employees.isEmpty()) Hint("No employees match your search.")
        employees.forEach { employee ->
            val metrics =
                state.report
                    .rows("staff")
                    .firstOrNull { it.str("id") == employee.str("uid") }
                    .orEmpty()
            val device =
                state.devices.firstOrNull {
                    it.str("employeeUid") == employee.str("uid") && it.flag("active")
                }
            Card(
                onClick = { selected = employee },
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(employee.str("name"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${employee.str("code")} · ${employee.str("department")} · ${employee.str("status")}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Selected period: ${number(metrics.num("total"))} calls · ${duration(metrics.num("talk"))}"
                    )
                    Text(
                        if (device == null) "No active device"
                        else
                            "${deviceHealth(device)} · Synced ${dateTime(device.num("lastSuccessfulSyncAt"), s.zone)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
    edit?.let { employee ->
        EmployeeForm(employee, { edit = null }, state.busy > 0) { values ->
            model.saveEmployee(values) { edit = null }
        }
    }
    selected?.let { employee ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(employee.str("name")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("${employee.str("code")} · ${employee.str("status")}")
                    Text(employee.str("email"))
                    Text(employee.str("mobile"))
                    Text(employee.str("designation"))
                    if (employee.str("uid").isNotBlank())
                        TextButton({
                            model.setFilters(
                                state.filters.copy(employees = setOf(employee.str("uid")))
                            )
                            selected = null
                            navigate("reports")
                        }) {
                            Text("View performance")
                        }
                    if (s.canManage) {
                        TextButton({
                            edit = employee
                            selected = null
                        }) {
                            Text("Edit employee")
                        }
                        if (employee.str("status") == "INVITED") {
                            TextButton({
                                model.invite(employee, 7)
                                selected = null
                            }) {
                                Text("Regenerate invitation · 7 days")
                            }
                            TextButton({
                                model.invite(employee, 30)
                                selected = null
                            }) {
                                Text("Extend with new invitation · 30 days")
                            }
                            TextButton({
                                model.action(
                                    "revokeInvite",
                                    mapOf("employeeId" to employee.str("id")),
                                )
                                selected = null
                            }) {
                                Text("Revoke invitation")
                            }
                        } else if (employee.str("status") != "REMOVED") {
                            listOf("ACTIVE", "INACTIVE", "SUSPENDED")
                                .filter { it != employee.str("status") }
                                .forEach { action ->
                                    TextButton({
                                        confirmation = employee to action
                                        selected = null
                                    }) {
                                        Text("Set ${action.lowercase()}")
                                    }
                                }
                            if (s.role == "OWNER") {
                                TextButton({
                                    confirmation = employee to "ADMIN"
                                    selected = null
                                }) {
                                    Text("Make admin · reports only")
                                }
                                TextButton({
                                    confirmation = employee to "ADMIN_MANAGE"
                                    selected = null
                                }) {
                                    Text("Make admin · manage employees")
                                }
                                TextButton({
                                    confirmation = employee to "EMPLOYEE"
                                    selected = null
                                }) {
                                    Text("Set employee role")
                                }
                            }
                        }
                        if (employee.str("status") != "REMOVED")
                            TextButton({
                                confirmation = employee to "REMOVED"
                                selected = null
                            }) {
                                Text("Remove employee")
                            }
                    }
                }
            },
            confirmButton = { TextButton({ selected = null }) { Text("Close") } },
        )
    }
    confirmation?.let { (employee, action) ->
        Confirm(
            "Change employee access",
            "Apply $action to ${employee.str("name")}? Historical call records remain under company retention. Removal unlinks the account and requires a new invitation to rejoin.",
            "CONFIRM",
            { confirmation = null },
        ) {
            val role = action in listOf("ADMIN", "ADMIN_MANAGE", "EMPLOYEE")
            model.action(
                "employeeAction",
                mapOf(
                    "employeeId" to employee.str("id"),
                    "action" to if (role) "ROLE" else action,
                    "role" to if (action.startsWith("ADMIN")) "ADMIN" else "EMPLOYEE",
                    "manageEmployees" to (action == "ADMIN_MANAGE"),
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeeForm(employee: Row, close: () -> Unit, busy: Boolean = false, save: (Row) -> Unit) {
    val fields =
        listOf(
            "name" to "Employee name",
            "code" to "Staff code",
            "email" to "Google email · optional",
            "mobile" to "Mobile",
            "department" to "Department",
            "designation" to "Designation",
        )
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            fields.forEach { put(it.first, employee.str(it.first)) }
        }
    }
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (employee.isEmpty()) "Add employee" else "Edit employee",
                style = MaterialTheme.typography.headlineSmall,
            )
            fields.forEach { (key, label) ->
                Field(label, values[key].orEmpty(), { values[key] = it })
            }
            Text(
                "If supplied, the employee must join using this exact Google email.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                { save(values.toMap() + ("employeeId" to employee.str("id"))) },
                Modifier.fillMaxWidth(),
                enabled =
                    !busy && !values["name"].isNullOrBlank() && !values["code"].isNullOrBlank(),
            ) {
                Text("Save employee")
            }
        }
    }
}

@Composable
fun InvitationDialog(invite: Row, zone: String, close: () -> Unit) {
    val context = LocalContext.current
    val token = invite.str("token")
    val bitmap =
        remember(token) {
            val matrix = MultiFormatWriter().encode(token, BarcodeFormat.QR_CODE, 480, 480)
            Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until 480) for (y in 0 until 480) setPixel(
                    x,
                    y,
                    if (matrix[x, y]) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE,
                )
            }
        }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Employee invitation") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("${invite.str("name")} · ${invite.str("code")}")
                Image(
                    bitmap.asImageBitmap(),
                    "Single-use employee invitation QR",
                    Modifier.fillMaxWidth().height(220.dp),
                )
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(token, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Expires ${dateTime(invite.num("expiresAt"), zone)}. Single use. Regenerating revokes this invitation."
                )
            }
        },
        confirmButton = {
            TextButton({
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(
                                Intent.EXTRA_TEXT,
                                "Join ${invite.str("name")}'s employee workspace in CallTrack Pro. Invitation code: $token\nExpires ${dateTime(invite.num("expiresAt"), zone)}",
                            ),
                        "Share invitation",
                    )
                )
            }) {
                Text("Share invitation")
            }
        },
        dismissButton = { TextButton(close) { Text("Done") } },
    )
}
