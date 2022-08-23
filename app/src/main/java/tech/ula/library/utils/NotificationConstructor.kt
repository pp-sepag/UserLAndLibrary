package tech.ula.library.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import tech.ula.customlibrary.BuildConfig
import tech.ula.library.MainActivity
import tech.ula.library.R
import tech.ula.library.ServerService

class NotificationConstructor(val context: Context) {

    companion object {
        const val serviceNotificationId = 1000
        const val GROUP_KEY_USERLAND = "tech.ula.userland"
        const val serviceNotificationChannelId = "UserLAnd"
    }

    private val serviceNotificationTitle = context.getString(R.string.app_name)
    private val serviceNotificationDescription = context.getString(R.string.service_notification_description, context.getString(R.string.app_name))

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    fun createServiceNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.services_notification_channel_name)
            val description = context.getString(R.string.services_notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW

            val channel = NotificationChannel(serviceNotificationChannelId, name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildPersistentServiceNotification(): Notification {
        val sessionListIntent = Intent(context, MainActivity::class.java)
        sessionListIntent.type = "sessionList"
        val pendingSessionListIntent = PendingIntent
                .getActivity(context, 0, sessionListIntent, 0)

        val stopSessionsIntent = Intent(context, ServerService::class.java).putExtra("type", "stopAll")
        val stopSessionsPendingIntent = PendingIntent
                .getService(context, 0, stopSessionsIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        val settingsIntent = Intent(context, MainActivity::class.java)
        settingsIntent.type = "settings"
        val settingsPendingIntent = PendingIntent
                .getActivity(context, 0, settingsIntent, 0)

        val builder = NotificationCompat.Builder(context, serviceNotificationChannelId)
                .setSmallIcon(R.drawable.ic_stat_icon)
                .setContentTitle(serviceNotificationTitle)
                .setContentText(serviceNotificationDescription)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setGroup(GROUP_KEY_USERLAND)
                .setGroupSummary(true)
                .setAutoCancel(false)
                .setContentIntent(pendingSessionListIntent)
                .addAction(0, "Stop Sessions", stopSessionsPendingIntent)

        if (!context.defaultSharedPreferences.getBoolean("pref_hide_settings", BuildConfig.DEFAULT_HIDE_SETTINGS)) {
            builder.addAction(0, "Settings", settingsPendingIntent)
        }

        return builder.build()
    }
}
