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
    
    /**
     * Initialize the BadgeStateManager with application context
     * @param context Application context
     */
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        Timber.tag("BadgeStateManager").d("Initialized with context: ${context.packageName}")
    }
    
    fun updateBadgeCount(count: Int) {
        val previousCount = _badgeCount.value
        _badgeCount.value = count
        updateLauncherBadge(count)
        
        val formattedText = LauncherBadgeManager.formatBadgeText(count)
        Timber.tag("BadgeStateManager").d("Badge updated: $previousCount -> $count (display: '$formattedText')")
    }
    
    fun clearBadge() {
        val previousCount = _badgeCount.value
        _badgeCount.value = 0
        updateLauncherBadge(0)
        Timber.tag("BadgeStateManager").d("Badge cleared: $previousCount -> 0")
    }
    
    fun incrementBadge() {
        val newCount = _badgeCount.value + 1
        _badgeCount.value = newCount
        updateLauncherBadge(newCount)
        
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
    
    /**
     * Get the formatted badge text for display
     * @return Formatted text: "1"-"9" for counts 1-9, "n" for 10+, empty string for 0
     */
    fun getFormattedBadgeText(): String {
        return LauncherBadgeManager.formatBadgeText(_badgeCount.value)
    }
} 