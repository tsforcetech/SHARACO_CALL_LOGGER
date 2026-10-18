package com.sharaco.callwebhook.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.sharaco.callwebhook.domain.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Shares the Spark atomic outcome protocol with the web app. */
@Composable
fun CrmScreen(state: AppState) {
    val session = state.session ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val company =
        remember(session.companyId) {
            FirebaseFirestore.getInstance().collection("companies").document(session.companyId)
        }
    var leads by remember { mutableStateOf<List<Row>>(emptyList()) }
    var statuses by remember { mutableStateOf<List<Row>>(emptyList()) }
    var selected by remember { mutableStateOf<Row?>(null) }
    var claimId by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var remarks by remember { mutableStateOf("") }
    var followUp by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var saved by remember { mutableStateOf(false) }
    fun failed(e: Exception) {
        error = "${(e as? FirebaseFirestoreException)?.code ?: "CRM_ERROR"}: ${e.message}"
    }
    DisposableEffect(company, session.uid) {
        val first =
            company
                .collection("leads")
                .whereEqualTo("assignedTo", session.uid)
                .limit(200)
                .addSnapshotListener { snapshot, e ->
                    loading = false
                    if (e != null) {
                        failed(e)
                        leads = emptyList()
                    } else
                        leads =
                            snapshot
                                ?.documents
                                ?.map { it.data.orEmpty() + ("id" to it.id) }
                                .orEmpty()
                }
        val second =
            company.collection("dispositionStatuses").addSnapshotListener { snapshot, e ->
                if (e != null) failed(e)
                else
                    statuses =
                        snapshot?.documents?.map { it.data.orEmpty() + ("id" to it.id) }.orEmpty()
            }
        onDispose {
            first.remove()
            second.remove()
        }
    }
    suspend fun claim(row: Row) {
        val sid = UUID.randomUUID().toString()
        val leadRef = company.collection("leads").document(row.str("id"))
        val data =
            FirebaseFirestore.getInstance()
                .runTransaction { tx ->
                    val lead = tx.get(leadRef).data ?: kotlin.error("Lead no longer exists.")
                    check(
                        lead["assignedTo"] == session.uid &&
                            lead["terminal"] != true &&
                            lead["doNotCall"] != true
                    ) {
                        "Lead is unavailable or reassigned."
                    }
                    val expires = System.currentTimeMillis() + 840_000
                    tx.update(
                        leadRef,
                        mapOf(
                            "claimedBy" to session.uid,
                            "claimSessionId" to sid,
                            "claimExpiresAt" to expires,
                        ),
                    )
                    tx.set(
                        company.collection("callingSessions").document(sid),
                        mapOf(
                            "employeeUid" to session.uid,
                            "leadId" to leadRef.id,
                            "status" to "READY",
                            "createdAt" to FieldValue.serverTimestamp(),
                            "expiresAt" to expires,
                        ),
                    )
                    lead + ("id" to leadRef.id)
                }
                .await()
        selected = data
        claimId = sid
        status = ""
        remarks = ""
        followUp = ""
        saved = false
    }
    fun open(row: Row) {
        busy = true
        error = ""
        scope.launch {
            try {
                claim(row)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failed(e)
            } finally {
                busy = false
            }
        }
    }
    fun save() {
        val row = selected ?: return
        val sid = claimId
        busy = true
        error = ""
        scope.launch {
            try {
                val due =
                    if (followUp.isBlank()) null
                    else
                        LocalDateTime.parse(
                                followUp,
                                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                            )
                            .atZone(ZoneId.of(session.zone))
                            .toInstant()
                            .toEpochMilli()
                FirebaseFirestore.getInstance()
                    .runTransaction { tx ->
                        val leadRef = company.collection("leads").document(row.str("id"))
                        val lead = tx.get(leadRef).data ?: kotlin.error("Lead no longer exists.")
                        if (lead["lastAttemptId"] == sid) {
                            val prior =
                                tx.get(company.collection("crmCallAttempts").document(sid)).data
                            check(
                                prior?.get("dispositionId") == status &&
                                    prior?.get("remarks") == remarks &&
                                    prior?.get("nextFollowUpAt") == due
                            ) {
                                "This outcome was already saved with different details. Reopen the customer."
                            }
                            return@runTransaction
                        }
                        check(
                            lead["assignedTo"] == session.uid &&
                                lead["version"] == row["version"] &&
                                lead["claimSessionId"] == sid
                        ) {
                            "Lead changed. Reopen it before saving."
                        }
                        val outcome =
                            tx.get(company.collection("dispositionStatuses").document(status)).data
                                ?: kotlin.error("Choose an outcome.")
                        check(outcome["active"] == true) { "This outcome is no longer available." }
                        check(outcome["requiresRemarks"] != true || remarks.isNotBlank()) {
                            "Enter remarks for this outcome."
                        }
                        check(outcome["requiresFollowUp"] != true || due != null) {
                            "Enter a follow-up date and time."
                        }
                        check(due == null || due > System.currentTimeMillis()) {
                            "Follow-up must be in the future."
                        }
                        val attempt =
                            mapOf(
                                "attemptId" to sid,
                                "companyId" to session.companyId,
                                "leadId" to leadRef.id,
                                "employeeUid" to session.uid,
                                "campaignId" to (lead["campaignId"] ?: ""),
                                "sessionId" to sid,
                                "dispositionId" to status,
                                "remarks" to remarks,
                                "tags" to emptyList<String>(),
                                "nextFollowUpAt" to due,
                                "createdAt" to FieldValue.serverTimestamp(),
                                "verificationStatus" to "UNVERIFIED",
                                "matchedCallId" to null,
                                "durationSeconds" to null,
                                "connectionStatus" to "UNKNOWN",
                            )
                        tx.set(company.collection("crmCallAttempts").document(sid), attempt)
                        tx.set(
                            company.collection("leadActivities").document(sid),
                            attempt + ("type" to "DISPOSITION"),
                        )
                        tx.update(
                            company.collection("callingSessions").document(sid),
                            mapOf("status" to "DISPOSITION_SAVED", "attemptId" to sid),
                        )
                        tx.update(
                            leadRef,
                            mapOf(
                                "status" to status,
                                "terminal" to outcome["isTerminal"],
                                "doNotCall" to (status == "do_not_call"),
                                "remarks" to remarks,
                                "tags" to emptyList<String>(),
                                "nextFollowUpAt" to due,
                                "lastContactAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp(),
                                "version" to ((lead["version"] as Number).toLong() + 1),
                                "claimedBy" to "",
                                "claimSessionId" to "",
                                "claimExpiresAt" to 0L,
                                "lastAttemptId" to sid,
                            ),
                        )
                        if (due != null)
                            tx.set(
                                company.collection("followUps").document(sid),
                                mapOf(
                                    "leadId" to leadRef.id,
                                    "employeeUid" to session.uid,
                                    "dueAt" to due,
                                    "status" to "pending",
                                    "remarks" to remarks,
                                    "createdAt" to FieldValue.serverTimestamp(),
                                ),
                            )
                    }
                    .await()
                saved = true
                selected = null
                val next =
                    leads.firstOrNull {
                        it.str("id") != row.str("id") &&
                            !it.flag("terminal") &&
                            !it.flag("doNotCall")
                    }
                if (next != null) claim(next)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failed(e)
            } finally {
                busy = false
            }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("My calling workspace", style = MaterialTheme.typography.headlineSmall)
        Text("Assigned leads and outcomes are shared with your web workspace.")
        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (saved) Text("Outcome saved.")
        val row = selected
        if (row == null) {
            if (!loading && leads.isEmpty()) Text("No leads assigned yet.")
            Text("Showing up to 200 assigned leads.", style = MaterialTheme.typography.bodySmall)
            leads
                .filter { !it.flag("terminal") && !it.flag("doNotCall") }
                .forEach { lead ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                lead.str("customerName"),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text("${lead.str("primaryPhone")} · ${lead.str("status")}")
                            TextButton(onClick = { open(lead) }, enabled = !busy) {
                                Text("Open customer")
                            }
                        }
                    }
                }
        } else {
            Text(row.str("customerName"), style = MaterialTheme.typography.headlineMedium)
            Text(row.str("companyName"))
            Text(row.str("primaryPhone"))
            if (row.str("remarks").isNotBlank()) Text("Previous remarks: ${row.str("remarks")}")
            Button(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_DIAL,
                                Uri.fromParts("tel", row.str("primaryPhone"), null),
                            )
                        )
                    } catch (e: Exception) {
                        failed(e)
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Call customer")
            }
            Text(
                "Choose the outcome. Call verification is pending.",
                style = MaterialTheme.typography.bodySmall,
            )
            statuses
                .filter { it.flag("active") }
                .forEach { outcome ->
                    FilterChip(
                        selected = status == outcome.str("id"),
                        onClick = { status = outcome.str("id") },
                        label = { Text(outcome.str("label")) },
                        enabled = !busy,
                    )
                }
            OutlinedTextField(
                remarks,
                { if (it.length <= 1000) remarks = it },
                label = { Text("Remarks") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                followUp,
                { followUp = it },
                label = { Text("Follow-up: yyyy-MM-dd HH:mm") },
                supportingText = { Text(session.zone) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = ::save,
                enabled = !busy && status.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save & next")
            }
            TextButton(onClick = { selected = null }, enabled = !busy) { Text("Back to queue") }
        }
    }
}
