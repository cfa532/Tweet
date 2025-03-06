package us.fireshare.tweet.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import us.fireshare.tweet.HproseInstance
import kotlin.concurrent.timer

class BottomBarViewModel : ViewModel() {
    private val _badgeCount = MutableStateFlow<Int?>(null)
    val badgeCount: StateFlow<Int?> get() = _badgeCount

    fun updateBadgeCount(count: Int? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            // check for new messages and update the badge count in the main page bottom bar
            if (count != null) _badgeCount.value = count
            else
                _badgeCount.value = HproseInstance.checkNewMessages()?.size
        }
    }

    init {
        timer(period = 900000, action = {
            updateBadgeCount()
        }, initialDelay = 30000)
    }
}