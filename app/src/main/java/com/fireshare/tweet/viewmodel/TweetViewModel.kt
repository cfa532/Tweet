package com.fireshare.tweet.viewmodel

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.network.HproseInstance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TweetViewModel @Inject constructor(
    tweet: Tweet
) : ViewModel()
{
    private val _tweet = MutableStateFlow<Tweet?>(tweet)
    val tweet: StateFlow<Tweet?> get() = _tweet.asStateFlow()
    private val _comments = MutableStateFlow<List<Tweet>>(emptyList())
    val comments: StateFlow<List<Tweet>> get() = _comments

    fun loadComments(pageNumber: Number = 0) {
        viewModelScope.launch(Dispatchers.Default) {
            tweet.value?.let {
                _comments.value = it.mid?.let { it1 -> HproseInstance.getCommentList(it1) }!!
            }
        }
    }

    fun getCommentById(commentId: MimeiId): Tweet? {
        return comments.value.find { it.mid == commentId }
    }

    fun uploadComment(tweet: Tweet, comment: Tweet, updateTweetFeed: (Tweet) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            _tweet.value = HproseInstance.uploadComment(tweet, comment)
            updateTweetFeed(_tweet.value as Tweet)
            addComment(comment)     // add it to top of comment list
        }
    }

    private fun addComment(comment: Tweet) {
        _comments.update { newComments ->
            listOf(comment) + newComments
        }
    }

    fun likeTweet(tweet: Tweet, updateTweetFeed: (Tweet) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _tweet.value = HproseInstance.likeTweet(tweet)
                updateTweetFeed(_tweet.value as Tweet)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun bookmarkTweet(tweet: Tweet, updateTweetFeed: (Tweet) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _tweet.value = HproseInstance.bookmarkTweet(tweet)
                updateTweetFeed(_tweet.value as Tweet)  // update single source of truth
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setTweet(tweet: Tweet) {
        _tweet.value = tweet
    }
}
