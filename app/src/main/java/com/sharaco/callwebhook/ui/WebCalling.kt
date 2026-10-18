package com.sharaco.callwebhook.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.sharaco.callwebhook.domain.str
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/** Foreground-only companion. Every native dial action requires a visible user tap. */
@Composable
fun WebCallingControl(state: AppState) {
    val session = state.session ?: return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var enabled by remember(session.uid, session.companyId) { mutableStateOf(false) }
    val pairId = remember(session.uid, session.companyId, enabled) { UUID.randomUUID().toString() }
    var error by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val db = remember { FirebaseFirestore.getInstance() }
    val company = db.collection("companies").document(session.companyId)
    val pairing = company.collection("webDevicePairings").document(session.uid)
    val deviceId = state.installation
    fun failure(e: Exception) {
        error = "${(e as? FirebaseFirestoreException)?.code ?: "WEB_CALL_ERROR"}: ${e.message}"
    }
    LaunchedEffect(enabled, pairId, deviceId) {
        if (!enabled) return@LaunchedEffect
        if (deviceId.isBlank() || session.member.str("activeDeviceId") != deviceId) {
            error = "Register this phone in Sync before enabling web calling."
            enabled = false
            return@LaunchedEffect
        }
        val expires = System.currentTimeMillis() + 28_740_000L
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val listener =
                company
                    .collection("callRequests")
                    .whereEqualTo("employeeUid", session.uid)
                    .whereEqualTo("pairingId", pairId)
                    .whereEqualTo("status", "CREATED")
                    .whereGreaterThan("expiresAt", System.currentTimeMillis())
                    .limit(20)
                    .addSnapshotListener { snapshot, e ->
                        if (e != null) {
                            failure(e)
                            return@addSnapshotListener
                        }
                        val request =
                            snapshot?.documents?.firstOrNull {
                                it.getString("deviceId") == deviceId &&
                                    (it.getLong("expiresAt") ?: 0) > System.currentTimeMillis()
                            }
                        pending = request?.let { it.id to it.getString("phoneNumber").orEmpty() }
                    }
            try {
                while (isActive) {
                    pairing
                        .set(
                            mapOf(
                                "employeeUid" to session.uid,
                                "deviceId" to deviceId,
                                "pairingId" to pairId,
                                "enabled" to true,
                                "expiresAt" to expires,
                                "readyUntil" to System.currentTimeMillis() + 80_000L,
                                "updatedAt" to FieldValue.serverTimestamp(),
                            )
                        )
                        .await()
                    delay(30_000)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure(e)
                enabled = false
            } finally {
                listener.remove()
                pending = null
                // Best effort when backgrounded; rules also enforce the short readiness expiry.
                pairing.update(
                    mapOf("readyUntil" to 0L, "updatedAt" to FieldValue.serverTimestamp())
                )
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(
            onClick = {
                error = ""
                enabled = !enabled
            }
        ) {
            Text(if (enabled) "Web calling enabled · keep app open" else "Enable web calling")
        }
    }
    if (error.isNotBlank())
        Text(error, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
    val request = pending
    if (enabled && request != null) {
        fun consume(dial: Boolean) {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    val intent =
                        Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", request.second, null))
                    if (dial && intent.resolveActivity(context.packageManager) == null)
                        kotlin.error("No dialer is available.")
                    db.runTransaction { tx ->
                            val r = company.collection("callRequests").document(request.first)
                            val data = tx.get(r)
                            check(
                                data.getString("pairingId") == pairId &&
                                    data.getString("status") == "CREATED"
                            ) {
                                "Request already handled."
                            }
                            check((data.getLong("expiresAt") ?: 0) > System.currentTimeMillis()) {
                                "Request expired. Send a new request from the web."
                            }
                            check(data.getString("phoneNumber") == request.second) {
                                "Request changed."
                            }
                            tx.update(r, "status", if (dial) "DIALER_OPENED" else "CANCELLED")
                        }
                        .await()
                    pending = null
                    if (dial) context.startActivity(intent)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failure(e)
                    pending = null
                } finally {
                    busy = false
                }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!busy) consume(false) },
            title = { Text("Call requested from your web workspace") },
            text = {
                Text(
                    "Open the phone dialer for ${request.second}? You control whether the call is placed."
                )
            },
            confirmButton = {
                TextButton(onClick = { consume(true) }, enabled = !busy) { Text("Open dialer") }
            },
            dismissButton = {
                TextButton(onClick = { consume(false) }, enabled = !busy) { Text("Decline") }
            },
        )
    }
}
