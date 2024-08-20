package com.fireshare.tweet.viewmodel

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.network.HproseInstance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TweetFeedState(val tweets: List<Tweet>)

@HiltViewModel
class TweetFeedViewModel @Inject constructor() : ViewModel()
{
//    private val _tweets = InMemoryData._tweets
    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> get() = _tweets

    private var startTimestamp = mutableLongStateOf(System.currentTimeMillis())     // current time
    private var endTimestamp = mutableLongStateOf(System.currentTimeMillis() - 1000 * 60 * 60 * 72)     // previous time

    init {
//        getTweets(startTimestamp.longValue, endTimestamp.longValue)
        getTweets(startTimestamp.longValue)
    }

    fun getTweetById(tweetId: MimeiId?): Tweet? {
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

    fun toggleRetweet(tweet: Tweet) {
        var originalTweet: Tweet = tweet
        viewModelScope.launch(Dispatchers.Default) {
            tweet.originalTweet?.let {
                // the tweet to be forwarded is a retweet itself. Find the original tweet to forward.
                if (tweet.content == "") {
                    originalTweet = it
                } else {
                    // update timestamp of the old retweet to move it forward.
                }
            }
            HproseInstance.toggleRetweet( originalTweet, ::delTweet )?.let {
//                _tweet.value = it
            }
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
            HproseInstance.uploadTweet(tweet, false)?.let { newTweet: Tweet ->
                _tweets.update { currentTweets ->
                    // add new tweet at top of the list
                    listOf(newTweet) + currentTweets
                }
            }
        }
    }
}