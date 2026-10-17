package com.sharaco.callwebhook.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sharaco.callwebhook.MainActivity
import com.sharaco.callwebhook.R

class MessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "company",
                "Company updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val intent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        manager.notify(
            System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(this, "company")
                .setSmallIcon(R.drawable.ic_brand)
                .setContentTitle(message.notification?.title ?: getString(R.string.app_name))
                .setContentText(message.notification?.body)
                .setContentIntent(intent)
                .setAutoCancel(true)
                .build(),
        )
    }
}
