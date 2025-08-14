package us.fireshare.tweet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import timber.log.Timber
import us.fireshare.tweet.R
import us.fireshare.tweet.TweetActivity

/**
 * Manages system bar notifications (status bar notifications) for various app events
 * Works alongside LauncherBadgeManager to provide multiple notification methods
 */
object SystemNotificationManager {
    
    // Use default notification channel
    private const val DEFAULT_CHANNEL = "default"
    
    // Notification IDs
    private const val NOTIFICATION_ID_CHAT_MESSAGE = 1001
    private const val NOTIFICATION_ID_SYSTEM_UPDATE = 1003
    
    /**
     * Initialize default notification channel (required for Android 8+)
     */
    fun initializeChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Default Channel
            val defaultChannel = NotificationChannel(
                DEFAULT_CHANNEL,
                context.getString(R.string.app_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_default_desc)
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                setAllowBubbles(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            
            notificationManager.createNotificationChannels(listOf(defaultChannel))
            Timber.d("Default notification channel initialized")
        }
    }
    
    /**
     * Show notification for new chat message
     */
    fun showChatMessageNotification(
        context: Context,
        senderName: String,
        messagePreview: String,
        messageCount: Int = 1
    ) {
        Timber.d("Attempting to show chat message notification: $senderName - $messagePreview")
        
        if (!NotificationPermissionManager.isNotificationPermissionGranted(context)) {
            Timber.w("Notification permission not granted, skipping chat message notification")
            return
        }
        
        // Check if app is in foreground - if so, don't show notification
        val isAppInForeground = try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val appProcesses = activityManager.runningAppProcesses
            appProcesses?.any { it.processName == context.packageName && it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND } ?: false
        } catch (e: Exception) {
            Timber.e(e, "Error checking app foreground state")
            false
        }
        
        if (isAppInForeground) {
            Timber.d("App is in foreground, skipping notification")
            return
        }
        
        try {
            val intent = Intent(context, TweetActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_to", "chat_list")
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID_CHAT_MESSAGE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val title = if (messageCount == 1) {
                context.getString(R.string.notification_new_chat_message_simple)
            } else {
                context.getString(R.string.notification_new_chat_messages_simple, messageCount)
            }
            
            val notification = NotificationCompat.Builder(context, DEFAULT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notice)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.notification_tap_to_view))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setNumber(messageCount)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_CHAT_MESSAGE, notification)
            } catch (e: SecurityException) {
                Timber.e(e, "Security exception when showing notification - permission may be denied")
            }
            Timber.d("Chat message notification shown: $title")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to show chat message notification")
        }
    }
    

    
    /**
     * Show notification for system updates or important events
     */
    fun showSystemNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = NOTIFICATION_ID_SYSTEM_UPDATE
    ) {
        if (!NotificationPermissionManager.isNotificationPermissionGranted(context)) {
            Timber.d("Notification permission not granted, skipping system notification")
            return
        }
        
        try {
            val intent = Intent(context, TweetActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val notification = NotificationCompat.Builder(context, DEFAULT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notice)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            
            try {
                NotificationManagerCompat.from(context).notify(notificationId, notification)
            } catch (e: SecurityException) {
                Timber.e(e, "Security exception when showing notification - permission may be denied")
            }
            Timber.d("System notification shown: $title")
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to show system notification")
        }
    }
    
    /**
     * Clear all notifications
     */
    fun clearAllNotifications(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancelAll()
            Timber.d("All notifications cleared")
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear notifications")
        }
    }
    
    /**
     * Clear specific notification by ID
     */
    fun clearNotification(context: Context, notificationId: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
            Timber.d("Notification cleared: $notificationId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear notification: $notificationId")
        }
    }
    
    /**
     * Check if notifications are enabled for the app
     */
    fun areNotificationsEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    
    /**
     * Test notification for debugging purposes
     */
    fun testNotification(context: Context) {
        Timber.d("Testing notification...")
        showChatMessageNotification(
            context,
            "Test User",
            "This is a test notification message",
            1
        )
    }
    
    /**
     * Get notification debug info
     */
    fun getNotificationDebugInfo(context: Context): String {
        val permissionGranted = NotificationPermissionManager.isNotificationPermissionGranted(context)
        val notificationsEnabled = areNotificationsEnabled(context)
        val channelExists = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.getNotificationChannel(DEFAULT_CHANNEL) != null
        } else {
            true
        }
        
        return """
            Notification Debug Info:
            - Permission Granted: $permissionGranted
            - Notifications Enabled: $notificationsEnabled
            - Channel Exists: $channelExists
            - Android Version: ${android.os.Build.VERSION.SDK_INT}
        """.trimIndent()
    }
} 