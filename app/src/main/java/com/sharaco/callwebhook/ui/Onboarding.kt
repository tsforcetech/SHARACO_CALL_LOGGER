package com.sharaco.callwebhook.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.sharaco.callwebhook.domain.*

const val MONITORING =
    "Your company receives phone numbers, call types, timestamps and durations from this device's Android call history, starting at the configured enrollment boundary. No audio is recorded. Android cannot reliably distinguish personal calls from work calls: use a dedicated work phone. You can revoke call history permission at any time."

@Composable
fun LegalDialog(terms: Boolean, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (terms) "Terms of use" else "Privacy & monitoring") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (terms)
                        "Use this application only for authorized business monitoring on devices whose users have received the disclosure and consented. Your company controls membership, access and retention. Reports reflect Android device metadata and are not telecom-grade answer detection. Background synchronization is subject to device and network availability."
                    else MONITORING
                )
                Text(
                    "Google authentication identifies your account. Firebase stores company records and diagnostic crash information. Your company owner and authorized administrators can view your business call metadata. Contact your company administrator for access and retention questions. Leaving or deleting a personal account stops access; historical company records remain until company retention or deletion removes them."
                )
            }
        },
        confirmButton = { TextButton(close) { Text("Close") } },
    )
}

@Composable
fun SignInScreen(model: AppViewModel, busy: Boolean = false, error: String = "") {
    val activity = LocalActivity.current ?: return
    SignInContent(!busy, error) { model.signIn(activity) }
}

