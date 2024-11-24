package com.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.HproseInstance.getUserBase
import com.fireshare.tweet.HproseInstance.hproseClient
import com.fireshare.tweet.HproseService
import com.fireshare.tweet.R
import com.fireshare.tweet.TweetApplication
import com.fireshare.tweet.TweetApplication.Companion.preferenceHelper
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.TweetActionListener
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.service.SnackbarController
import com.fireshare.tweet.service.SnackbarEvent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import hprose.client.HproseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel(assistedFactory = UserViewModel.UserViewModelFactory::class)
class UserViewModel @AssistedInject constructor(
    @Assisted private val userId: MimeiId,
): ViewModel(), TweetActionListener {
    private var _user = MutableStateFlow(appUser)
    val user: StateFlow<User> get() = _user.asStateFlow()

    // unpinned tweets
    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> get() = _tweets.asStateFlow()

    // pinned tweets
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
    private var initState = MutableStateFlow(true)      // initial load state

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
    var nodeId = mutableStateOf(if (user.value.nodeIds != null) user.value.nodeIds!![0] else "")

    var isPasswordVisible = mutableStateOf(false)
    var isLoading = mutableStateOf(false)
    var loginError = mutableStateOf("")
    var hasLogon = mutableStateOf(false)

    fun loadNewerTweets() {
        if (initState.value) return
        _isRefreshing.value = true
        startTimestamp = System.currentTimeMillis()
        val endTimestamp = startTimestamp - SEVEN_DAYS_IN_MILLIS
        Timber.tag("UserVM.loadNewerTweets")
            .d("startTimestamp=$startTimestamp, endTimestamp=$endTimestamp")
        getTweets()
        _isRefreshing.value = false
    }
    fun loadOlderTweets() {
        if (initState.value) return
        _isRefreshingAtBottom.value = true
        val startTimestamp = endTimestamp
        endTimestamp = startTimestamp - SEVEN_DAYS_IN_MILLIS
        Timber.tag("UserVM.loadOlderTweets")
            .d("startTimestamp=$startTimestamp, endTimestamp=$endTimestamp")
        getTweets()
        _isRefreshingAtBottom.value = false
    }

    /**
     * Whether the tweet is pinned to top list.
     * */
    fun hasPinned(tweet: Tweet): Boolean {
        return topTweets.value.any { it.mid == tweet.mid }
    }

    /**
     * User can pin or unpin any tweet, including quoted or retweet by this user.
     * */
    fun pinToTop(tweet: Tweet) {
        viewModelScope.launch(Dispatchers.IO) {
            HproseInstance.toggleTopList(tweet.mid)

            // Check if tweet is already in topTweets
            val isInTopTweets = topTweets.value.any { it.mid == tweet.mid }
            if (isInTopTweets) {
                // Remove from topTweets, add to tweets
                _topTweets.update { it.filterNot { existingTweet -> existingTweet.mid == tweet.mid } }
                _tweets.update { (it + tweet).sortedByDescending {t-> t.timestamp } }
            } else {
                // Remove from tweets, add to topTweets
                _tweets.update { it.filterNot { existingTweet -> existingTweet.mid == tweet.mid } }
                _topTweets.update { (it + tweet).sortedByDescending {t-> t.timestamp } }
            }
        }
    }

    fun updateAvatar(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            // For now, user avatar can only be image.
            HproseInstance.uploadToIPFS(context, uri)?.mid?.let {
                HproseInstance.setUserAvatar(userId, it)   // Update database value
                _user.value = user.value.copy(avatar = it)
                appUser = appUser.copy(avatar = it)
            }
            isLoading.value = false
        }
    }

    fun toggleFollow(userId: MimeiId, updateTweetFeed: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            // toggle the Following status on the given UserId
            HproseInstance.toggleFollowing(userId)?.let { isFollowing ->
                // toggle following succeed, now it is the other party's turn
                // to update its follower.
                HproseInstance.toggleFollower(userId, isFollowing)
                _followings.update { list ->
                    if (isFollowing) {
                        if (!list.contains(userId)) list + userId else list
                    } else {
                        list.filter { id -> id != userId }
                    }
                }
                updateTweetFeed(isFollowing)
            }
        }
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
                if (userId == appUser.mid) {
                    // By default NOT to update fans and followings list of an user object.
                    // Do it only when opening the user's profile page.
                    // Only get current user's fans list when opening the app.
                    updateFollowingsAndFans()
                }
            }
        }
    }

    fun getTweets() {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Fetch all tweets of the author and update _tweets
            val pinnedTweets = mutableSetOf<Tweet>()
            val tweetsByUser = MutableStateFlow<List<Tweet>>(emptyList())
            HproseInstance.getTweetList(user.value, tweetsByUser, startTimestamp, endTimestamp)

            // 2. Get pinned tweets and update _topTweets, while avoiding duplication
            HproseInstance.getTopList(user.value)?.forEach { mid ->
                val tweet = tweetsByUser.value.find { it.mid == mid }
                if (tweet != null) {
                    // Remove from all tweets and add to topTweets
                    tweetsByUser.update { it.filterNot { existingTweet -> existingTweet.mid == mid } }
                    pinnedTweets.add(tweet)
                } else {
                    HproseInstance.getTweet(mid, user.value.mid)?.let { tweet1 ->
                        tweet1.originalTweetId?.let {
                            tweet1.originalAuthorId?.let { it1 ->
                                tweet1.originalTweet = HproseInstance.getTweet(it, it1)
                            }
                        }
                        pinnedTweets.add(tweet1)
                    }
                }
            }

            // 3. Filter tweetsList to exclude those in topTweets and _tweets, and update _tweets
            _tweets.update { currentTweets ->
                (currentTweets + tweetsByUser.value).distinctBy { it.mid }
                    .sortedByDescending { it.timestamp }
            }
            _topTweets.update {currentTweets ->
                (currentTweets + pinnedTweets.toList()).distinctBy { it.mid }
                    .sortedByDescending { it.timestamp }
            }
            initState.value = false
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
            ) {
                val ret = HproseInstance.login(username.value!!, password.value)
                isLoading.value = false

                if (ret.second != null) {
                    loginError.value = ret.second.toString()
                } else {
                    val u = ret.first as User
                    preferencesHelper.setUserId(u.mid)
                    appUser = u
                    _user.value = u
                    hasLogon.value = true
                }
            } else {
                loginError.value = context.getString(R.string.username_required)
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
     * Handle both register and update of user profile. Username, password are required.
     * */
    fun register(context: Context, popBack: () -> Unit) {
        if (username.value?.isEmpty() == true
            || password.value.isEmpty()
        ) {
            var message = ""
            if (username.value?.isEmpty() == true) {
                message = context.getString(R.string.username_required)
            } else if (password.value.isEmpty()) {
                message = context.getString(R.string.password_required)
            }
            val event = SnackbarEvent(
                message = message
            )
            showSnackbar(event)
            return
        }

        isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            if (nodeId.value.isNotEmpty()) {
                // Find IP of desired node.
                val ip = HproseInstance.getNodeIP(nodeId.value)
                if (ip == null) {
                    showSnackbar(SnackbarEvent(message = context.getString(R.string.node_not_found)))
                    isLoading.value = false
                    return@launch
                } else {
                    // register user on desired node, and use it henceforth.
                    appUser = appUser.copy(baseUrl = "http://$ip")
                    hproseClient = HproseClient.create("${appUser.baseUrl}/webapi/")
                        .useService(HproseService::class.java)
                }
            }
            val user = User(
                mid = appUser.mid, name = name.value,
                username = username.value, password = password.value,
                profile = profile.value, avatar = appUser.avatar
            )
            val ret = HproseInstance.setUserData(user)
            if (ret != null) {
                if (ret["status"] == "success") {
                    val gson = Gson()
                    val type = object : TypeToken<User>() {}.type
                    val u: User = gson.fromJson(ret["user"].toString(), type)
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
                        appUser = appUser.copy(
                            name = u.name, profile = u.profile, username = u.username
                        )
                        _user.value = appUser
                        u.name?.let { preferenceHelper.saveName(it) }
                        u.profile?.let { preferenceHelper.saveProfile(it) }

                        val event = SnackbarEvent(
                            message = context.getString(R.string.profile_update_ok)
                        )
                        showSnackbar(event)
                    }
                } else {
                    showSnackbar(SnackbarEvent(message = ret["reason"].toString()))
                }
            } else {
                showSnackbar(SnackbarEvent(message = context.getString(R.string.registration_failed)))
            }
            isLoading.value = false

        }
    }

    suspend fun getSuggestions(query: String): List<String> {
        val suggestions = mutableListOf<String>()

        // Check fans
        fans.value.forEach { fanId ->
            getUserBase(fanId)?.let { fan ->
                if (fan.username?.startsWith(query, ignoreCase = true) == true) {
                    suggestions.add(fan.username!!)
                }
            }
        }

        // Check followings
        followings.value.forEach { followingId ->
            getUserBase(followingId)?.let { following ->
                if (following.username?.startsWith(query, ignoreCase = true) == true) {
                    suggestions.add(following.username!!)
                }
            }
        }

        return suggestions.distinct()
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
    fun onNodeIdChange(value: String) {
        nodeId.value = value
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
        _topTweets.update { topTweets -> topTweets.filterNot { it.mid == tweetId } }
        _tweets.update { currentTweets -> currentTweets.filterNot { it.mid == tweetId } }
    }
}
