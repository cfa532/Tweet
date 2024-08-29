package com.fireshare.tweet.viewmodel

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
class TweetViewModel @Inject constructor (
    private val tweet: Tweet,
) : ViewModel()
{
    private var tweetFeedViewModel: TweetFeedViewModel? = null
    private val _tweetState = MutableStateFlow(this.tweet)
    val tweetState: StateFlow<Tweet> get() = _tweetState

    private val _comments = MutableStateFlow<List<Tweet>>(emptyList())
    val comments: StateFlow<List<Tweet>> get() = _comments.asStateFlow()

    fun loadComments(pageNumber: Number = 0) {
        viewModelScope.launch(Dispatchers.Default) {
            tweetState.value.mid?.let { mid ->
                _comments.value = HproseInstance.getCommentList(mid) ?: emptyList()
            }
        }
    }

    fun updateTweet(tweet: Tweet) {
        _tweetState.value = tweet.copy()
    }

    fun getCommentById(commentId: MimeiId): Tweet? {
        return comments.value.find { it.mid == commentId }
    }

    fun uploadComment(comment: Tweet) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // comment is changed within uploadComment()
                val updatedTweet = HproseInstance.uploadComment(_tweetState.value, comment)
                _tweetState.value = updatedTweet
                tweetFeedViewModel?.updateTweet(updatedTweet)
                addComment(comment)
            } catch (e: Exception) {
                //
            }
        }
    }

    private fun addComment(comment: Tweet) {
        _comments.update { newComments ->
            listOf(comment) + newComments
        }
    }

    fun likeTweet() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updatedTweet = HproseInstance.likeTweet(_tweetState.value)
                _tweetState.value = updatedTweet
                tweetFeedViewModel?.updateTweet(updatedTweet)
            } catch (e: Exception) {
                // Handle the like error (e.g., show a toast)
            }
        }
    }

    fun bookmarkTweet() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updatedTweet = HproseInstance.bookmarkTweet(_tweetState.value)
                _tweetState.value = updatedTweet
                tweetFeedViewModel?.updateTweet(updatedTweet)
            } catch (e: Exception) {
                // Handle the bookmark error (e.g., show a toast)
            }
        }
    }

    fun init(tweet: Tweet, viewModel: TweetFeedViewModel) {
        _tweetState.value = tweet
        tweetFeedViewModel = viewModel
    }
}