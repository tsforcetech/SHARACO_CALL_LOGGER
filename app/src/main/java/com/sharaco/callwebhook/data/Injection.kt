package com.sharaco.callwebhook.data

import android.content.Context
import androidx.room.Room
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object Injection {
    @Provides @Singleton fun auth(): FirebaseAuth = FirebaseAuth.getInstance()
    @Provides @Singleton fun firestore(): FirebaseFirestore = FirebaseFirestore.getInstance()
    @Provides @Singleton fun functions(): FirebaseFunctions = FirebaseFunctions.getInstance("europe-west1")
    @Provides @Singleton fun database(@ApplicationContext context: Context): LocalDatabase = Room.databaseBuilder(context, LocalDatabase::class.java, "calltrack.db").build()
    @Provides fun dao(db: LocalDatabase): CallDao = db.calls()
}
