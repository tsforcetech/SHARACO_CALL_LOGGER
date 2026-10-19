package com.sharaco.callwebhook.domain

/** Same dated target metrics as the web workspace; outcomes never imply a verified call. */
fun targetAchievement(metric: String, calls: List<Row>, attempts: List<Row>): Double = when (metric) {
    "calls" -> calls.size.toDouble()
    "outgoing" -> calls.count { it.str("type") == "OUTGOING" }.toDouble()
    "connected" -> calls.count { it.str("type") in listOf("INCOMING", "OUTGOING", "ANSWERED_EXTERNALLY") && it.num("durationSec") > 0 }.toDouble()
    "minutes" -> calls.sumOf { it.num("durationSec") }.toDouble() / 60
    "interested", "converted" -> attempts.count { it.str("dispositionId") == metric }.toDouble()
    else -> 0.0
}

fun targetWindowCovered(start: Long, end: Long, targetStart: Long, targetEnd: Long) =
    targetStart > 0 && targetEnd > targetStart && start <= targetStart && end >= targetEnd
