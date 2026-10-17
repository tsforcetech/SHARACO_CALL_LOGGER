package com.sharaco.callwebhook.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sharaco.callwebhook.domain.*

@Composable
fun AppTheme(mode: String, content: @Composable () -> Unit) {
    val dark = mode == "DARK" || (mode == "SYSTEM" && isSystemInDarkTheme())
    val colors =
        if (dark)
            darkColorScheme(
                primary = Color(0xFFC4BEFF),
                primaryContainer = Color(0xFF352C70),
                onPrimaryContainer = Color(0xFFEAE7FF),
                surfaceContainer = Color(0xFF181C28),
                secondaryContainer = Color(0xFF243B36),
                background = Color(0xFF10131D),
                surface = Color(0xFF181C28),
                secondary = Color(0xFF86D6BC),
            )
        else
            lightColorScheme(
                primary = Color(0xFF5145E8),
                primaryContainer = Color(0xFFEEECFF),
                onPrimaryContainer = Color(0xFF5145D9),
                onBackground = Color(0xFF15213C),
                onSurface = Color(0xFF15213C),
                onSurfaceVariant = Color(0xFF6E7A91),
                outlineVariant = Color(0xFFEDF0F6),
                surfaceContainer = Color.White,
                secondaryContainer = Color(0xFFE7F8EF),
                background = Color(0xFFF6F8FC),
                surface = Color.White,
                secondary = Color(0xFF156E56),
            )
    MaterialTheme(
        colorScheme = colors,
        shapes =
            Shapes(
                small = RoundedCornerShape(11.dp),
                medium = RoundedCornerShape(15.dp),
                large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(24.dp),
            ),
        typography =
            Typography(
                headlineSmall =
                    androidx.compose.ui.text.TextStyle(
                        fontSize = 26.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.7).sp,
                    ),
                titleLarge =
                    androidx.compose.ui.text.TextStyle(
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                titleMedium =
                    androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                bodyMedium =
                    androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
                bodySmall =
                    androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
                labelMedium =
                    androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium,
                    ),
            ),
        content = content,
    )
}

@Composable
fun Page(title: String, subtitle: String = "", content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (subtitle.isNotEmpty())
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun Panel(title: String = "", content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title.isNotEmpty())
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            content()
        }
    }
}

@Composable
fun Hint(text: String, problem: Boolean = false) {
    Surface(
        color =
            if (problem) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text,
            Modifier.fillMaxWidth().padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun Field(label: String, value: String, change: (String) -> Unit, enabled: Boolean = true) {
    OutlinedTextField(
        value,
        change,
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
    )
}

@Composable
fun Choices(values: List<String>, selected: String, change: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach {
            FilterChip(selected == it, { change(it) }, label = { Text(it.replace('_', ' ')) })
        }
    }
}

@Composable
fun Confirm(title: String, message: String, word: String, close: () -> Unit, confirm: () -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                Field("Type $word", input, { input = it })
            }
        },
        confirmButton = {
            TextButton(
                {
                    confirm()
                    close()
                },
                enabled = input == word,
            ) {
                Text("Confirm")
            }
        },
        dismissButton = { TextButton(close) { Text("Cancel") } },
    )
}

@Composable
fun Metrics(metrics: Row, click: (String) -> Unit) {
    val items =
        listOf(
            "total" to "Total calls",
            "outgoing" to "Outgoing",
            "incoming" to "Incoming",
            "connected" to "Connected",
            "missed" to "Missed",
            "talk" to "Talk time",
            "average" to "Avg. connected",
            "unique" to "Unique numbers",
        )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (key, label) ->
                    Card(
                        onClick = { click(key) },
                        modifier = Modifier.weight(1f).heightIn(min = 126.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(
                                color =
                                    if (key == "missed") MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(
                                    when (key) {
                                        "outgoing" -> Icons.AutoMirrored.Filled.CallMade
                                        "incoming" -> Icons.AutoMirrored.Filled.CallReceived
                                        "connected" -> Icons.Default.CheckCircle
                                        "missed" -> Icons.AutoMirrored.Filled.CallMissed
                                        "talk" -> Icons.Default.Schedule
                                        "average" -> Icons.Default.Timer
                                        "unique" -> Icons.Default.PeopleOutline
                                        else -> Icons.Default.Call
                                    },
                                    null,
                                    Modifier.padding(7.dp).size(18.dp),
                                    tint =
                                        if (key == "missed") MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                if (key in listOf("talk", "average")) duration(metrics.num(key))
                                else number(metrics.num(key)),
                                style =
                                    MaterialTheme.typography.titleLarge.copy(
                                        fontFeatureSettings = "tnum"
                                    ),
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SummaryHero(label: String, value: String, caption: String, badge: String = "") {
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(23.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF5F52ED), Color(0xFF3831A9))))
    ) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            drawCircle(
                Color.White.copy(alpha = 0.06f),
                radius = 85.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(size.width, 0f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(26.dp.toPx()),
            )
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    Modifier.weight(1f),
                    color = Color.White.copy(alpha = .82f),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (badge.isNotEmpty())
                    Surface(
                        color = Color.White.copy(alpha = .15f),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(
                            badge,
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
            }
            Text(
                value,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = .85f),
            )
        }
    }
}

