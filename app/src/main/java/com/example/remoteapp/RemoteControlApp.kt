package com.example.remoteapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class RemoteControlApp : Application() {

    companion object {
        const val CHANNEL_ID = "remote_control_channel"
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Remote session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while a remote viewing/control session is active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
