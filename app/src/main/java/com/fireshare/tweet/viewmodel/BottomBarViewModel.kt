package com.fireshare.tweet.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.HproseInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BottomBarViewModel : ViewModel() {
    private val _badgeCount = MutableStateFlow<Int?>(null)
    val badgeCount: StateFlow<Int?> get() = _badgeCount

    fun updateBadgeCount() {
        viewModelScope.launch(Dispatchers.IO) {
            _badgeCount.value = HproseInstance.checkNewMessages()?.size
        }
    }
}