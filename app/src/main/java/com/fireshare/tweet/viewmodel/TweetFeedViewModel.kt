package com.fireshare.tweet.viewmodel

import androidx.compose.runtime.mutableLongStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.network.HproseInstance
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@HiltViewModel
class TweetFeedViewModel @Inject constructor(
) : ViewModel() {
    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> get() = _tweets.asStateFlow()

    private var startTimestamp = mutableLongStateOf(System.currentTimeMillis())     // current time
    private var endTimestamp = mutableLongStateOf(System.currentTimeMillis() - 1000 * 60 * 60 * 72)     // previous time

    init {
//        getTweets(startTimestamp.longValue, endTimestamp.longValue)
        getTweets(startTimestamp.longValue)
    }

    // given a tweet, update its counterpart in Tweet list
    fun updateTweet(tweet: Tweet) {
        _tweets.value = _tweets.value.map {
            if (it.mid == tweet.mid) tweet else tweet
        }
    }

    // get tweet from preload tweet feed list
    fun getTweetById(tweetId: MimeiId): Tweet? {
        return tweets.value.find { it.mid == tweetId }
    }

    fun addTweet(tweet: Tweet) {
        _tweets.update { currentTweets -> listOf(tweet) + currentTweets }
    }

    fun delTweet(tweetId: MimeiId) {
        _tweets.update { currentTweets ->
            currentTweets.filterNot { it.mid == tweetId }
        }
    }

    fun toggleRetweet(tweet: Tweet, viewModel: TweetViewModel) {
        viewModelScope.launch(Dispatchers.Default) {
            // tweet object is updated in toggleRetweet()
            HproseInstance.toggleRetweet( tweet, this@TweetFeedViewModel )
//            viewModel.updateTweet(tweet)
        }
    }

    private fun getTweets(
        startTimestamp: Long = System.currentTimeMillis(),
        endTimestamp: Long? = null
    ) {
        viewModelScope.launch {
            val followings = HproseInstance.getFollowings()
            coroutineScope {  // Create a child coroutine scope
                followings.forEach { userId ->
                    launch(Dispatchers.IO) {
                        val tweetsList = _tweets.value.filter { it.authorId == userId }.toMutableList()
                        HproseInstance.getTweetList(userId, tweetsList, startTimestamp, endTimestamp)
                        _tweets.update { currentTweets -> currentTweets + tweetsList }
                    }
                }
            }
        }
    }

    fun uploadTweet(tweet: Tweet) {
        viewModelScope.launch(Dispatchers.IO) {
            HproseInstance.uploadTweet(tweet)?.let { newTweet: Tweet ->
                _tweets.update { currentTweets ->
                    // add new tweet at top of the list
                    listOf(newTweet) + currentTweets
                }
            }
        }
    }
}