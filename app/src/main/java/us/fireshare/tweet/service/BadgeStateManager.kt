package us.fireshare.tweet.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

object BadgeStateManager {
    private val _badgeCount = MutableStateFlow<Int>(0)
    val badgeCount: StateFlow<Int> = _badgeCount.asStateFlow()
    
    // Store application context for launcher badge updates
    private var applicationContext: Context? = null
    private var badgeSupported: Boolean = false
    
    /**
     * Initialize the BadgeStateManager with application context
     * @param context Application context
     */
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        badgeSupported = LauncherBadgeManager.isBadgeSupported(context.applicationContext)
        Timber.tag("BadgeStateManager").d("Initialized with context: ${context.packageName}, badge supported: $badgeSupported")
    }
    
    fun updateBadgeCount(count: Int) {
        val previousCount = _badgeCount.value
        _badgeCount.value = count
        
        if (badgeSupported) {
            updateLauncherBadge(count)
        } else {
            Timber.tag("BadgeStateManager").d("Skipping launcher badge update - not supported on this device")
        }
        
        val formattedText = LauncherBadgeManager.formatBadgeText(count)
        Timber.tag("BadgeStateManager").d("Badge updated: $previousCount -> $count (display: '$formattedText')")
    }
    
    fun clearBadge() {
        val previousCount = _badgeCount.value
        _badgeCount.value = 0
        
        if (badgeSupported) {
            updateLauncherBadge(0)
        } else {
            Timber.tag("BadgeStateManager").d("Skipping launcher badge clear - not supported on this device")
        }
        
        Timber.tag("BadgeStateManager").d("Badge cleared: $previousCount -> 0")
    }
    
    fun incrementBadge() {
        val newCount = _badgeCount.value + 1
        _badgeCount.value = newCount
        
        if (badgeSupported) {
            updateLauncherBadge(newCount)
        } else {
            Timber.tag("BadgeStateManager").d("Skipping launcher badge increment - not supported on this device")
        }
        
        val formattedText = LauncherBadgeManager.formatBadgeText(newCount)
        Timber.tag("BadgeStateManager").d("Badge incremented: ${newCount - 1} -> $newCount (display: '$formattedText')")
    }
    
    /**
     * Update launcher badge count on device icon
     * @param count Badge count to display
     */
    private fun updateLauncherBadge(count: Int) {
        applicationContext?.let { context ->
            try {
                LauncherBadgeManager.updateBadgeCount(context, count)
            } catch (e: Exception) {
                Timber.tag("BadgeStateManager").e(e, "Error updating launcher badge")
            }
        } ?: run {
            Timber.tag("BadgeStateManager").w("Application context not available for launcher badge update")
        }
    }
} 