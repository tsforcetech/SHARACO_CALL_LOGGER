package com.sharaco.callwebhook.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.store by preferencesDataStore("calltrack")
data class SyncConfig(val installation: String, val company: String, val uid: String, val initial: Long, val retention: Long, val marker: Long, val fullScan: Long, val success: Long, val error: String) {
    val scope get() = "$company:$uid"
}
@Singleton class Preferences @Inject constructor(@ApplicationContext private val context: Context) {
    companion object {
        val installation = stringPreferencesKey("installation")
        val company = stringPreferencesKey("company")
        val uid = stringPreferencesKey("uid")
        val initial = longPreferencesKey("initial")
        val retention = longPreferencesKey("retention")
        val marker = longPreferencesKey("marker")
        val fullScan = longPreferencesKey("fullScan")
        val success = longPreferencesKey("success")
        val error = stringPreferencesKey("error")
        val theme = stringPreferencesKey("theme")
    }
    val themeFlow = context.store.data.map { it[theme] ?: "SYSTEM" }
    val statusFlow = context.store.data.map { (it[success] ?: 0L) to (it[error] ?: "") }
    suspend fun config(): SyncConfig {
        context.store.edit { if (it[installation] == null) it[installation] = UUID.randomUUID().toString() }
        val p = context.store.data.first()
        return SyncConfig(p[installation].orEmpty(), p[company].orEmpty(), p[uid].orEmpty(), p[initial] ?: 0L, p[retention] ?: 365L, p[marker] ?: 0L, p[fullScan] ?: 0L, p[success] ?: 0L, p[error].orEmpty())
    }
    suspend fun edit(block: (MutablePreferences) -> Unit) { context.store.edit(block) }
    suspend fun clearSession() { context.store.edit { p -> val install = p[installation]; val appearance = p[theme]; p.clear(); install?.let { p[installation] = it }; appearance?.let { p[theme] = it } } }
}
