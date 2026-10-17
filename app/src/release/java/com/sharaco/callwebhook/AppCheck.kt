package com.sharaco.callwebhook
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
fun configureAppCheck() { FirebaseAppCheck.getInstance().installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance()) }
