package com.sharaco.callwebhook.ui

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.sharaco.callwebhook.R
import com.sharaco.callwebhook.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ReportExport {
    fun safeCell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    suspend fun share(context: Context, title: String, headers: List<String>, rows: List<List<String>>, pdf: Boolean) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86400000 }?.forEach { it.delete() }
            val target = File(dir, "CallTrack_${title.replace(Regex("[^A-Za-z0-9_-]"), "_")}.${if (pdf) "pdf" else "csv"}")
            if (!pdf) target.writeText((listOf(headers) + rows).joinToString("\r\n") { row -> row.joinToString(",", transform = ::safeCell) })
            else PdfDocument().use { document ->
                val chunks = rows.chunked(28).ifEmpty { listOf(emptyList()) }
                chunks.forEachIndexed { index, pageRows ->
                    val width = maxOf(842, headers.size * 90 + 40)
                    val page = document.startPage(PdfDocument.PageInfo.Builder(width, 595, index + 1).create())
                    val paint = Paint().apply { textSize = 10f; isAntiAlias = true }
                    page.canvas.drawText("${context.getString(R.string.app_name)} · $title · ${index + 1}/${chunks.size}", 20f, 30f, paint)
                    (listOf(headers) + pageRows).forEachIndexed { rowIndex, row -> row.forEachIndexed { column, value ->
                        paint.isFakeBoldText = rowIndex == 0
                        val text = if (value.length > 17) value.take(16) + "…" else value
                        page.canvas.drawText(text, 20f + column * 90, 60f + rowIndex * 17, paint)
                    } }
                    document.finishPage(page)
                }
                target.outputStream().use(document::writeTo)
            }
            target
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if (pdf) "application/pdf" else "text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export report"))
    }
}
