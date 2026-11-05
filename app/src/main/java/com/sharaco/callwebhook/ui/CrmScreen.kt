package com.sharaco.callwebhook.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.firebase.Timestamp
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
    var queuePage by remember { mutableStateOf(0) }
    var queueView by remember { mutableStateOf("pending") }
    var saved by remember { mutableStateOf(false) }
    val queueRows = leads.filter { if (queueView == "pending") pendingLead(it) else !pendingLead(it) }
    LaunchedEffect(queueView) { queuePage = 0 }
    LaunchedEffect(queueRows.size) { queuePage = queuePage.coerceAtMost(((queueRows.size - 1) / 50).coerceAtLeast(0)) }
    fun failed(e: Exception) {
        error = "${(e as? FirebaseFirestoreException)?.code ?: "CRM_ERROR"}: ${e.message}"
    }
    DisposableEffect(company, session.uid) {
        val first =
            company
                .collection("leads")
                .whereEqualTo("assignedTo", session.uid)
                .limit(10000)
                .addSnapshotListener { snapshot, e ->
                    loading = false
                    if (e != null) {
                        failed(e)
                        leads = emptyList()
                    } else if (snapshot != null && snapshot.size() >= 10000) {
                        error = "Lead workspace limit reached (10,000). Contact your administrator to archive old leads."
                        leads = emptyList()
                    } else {
                        leads = orderedLeads(snapshot?.documents?.map { doc ->
                            doc.data.orEmpty().mapValues { (_, value) -> if (value is Timestamp) value.toDate().time else value } + ("id" to doc.id)
                        }.orEmpty())
                    }
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
                leads = leads.map { if (it.str("id") == row.str("id")) it + mapOf("lastAttemptId" to sid, "status" to status, "lastContactAt" to System.currentTimeMillis()) else it }
                val next =
                    leads.firstOrNull {
                        it.str("id") != row.str("id") &&
                            pendingLead(it)
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
        Text("Work through customers in upload order. Saved outcomes leave the pending list.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = queueView == "pending", onClick = { queueView = "pending" }, label = { Text("Pending (${leads.count(::pendingLead)})") })
            FilterChip(selected = queueView == "updated", onClick = { queueView = "updated" }, label = { Text("Updated (${leads.count { !pendingLead(it) }})") })
        }
        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (saved) Text("Status saved. Customer moved out of pending calls.")
        val row = selected
        if (row == null) {
            if (!loading && leads.isEmpty()) Text("No leads assigned yet.")
            if (!loading && leads.none { if (queueView == "pending") pendingLead(it) else !pendingLead(it) }) Text("No customers in this list.")
            if (queueRows.size > 50) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { queuePage-- }, enabled = queuePage > 0 && !busy) { Text("Previous") }
                Text("Page ${queuePage + 1}")
                TextButton(onClick = { queuePage++ }, enabled = (queuePage + 1) * 50 < queueRows.size && !busy) { Text("Next") }
            }
            queueRows.drop(queuePage * 50).take(50)
                .forEach { lead ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                lead.str("customerName"),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text("${lead.str("primaryPhone")} · ${lead.str("status")}")
                            TextButton(onClick = { open(lead) }, enabled = !busy && !lead.flag("terminal") && !lead.flag("doNotCall")) {
                                Text("Open customer")
                            }
                        }
                    }
                }
        } else {
          Dialog(onDismissRequest = { if (!busy) selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
           Surface(modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 760.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp)) {
             Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Update customer", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { selected = null }, enabled = !busy) { Text("Close") }
             }
             Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            Text("How did the conversation go?", style = MaterialTheme.typography.titleMedium)
            statuses.filter { it.flag("active") }.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { outcome ->
                        FilterChip(
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            selected = status == outcome.str("id"),
                            onClick = { status = outcome.str("id") },
                            label = { Text((if (status == outcome.str("id")) "✓ " else "") + outcome.str("label")) },
                            enabled = !busy,
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            OutlinedTextField(
                remarks,
                { if (it.length <= 1000) remarks = it },
                label = { Text("Remarks") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            val needsFollowUp = statuses.firstOrNull { it.str("id") == status }?.flag("requiresFollowUp") == true
            Text(if (needsFollowUp) "Follow-up (required)" else "Follow-up (optional)")
            OutlinedButton(onClick = {
                val now = LocalDateTime.now(ZoneId.of(session.zone))
                DatePickerDialog(context, { _, y, m, d ->
                    TimePickerDialog(context, { _, hour, minute ->
                        followUp = LocalDateTime.of(y, m + 1, d, hour, minute).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    }, now.hour, now.minute, true).show()
                }, now.year, now.monthValue - 1, now.dayOfMonth).show()
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (followUp.isBlank()) "Choose date & time" else followUp)
            }
            Text("Time zone: ${session.zone}", style = MaterialTheme.typography.bodySmall)
            if (followUp.isNotBlank()) TextButton(onClick = { followUp = "" }, enabled = !busy) { Text("Clear follow-up") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
             }
             HorizontalDivider(Modifier.padding(vertical = 12.dp))
             Text("Save moves this customer out of pending calls.", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = ::save,
                enabled = !busy && status.isNotBlank() && (statuses.firstOrNull { it.str("id") == status }?.flag("requiresFollowUp") != true || followUp.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "Saving…" else "Save & next customer")
            }
                        }
           }
          }
        }
    }
}
