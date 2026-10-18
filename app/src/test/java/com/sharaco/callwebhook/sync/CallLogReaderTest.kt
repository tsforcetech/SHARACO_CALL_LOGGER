package com.sharaco.callwebhook.sync

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CallLog
import androidx.room.Room
import com.sharaco.callwebhook.data.*
import com.sharaco.callwebhook.domain.callId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class CallLogReaderTest {
    private lateinit var db: LocalDatabase
    private lateinit var reader: CallLogReader
    private lateinit var provider: HistoryProvider
    private val config =
        SyncConfig(
            "11111111-1111-1111-1111-111111111111",
            "company",
            "employee",
            1000L,
            365L,
            0L,
            0L,
            0L,
            "",
        )

    @Before
    fun setup() {
        val app = RuntimeEnvironment.getApplication<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALL_LOG)
        db =
            Room.inMemoryDatabaseBuilder(app, LocalDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        reader = CallLogReader(app, db.calls())
        provider = HistoryProvider()
        ShadowContentResolver.registerProviderInternal(CallLog.AUTHORITY, provider)
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun readsIncomingOutgoingMissedAndReconcilesDuration() = runBlocking {
        provider.rows =
            listOf(
                arrayOf("1", "+971500000001", 1, 2000L, 15L),
                arrayOf("2", "+971500000002", 2, 3000L, 0L),
                arrayOf("3", "", 3, 4000L, 0L),
                arrayOf("0", "old", 1, 500L, 20L),
            )
        assertEquals(4000L, reader.scan(config, config.initial))
        assertEquals(3, db.calls().pendingCount(config.scope))
        assertEquals("INCOMING", db.calls().find(callId(config.installation, "1", 2000L))!!.type)
        assertEquals("OUTGOING", db.calls().find(callId(config.installation, "2", 3000L))!!.type)
        assertEquals("MISSED", db.calls().find(callId(config.installation, "3", 4000L))!!.type)
        assertNull(db.calls().find(callId(config.installation, "0", 500L)))
        val id = callId(config.installation, "2", 3000L)
        db.calls().confirm(id, 0, "OUTGOING")
        provider.rows = listOf(arrayOf("2", "+971500000002", 2, 3000L, 45L))
        reader.scan(config, config.initial)
        assertEquals(45L, db.calls().find(id)!!.seconds)
        assertFalse(db.calls().find(id)!!.synced)
    }

    @Test
    fun deniedPermissionNeverQueriesHistory() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication<Application>())
            .denyPermissions(Manifest.permission.READ_CALL_LOG)
        try {
            reader.scan(config, config.initial)
            fail("Expected denied permission")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("permission"))
        }
        assertEquals(0, provider.queries)
    }

    @Test
    fun unavailableProviderIsNotReportedAsEmptyHistory() = runBlocking {
        provider.unavailable = true
        try {
            reader.scan(config, config.initial)
            fail("Expected provider error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("unavailable"))
        }
    }

    class HistoryProvider : ContentProvider() {
        var rows: List<Array<Any>> = emptyList()
        var queries = 0
        var unavailable = false

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            queries++
            if (unavailable) return null
            assertEquals("date >= ?", selection)
            assertEquals("date ASC", sortOrder)
            val from = selectionArgs!![0].toLong()
            return MatrixCursor(projection!!).apply {
                rows
                    .filter { (it[3] as Long) >= from }
                    .sortedBy { it[3] as Long }
                    .forEach { addRow(it) }
            }
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            0
    }
}
