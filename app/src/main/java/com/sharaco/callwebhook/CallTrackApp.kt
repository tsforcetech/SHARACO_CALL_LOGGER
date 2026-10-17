package com.sharaco.callwebhook

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp class CallTrackApp : Application() {
    override fun onCreate() { super.onCreate(); configureAppCheck() }
}
