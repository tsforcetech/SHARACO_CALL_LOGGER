package com.sharaco.callwebhook.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sharaco.callwebhook.domain.*

@Composable
fun ActivityChart(points: List<Row>) {
    val color = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outlineVariant
    if (points.isEmpty()) {
        Text("No activity in this period.")
        return
    }
    val max = points.maxOf { it.num("value") }.coerceAtLeast(1)
    Canvas(
        Modifier.fillMaxWidth().height(144.dp).semantics {
            contentDescription = points.joinToString {
                "${it.str("label")}: ${it.num("value")} calls"
            }
        }
    ) {
        drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height))
        val width = size.width / points.size
        points.forEachIndexed { i, point ->
            val h = size.height * point.num("value") / max
            drawRoundRect(
                color,
                Offset(i * width + width * .15f, size.height - h),
                Size(width * .7f, h),
                androidx.compose.ui.geometry.CornerRadius(4f),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(points.first().str("label"), style = MaterialTheme.typography.labelSmall)
        Text("Peak ${number(max)} calls", style = MaterialTheme.typography.labelSmall)
        Text(points.last().str("label"), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun Distribution(metrics: Row) {
    val colors =
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.error,
            Color(0xFFA96A11),
            MaterialTheme.colorScheme.outline,
        )
    val values =
        listOf(
            "Outgoing" to metrics.num("outgoing"),
            "Incoming" to metrics.num("incoming"),
            "Missed" to metrics.num("missed"),
            "Rejected" to metrics.num("rejected"),
            "Other" to
                (metrics.num("total") -
                    metrics.num("outgoing") -
                    metrics.num("incoming") -
                    metrics.num("missed") -
                    metrics.num("rejected")),
        )
    val total = values.sumOf { it.second }
    if (total == 0L) {
        Text("No calls recorded for this period.")
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Canvas(
            Modifier.size(120.dp).padding(12.dp).semantics {
                contentDescription = values.joinToString { "${it.first}: ${it.second}" }
            }
        ) {
            var start = -90f
            values.forEachIndexed { i, (_, count) ->
                val sweep = 360f * count / total
                drawArc(colors[i], start, sweep, false, style = Stroke(20f))
                start += sweep
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { (label, count) ->
                Text("$label   ${number(count)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun StaffChart(rows: List<Row>, click: (Row) -> Unit) {
    var metric by remember { mutableStateOf("Calls") }
    Choices(listOf("Calls", "Talk time", "Connection %"), metric, { metric = it })
    val key =
        when (metric) {
            "Talk time" -> "talk"
            "Connection %" -> "outgoingRate"
            else -> "total"
        }
    val sorted = rows.sortedByDescending { it.dec(key) }.take(10)
    val max = sorted.maxOfOrNull { it.dec(key) }?.coerceAtLeast(1.0) ?: 1.0
    if (sorted.isEmpty()) Text("Add employees to see team performance.")
    sorted.forEach { row ->
        Column(
            Modifier.fillMaxWidth().clickable { click(row) }.padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.str("name"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    when (key) {
                        "talk" -> duration(row.num(key))
                        "outgoingRate" -> "%.1f%%".format(row.dec(key))
                        else -> number(row.num(key))
                    }
                )
            }
            LinearProgressIndicator(
                progress = { (row.dec(key) / max).toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
