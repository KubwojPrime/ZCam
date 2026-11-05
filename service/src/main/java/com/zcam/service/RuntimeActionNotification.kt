package com.zcam.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object RuntimeActionNotification {
    private const val CHANNEL = "zcam.runtime.action"
    private const val ID = 5106

    fun show(context: Context, cameraActivation: Boolean) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val pending = PendingIntent.getActivity(
            context, ID, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "ZCam action needed", NotificationManager.IMPORTANCE_DEFAULT))
        val message = if (cameraActivation) "Open ZCam on this device and turn the camera on."
            else "Tap to restore the server. Android blocked automatic recovery."
        try {
            manager.notify(ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(if (cameraActivation) "Camera activation needed" else "ZCam server stopped")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pending)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build())
        } catch (_: SecurityException) {
            // Notification permission can be revoked between the check and notify.
        }
    }

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(ID)
}
