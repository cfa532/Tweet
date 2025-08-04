package us.fireshare.tweet.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object BadgeStateManager {
    private val _badgeCount = MutableStateFlow<Int>(0)
    val badgeCount: StateFlow<Int> = _badgeCount.asStateFlow()
    
    fun updateBadgeCount(count: Int) {
        _badgeCount.value = count
    }
    
    fun clearBadge() {
        _badgeCount.value = 0
    }
    
    fun incrementBadge() {
        _badgeCount.value = _badgeCount.value + 1
    }
} 