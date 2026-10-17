package com.sharaco.callwebhook.domain

import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

typealias Row = Map<String, Any?>

fun Row.str(key: String) = this[key] as? String ?: ""

fun Row.num(key: String) = (this[key] as? Number)?.toLong() ?: 0L

fun Row.dec(key: String) = (this[key] as? Number)?.toDouble() ?: 0.0

fun Row.flag(key: String) = this[key] == true

@Suppress("UNCHECKED_CAST") fun Row.row(key: String): Row = this[key] as? Row ?: emptyMap()

@Suppress("UNCHECKED_CAST")
fun Row.rows(key: String): List<Row> = this[key] as? List<Row> ?: emptyList()

fun duration(seconds: Long): String =
    if (seconds >= 3600) "${seconds / 3600}h ${seconds % 3600 / 60}m"
    else "${seconds / 60}m ${seconds % 60}s"

fun number(n: Long) = String.format(Locale.getDefault(), "%,d", n)

fun dateTime(ms: Long, zone: String): String =
    if (ms <= 0) "Never"
    else
        Instant.ofEpochMilli(ms)
            .atZone(ZoneId.of(zone))
            .format(DateTimeFormatter.ofPattern("dd MMM, HH:mm"))

fun callId(device: String, row: String, time: Long): String =
    MessageDigest.getInstance("SHA-256").digest("$device:$row:$time".toByteArray()).joinToString(
        ""
    ) {
        "%02x".format(it)
    }

fun callType(value: Int) =
    mapOf(
        1 to "INCOMING",
        2 to "OUTGOING",
        3 to "MISSED",
        4 to "VOICEMAIL",
        5 to "REJECTED",
        6 to "BLOCKED",
        7 to "ANSWERED_EXTERNALLY",
    )[value] ?: "UNKNOWN"

fun connected(type: String, seconds: Long) = type in listOf("INCOMING", "OUTGOING") && seconds > 0

val callTypes =
    listOf(
        "INCOMING",
        "OUTGOING",
        "MISSED",
        "REJECTED",
        "BLOCKED",
        "VOICEMAIL",
        "ANSWERED_EXTERNALLY",
        "UNKNOWN",
    )

fun deviceHealth(d: Row, now: Long = System.currentTimeMillis()): String =
    when {
        !d.flag("active") || !d.flag("callLogPermission") || d.str("lastSyncError").isNotEmpty() ->
            "PROBLEM"
        now - d.num("lastSuccessfulSyncAt") > 3600000 -> "DELAYED"
        else -> "HEALTHY"
    }

data class Session(
    val uid: String,
    val companyId: String = "",
    val company: Row = emptyMap(),
    val member: Row = emptyMap(),
    val pendingCompanyId: String = "",
    val pendingCompanyName: String = "",
) {
    val role
        get() = member.str("role").uppercase(Locale.ROOT)

    val manager
        get() = role in listOf("OWNER", "ADMIN")

    val canManage
        get() = role in listOf("OWNER", "ADMIN")

    val zone
        get() = company.str("timeZone").ifBlank { "Asia/Dubai" }

    val scope
        get() = "$companyId:$uid"
}

data class Filters(
    val start: LocalDate = LocalDate.now(),
    val end: LocalDate = start,
    val employees: Set<String> = emptySet(),
    val department: String = "",
    val types: Set<String> = emptySet(),
    val connection: String = "",
    val minDuration: String = "",
    val maxDuration: String = "",
    val phone: String = "",
) {
    fun payload(zone: String): Row = buildMap {
        put("from", start.atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli())
        put("to", end.plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli())
        put("employees", employees.toList())
        put("department", department)
        put("types", types.toList())
        put("connection", connection)
        put("phone", phone)
        minDuration.toLongOrNull()?.let { put("minDuration", it) }
        maxDuration.toLongOrNull()?.let { put("maxDuration", it) }
    }

    val count
        get() =
            listOf(
                    employees.isNotEmpty(),
                    department.isNotEmpty(),
                    types.isNotEmpty(),
                    connection.isNotEmpty(),
                    minDuration.isNotBlank() || maxDuration.isNotBlank(),
                    phone.isNotEmpty(),
                )
                .count { it }
}
