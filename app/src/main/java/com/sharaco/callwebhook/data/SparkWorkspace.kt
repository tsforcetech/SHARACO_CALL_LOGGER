package com.sharaco.callwebhook.data

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.sharaco.callwebhook.domain.*
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

/** Client operations are authorized by firestore.rules, including every atomic transition. */
@Singleton
class SparkWorkspace
@Inject
constructor(private val auth: FirebaseAuth, private val db: FirebaseFirestore) {
    private fun user() = checkNotNull(auth.currentUser) { "Sign in first. Code: UNAUTHENTICATED" }

    private fun path(cid: String) = db.collection("companies").document(cid)

    private fun index(uid: String, cid: String) = db.document("users/$uid/companies/$cid")

    private fun stamp() = FieldValue.serverTimestamp()

    private fun indexData(name: String, role: String, status: String): Row =
        mapOf("name" to name, "role" to role, "status" to status, "updatedAt" to stamp())

    suspend fun createCompany(data: Row): Row {
        val u = user()
        val name = data.str("name").trim()
        require(name.length in 1..160) { "Enter a company name (1–160 characters)." }
        java.time.ZoneId.of(data.str("timeZone").ifBlank { "Asia/Dubai" })
        val company = db.collection("companies").document()
        val owner =
            mapOf(
                "uid" to u.uid,
                "role" to "owner",
                "approved" to true,
                "name" to (u.displayName ?: "Owner"),
                "email" to u.email.orEmpty(),
                "sim" to "",
            )
        // Legacy company, membership and index field names are deliberately retained.
        db.runBatch { b ->
                b.set(
                    company,
                    mapOf(
                        "name" to name,
                        "ownerUid" to u.uid,
                        "createdAt" to stamp(),
                        "joinToken" to InvitationCode.create(),
                    ),
                )
                b.set(company.collection("members").document(u.uid), owner)
                b.set(index(u.uid, company.id), indexData(name, "owner", "approved"))
                b.set(
                    company.collection("settings").document("profile"),
                    data.filterKeys {
                        it in
                            listOf(
                                "industry",
                                "country",
                                "timeZone",
                                "phone",
                                "email",
                                "address",
                                "workingHours",
                                "retentionDays",
                                "initialSyncPolicy",
                                "notificationsEnabled",
                            )
                    },
                )
            }
            .await()
        return mapOf("companyId" to company.id)
    }

    suspend fun session(): Session? {
        val u = auth.currentUser ?: return null
        val indices = db.collection("users/${u.uid}/companies").get(Source.SERVER).await().documents
        for (i in indices.filter { it.getString("status") == "approved" }) {
            val member =
                path(i.id)
                    .collection("members")
                    .document(u.uid)
                    .get(Source.SERVER)
                    .await()
                    .data
                    .orEmpty()
            if (!member.flag("approved")) continue
            val c = path(i.id).get(Source.SERVER).await().data.orEmpty()
            val settings =
                path(i.id)
                    .collection("settings")
                    .document("profile")
                    .get(Source.SERVER)
                    .await()
                    .data
                    .orEmpty()
            return Session(u.uid, i.id, c + settings, member)
        }
        val pending = indices.firstOrNull { it.getString("status") == "pending" }
        return Session(
            u.uid,
            pendingCompanyId = pending?.id.orEmpty(),
            pendingCompanyName = pending?.getString("name").orEmpty(),
        )
    }

    suspend fun saveEmployee(data: Row): Row {
        val company = path(data.str("companyId"))
        val email = data.str("email").trim().lowercase()
        require(email.contains("@")) {
            "Enter the employee's Google email. Invitations are bound to this account."
        }
        val ref =
            data
                .str("employeeId")
                .takeIf { it.isNotBlank() }
                ?.let { company.collection("employees").document(it) }
                ?: company.collection("employees").document()
        val old = ref.get(Source.SERVER).await().data.orEmpty()
        val fields =
            data.filterKeys {
                it in listOf("name", "code", "mobile", "department", "designation")
            } +
                mapOf(
                    "email" to email,
                    "status" to old.str("status").ifBlank { "INVITED" },
                    "uid" to old.str("uid"),
                )
        ref.set(fields, SetOptions.merge()).await()
        return mapOf("employeeId" to ref.id)
    }

    suspend fun createInvite(data: Row): Row {
        val cid = data.str("companyId")
        val employee = path(cid).collection("employees").document(data.str("employeeId"))
        val code = InvitationCode.create()
        val expiry =
            Timestamp(
                Date(
                    System.currentTimeMillis() +
                        data.num("days").let { if (it == 0L) 7 else it.coerceIn(1, 30) } * 86400000
                )
            )
        val result =
            db.runTransaction { tx ->
                    val e = tx.get(employee).data.orEmpty()
                    val c = tx.get(path(cid)).data.orEmpty()
                    check(e.str("status") == "INVITED") {
                        "Only an unlinked employee can be invited."
                    }
                    require(e.str("email").contains("@")) {
                        "Set the employee's Google email first."
                    }
                    val previous = e.str("inviteCode")
                    if (previous.isNotBlank())
                        tx.update(db.document("invitations/$previous"), "revoked", true)
                    val invitation =
                        mapOf(
                            "companyId" to cid,
                            "employeeId" to employee.id,
                            "companyName" to c.str("name"),
                            "employeeName" to e.str("name"),
                            "employeeCode" to e.str("code"),
                            "email" to e.str("email"),
                            "expiresAt" to expiry,
                            "revoked" to false,
                            "claimedBy" to "",
                        )
                    tx.set(db.document("invitations/$code"), invitation)
                    tx.update(employee, "inviteCode", code)
                    invitation
                }
                .await()
        return result +
            mapOf("token" to InvitationCode.display(code), "expiresAt" to expiry.toDate().time)
    }

    suspend fun inspectInvite(raw: String): Row {
        val code = InvitationCode.parse(raw)
        val u = user()
        check(u.isEmailVerified) { "Use your verified Google account. Code: EMAIL_NOT_VERIFIED" }
        val data = db.document("invitations/$code").get(Source.SERVER).await().data
        checkNotNull(data) { "Invitation was not found. Code: INVITE_NOT_FOUND" }
        check(
            !data.flag("revoked") &&
                data.str("claimedBy").isBlank() &&
                (data["expiresAt"] as Timestamp).toDate().time > System.currentTimeMillis()
        ) {
            "Invitation expired, was revoked, or has already been used. Code: INVITE_UNAVAILABLE"
        }
        check(data.str("email").equals(u.email, true)) {
            "Sign in with the Google email on this invitation. Code: INVITE_EMAIL"
        }
        return data + ("token" to InvitationCode.display(code))
    }

    suspend fun join(raw: String): Row {
        val code = InvitationCode.parse(raw)
        val u = user()
        val invite = db.document("invitations/$code")
        db.runTransaction { tx ->
                val i = tx.get(invite).data ?: error("Invitation not found. Code: INVITE_NOT_FOUND")
                val cid = i.str("companyId")
                val request = path(cid).collection("join_requests").document(u.uid)
                val existing = tx.get(request)
                // Safe retry after an interrupted success; never create membership here.
                if (existing.exists() && existing.getString("token") == code) return@runTransaction
                check(!i.flag("revoked") && i.str("claimedBy").isBlank()) {
                    "Invitation already used or revoked. Code: INVITE_UNAVAILABLE"
                }
                tx.update(invite, "claimedBy", u.uid)
                tx.set(
                    request,
                    mapOf(
                        "uid" to u.uid,
                        "name" to (u.displayName ?: i.str("employeeName")),
                        "sim" to "",
                        "token" to code,
                        "status" to "pending",
                        "requestedAt" to stamp(),
                        "email" to u.email.orEmpty().lowercase(),
                        "employeeId" to i.str("employeeId"),
                    ),
                )
                tx.set(index(u.uid, cid), indexData(i.str("companyName"), "employee", "pending"))
            }
            .await()
        return emptyMap()
    }

    suspend fun approve(cid: String, uid: String) {
        val request = path(cid).collection("join_requests").document(uid)
        db.runTransaction { tx ->
                val r = tx.get(request).data ?: error("Join request not found.")
                val c = tx.get(path(cid)).data.orEmpty()
                val employee = path(cid).collection("employees").document(r.str("employeeId"))
                val e = tx.get(employee).data.orEmpty()
                check(r.str("status") == "pending" && e.str("uid").isBlank()) {
                    "Request already processed or employee already linked."
                }
                tx.set(
                    path(cid).collection("members").document(uid),
                    mapOf(
                        "uid" to uid,
                        "role" to "employee",
                        "approved" to true,
                        "name" to r.str("name"),
                        "email" to r.str("email"),
                        "sim" to r.str("sim"),
                    ),
                )
                tx.set(index(uid, cid), indexData(c.str("name"), "employee", "approved"))
                tx.update(request, "status", "approved")
                tx.update(employee, mapOf("uid" to uid, "status" to "ACTIVE"))
            }
            .await()
    }

    suspend fun revoke(data: Row): Row {
        val e =
            path(data.str("companyId"))
                .collection("employees")
                .document(data.str("employeeId"))
                .get(Source.SERVER)
                .await()
                .data
                .orEmpty()
        if (e.str("inviteCode").isNotBlank())
            db.document("invitations/${e.str("inviteCode")}").update("revoked", true).await()
        return emptyMap()
    }

    suspend fun calls(data: Row, snapshotRows: List<Row>? = null): List<Row> {
        val cid = data.str("companyId")
        val member =
            path(cid)
                .collection("members")
                .document(user().uid)
                .get(Source.SERVER)
                .await()
                .data
                .orEmpty()
        var q: Query = path(cid).collection("call_logs")
        if (member.str("role") !in listOf("owner", "admin")) q = q.whereEqualTo("uid", user().uid)
        val f = data.row("filters")
        val profiles =
            if (member.str("role") in listOf("owner", "admin"))
                path(cid).collection("members").get(Source.SERVER).await().documents.associate {
                    it.id to it.data.orEmpty()
                }
            else mapOf(user().uid to member)
        val departments =
            if (f.str("department").isNotBlank() && member.str("role") in listOf("owner", "admin"))
                path(cid)
                    .collection("employees")
                    .whereEqualTo("department", f.str("department"))
                    .get(Source.SERVER)
                    .await()
                    .documents
                    .map { it.getString("uid") }
                    .toSet()
            else emptySet()
        if (f.num("from") > 0) q = q.whereGreaterThanOrEqualTo("startedAt", f.num("from"))
        if (f.num("to") > 0) q = q.whereLessThan("startedAt", f.num("to"))
        val docs =
            snapshotRows
                ?: q.limit(5001).get(Source.SERVER).await().documents.map {
                    it.data.orEmpty() + ("id" to it.id)
                }
        check(docs.size <= 5000) {
            "More than 5,000 calls. Narrow the date range for a complete report. Code: REPORT_LIMIT"
        }
        return docs
            .map { c ->
                c +
                    mapOf(
                        "id" to c.str("id"),
                        "employeeUid" to c.str("uid"),
                        "callType" to c.str("type"),
                        "callTimestamp" to c.num("startedAt"),
                        "durationSeconds" to c.num("durationSec"),
                        "phoneNumber" to c.str("remoteNumber"),
                        "employeeName" to profiles[c.str("uid")].orEmpty().str("name"),
                        "memberProfile" to profiles[c.str("uid")].orEmpty(),
                        "syncStatus" to "SYNCED",
                    )
            }
            .filter { c ->
                val employees = f["employees"] as? List<*> ?: emptyList<Any>()
                val types = f["types"] as? List<*> ?: emptyList<Any>()
                (employees.isEmpty() || c.str("uid") in employees) &&
                    (types.isEmpty() || c.str("type") in types) &&
                    (f.str("department").isBlank() || c.str("uid") in departments) &&
                    (f.str("phone").isBlank() || c.str("remoteNumber").contains(f.str("phone"))) &&
                    (!f.containsKey("minDuration") ||
                        c.num("durationSec") >= f.num("minDuration")) &&
                    (!f.containsKey("maxDuration") ||
                        c.num("durationSec") <= f.num("maxDuration")) &&
                    (f.str("connection").isBlank() ||
                        (if (f.str("connection") == "CONNECTED")
                            connected(c.str("type"), c.num("durationSec"))
                        else !connected(c.str("type"), c.num("durationSec"))))
            }
            .let { rows ->
                when (data.str("sort")) {
                    "OLDEST" -> rows.sortedBy { it.num("callTimestamp") }
                    "LONGEST" -> rows.sortedByDescending { it.num("durationSeconds") }
                    "SHORTEST" -> rows.sortedBy { it.num("durationSeconds") }
                    else -> rows.sortedByDescending { it.num("callTimestamp") }
                }
            }
    }

    suspend fun liveWorkspace(data: Row, snapshotRows: List<Row>): Row {
        val rows = calls(data, snapshotRows)
        return mapOf("calls" to rows, "report" to reportRows(rows))
    }

    suspend fun report(data: Row): Row = reportRows(calls(data))

    private fun reportRows(rows: List<Row>): Row {
        return mapOf(
            "metrics" to metrics(rows),
            "staff" to
                rows
                    .groupBy { it.str("uid") }
                    .map { (uid, calls) ->
                        val m = calls.firstOrNull()?.row("memberProfile").orEmpty()
                        m + metrics(calls) + mapOf("id" to uid)
                    },
            "timeline" to emptyList<Row>(),
            "generatedAt" to System.currentTimeMillis(),
        )
    }

    private fun metrics(rows: List<Row>): Row {
        val outgoing = rows.count { it.str("type") == "OUTGOING" }
        val incoming = rows.count { it.str("type") == "INCOMING" }
        val answered = rows.filter { connected(it.str("type"), it.num("durationSec")) }
        val talk = rows.sumOf { it.num("durationSec") }
        return mapOf(
            "total" to rows.size,
            "outgoing" to outgoing,
            "incoming" to incoming,
            "connected" to answered.size,
            "missed" to rows.count { it.str("type") == "MISSED" },
            "rejected" to rows.count { it.str("type") == "REJECTED" },
            "talk" to talk,
            "average" to if (answered.isEmpty()) 0 else talk / answered.size,
            "unique" to
                rows.map { it.str("remoteNumber") }.filter { it.isNotBlank() }.distinct().size,
            "outgoingRate" to
                if (outgoing == 0) 0.0
                else 100.0 * answered.count { it.str("type") == "OUTGOING" } / outgoing,
            "incomingRate" to
                if (incoming == 0) 0.0
                else 100.0 * answered.count { it.str("type") == "INCOMING" } / incoming,
            "lastCallAt" to (rows.maxOfOrNull { it.num("startedAt") } ?: 0),
        )
    }
}
