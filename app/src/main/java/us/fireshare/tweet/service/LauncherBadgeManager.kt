package us.fireshare.tweet.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import me.leolin.shortcutbadger.ShortcutBadger
import timber.log.Timber

/**
 * Manages launcher badge count on the device's app icon.
 * Uses Android's official notification badge system (Android 8.0+) when available,
 * falls back to ShortcutBadger library for older devices or unsupported launchers.
 * Shows numbers 1-9, and "n" for 10 or more messages.
 */
object LauncherBadgeManager {
    
    private const val BADGE_NOTIFICATION_ID = 1001
    private const val BADGE_CHANNEL_ID = "badge_channel"
    private const val BADGE_CHANNEL_NAME = "Badge Notifications"
    
    /**
     * Updates the launcher badge count
     * Shows numbers 1-9, and "n" for 10 or more messages
     * @param context Application context
     * @param count Badge count to display (0 to clear badge)
     */
    fun updateBadgeCount(context: Context, count: Int) {
        try {
            // Log device and launcher information for debugging
            logDeviceInfo(context)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Use Android's official notification badge system (Android 8.0+)
                updateNotificationBadge(context, count)
            } else {
                // Fall back to ShortcutBadger for older Android versions
                updateShortcutBadger(context, count)
            }
        } catch (e: Exception) {
            Timber.tag("LauncherBadgeManager").e(e, "Error updating launcher badge")
        }
    }
    
    /**
     * Updates badge using Android's official notification system (Android 8.0+)
     */
    private fun updateNotificationBadge(context: Context, count: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Create notification channel for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BADGE_CHANNEL_ID,
                BADGE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(true)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        if (count > 0) {
            // Show notification with badge
            val notification = NotificationCompat.Builder(context, BADGE_CHANNEL_ID)
                .setContentTitle("New Messages")
                .setContentText("You have $count new message${if (count > 1) "s" else ""}")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setNumber(count)
                .setAutoCancel(false)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            
            notificationManager.notify(BADGE_NOTIFICATION_ID, notification)
            Timber.tag("LauncherBadgeManager").d("Notification badge set to: $count")
        } else {
            // Clear notification badge
            notificationManager.cancel(BADGE_NOTIFICATION_ID)
            Timber.tag("LauncherBadgeManager").d("Notification badge cleared")
        }
    }
    
    /**
     * Updates badge using ShortcutBadger library (fallback for older devices)
     */
    private fun updateShortcutBadger(context: Context, count: Int) {
        if (count > 0) {
            // Format badge text: 1-9 show actual numbers, 10+ show "n"
            val badgeText = when {
                count <= 9 -> count.toString()
                else -> "n"
            }
            
            val success = ShortcutBadger.applyCount(context, count)
            if (success) {
                Timber.tag("LauncherBadgeManager").d("ShortcutBadger count updated to: $badgeText (actual count: $count)")
            } else {
                Timber.tag("LauncherBadgeManager").w("Failed to update ShortcutBadger count to: $badgeText (actual count: $count)")
                logBadgeFailureInfo(context, "update", count)
            }
        } else {
            // Clear badge when count is 0
            val success = ShortcutBadger.removeCount(context)
            if (success) {
                Timber.tag("LauncherBadgeManager").d("ShortcutBadger cleared successfully")
            } else {
                Timber.tag("LauncherBadgeManager").w("Failed to clear ShortcutBadger")
                logBadgeFailureInfo(context, "clear", 0)
            }
        }
    }
    
    /**
     * Clears the launcher badge
     * @param context Application context
     */
    fun clearBadge(context: Context) {
        updateBadgeCount(context, 0)
    }
    
    /**
     * Checks if launcher badge is supported on this device
     * @param context Application context
     * @return true if supported, false otherwise
     */
    fun isBadgeSupported(context: Context): Boolean {
        return try {
            // Android 8.0+ always supports notification badges
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Timber.tag("LauncherBadgeManager").d("Badge support: true (Android 8.0+ notification badges)")
                return true
            }
            
            // For older versions, check ShortcutBadger support
            val supported = ShortcutBadger.isBadgeCounterSupported(context)
            Timber.tag("LauncherBadgeManager").d("Badge support check: $supported (ShortcutBadger)")
            supported
        } catch (e: Exception) {
            Timber.tag("LauncherBadgeManager").e(e, "Error checking badge support")
            false
        }
    }
    
    /**
     * Formats badge text for display
     * @param count The actual count of new messages
     * @return Formatted text: "1"-"9" for counts 1-9, "n" for 10+
     */
    fun formatBadgeText(count: Int): String {
        return when {
            count <= 0 -> ""
            count <= 9 -> count.toString()
            else -> "n"
        }
    }
    
    /**
     * Log device and launcher information for debugging badge issues
     */
    private fun logDeviceInfo(context: Context) {
        val deviceInfo = """
            Device Info:
            - Manufacturer: ${Build.MANUFACTURER}
            - Model: ${Build.MODEL}
            - Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            - Package: ${context.packageName}
            - Badge Supported: ${isBadgeSupported(context)}
            - Using: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "Notification Badges" else "ShortcutBadger"}
        """.trimIndent()
        
        Timber.tag("LauncherBadgeManager").d(deviceInfo)
    }
    
    /**
     * Log detailed information when badge operations fail
     */
    private fun logBadgeFailureInfo(context: Context, operation: String, count: Int) {
        val failureInfo = """
            Badge $operation failed:
            - Operation: $operation
            - Count: $count
            - Manufacturer: ${Build.MANUFACTURER}
            - Model: ${Build.MODEL}
            - Android Version: ${Build.VERSION.RELEASE}
            - Badge Supported: ${isBadgeSupported(context)}
            - Method: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "Notification Badges" else "ShortcutBadger"}
        """.trimIndent()
        
        Timber.tag("LauncherBadgeManager").w(failureInfo)
    }
} 