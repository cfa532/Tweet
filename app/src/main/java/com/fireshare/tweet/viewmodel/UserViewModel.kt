package com.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.HproseInstance.getUserBase
import com.fireshare.tweet.R
import com.fireshare.tweet.TweetApplication
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.TweetActionListener
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.service.SnackbarController
import com.fireshare.tweet.service.SnackbarEvent
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = UserViewModel.UserViewModelFactory::class)
class UserViewModel @AssistedInject constructor(
    @Assisted private val userId: MimeiId,
): ViewModel(), TweetActionListener {
    private var _user = MutableStateFlow(appUser)
    val user: StateFlow<User> get() = _user.asStateFlow()

    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> get() = _tweets.asStateFlow()
    private val _topTweets = MutableStateFlow<List<Tweet>>(emptyList())
    val topTweets: StateFlow<List<Tweet>> get() = _topTweets.asStateFlow()

    private var _fans = MutableStateFlow(emptyList<MimeiId>())
    val fans: StateFlow<List<MimeiId>> get() = _fans.asStateFlow()
    private var _followings = MutableStateFlow(emptyList<MimeiId>())
    val followings: StateFlow<List<MimeiId>> get() = _followings.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshingAtTop: StateFlow<Boolean> get() = _isRefreshing.asStateFlow()
    private val _isRefreshingAtBottom = MutableStateFlow(false)
    val isRefreshingAtBottom: StateFlow<Boolean> get() = _isRefreshingAtBottom.asStateFlow()

    companion object {
        private const val THIRTY_DAYS_IN_MILLIS = 2_592_000_000L
        private const val SEVEN_DAYS_IN_MILLIS = 648_000_000L
    }
    private var startTimestamp = System.currentTimeMillis()    // current time
    private var endTimestamp = startTimestamp - THIRTY_DAYS_IN_MILLIS   // previous time

    // variable for login management
    private val preferencesHelper = TweetApplication.preferenceHelper
    var username = mutableStateOf(user.value.username)
    var password = mutableStateOf("")
    var name = mutableStateOf(user.value.name)
    var profile = mutableStateOf(user.value.profile)
    var preferencePhrase = preferencesHelper.getKeyPhrase()
    var keyPhrase = mutableStateOf(preferencePhrase)
    var isPasswordVisible = mutableStateOf(false)
    var isLoading = mutableStateOf(false)
    var loginError = mutableStateOf("")
    var hasLogon = mutableStateOf(false)

    fun loadNewerTweets() {
        println("UserVM at top already")
        _isRefreshing.value = true
        startTimestamp = System.currentTimeMillis()
        val endTimestamp = startTimestamp - SEVEN_DAYS_IN_MILLIS
        Log.d("UserVM.loadNewerTweets", "startTimestamp=$startTimestamp, endTimestamp=$endTimestamp")
        getTweets()
        _isRefreshing.value = false
    }
    fun loadOlderTweets() {
        println("UserVM at bottom already")
        _isRefreshingAtBottom.value = true
        val startTimestamp = endTimestamp
        endTimestamp = startTimestamp - SEVEN_DAYS_IN_MILLIS
        Log.d("UserVM.loadOlderTweets", "startTimestamp=$startTimestamp, endTimestamp=$endTimestamp")
        getTweets()
        _isRefreshingAtBottom.value = false
    }

    fun pinToTop(tweet: Tweet) {
        viewModelScope.launch {
            HproseInstance.addToTopList(tweet.mid!!)
            // if the tweet is already on the top list, remove it
            if (_topTweets.value.indexOf(tweet) > -1) {
                _topTweets.update { currentTopTweets ->
                    currentTopTweets.filterNot { it.mid == tweet.mid }
                }
                _tweets.update { list -> (list + tweet).sortedByDescending { it.timestamp } }
            } else {
                _tweets.update { currentTopTweets ->
                    currentTopTweets.filterNot { it.mid == tweet.mid }
                }
                _topTweets.update { list -> (list + tweet).sortedByDescending { it.timestamp } }
            }
        }
    }
    fun hidePhrase() {
        // Even after user logout, its key phrase may still on the device,
        // for future convenience. Hide it in case someone else tries to register a new account.
        keyPhrase.value = ""
    }

    fun updateAvatar(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            // For now, user avatar can only be image.
            val mimeiId = HproseInstance.uploadToIPFS(context, uri)?.mid
            if (userId != TW_CONST.GUEST_ID && mimeiId != null) {
                // Update avatar for logged-in user right away.
                // Otherwise, wait for user to submit.
                HproseInstance.setUserAvatar(userId, mimeiId)   // Update database value
                _user.value = user.value.copy(avatar = mimeiId)
                appUser = appUser.copy(avatar = mimeiId)
            }
            isLoading.value = false
        }
    }

    fun toggleFollow(userId: MimeiId, updateFollowings: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            // toggle the Following status on the given UserId
            HproseInstance.toggleFollowing(userId)?.let {
                updateFollowings()
                // toggle following succeed, now it is the other party's turn
                // to update its follower.
                HproseInstance.toggleFollower(userId)?.let {
                    _followings.update { list ->
                        if (it && !list.contains(userId)) {
                            list + userId
                        } else {
                            list.filter { id -> id != userId }
                        }
                    }
                }
            }
        }
    }

    fun removeTweet(tweetId: MimeiId) {
        _tweets.update { list -> list.filter { it.mid != tweetId } }
        _user.value = user.value.copy(tweetCount = user.value.tweetCount-1)
    }

    fun updateFollowingsAndFans() {
        viewModelScope.launch(Dispatchers.IO) {
            _fans.value = HproseInstance.getFans(user.value) ?: emptyList()
            _followings.value = HproseInstance.getFollowings(user.value) ?: emptyList()
        }
    }

    @AssistedFactory
    interface UserViewModelFactory {
        fun create(userId: MimeiId): UserViewModel
    }

    init {
        if (userId != TW_CONST.GUEST_ID) {
            viewModelScope.launch(Dispatchers.IO) {
                _user.value = getUserBase(userId) ?: return@launch
            }
        }
    }

    private suspend fun getToppedTweets() {
        val user = getUserBase(userId) ?: return
        HproseInstance.getTopList(user)?.forEach { mid ->
            HproseInstance.getTweet(mid, user.mid)?.let {
                _topTweets.update { list-> list + it }
            }
        }
        _topTweets.value = topTweets.value.distinctBy { it.mid }
            .sortedByDescending { it.timestamp }
    }

    fun getTweets() {
        viewModelScope.launch(Dispatchers.IO) {
            getToppedTweets()
            val user = getUserBase(userId) ?: return@launch
            val tweetsList = HproseInstance.getTweetList(user, startTimestamp, endTimestamp)
            val updatedTweets = tweetsList.toMutableList()
            updatedTweets.removeAll { currentTweet ->
                topTweets.value.any { it.mid == currentTweet.mid }
            }
            _tweets.update { currentTweets ->
                (updatedTweets + currentTweets).distinctBy { it.mid }
                    .sortedByDescending { it.timestamp }
            }
        }
    }

    fun showSnackbar(event: SnackbarEvent) {
        viewModelScope.launch { SnackbarController.sendEvent(event) }
    }

    fun login(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            if (username.value?.isNotEmpty() == true
                && password.value.isNotEmpty()
                && keyPhrase.value?.isNotEmpty() == true
            ) {
                val user =
                    HproseInstance.login(username.value!!, password.value, keyPhrase.value!!)
                isLoading.value = false
                if (user == null) {
                    loginError.value = context.getString(R.string.login_failed)
                    preferencesHelper.saveKeyPhrase("")
                    preferencePhrase = ""
                    keyPhrase.value = null
                } else {
                    preferencesHelper.saveKeyPhrase(keyPhrase.value!!)
                    preferencesHelper.setUserId(user.mid)
                    appUser = user
                    _user.value = user
                    hasLogon.value = true
                }
            } else {
                loginError.value = context.getString(R.string.login_failed)
                preferencesHelper.saveKeyPhrase("")
                preferencePhrase = ""
                keyPhrase.value = null
                isLoading.value = false
            }
        }
    }

    fun logout() {
        hasLogon.value = false
        appUser = User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl)
        preferencesHelper.setUserId(null)
    }

    /**
     * Handle both register and update of user profile. Username, password
     * and key phrase are all required.
     * */
    fun register(context: Context, popBack: () -> Unit) {
        if (username.value?.isNotEmpty() == true
            && password.value.isNotEmpty()
            && keyPhrase.value?.isNotEmpty() == true
        ) {
            isLoading.value = true
            val user = User( mid = appUser.mid, name = name.value,
                username = username.value, password = password.value,
                profile = profile.value, avatar = appUser.avatar
            )
            viewModelScope.launch(Dispatchers.IO) {
                HproseInstance.setUserData(user, keyPhrase.value!!)?.let {
                    if (appUser.mid == TW_CONST.GUEST_ID) {
                        // register new user. Do not update appUser, wait for
                        // new user to login.
                        val event = SnackbarEvent(
                            message = context.getString(R.string.registration_ok)
                        )
                        showSnackbar(event)
                        viewModelScope.launch(Dispatchers.Main) {
                            popBack()
                        }
                    } else {
                        // update user profile
                        appUser = appUser.copy(name = it.name, profile = it.profile,
                            username = it.username, password = it.password)
                        _user.value = appUser.copy(name = it.name, profile = it.profile,
                            username = it.username, password = it.password)
                        val event = SnackbarEvent(
                            message = context.getString(R.string.profile_update_ok)
                        )
                        showSnackbar(event)
                    }
                }
                isLoading.value = false
            }
        } else {
            var message = ""
            if (username.value?.isEmpty() == true) {
                message = context.getString(R.string.username_required)
            } else if (password.value.isEmpty()) {
                message = context.getString(R.string.password_required)
            } else if (keyPhrase.value?.isEmpty() == true) {
                message = context.getString(R.string.keyphrase_required)
            }
            val event = SnackbarEvent(
                message = message
            )
            showSnackbar(event)
        }
    }

    fun onUsernameChange(value: String) {
        username.value = value.trim()
        isLoading.value = false
        loginError.value = ""
    }
    fun onNameChange(value: String) {
        name.value = value
        isLoading.value = false
        loginError.value = ""
    }
    fun onProfileChange(value: String) {
        profile.value = value
        isLoading.value = false
        loginError.value = ""
    }
    fun onKeyPhraseChange(phrase: String) {
        keyPhrase.value = phrase
        isLoading.value = false
        loginError.value = ""
    }
    fun onPasswordChange(pwd: String) {
        password.value = pwd.trim()
        isLoading.value = false
        loginError.value = ""
    }
    fun onPasswordVisibilityChange() {
        isPasswordVisible.value = ! isPasswordVisible.value
    }

    override fun onTweetAdded(tweet: Tweet) {
        _tweets.update { currentTweets -> listOf(tweet) + currentTweets }
    }

    override fun onTweetDeleted(tweetId: MimeiId) {
        _tweets.update { currentTweets -> currentTweets.filterNot { it.mid == tweetId } }
    }
}
