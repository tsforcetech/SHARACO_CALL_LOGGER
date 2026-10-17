package com.sharaco.callwebhook

import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.sharaco.callwebhook.sync.CallLogReader
import com.sharaco.callwebhook.sync.SyncWork
import com.sharaco.callwebhook.ui.AppViewModel
import com.sharaco.callwebhook.ui.CallTrackRoot
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint class MainActivity : ComponentActivity() {
    @Inject lateinit var reader: CallLogReader
    private val model: AppViewModel by viewModels()
    private var observing = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { SyncWork.now(this@MainActivity) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CallTrackRoot(model) }
    }
    override fun onResume() {
        super.onResume()
        model.permissionsChanged()
        if (reader.permitted() && !observing) {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            observing = true
        }
        SyncWork.now(this)
    }
    override fun onPause() {
        if (observing) { contentResolver.unregisterContentObserver(observer); observing = false }
        super.onPause()
    }
}
