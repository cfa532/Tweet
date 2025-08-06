package us.fireshare.tweet.service

import android.content.Context
import me.leolin.shortcutbadger.ShortcutBadger
import timber.log.Timber

/**
 * Manages launcher badge count on the device's app icon.
 * Uses ShortcutBadger library to support various launcher implementations.
 * Shows numbers 1-9, and "n" for 10 or more messages.
 */
object LauncherBadgeManager {
    
    /**
     * Updates the launcher badge count
     * Shows numbers 1-9, and "n" for 10 or more messages
     * @param context Application context
     * @param count Badge count to display (0 to clear badge)
     */
    fun updateBadgeCount(context: Context, count: Int) {
        try {
            if (count > 0) {
                // Format badge text: 1-9 show actual numbers, 10+ show "n"
                val badgeText = when {
                    count <= 9 -> count.toString()
                    else -> "n"
                }
                
                val success = ShortcutBadger.applyCount(context, count)
                if (success) {
                    Timber.tag("LauncherBadgeManager").d("Badge count updated to: $badgeText (actual count: $count)")
                } else {
                    Timber.tag("LauncherBadgeManager").w("Failed to update badge count to: $badgeText (actual count: $count)")
                }
            } else {
                // Clear badge when count is 0
                val success = ShortcutBadger.removeCount(context)
                if (success) {
                    Timber.tag("LauncherBadgeManager").d("Badge cleared successfully")
                } else {
                    Timber.tag("LauncherBadgeManager").w("Failed to clear badge")
                }
            }
        } catch (e: Exception) {
            Timber.tag("LauncherBadgeManager").e(e, "Error updating launcher badge")
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
            ShortcutBadger.isBadgeCounterSupported(context)
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
} 