package com.sharaco.callwebhook.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.sharaco.callwebhook.R
import com.sharaco.callwebhook.domain.*

const val MONITORING = "Your company receives phone numbers, call types, timestamps and durations from this device's Android call history, starting at the configured enrollment boundary. No audio is recorded. Android cannot reliably distinguish personal calls from work calls: use a dedicated work phone. You can revoke call history permission at any time."
@Composable fun LegalDialog(terms: Boolean, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(if (terms) "Terms of use" else "Privacy & monitoring") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (terms) "Use this application only for authorized business monitoring on devices whose users have received the disclosure and consented. Your company controls membership, access and retention. Reports reflect Android device metadata and are not telecom-grade answer detection. Background synchronization is subject to device and network availability." else MONITORING)
            Text("Google authentication identifies your account. Firebase stores company records and diagnostic crash information. Your company owner and authorized administrators can view your business call metadata. Contact your company administrator for access and retention questions. Leaving or deleting a personal account stops access; historical company records remain until company retention or deletion removes them.")
        }
    }, confirmButton = { TextButton(close) { Text("Close") } })
}
@Composable fun SignInScreen(model: AppViewModel) {
    val activity = LocalContext.current as Activity
    var legal by remember { mutableStateOf<String?>(null) }
    Page(stringResource(R.string.app_name), stringResource(R.string.tagline)) {
        Spacer(Modifier.height(48.dp))
        Text("Business calls.\nClearer decisions.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("One workspace for your team's call activity, performance and sync health.")
        Panel("Built for your workday") {
            Text("Understand activity across your team.")
            Text("Keep calls queued safely when offline.")
            Text("Know when a device needs attention.")
        }
        Button({ model.signIn(activity) }, Modifier.fillMaxWidth()) { Text("Continue with Google") }
        Row { TextButton({ legal = "privacy" }) { Text("Privacy Policy") }; TextButton({ legal = "terms" }) { Text("Terms") } }
    }
    legal?.let { LegalDialog(it == "terms") { legal = null } }
}
@Composable fun WorkspaceScreen(state: AppState, model: AppViewModel) {
    var create by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let { token = it; model.inspectInvite(it) } }
    Page("Your workspace", "Create a company or join with your personal employee invitation.") {
        Button({ create = true }, Modifier.fillMaxWidth()) { Text("Create company") }
        Panel("Join your team") {
            Field("Invitation code", token, { token = it; model.clearPreview() })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan your employee invitation").setBeepEnabled(false)) }) { Text("Scan QR") }
                Button({ model.inspectInvite(token.trim()) }, enabled = token.isNotBlank()) { Text("Verify code") }
            }
            if (state.preview.isNotEmpty()) {
                Text(state.preview.str("companyName"), style = MaterialTheme.typography.titleLarge)
                Text("${state.preview.str("employeeName")} · ${state.preview.str("employeeCode")}")
                Hint(MONITORING)
                Row { Checkbox(consent, { consent = it }); Text("I understand and consent to business call metadata monitoring.", Modifier.padding(top = 8.dp)) }
                Button({ model.join(state.preview.str("token")) }, enabled = consent) { Text("Join company") }
            }
        }
        TextButton({ model.signOut() }) { Text("Sign out") }
    }
    if (create) CompanyForm(emptyMap(), { create = false }) { model.createCompany(it); create = false }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CompanyForm(initial: Row, close: () -> Unit, save: (Row) -> Unit) {
    val fields = listOf("name" to "Company name", "industry" to "Industry", "country" to "Country · ISO code", "timeZone" to "Time zone · e.g. Asia/Dubai", "phone" to "Contact mobile", "email" to "Company email", "address" to "Address", "workingHours" to "Working hours", "retentionDays" to "Retention · days (7–3650)")
    val values = remember { mutableStateMapOf<String, String>().apply { fields.forEach { put(it.first, initial[it.first]?.toString().orEmpty()) }; if (initial.isEmpty()) { put("country", "AE"); put("timeZone", "Asia/Dubai"); put("retentionDays", "365") } } }
    var policy by remember { mutableStateOf(initial.str("initialSyncPolicy").ifBlank { "DAY_START" }) }
    var notifications by remember { mutableStateOf(initial["notificationsEnabled"] != false) }
    var error by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (initial.isEmpty()) "Create company" else "Company settings", style = MaterialTheme.typography.headlineSmall)
            fields.forEach { (key, label) -> Field(label, values[key].orEmpty(), { values[key] = it }, enabled = key != "timeZone" || initial.isEmpty()) }
            Text("Initial monitoring boundary")
            Choices(listOf("DAY_START", "JOIN_TIME"), policy, { policy = it })
            Text("Applies to employees who join after this change. Company timezone is fixed after creation to preserve report boundaries.", style = MaterialTheme.typography.bodySmall)
            Row { Switch(notifications, { notifications = it }); Text("Device health notifications", Modifier.padding(12.dp)) }
            Text("Device policy: one primary device per employee. Invitations are single-use and expire after 7 days by default.", style = MaterialTheme.typography.bodySmall)
            if (error.isNotEmpty()) Hint(error, true)
            Button({
                if (values["name"].isNullOrBlank() || values["retentionDays"]?.toIntOrNull() !in 7..3650) error = "Enter a company name and retention from 7 to 3650 days."
                else save(values.toMap() + mapOf("retentionDays" to values["retentionDays"]?.toInt(), "initialSyncPolicy" to policy, "notificationsEnabled" to notifications))
            }, Modifier.fillMaxWidth()) { Text("Save company") }
        }
    }
}
