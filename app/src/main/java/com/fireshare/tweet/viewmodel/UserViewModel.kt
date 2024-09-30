package com.fireshare.tweet.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.service.SnackbarController
import com.fireshare.tweet.service.SnackbarEvent
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@HiltViewModel(assistedFactory = UserViewModel.UserViewModelFactory::class)
class UserViewModel @AssistedInject constructor(
    @Assisted private val userId: MimeiId,
): ViewModel() {
    private var _user = MutableStateFlow(appUser)
    val user: StateFlow<User> get() = _user.asStateFlow()
    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> get() = _tweets.asStateFlow()

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

    fun loadNewerTweets() {
        println("UserVM at top already")
        _isRefreshing.value = true
        startTimestamp = System.currentTimeMillis()
        val endTimestamp = startTimestamp - SEVEN_DAYS_IN_MILLIS
        Log.d("UserVM.loadNewerTweets", "startTimestamp=$startTimestamp, endTimestamp=$endTimestamp")
        getTweets()
        _isRefreshing.value = false
    }
    suspend fun loadOlderTweets() {
        println("UserVM at bottom already")
        _isRefreshingAtBottom.value = true
        val startTimestamp = endTimestamp
        endTimestamp = startTimestamp - SEVEN_DAYS_IN_MILLIS
        Log.d("UserVM.loadOlderTweets", "startTimestamp=$startTimestamp, endTimestamp=$endTimestamp")
        delay(500)
        getTweets()
        _isRefreshingAtBottom.value = false
    }
    fun isLoggedIn(): Boolean {
        // check if the current user is guest user.
        val log = preferencesHelper.getUserId()
        return log.isEmpty() || log != TW_CONST.GUEST_ID
    }
    fun hidePhrase() {
        // Even after user logout, its key phrase may still on the device,
        // for future convenience. Hide it in case someone else tries to register a new account.
        keyPhrase.value = ""
    }

    fun updateAvatar(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.let { stream ->
                isLoading.value = true

                // Decode the image from the input stream
                val originalBitmap = BitmapFactory.decodeStream(stream)
                var compressedBitmap: Bitmap? = null
                var quality = 80
                val outputStream = ByteArrayOutputStream()

                // Compress the image to be less than 1MB
                do {
                    outputStream.reset()
                    originalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                    quality -= 5
                } while (outputStream.size() > 500_000 && quality > 0)

                // Convert the compressed image to an input stream
                val compressedStream = ByteArrayInputStream(outputStream.toByteArray())

                val mimeiId = HproseInstance.uploadToIPFS(compressedStream)
                if (userId != TW_CONST.GUEST_ID && mimeiId != null) {
                    // Update avatar for logged-in user right away
                    // Otherwise, wait for user to submit.
                    HproseInstance.setUserAvatar(userId, mimeiId)   // Update database value
                    appUser.avatar = mimeiId
                    _user.value = user.value.copy(avatar = mimeiId)
                }
                isLoading.value = false
            }
        }
    }

    fun toggleFollow(userId: MimeiId) {
        viewModelScope.launch(Dispatchers.IO) {
            // toggle the Following status on the given UserId
            HproseInstance.toggleFollowing(userId)?.let {
                // toggle following succeed, now it is the other party's turn
                // to update follower.
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
//                getTweets(startTimestamp.longValue, endTimestamp.longValue)
            }
        }
    }

    fun getTweets() {
        viewModelScope.launch(Dispatchers.IO) {
            val user = getUserBase(userId) ?: return@launch
            Log.d("UserViewModel.getTweets", "user=$user")
            val tweetsList = HproseInstance.getTweetList(user, startTimestamp, endTimestamp)

            // Update _tweets with tweetList, replacing duplicates and ensuring no duplicates
            val updatedTweets = _tweets.value.toMutableList() // Get current tweets from _tweets
            tweetsList.forEach { newTweet ->
                val existingTweetIndex = updatedTweets.indexOfFirst { it.mid == newTweet.mid }
                if (existingTweetIndex != -1) {
                    updatedTweets[existingTweetIndex] = newTweet // Replace existing tweet
                } else {
                    updatedTweets.add(newTweet) // Add new tweet
                }
            }
            _tweets.value = updatedTweets.distinctBy { it.mid }.sortedByDescending { it.timestamp } // Update _tweets with the final result
        }
    }

    fun showSnackbar(event: SnackbarEvent) {
        viewModelScope.launch { SnackbarController.sendEvent(event) }
    }

    fun login(): User? {
        isLoading.value = true
        if (username.value?.isNotEmpty() == true
            && password.value.isNotEmpty()
            && keyPhrase.value?.isNotEmpty() == true) {
            val user =
                HproseInstance.login(username.value!!, password.value, keyPhrase.value!!)
            isLoading.value = false
            if (user == null) {
                loginError.value = "Login failed"
                preferencesHelper.saveKeyPhrase("")
                preferencePhrase = ""
                keyPhrase.value = null
                return null
            } else {
                preferencesHelper.saveKeyPhrase(keyPhrase.value!!)
                preferencesHelper.setUserId(user.mid)
                appUser = user
                _user.value = user
                return user
            }
        } else {
            loginError.value = "Login failed"
            preferencesHelper.saveKeyPhrase("")
            preferencePhrase = ""
            keyPhrase.value = null
            isLoading.value = false
            return null
        }
    }

    fun logout() {
        appUser = User(mid = TW_CONST.GUEST_ID, baseUrl = HproseInstance.BASE_URL)
        appUser.followingList = HproseInstance.getAlphaIds()
        preferencesHelper.setUserId(null)
    }

    // handle both register and update of user profile
    fun register(context: Context, popBack: () -> Unit) {
        if (username.value?.isNotEmpty() == true
            && password.value.isNotEmpty()
            && keyPhrase.value?.isNotEmpty() == true
        ) {
            isLoading.value = true
            val user = User(
                mid = TW_CONST.GUEST_ID, name = name.value,
                username = username.value, password = password.value,
                profile = profile.value, avatar = appUser.avatar
            )
            viewModelScope.launch(Dispatchers.IO) {
                HproseInstance.setUserData(user, keyPhrase.value!!)?.let { it1 ->
                    if (appUser.mid == TW_CONST.GUEST_ID) {
                        appUser = it1
                        val event = SnackbarEvent(
                            message = context.getString(R.string.registration_ok)
                        )
                        showSnackbar(event)
                        viewModelScope.launch(Dispatchers.Main) {
                            popBack()
                        }
                    } else {
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
}
