package com.sharaco.callwebhook.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.sharaco.callwebhook.data.*
import com.sharaco.callwebhook.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CallLogReader @Inject constructor(@ApplicationContext private val context: Context, private val dao: CallDao) {
    fun permitted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    suspend fun scan(config: SyncConfig, from: Long): Long = withContext(Dispatchers.IO) {
        check(permitted()) { "Call history permission is disabled. Enable it in Device Setup." }
        var marker = config.marker
        val columns = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
        val cursor = context.contentResolver.query(CallLog.Calls.CONTENT_URI, columns, "${CallLog.Calls.DATE} >= ?", arrayOf(from.toString()), "${CallLog.Calls.DATE} ASC")
            ?: error("Android call history is unavailable. Please retry.")
        cursor.use {
            while (it.moveToNext()) {
                val row = it.getString(0)
                val timestamp = it.getLong(3)
                val item = LocalCall(callId(config.installation, row, timestamp), config.scope, config.installation, row, it.getString(1).orEmpty(), callType(it.getInt(2)), timestamp, it.getLong(4).coerceAtLeast(0))
                val old = dao.find(item.id)
                if (old == null || old.phone != item.phone || old.type != item.type || old.seconds != item.seconds) dao.upsert(item)
                marker = maxOf(marker, timestamp)
            }
        }
        marker
    }
}