data class TableColumn(val label: String, val key: String, val format: (Row) -> String)

@Composable
fun DataTable(
    rows: List<Row>,
    first: TableColumn,
    columns: List<TableColumn>,
    click: (Row) -> Unit,
) {
    var sort by remember { mutableStateOf("") }
    var descending by remember { mutableStateOf(true) }
    var page by remember { mutableIntStateOf(0) }
    LaunchedEffect(rows, sort, descending) { page = 0 }
    val sorted =
        remember(rows, sort, descending) {
            val data =
                if (sort.isBlank()) rows
                else
                    rows.sortedWith { a, b ->
                        val av = a[sort]
                        val bv = b[sort]
                        if (av is Number && bv is Number) av.toDouble().compareTo(bv.toDouble())
                        else av.toString().compareTo(bv.toString(), true)
                    }
            if (sort.isNotBlank() && descending) data.reversed() else data
        }
    fun sortBy(key: String) {
        if (sort == key) descending = !descending
        else {
            sort = key
            descending = true
        }
    }
    Column {
        if (rows.size > 50)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ page-- }, enabled = page > 0) { Text("Previous") }
                Text("${page + 1} / ${(rows.size + 49) / 50}", Modifier.padding(12.dp))
                TextButton({ page++ }, enabled = (page + 1) * 50 < rows.size) { Text("Next") }
            }
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(144.dp)) {
                    TableCell(first.label + if (sort == first.key) " ↕" else "", true) {
                        sortBy(first.key)
                    }
                    sorted.drop(page * 50).take(50).forEach { row ->
                        TableCell(first.format(row)) { click(row) }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    columns.forEach { column ->
                        Column(Modifier.width(116.dp)) {
                            TableCell(column.label + if (sort == column.key) " ↕" else "", true) {
                                sortBy(column.key)
                            }
                            sorted.drop(page * 50).take(50).forEach { row ->
                                TableCell(column.format(row)) { click(row) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, header: Boolean = false, click: () -> Unit) {
    Column(
        Modifier.height(60.dp)
            .fillMaxWidth()
            .clickable(onClick = click)
            .background(
                if (header) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surface
            )
    ) {
        Box(
            Modifier.weight(1f).padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style =
                    if (header) MaterialTheme.typography.labelMedium
                    else MaterialTheme.typography.bodySmall,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
    }
}

@Composable
fun Avatar(name: String, logo: String = "", modifier: Modifier = Modifier) {
    val bitmap =
        remember(logo) {
            if (logo.isBlank()) null
            else
                runCatching {
                    val bytes = android.util.Base64.decode(logo, android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                    .getOrNull()
        }
    Surface(
        modifier.size(44.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        if (bitmap != null)
            Image(bitmap.asImageBitmap(), "Company logo", Modifier.fillMaxSize().clip(CircleShape))
        else
            Box(contentAlignment = Alignment.Center) {
                Text(
                    name
                        .split(" ")
                        .filter { it.isNotBlank() }
                        .take(2)
                        .joinToString("") { it.take(1).uppercase() },
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
    }
}

@Composable
fun StatusPill(label: String) {
    val good = label in listOf("HEALTHY", "ACTIVE", "Granted")
    val bad = label in listOf("PROBLEM", "SUSPENDED", "REMOVED")
    Surface(
        shape = RoundedCornerShape(8.dp),
        color =
            when {
                good -> MaterialTheme.colorScheme.secondaryContainer
                bad -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            },
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                if (good) Icons.Default.CheckCircle
                else if (bad) Icons.Default.ErrorOutline else Icons.Default.Schedule,
                null,
                Modifier.size(13.dp),
            )
            Text(
                label.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
fun LoadingCards() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) {
                    Surface(
                        Modifier.weight(1f).height(116.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Box(
                                Modifier.width(80.dp)
                                    .height(12.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                        CircleShape,
                                    )
                            )
                            Box(
                                Modifier.width(56.dp)
                                    .height(28.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                        RoundedCornerShape(8.dp),
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}
