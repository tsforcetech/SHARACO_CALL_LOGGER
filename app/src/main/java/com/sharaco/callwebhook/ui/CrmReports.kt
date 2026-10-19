package com.sharaco.callwebhook.ui

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.Timestamp
import com.google.firebase.firestore.*
import com.sharaco.callwebhook.domain.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private val crmMetrics = linkedMapOf("calls" to "Calls", "outgoing" to "Outgoing", "connected" to "Connected", "minutes" to "Talk minutes", "interested" to "Interested", "converted" to "Converted")

@Composable
private fun CrmSelect(label: String, value: String, options: List<Pair<String, String>>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) {
            Text("$label: ${options.firstOrNull { it.first == value }?.second ?: "Choose"}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { change(key); expanded = false }) }
        }
    }
}

@Composable
fun CrmReportsScreen(state: AppState, navigate: (String) -> Unit) {
    val s = state.session ?: return
    key(s.scope, s.role) { CrmReportsContent(s, navigate) }
}

@Composable
private fun CrmReportsContent(s: Session, navigate: (String) -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }
    val company = remember(s.companyId) { db.collection("companies").document(s.companyId) }
    val zone = remember(s.zone) { ZoneId.of(s.zone) }
    val today = remember { LocalDate.now(zone) }
    var from by remember { mutableStateOf(today.toString()) }
    var to by remember { mutableStateOf(today.toString()) }
    var start by remember { mutableLongStateOf(today.atStartOfDay(zone).toInstant().toEpochMilli()) }
    var end by remember { mutableLongStateOf(today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()) }
    var employee by remember { mutableStateOf(if (s.manager) "" else s.uid) }
    var tab by remember { mutableStateOf("Overview") }
    val data = remember { mutableStateMapOf<String, List<Row>>() }
    val errors = remember { mutableStateMapOf<String, String>() }
    val cached = remember { mutableStateMapOf<String, Boolean>() }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var targetEmployee by remember { mutableStateOf("") }
    var metric by remember { mutableStateOf("calls") }
    var goal by remember { mutableStateOf("") }
    var period by remember { mutableStateOf("daily") }
    var targetDate by remember { mutableStateOf(today.toString()) }
    var search by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var assigning by remember { mutableStateOf<Row?>(null) }
    var assignee by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun failed(e: Exception) {
        if (e is CancellationException) throw e
        Log.e("CrmReports", "CRM operation failed", e)
        message = "${(e as? FirebaseFirestoreException)?.code ?: "INVALID_INPUT"}: ${e.message}"
    }
    DisposableEffect(company, employee, start, end) {
        data.clear(); errors.clear(); cached.clear()
        fun watch(kind: String, query: Query): ListenerRegistration = query.limit(10000).addSnapshotListener(MetadataChanges.INCLUDE) { snap, e ->
            if (e != null) {
                Log.e("CrmReports", "$kind: ${e.code}", e)
                errors[kind] = "${e.code}: ${e.message}"
                data.remove(kind)
            } else if (snap != null) {
                errors.remove(kind)
                cached[kind] = snap.metadata.isFromCache || snap.metadata.hasPendingWrites()
                data[kind] = snap.documents.map { doc -> doc.data.orEmpty().mapValues { (_, v) -> if (v is Timestamp) v.toDate().time else v } + ("id" to doc.id) }
            }
        }
        fun scoped(kind: String, field: String = "employeeUid"): Query {
            val q = company.collection(kind)
            return if (employee.isBlank()) q else q.whereEqualTo(field, employee)
        }
        val listeners = mutableListOf(
            watch("call_logs", scoped("call_logs", "uid").whereGreaterThanOrEqualTo("startedAt", start).whereLessThan("startedAt", end).orderBy("startedAt")),
            watch("crmCallAttempts", scoped("crmCallAttempts").whereGreaterThanOrEqualTo("createdAt", Timestamp(Date(start))).whereLessThan("createdAt", Timestamp(Date(end))).orderBy("createdAt")),
            watch("targetDefinitions", scoped("targetDefinitions")),
            watch("leads", scoped("leads", "assignedTo")),
            watch("followUps", scoped("followUps")),
        )
        if (s.manager) listeners += watch("members", company.collection("members"))
        else data["members"] = listOf(s.member + ("id" to s.uid))
        onDispose { listeners.forEach { it.remove() } }
    }
    fun complete(vararg kinds: String) = kinds.all { data.containsKey(it) && !errors.containsKey(it) && data[it]!!.size < 10000 && cached[it] != true }
    val calls = data["call_logs"].orEmpty()
    val attempts = data["crmCallAttempts"].orEmpty()
    val members = data["members"].orEmpty().filter { it.flag("approved") }
    fun name(uid: String) = members.firstOrNull { it.str("id") == uid }?.str("name")?.ifBlank { uid } ?: uid
    val options = members.map { it.str("id") to name(it.str("id")) }
    val ready = complete("call_logs", "crmCallAttempts")
    Page("Targets & lead reports", "Live Firestore updates · ${s.zone}") {
        TextButton({ navigate("calls") }) { Text("Open existing call history") }
        CrmSelect("View", tab, listOf("Overview", "Targets", "Assigned leads", "Follow-ups", "Outcomes").map { it to it }) { tab = it; page = 0 }
        Panel("Report dates") {
            OutlinedTextField(from, { from = it }, label = { Text("From · yyyy-MM-dd") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(to, { to = it }, label = { Text("To · yyyy-MM-dd") }, modifier = Modifier.fillMaxWidth())
            TextButton({
                try {
                    val a = LocalDate.parse(from); val b = LocalDate.parse(to)
                    require(ChronoUnit.DAYS.between(a, b) in 0..92) { "Choose up to 93 days, with the end after the start." }
                    start = a.atStartOfDay(zone).toInstant().toEpochMilli(); end = b.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(); page = 0; message = ""
                } catch (e: Exception) { failed(e) }
            }) { Text("Apply dates") }
            Text("Showing ${dateTime(start, s.zone)} – ${dateTime(end - 1, s.zone)}")
            if (s.manager) CrmSelect("Employee", employee, listOf("" to "All employees") + options) { employee = it; page = 0 }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        errors.forEach { (kind, error) -> Text("$kind: $error", color = MaterialTheme.colorScheme.error) }
        if (cached.values.any { it }) Text("Waiting for server confirmation. Cached data may be out of date; achievement totals are withheld.")
        data.filterValues { it.size >= 10000 }.keys.forEach { Text("$it reached 10,000 records. Results may be incomplete; narrow the employee or date filter.") }
        if (tab == "Overview") {
            Panel("Call & outcome performance") {
                if (!ready) Text("Waiting for complete call and outcome data…")
                else crmMetrics.forEach { (key, label) -> Text("$label: %.1f".format(targetAchievement(key, calls, attempts))) }
                Text("Interested and converted are recorded CRM outcomes, not verified telephone connections.", style = MaterialTheme.typography.bodySmall)
            }
            Panel("Assigned workload · current") {
                if (complete("leads")) {
                    val leads = data["leads"].orEmpty()
                    Text("${leads.size} assigned · ${leads.count { !it.flag("terminal") && !it.flag("doNotCall") }} open · ${leads.count { it.flag("terminal") }} closed")
                    leads.groupBy { it.str("status") }.forEach { (status, rows) -> Text("${status.ifBlank { "Unspecified" }}: ${rows.size}") }
                } else Text("Waiting for complete assigned-lead data…")
            }
            if (ready && complete("leads")) {
                val uids = (members.map { it.str("id") } + calls.map { it.str("uid") } + attempts.map { it.str("employeeUid") } + data["leads"].orEmpty().map { it.str("assignedTo") }).distinct().filter { employee.isBlank() || it == employee }
                val rows = uids.map { uid ->
                    val c = calls.filter { it.str("uid") == uid }; val a = attempts.filter { it.str("employeeUid") == uid }
                    listOf(name(uid), data["leads"].orEmpty().count { it.str("assignedTo") == uid }.toString()) + crmMetrics.keys.map { "%.1f".format(targetAchievement(it, c, a)) }
                }
                Panel("Employee performance") {
                    val tableRows: List<com.sharaco.callwebhook.domain.Row> = uids.map { uid ->
                        val c = calls.filter { it.str("uid") == uid }
                        val a = attempts.filter { it.str("employeeUid") == uid }
                        mapOf("name" to name(uid), "assigned" to data["leads"].orEmpty().count { it.str("assignedTo") == uid }) + crmMetrics.keys.associateWith { targetAchievement(it, c, a) }
                    }
                    DataTable(tableRows, TableColumn("Employee", "name") { it.str("name") },
                        listOf(TableColumn("Assigned now", "assigned") { number(it.num("assigned")) }) + crmMetrics.map { (key, label) -> TableColumn(label, key) { "%.1f".format(it.dec(key)) } }) { }
                    listOf(false, true).forEach { pdf -> TextButton({ scope.launch { try { ReportExport.share(context, "CRM_performance", listOf("Employee", "Assigned now") + crmMetrics.values, rows, pdf) } catch (e: Exception) { failed(e) } } }) { Text(if (pdf) "Export PDF" else "Export CSV") } }
                }
            }
        }
        if (tab == "Targets" || tab == "Overview") {
            if (tab == "Targets" && s.manager) Panel("Create dated target") {
                CrmSelect("Employee", targetEmployee, options) { targetEmployee = it }
                CrmSelect("Metric", metric, crmMetrics.map { it.key to it.value }) { metric = it }
                CrmSelect("Period", period, listOf("daily", "weekly", "monthly").map { it to it }) { period = it }
                OutlinedTextField(goal, { goal = it }, label = { Text("Target value") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(targetDate, { targetDate = it }, label = { Text("Start · yyyy-MM-dd") }, modifier = Modifier.fillMaxWidth())
                Button({ scope.launch {
                    busy = true
                    try {
                        val value = goal.toDoubleOrNull(); require(value != null && value.isFinite() && value > 0) { "Enter a positive target." }
                        require(members.any { it.str("id") == targetEmployee }) { "Choose an approved employee." }
                        val a = LocalDate.parse(targetDate); val b = when (period) { "weekly" -> a.plusWeeks(1); "monthly" -> a.plusMonths(1); else -> a.plusDays(1) }
                        company.collection("targetDefinitions").add(mapOf("name" to "${crmMetrics[metric]} target", "employeeUid" to targetEmployee, "metric" to metric, "value" to value, "period" to period, "startAt" to a.atStartOfDay(zone).toInstant().toEpochMilli(), "endAt" to b.atStartOfDay(zone).toInstant().toEpochMilli(), "timeZone" to s.zone, "createdAt" to FieldValue.serverTimestamp(), "createdBy" to s.uid)).await()
                        message = "Target created."; goal = ""
                    } catch (e: Exception) { failed(e) } finally { busy = false }
                } }, enabled = !busy && complete("members")) { Text("Create target") }
                Text("One dated period; create a new target for the next period.")
            }
            data["targetDefinitions"].orEmpty().filter { it.num("startAt") == 0L || it.num("startAt") < end && it.num("endAt") > start }.forEach { target ->
                Panel("${name(target.str("employeeUid"))} · ${crmMetrics[target.str("metric")] ?: target.str("metric")}") {
                    val a = target.num("startAt"); val b = target.num("endAt"); val value = target.dec("value")
                    Text("${dateTime(a, s.zone)} – ${dateTime(b - 1, s.zone)}")
                    if (ready && targetWindowCovered(start, end, a, b) && crmMetrics.containsKey(target.str("metric")) && value > 0) {
                        val uid = target.str("employeeUid")
                        val n = targetAchievement(target.str("metric"), calls.filter { it.str("uid") == uid && it.num("startedAt") >= a && it.num("startedAt") < b }, attempts.filter { it.str("employeeUid") == uid && it.num("createdAt") >= a && it.num("createdAt") < b })
                        Text("%.1f / %.1f · %.0f%%".format(n, value, n / value * 100))
                        LinearProgressIndicator(progress = { (n / value).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("Remaining: %.1f".format((value - n).coerceAtLeast(0.0)))
                    } else if (a > 0 && b > a) TextButton({ start = a; end = b; from = java.time.Instant.ofEpochMilli(a).atZone(zone).toLocalDate().toString(); to = java.time.Instant.ofEpochMilli(b - 1).atZone(zone).toLocalDate().toString() }) { Text("Load full target period") }
                    else Text("This target needs a supported metric and dated period.")
                }
            }
            if (data["targetDefinitions"]?.isEmpty() == true) Text("No targets assigned.")
        }
        if (tab in listOf("Assigned leads", "Follow-ups", "Outcomes")) {
            val kind = when (tab) { "Assigned leads" -> "leads"; "Follow-ups" -> "followUps"; else -> "crmCallAttempts" }
            Text(if (kind == "crmCallAttempts") "Outcomes in the applied date range." else "Current workload across all dates. Employee filter applies.")
            OutlinedTextField(search, { search = it; page = 0 }, label = { Text("Search customer, number, status or remarks") }, modifier = Modifier.fillMaxWidth())
            val rows = data[kind].orEmpty().filter { row -> search.isBlank() || listOf("customerName", "primaryPhone", "status", "remarks", "dispositionId", "leadId").any { row.str(it).contains(search, true) } }.sortedByDescending { it.num(if (kind == "followUps") "dueAt" else "createdAt") }
            Text("${rows.size} records")
            val lastPage = ((rows.size - 1) / 25).coerceAtLeast(0)
            val currentPage = page.coerceAtMost(lastPage)
            rows.drop(currentPage * 25).take(25).forEach { row ->
                Panel(if (kind == "leads") row.str("customerName") else data["leads"].orEmpty().firstOrNull { it.str("id") == row.str("leadId") }?.str("customerName") ?: row.str("leadId")) {
                    Text("Employee: ${name(row.str(if (kind == "leads") "assignedTo" else "employeeUid"))}")
                    if (kind == "leads") {
                        Text("${row.str("primaryPhone")} · ${row.str("status")}")
                        Text(row.str("companyName"))
                        if (row.num("nextFollowUpAt") > 0) Text("Follow-up: ${dateTime(row.num("nextFollowUpAt"), s.zone)}")
                        if (row.str("assignedTo") == s.uid) TextButton({ navigate("crm") }) { Text("Open my calling workspace") }
                        if (s.manager) TextButton({ assigning = row; assignee = row.str("assignedTo") }) { Text("Assign / reassign") }
                    } else if (kind == "followUps") Text("${row.str("status")} · Due ${dateTime(row.num("dueAt"), s.zone)}")
                    else { Text("${row.str("dispositionId")} · ${dateTime(row.num("createdAt"), s.zone)}"); Text("Verification: ${row.str("verificationStatus")}") }
                    if (row.str("remarks").isNotBlank()) Text(row.str("remarks"))
                }
            }
            Row { TextButton({ page = (currentPage - 1).coerceAtLeast(0) }, enabled = currentPage > 0) { Text("Previous") }; TextButton({ page = currentPage + 1 }, enabled = currentPage < lastPage) { Text("Next") } }
            Text("Page ${currentPage + 1} of ${lastPage + 1}")
        }
    }
    assigning?.let { lead -> AlertDialog(
        onDismissRequest = { if (!busy) assigning = null }, title = { Text("Assign ${lead.str("customerName")}") },
        text = { CrmSelect("Employee", assignee, options) { assignee = it } },
        dismissButton = { TextButton({ assigning = null }, enabled = !busy) { Text("Cancel") } },
        confirmButton = { TextButton({ scope.launch {
            busy = true
            try {
                require(assignee.isNotBlank()) { "Choose an employee." }
                db.runTransaction { tx ->
                    val ref = company.collection("leads").document(lead.str("id"))
                    val current = tx.get(ref).data ?: error("Lead no longer exists.")
                    val member = tx.get(company.collection("members").document(assignee))
                    check(member.getBoolean("approved") == true) { "Employee approval is required." }
                    check(current.num("version") == lead.num("version")) { "Lead changed. Reopen assignment." }
                    check(current.num("claimExpiresAt") <= System.currentTimeMillis()) { "Employee is working this lead. Retry after their claim expires." }
                    tx.update(ref, mapOf("assignedTo" to assignee, "version" to current.num("version") + 1, "updatedAt" to FieldValue.serverTimestamp(), "claimedBy" to "", "claimSessionId" to "", "claimExpiresAt" to 0L))
                }.await()
                assigning = null; message = "Lead assigned. Earlier outcomes and follow-ups remain attributed to their original employee."
            } catch (e: Exception) { failed(e); assigning = null } finally { busy = false }
        } }, enabled = !busy) { Text("Confirm assignment") } },
    ) }
}
