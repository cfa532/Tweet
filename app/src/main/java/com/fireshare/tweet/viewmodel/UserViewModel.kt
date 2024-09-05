package com.fireshare.tweet.viewmodel

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.TW_CONST
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
    @Assisted private val userId: MimeiId
): ViewModel() {
    private var _user = MutableStateFlow(User(mid = userId))
    val user: StateFlow<User> get() = _user.asStateFlow()

    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> get() = _tweets.asStateFlow()

    private var _fans = MutableStateFlow(emptyList<MimeiId>())
    val fans: StateFlow<List<MimeiId>> get() = _fans.asStateFlow()

    private var _followings = MutableStateFlow(emptyList<MimeiId>())
    val followings: StateFlow<List<MimeiId>> get() = _followings.asStateFlow()

    private var startTimestamp = mutableLongStateOf(System.currentTimeMillis())     // current time
    private var endTimestamp =
        mutableLongStateOf(System.currentTimeMillis() - 1000 * 60 * 60 * 72)     // previous time

    val followButtonText: String =
        when(userId) {
            appUser.mid -> "Edit"
            else -> {
                if (appUser.followingList.contains(userId))
                    "Unfollow"
                else "Follow"
            }
        }

    fun toggleFollow(userId: MimeiId) {
        viewModelScope.launch(Dispatchers.IO) {
            HproseInstance.toggleFollowing(userId)?.let { _followings.value = it }
            HproseInstance.toggleFollower(userId)?.let { _fans.value = it}
        }
    }

    @AssistedFactory
    interface UserViewModelFactory {
        fun create(userId: MimeiId): UserViewModel
    }

    init {
        if (userId != TW_CONST.GUEST_ID) {
            // read data from db
            viewModelScope.launch(Dispatchers.IO) {
                _user.value = HproseInstance.getUserBase(userId) ?: return@launch
                getTweets()
            }
            if (userId == appUser.mid)
                viewModelScope.launch(Dispatchers.IO) {
                    HproseInstance.getFollowings(appUser)
                    HproseInstance.getFans(appUser)
                    _fans.value = appUser.fansList
                    _followings.value = appUser.followingList
                }
        }
    }

    private fun getTweets(
        startTimestamp: Long = System.currentTimeMillis(),
        endTimestamp: Long? = null
    ) {
        if (userId == TW_CONST.GUEST_ID) return
        viewModelScope.launch(Dispatchers.IO) {
            val tweetsList = _tweets.value.toMutableList()
            HproseInstance.getTweetList(userId, tweetsList, startTimestamp, endTimestamp)
            _tweets.update { currentTweets -> currentTweets + tweetsList }
        }
    }
}