@Composable
fun SignInContent(enabled: Boolean = true, error: String = "", signIn: () -> Unit) {
    var legal by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(17.dp)) {
                Icon(
                    Icons.Default.Insights,
                    null,
                    Modifier.padding(13.dp).size(28.dp),
                    tint = Color.White,
                )
            }
            Text("CallTrack Pro", style = MaterialTheme.typography.titleLarge)
        }
        Box(
            Modifier.fillMaxWidth()
                .height(212.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.surface,
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                Modifier.width(182.dp).height(180.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(22.dp),
                shadowElevation = 8.dp,
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Call activity", style = MaterialTheme.typography.labelLarge)
                        Icon(
                            Icons.Default.TrendingUp,
                            null,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Box(
                        Modifier.width(90.dp)
                            .height(8.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(4.dp),
                            )
                    )
                    Box(
                        Modifier.width(60.dp)
                            .height(8.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(4.dp),
                            )
                    )
                    val barColor = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxWidth().weight(1f)) {
                        val heights = listOf(.33f, .60f, .47f, .81f, .59f, .95f)
                        val step = size.width / heights.size
                        heights.forEachIndexed { i, h ->
                            drawRoundRect(
                                barColor.copy(alpha = if (i == 5) 1f else .35f),
                                androidx.compose.ui.geometry.Offset(
                                    i * step,
                                    size.height * (1 - h),
                                ),
                                androidx.compose.ui.geometry.Size(step * .65f, size.height * h),
                                androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
                            )
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "WELCOME TO CALLTRACK PRO",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Every call.\nEvery insight.",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Stay on top of your calling team's performance with a simple, powerful mobile workspace.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (error.isNotBlank()) Hint(error, true)
        Button(
            signIn,
            Modifier.fillMaxWidth().heightIn(min = 54.dp),
            enabled = enabled,
            shape = RoundedCornerShape(15.dp),
        ) {
            if (!enabled) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(if (enabled) "Continue with Google" else "Signing in…")
        }
        Text(
            "By continuing you agree to the Terms and Privacy Policy. No call audio is recorded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton({ legal = "privacy" }) { Text("Privacy Policy") }
            TextButton({ legal = "terms" }) { Text("Terms") }
        }
    }
    legal?.let { LegalDialog(it == "terms") { legal = null } }
}

@Composable
fun WorkspaceRecovery(state: AppState, model: AppViewModel) {
    Page(
        "Let's reconnect",
        "${state.authenticatedEmail.ifBlank { "Your Google account" }} is signed in.",
    ) {
        Panel("Workspace unavailable") {
            Icon(
                Icons.Default.CloudOff,
                null,
                Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(state.workspaceError)
            Button({ model.reload() }, Modifier.fillMaxWidth(), enabled = state.busy == 0) {
                Text(if (state.busy == 0) "Retry workspace" else "Connecting…")
            }
            OutlinedButton(
                { model.signOut() },
                Modifier.fillMaxWidth(),
                enabled = state.busy == 0,
            ) {
                Text("Use another account")
            }
        }
    }
}

@Composable
fun WorkspaceScreen(state: AppState, model: AppViewModel) {
    var create by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    val scan =
        rememberLauncherForActivityResult(ScanContract()) { result ->
            result.contents?.let {
                token = it
                model.inspectInvite(it)
            }
        }
    Page("Your workspace", "Create a company or join with your personal employee invitation.") {
        Button({ create = true }, Modifier.fillMaxWidth()) { Text("Create company") }
        Panel("Join your team") {
            Field(
                "Invitation code",
                token,
                {
                    token = it
                    model.clearPreview()
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    scan.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan your employee invitation")
                            .setBeepEnabled(false)
                    )
                }) {
                    Text("Scan QR")
                }
                Button(
                    { model.inspectInvite(token.trim()) },
                    enabled = token.isNotBlank() && state.busy == 0,
                ) {
                    Text("Verify code")
                }
            }
            if (state.preview.isNotEmpty()) {
                Text(state.preview.str("companyName"), style = MaterialTheme.typography.titleLarge)
                Text("${state.preview.str("employeeName")} · ${state.preview.str("employeeCode")}")
                Hint(MONITORING)
                Row {
                    Checkbox(consent, { consent = it })
                    Text(
                        "I understand and consent to business call metadata monitoring.",
                        Modifier.padding(top = 8.dp),
                    )
                }
                Button(
                    { model.join(state.preview.str("token")) },
                    enabled = consent && state.busy == 0,
                ) {
                    Text("Join company")
                }
            }
        }
        TextButton({ model.signOut() }) { Text("Sign out") }
    }
    if (create)
        CompanyForm(emptyMap(), { create = false }, state.busy > 0) {
            model.createCompany(it) { create = false }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyForm(initial: Row, close: () -> Unit, busy: Boolean = false, save: (Row) -> Unit) {
    val fields =
        listOf(
            "name" to "Company name",
            "industry" to "Industry",
            "country" to "Country · ISO code",
            "timeZone" to "Time zone · e.g. Asia/Dubai",
            "phone" to "Contact mobile",
            "email" to "Company email",
            "address" to "Address",
            "workingHours" to "Working hours",
            "retentionDays" to "Retention · days (7–3650)",
        )
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            fields.forEach { put(it.first, initial[it.first]?.toString().orEmpty()) }
            if (initial.isEmpty()) {
                put("country", "AE")
                put("timeZone", "Asia/Dubai")
                put("retentionDays", "365")
            }
        }
    }
    var policy by remember {
        mutableStateOf(initial.str("initialSyncPolicy").ifBlank { "DAY_START" })
    }
    var notifications by remember { mutableStateOf(initial["notificationsEnabled"] != false) }
    var error by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (initial.isEmpty()) "Create company" else "Company settings",
                style = MaterialTheme.typography.headlineSmall,
            )
            fields.forEach { (key, label) ->
                Field(
                    label,
                    values[key].orEmpty(),
                    { values[key] = it },
                    enabled = key != "timeZone" || initial.isEmpty(),
                )
            }
            Text("Initial monitoring boundary")
            Choices(listOf("DAY_START", "JOIN_TIME"), policy, { policy = it })
            Text(
                "Applies to employees who join after this change. Company timezone is fixed after creation to preserve report boundaries.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                Switch(notifications, { notifications = it })
                Text("Device health notifications", Modifier.padding(12.dp))
            }
            Text(
                "Device policy: one primary device per employee. Invitations are single-use and expire after 7 days by default.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (error.isNotEmpty()) Hint(error, true)
            Button(
                {
                    if (
                        values["name"].isNullOrBlank() ||
                            values["retentionDays"]?.toIntOrNull() !in 7..3650
                    )
                        error = "Enter a company name and retention from 7 to 3650 days."
                    else
                        save(
                            values.toMap() +
                                mapOf(
                                    "retentionDays" to values["retentionDays"]?.toInt(),
                                    "initialSyncPolicy" to policy,
                                    "notificationsEnabled" to notifications,
                                )
                        )
                },
                Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text(if (busy) "Saving…" else "Save company")
            }
        }
    }
}
