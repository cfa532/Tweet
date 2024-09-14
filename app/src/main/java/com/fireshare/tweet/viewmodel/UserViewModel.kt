package com.fireshare.tweet.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.TweetApplication
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.navigation.NavTweet
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

    private var startTimestamp = mutableLongStateOf(System.currentTimeMillis())     // current time
    private var endTimestamp =
        mutableLongStateOf(System.currentTimeMillis() - 1000 * 60 * 60 * 72)     // previous time

    // variable for login management
    private val preferencesHelper = TweetApplication.preferencesHelper
    var username = mutableStateOf(user.value.username)
    var password = mutableStateOf("")
    var name = mutableStateOf(user.value.name)
    var profile = mutableStateOf(user.value.profile)
    var keyPhrase = mutableStateOf(preferencesHelper.getKeyPhrase())
    var isPasswordVisible = mutableStateOf(false)

    var isLoading = mutableStateOf(false)
    var loginError = mutableStateOf("")

    val followButtonText: String =
        when(appUser.mid) {
            userId -> { if (appUser.mid != TW_CONST.GUEST_ID) "Edit" else "Login" }
            else -> {
                if (appUser.followingList?.contains(userId) == true && appUser.mid != TW_CONST.GUEST_ID)
                    "Unfollow"
                else "Follow"
            }
        }

    fun updateAvatar(context: Context, userId: MimeiId, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.let { stream ->
                isLoading.value = true

                // Decode the image from the input stream
                val originalBitmap = BitmapFactory.decodeStream(stream)
                var compressedBitmap: Bitmap? = null
                var quality = 100
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
                    _user.value = appUser.copy()
                }
                isLoading.value = false
            }
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

                _fans.value = HproseInstance.getFans(user.value) ?: emptyList()
                if (userId != appUser.mid) {
                    // followings of current user has been loaded at startup
                    _followings.value = HproseInstance.getFollowings(user.value) ?: emptyList()
                }
            }
        }
    }

    // whether the userId is in current user's following list
    fun isFollowing(userId: MimeiId): Boolean {
        return followings.value.contains(userId)
    }

    fun getFollows(user: User) {
        viewModelScope.launch(Dispatchers.IO) {
            _fans.value = HproseInstance.getFans(user) ?: emptyList()
            _followings.value = HproseInstance.getFollowings(user) ?: emptyList()
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

    fun showSnackbar(event: SnackbarEvent) {
        viewModelScope.launch { SnackbarController.sendEvent(event) }
    }

    fun login(): Boolean {
        isLoading.value = true
        if (username.value?.isNotEmpty() == true
            && password.value.isNotEmpty()
            && keyPhrase.value?.isNotEmpty() == true) {
            val user =
                HproseInstance.login(username.value!!, password.value, keyPhrase.value!!)
            isLoading.value = false
            if (user == null) {
                loginError.value = "Login failed"
                return false
            } else {
                preferencesHelper.saveKeyPhrase(keyPhrase.value!!)
                preferencesHelper.setUserId(user.mid)
                appUser = user
                return true
            }
        }
        return false
    }

    fun logout(navController: NavController) {
        appUser = User(mid = TW_CONST.GUEST_ID)
        preferencesHelper.setUserId(null)
        navController.navigate(NavTweet.TweetFeed)
    }

    // handle both register and update of user profile
    fun register() {
        viewModelScope.launch(Dispatchers.IO) {
            if (username.value?.isNotEmpty() == true
                && password.value.isNotEmpty()
                && keyPhrase.value?.isNotEmpty() == true)
            {
                isLoading.value = true
                val user = User(mid = TW_CONST.GUEST_ID, name = name.value,
                    username = username.value, password = password.value,
                    profile = profile.value, avatar = appUser.avatar
                )
                HproseInstance.setUserData(user, keyPhrase.value!!)?.let { it1 ->
                    appUser = it1
                    // Do NOT save phrase or userId until user has successfully logon.
                }
                isLoading.value = false
            } else {
                var message: String = ""
                if (username.value?.isEmpty() == true) {
                    message = "Username is required to register and login."
                } else if (password.value.isEmpty())  {
                    message = "Password is required to register and login."
                } else if (keyPhrase.value?.isEmpty() == true) {
                    message = "Key phrase is required to register and login."
                }
                val event = SnackbarEvent(
                    message = message
                )
                showSnackbar(event)
            }
        }
    }
    fun onUsernameChange(value: String) {
        username.value = value.trim()
        isLoading.value = false
    }
    fun onNameChange(value: String) {
        name.value = value
        isLoading.value = false
    }
    fun onProfileChange(value: String) {
        profile.value = value
        isLoading.value = false
    }
    fun onKeyPhraseChange(phrase: String) {
        keyPhrase.value = phrase
        isLoading.value = false
    }
    fun onPasswordChange(pwd: String) {
        password.value = pwd.trim()
        isLoading.value = false
    }
    fun onPasswordVisibilityChange() {
        isPasswordVisible.value = ! isPasswordVisible.value
    }
}
