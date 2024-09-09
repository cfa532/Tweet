package com.fireshare.tweet.viewmodel

import android.content.Context
import android.net.Uri
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
import com.fireshare.tweet.navigation.LocalNavController
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
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            // only load current user data by default.
            if (userId == appUser.mid) getFollows(appUser)
        }
    }

    fun updateAvatar(context: Context, userId: MimeiId, uri: Uri) {
        isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.let { stream ->
                val mimeiId = HproseInstance.uploadToIPFS(stream)
                appUser.avatar = mimeiId
                if (userId != TW_CONST.GUEST_ID && mimeiId != null) {
                    // update avatar for logon user right away
                    // otherwise wait for user to submit.
                    HproseInstance.setUserAvatar(userId, mimeiId)
                }
                isLoading.value = false
            }
        }
    }

    fun getFollows(user: User) {
        viewModelScope.launch(Dispatchers.IO) {
            HproseInstance.getFollowings(user)
            HproseInstance.getFans(user)
            _fans.value = user.fansList
            _followings.value = user.followingList
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

    private fun showSnackbar(event: SnackbarEvent) {
        viewModelScope.launch { SnackbarController.sendEvent(event) }
    }

    fun onLoginClick(navController: NavController) {
        isLoading.value = true
        if (username.value?.isNotEmpty() == true && password.value.isNotEmpty()
            && keyPhrase.value?.isNotEmpty() == true) {
            val user =
                HproseInstance.login(username.value!!, password.value, keyPhrase.value!!)
            isLoading.value = false
            if (user == null) {
                loginError.value = "Login failed"
            } else {
                appUser = user
                navController.popBackStack()
            }
        }
    }
    fun register() {
        viewModelScope.launch(Dispatchers.IO) {
            if (username.value?.isNotEmpty() == true && password.value.isNotEmpty()
                && keyPhrase.value?.isNotEmpty() == true) {
                val user = User(mid = TW_CONST.GUEST_ID, name = name.value,
                    username = username.value, password = password.value,
                    profile = profile.value, avatar = appUser.avatar
                )
                HproseInstance.setUserData(user, keyPhrase.value!!)?.let { it1 ->
                    appUser = it1
                    preferencesHelper.saveKeyPhrase(keyPhrase.value!!)
                }
            } else {
                isLoading.value = false
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
    }
    fun onNameChange(value: String) {
        name.value = value
    }
    fun onProfileChange(value: String) {
        profile.value = value
    }
    fun onKeyPhraseChange(phrase: String) {
        keyPhrase.value = phrase
    }
    fun onPasswordChange(pwd: String) {
        password.value = pwd.trim()
    }
    fun onPasswordVisibilityChange() {
        isPasswordVisible.value = ! isPasswordVisible.value
    }
}
