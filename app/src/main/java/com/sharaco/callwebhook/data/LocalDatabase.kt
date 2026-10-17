package com.sharaco.callwebhook.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "calls",
    indices = [Index(value = ["scope", "timestamp"]), Index(value = ["scope", "synced"])],
)
data class LocalCall(
    @PrimaryKey val id: String,
    val scope: String,
    val deviceId: String,
    val androidId: String,
    val phone: String,
    val type: String,
    val timestamp: Long,
    val seconds: Long,
    val synced: Boolean = false,
    val error: String = "",
) {
    fun payload() =
        mapOf(
            "androidCallLogId" to androidId,
            "phoneNumber" to phone,
            "callType" to type,
            "callTimestamp" to timestamp,
            "durationSeconds" to seconds,
        )
}

@Dao
interface CallDao {
    @Query("SELECT * FROM calls WHERE scope = :scope ORDER BY timestamp DESC LIMIT :limit")
    fun observe(scope: String, limit: Int): Flow<List<LocalCall>>

    @Query("SELECT COUNT(*) FROM calls WHERE scope = :scope AND synced = 0")
    fun observePending(scope: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM calls WHERE scope = :scope AND synced = 0")
    suspend fun pendingCount(scope: String): Int

    @Query("SELECT * FROM calls WHERE id = :id") suspend fun find(id: String): LocalCall?

    @Upsert suspend fun upsert(call: LocalCall)

    @Query("SELECT * FROM calls WHERE scope = :scope AND synced = 0 ORDER BY timestamp LIMIT 100")
    suspend fun pending(scope: String): List<LocalCall>

    @Query(
        "UPDATE calls SET synced = 1, error = '' WHERE id = :id AND seconds = :seconds AND type = :type"
    )
    suspend fun confirm(id: String, seconds: Long, type: String)

    @Query("UPDATE calls SET error = :error WHERE scope = :scope AND synced = 0")
    suspend fun failure(scope: String, error: String)

    @Query("DELETE FROM calls WHERE scope = :scope AND timestamp < :cutoff")
    suspend fun prune(scope: String, cutoff: Long)

    @Query("DELETE FROM calls") suspend fun clear()
}

@Database(entities = [LocalCall::class], version = 1, exportSchema = true)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun calls(): CallDao
}
