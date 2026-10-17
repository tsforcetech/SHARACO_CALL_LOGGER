package com.sharaco.callwebhook.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Base64
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.sharaco.callwebhook.BuildConfig
import com.sharaco.callwebhook.R
import com.sharaco.callwebhook.domain.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(state: AppState, model: AppViewModel, navigate: (String) -> Unit) {
    val s = state.session ?: return
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val notifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            model.permissionsChanged()
        }
    var companyForm by remember { mutableStateOf(false) }
    var legal by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val logo =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null)
                scope.launch {
                    try {
                        val encoded =
                            withContext(Dispatchers.IO) {
                                val bitmap =
                                    if (android.os.Build.VERSION.SDK_INT >= 28)
                                        ImageDecoder.decodeBitmap(
                                            ImageDecoder.createSource(context.contentResolver, uri)
                                        ) { decoder, info, _ ->
                                            val ratio =
                                                256.0 / maxOf(info.size.width, info.size.height)
                                            decoder.setTargetSize(
                                                (info.size.width * ratio).toInt().coerceAtLeast(1),
                                                (info.size.height * ratio).toInt().coerceAtLeast(1),
                                            )
                                        }
                                    else
                                        context.contentResolver.openInputStream(uri).use {
                                            android.graphics.BitmapFactory.decodeStream(it)
                                        } ?: kotlin.error("Unable to read image.")
                                val output = ByteArrayOutputStream()
                                val small = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
                                small.compress(Bitmap.CompressFormat.JPEG, 80, output)
                                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                            }
                        model.action("updateCompany", mapOf("logo" to encoded))
                    } catch (e: Exception) {
                        model.message("Unable to read this image. Choose a smaller JPG or PNG.")
                    }
                }
        }
    Page(
        if (s.manager) "More" else "Profile",
        "${s.member.str("name")} · ${s.member.str("email")}",
    ) {
        Panel("Your account") {
            Text("${s.company.str("name")} · ${s.role}")
            if (s.member.str("code").isNotEmpty()) Text("Employee ID: ${s.member.str("code")}")
            Text("${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}")
            if (s.role != "OWNER")
                TextButton({ navigate("sync") }) { Text("Monitoring & device setup") }
        }
        if (s.manager)
            Panel("Company tools") {
                if (android.os.Build.VERSION.SDK_INT >= 33 && !state.notificationPermission)
                    TextButton({
                        notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }) {
                        Text("Enable company notifications")
                    }
                TextButton({ navigate("devices") }) { Text("Devices & sync health") }
                TextButton({ navigate("notifications") }) { Text("Notifications") }
                if (s.role == "OWNER") {
                    TextButton({ companyForm = true }) { Text("Company settings") }
                    TextButton({ logo.launch("image/*") }) { Text("Upload company logo") }
                    TextButton({ navigate("audit") }) { Text("Audit history") }
                }
            }
        Panel("Appearance") {
            Choices(listOf("SYSTEM", "LIGHT", "DARK"), state.theme, { model.theme(it) })
        }
        Panel("Privacy & account") {
            TextButton({ legal = "privacy" }) { Text("Privacy Policy") }
            TextButton({ legal = "terms" }) { Text("Terms of use") }
            TextButton({ confirm = "SIGN OUT" }) { Text("Sign out") }
            if (s.role != "OWNER") TextButton({ confirm = "LEAVE" }) { Text("Leave company") }
            TextButton({ confirm = "DELETE ACCOUNT" }) { Text("Delete personal account") }
            if (s.role == "OWNER")
                TextButton({ confirm = "DELETE COMPANY" }) { Text("Delete company") }
        }
    }
    if (companyForm)
        CompanyForm(s.company, { companyForm = false }) {
            model.action("updateCompany", it) { companyForm = false }
        }
    legal?.let { LegalDialog(it == "terms") { legal = null } }
    confirm?.let { action ->
        val message =
            when (action) {
                "SIGN OUT" ->
                    "Local call history and pending calls will be cleared. Synchronize pending calls before signing out; eligible calls can be recollected after this account registers again."
                "LEAVE" ->
                    "Your company access and device uploads will stop. Historical company call records remain under the company's retention policy."
                "DELETE COMPANY" ->
                    "All company members lose access immediately. A background job permanently deletes the company and its business records. Google sign-in will be requested again. This cannot be undone."
                else ->
                    "Your personal sign-in account will be deleted. Company business records remain under retention. Owners must delete their company first. Google sign-in will be requested again."
            }
        Confirm(
            action.lowercase().replaceFirstChar { it.uppercase() },
            message,
            if (action.startsWith("DELETE")) "DELETE" else action,
            { confirm = null },
        ) {
            when (action) {
                "SIGN OUT" -> model.signOut()
                "LEAVE" -> model.leave()
                else -> activity?.let { model.delete(it, action == "DELETE COMPANY") }
            }
        }
    }
}

@Composable
fun EventScreen(state: AppState, audit: Boolean) {
    val rows = if (audit) state.audit else state.notifications
    Page(if (audit) "Audit history" else "Notifications", "Most recent 100 events") {
        if (rows.isEmpty()) Hint("No events yet.")
        rows.forEach { row ->
            Panel(if (audit) row.str("action").replace('_', ' ') else row.str("title")) {
                Text(
                    if (audit) "Actor: ${row.str("actorUid")}\nTarget: ${row.str("targetId")}"
                    else row.str("body")
                )
                Text(
                    dateTime(row.num("timestamp"), state.session?.zone ?: "UTC"),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
