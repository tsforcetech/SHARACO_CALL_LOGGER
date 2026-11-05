package com.sharaco.callwebhook.domain

fun pendingLead(row: Row) = !row.flag("terminal") && !row.flag("doNotCall") && row.str("lastAttemptId").isBlank() && row.num("lastContactAt") == 0L && row.str("status") in listOf("", "new")

/** Input timestamps are normalized to milliseconds by the Firestore adapter. */
fun orderedLeads(rows: List<Row>): List<Row> = rows.sortedWith(
    compareBy<Row> { it.num("importStartedAt").takeIf { n -> n > 0 } ?: it.num("createdAt") }
        .thenBy { it.str("importBatchId") }.thenBy { it.num("importRow") }.thenBy { it.str("id") }
)
