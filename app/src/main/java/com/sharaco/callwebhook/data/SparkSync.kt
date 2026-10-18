package com.sharaco.callwebhook.data

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.sharaco.callwebhook.domain.*
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

/** Membership, primary-device binding and call ownership are also enforced by Firestore rules. */
@Singleton
class SparkSync
@Inject
constructor(private val auth: FirebaseAuth, private val db: FirebaseFirestore) {
    private fun denied(message: String): Nothing =
        throw FirebaseFirestoreException(message, FirebaseFirestoreException.Code.PERMISSION_DENIED)

    private fun uid() =
        auth.currentUser?.uid
            ?: throw FirebaseFirestoreException(
                "Sign in again",
                FirebaseFirestoreException.Code.UNAUTHENTICATED,
            )

    private fun device(data: Row): String =
        data.str("deviceId").also {
            require(it.matches(Regex("[0-9a-fA-F-]{36}"))) { "Invalid device identifier." }
        }

    private fun policy(member: Row, settings: Row): Row =
        mapOf(
            "initialSyncFrom" to member.num("initialSyncFrom"),
            "retentionDays" to
                ((settings["retentionDays"] as? Number)?.toLong() ?: 365L).coerceIn(1L, 3650L),
        )

    private fun checkDevice(member: Row, record: Row, id: String, user: String) {
        if (
            !member.flag("approved") ||
                member.str("activeDeviceId") != id ||
                record.str("uid") != user ||
                record.str("status") != "active"
        )
            denied("Device or membership revoked. Register the current device again.")
    }

    suspend fun register(data: Row): Row {
        val user = uid()
        val id = device(data)
        require(data.flag("consent") && data.flag("callLogPermission")) {
            "Confirm monitoring consent and allow call history first."
        }
        val company = db.collection("companies").document(data.str("companyId"))
        val mr = company.collection("members").document(user)
        val dr = company.collection("devices").document("${user}_$id")
        return db.runTransaction { tx ->
                val member = tx.get(mr).data.orEmpty()
                val old = tx.get(dr).data.orEmpty()
                val settings =
                    tx.get(company.collection("settings").document("profile")).data.orEmpty()
                if (!member.flag("approved")) denied("Administrator approval is required.")
                val previous = member.str("activeDeviceId")
                val previousRef =
                    if (previous.isNotBlank() && previous != id)
                        company.collection("devices").document("${user}_$previous")
                    else null
                val previousDoc = previousRef?.let { tx.get(it) }
                check(previousRef == null || data.flag("replace")) {
                    "Another device is registered. Choose Replace existing device."
                }
                val now = System.currentTimeMillis()
                val initial =
                    if (member.num("initialSyncFrom") > 0) member.num("initialSyncFrom")
                    else if (settings.str("initialSyncPolicy") == "JOIN_TIME") now
                    else
                        ZonedDateTime.now(
                                ZoneId.of(settings.str("timeZone").ifBlank { "Asia/Dubai" })
                            )
                            .toLocalDate()
                            .atStartOfDay(
                                ZoneId.of(settings.str("timeZone").ifBlank { "Asia/Dubai" })
                            )
                            .toInstant()
                            .toEpochMilli()
                            .coerceAtLeast(now - 86400000L + 60000L)
                if (previousDoc?.exists() == true) tx.update(previousRef!!, "status", "inactive")
                val stamp = FieldValue.serverTimestamp()
                tx.set(
                    dr,
                    old +
                        mapOf(
                            "uid" to user,
                            "deviceId" to id,
                            "employeeName" to member.str("name"),
                            "manufacturer" to data.str("manufacturer").take(100),
                            "model" to data.str("model").take(100),
                            "androidVersion" to data.str("androidVersion").take(50),
                            "appVersion" to data.str("appVersion").take(50),
                            "status" to "active",
                            "registeredAt" to (old["registeredAt"] ?: stamp),
                            "consentAt" to (old["consentAt"] ?: stamp),
                            "lastSeen" to stamp,
                            "callLogPermission" to true,
                            "notificationPermission" to data.flag("notificationPermission"),
                            "pendingRecordCount" to 0L,
                            "lastSyncError" to "",
                            "lastSyncAttemptStatus" to "REGISTERED",
                        ),
                )
                tx.update(mr, mapOf("activeDeviceId" to id, "initialSyncFrom" to initial))
                policy(member + ("initialSyncFrom" to initial), settings)
            }
            .await()
    }

    suspend fun heartbeat(data: Row): Row {
        val user = uid()
        val id = device(data)
        val company = db.collection("companies").document(data.str("companyId"))
        return db.runTransaction { tx ->
                val member = tx.get(company.collection("members").document(user)).data.orEmpty()
                val dr = company.collection("devices").document("${user}_$id")
                val record = tx.get(dr).data.orEmpty()
                val settings =
                    tx.get(company.collection("settings").document("profile")).data.orEmpty()
                checkDevice(member, record, id, user)
                val update =
                    mutableMapOf<String, Any?>(
                        "lastSeen" to FieldValue.serverTimestamp(),
                        "callLogPermission" to data.flag("callLogPermission"),
                        "notificationPermission" to data.flag("notificationPermission"),
                        "pendingRecordCount" to data.num("pendingRecordCount").coerceAtLeast(0),
                        "lastSyncError" to data.str("error").take(500),
                        "lastSyncAttemptStatus" to
                            (if (data.flag("success")) "SUCCESS" else data.str("phase").take(100)),
                        "appVersion" to data.str("appVersion").take(50),
                    )
                if (data.flag("success"))
                    update["lastSuccessfulSyncAt"] = FieldValue.serverTimestamp()
                tx.update(dr, update)
                policy(member, settings)
            }
            .await()
    }

    suspend fun ingest(data: Row): Row {
        val user = uid()
        val id = device(data)
        val calls = data.rows("calls")
        require(calls.size in 1..100) { "Send 1–100 calls per batch." }
        val company = db.collection("companies").document(data.str("companyId"))
        val ids = calls.map { callId(id, it.str("androidCallLogId"), it.num("callTimestamp")) }
        require(ids.toSet().size == ids.size) { "Duplicate calls in upload batch." }
        db.runTransaction { tx ->
                val member = tx.get(company.collection("members").document(user)).data.orEmpty()
                val record =
                    tx.get(company.collection("devices").document("${user}_$id")).data.orEmpty()
                checkDevice(member, record, id, user)
                val refs = ids.map { company.collection("call_logs").document(it) }
                val existing = refs.map { tx.get(it) }
                calls.forEachIndexed { i, call ->
                    require(call.str("androidCallLogId").matches(Regex("[0-9]{1,20}"))) {
                        "Invalid Android call identifier."
                    }
                    require(
                        call.num("callTimestamp") >= member.num("initialSyncFrom") &&
                            call.num("callTimestamp") <= System.currentTimeMillis() + 300000L
                    ) {
                        "Call is outside the monitoring period."
                    }
                    require(
                        call.num("durationSeconds") in 0L..604800L &&
                            call.str("callType") in callTypes
                    ) {
                        "Invalid call metadata."
                    }
                    if (existing[i].exists()) {
                        val old = existing[i].data.orEmpty()
                        if (old.str("uid") != user || old.str("deviceId") != id)
                            denied("Call belongs to another account or device.")
                        // Android may finalize a call's duration/type after the first scan.
                        // Identity never changes.
                        tx.update(
                            refs[i],
                            mapOf(
                                "type" to call.str("callType"),
                                "durationSec" to call.num("durationSeconds"),
                            ),
                        )
                    } else
                        tx.set(
                            refs[i],
                            mapOf(
                                "uid" to user,
                                "companyId" to company.id,
                                "sim" to member.str("sim"),
                                "remoteNumber" to call.str("phoneNumber").take(100),
                                "contactName" to "",
                                "type" to call.str("callType"),
                                "startedAt" to call.num("callTimestamp"),
                                "durationSec" to call.num("durationSeconds"),
                                "createdAt" to FieldValue.serverTimestamp(),
                                "deviceCallId" to ids[i],
                                "deviceId" to id,
                            ),
                        )
                }
            }
            .await()
        return mapOf("confirmedIds" to ids)
    }
}

/** Adapt Firestore timestamps and legacy document keys to the existing native device screens. */
fun deviceRow(id: String, data: Row): Row =
    data +
        mapOf(
            "id" to data.str("deviceId").ifBlank { id },
            "active" to (data.str("status") == "active"),
            "lastSeenAt" to ((data["lastSeen"] as? Timestamp)?.toDate()?.time ?: 0L),
            "registeredAt" to ((data["registeredAt"] as? Timestamp)?.toDate()?.time ?: 0L),
            "lastSuccessfulSyncAt" to
                ((data["lastSuccessfulSyncAt"] as? Timestamp)?.toDate()?.time ?: 0L),
        )
