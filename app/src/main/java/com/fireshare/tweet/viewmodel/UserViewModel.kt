package com.fireshare.tweet.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.HproseInstance.getUserBase
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

    companion object {
        private const val THIRTY_DAYS_IN_MILLIS = 2_592_000_000L
        private const val SEVEN_DAYS_IN_MILLIS = 648_000_000L
    }
    private var startTimestamp = mutableLongStateOf(System.currentTimeMillis())     // current time
    private var endTimestamp =
        mutableLongStateOf(startTimestamp.longValue - THIRTY_DAYS_IN_MILLIS)     // previous time

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

    fun updateAvatar(context: Context, userId: MimeiId, uri: Uri) {
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
                    _user.value = appUser.copy()
                }
                isLoading.value = false
            }
        }
    }

    fun toggleFollow(userId: MimeiId, updateFollowed: (MimeiId) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            // toggle the Following status on the given UserId
            HproseInstance.toggleFollowing(userId)?.let {
                _followings.update { list ->
                    if (it && !list.contains(userId)) { list + userId }
                    else { list.filter { id -> id != userId }}
                }
                HproseInstance.toggleFollower(userId)?.let {
                    // update account of the followed UserID
                    updateFollowed(userId)
                }
            }
        }
    }

    fun updateFans() {
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
        if (userId == TW_CONST.GUEST_ID)
            return
        viewModelScope.launch(Dispatchers.IO) {
            val user = getUserBase(userId) ?: return@launch    // author of the list of tweet
            Log.d("UserViewModel.getTweets()", "user=$user")
            val tweetsList = HproseInstance.getTweetList(user, startTimestamp.longValue, endTimestamp.longValue)
            _tweets.update { currentTweets -> currentTweets + tweetsList.sortedByDescending { it.timestamp } }
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
        appUser = User(mid = TW_CONST.GUEST_ID)
        appUser.followingList = HproseInstance.getAlphaIds()
        preferencesHelper.setUserId(null)
    }

    // handle both register and update of user profile
    fun register(popBack: () -> Unit) {
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
                            message = "Registration succeeded."
                        )
                        showSnackbar(event)
                        popBack()
                    } else {
                        val event = SnackbarEvent(
                            message = "User profile updated successfully"
                        )
                        showSnackbar(event)
                    }
                }
                isLoading.value = false
            }
        } else {
            var message = ""
            if (username.value?.isEmpty() == true) {
                message = "Username is required to register and login."
            } else if (password.value.isEmpty()) {
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
