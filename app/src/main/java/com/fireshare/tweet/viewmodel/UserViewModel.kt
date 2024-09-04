package com.fireshare.tweet.viewmodel

import androidx.compose.runtime.mutableLongStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.network.HproseInstance
import com.fireshare.tweet.network.HproseInstance.appUser
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = UserViewModel.UserViewModelFactory::class)
class UserViewModel @AssistedInject constructor(
    @Assisted private val userId: MimeiId?
): ViewModel() {
    private var _user = MutableStateFlow<User>(appUser)
    val user: StateFlow<User> get() = _user.asStateFlow()
    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> get() = _tweets.asStateFlow()

    private var startTimestamp = mutableLongStateOf(System.currentTimeMillis())     // current time
    private var endTimestamp =
        mutableLongStateOf(System.currentTimeMillis() - 1000 * 60 * 60 * 72)     // previous time

    @AssistedFactory
    interface UserViewModelFactory {
        fun create(userId: MimeiId): UserViewModel
    }

    init {
        if (userId != null) {
            // read data from db
            viewModelScope.launch {
                _user.value = HproseInstance.getUserBase(userId) ?: return@launch
            }
//            getTweets(startTimestamp.longValue, endTimestamp.longValue)
        } else {
            // this happens only when a unregistered user opens the App
            // do nothing.
        }
    }

    private fun getTweets(
        startTimestamp: Long = System.currentTimeMillis(),
        endTimestamp: Long? = null
    ) {
        if (userId == null) return
        viewModelScope.launch {
            HproseInstance.getTweetList(userId, _tweets.value.toMutableList(), startTimestamp, endTimestamp)
            _tweets.update { currentTweets -> currentTweets + _tweets.value }
        }
    }
}
