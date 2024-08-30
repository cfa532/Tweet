package com.fireshare.tweet.viewmodel

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.TweetKey
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
    private val key: TweetKey,
    private val viewModelStoreOwner: ViewModelStoreOwner
) : ViewModel(), LifecycleObserver
{
    private var tweetFeedViewModel: TweetFeedViewModel = getTweetFeedVM()
    private var tweet: Tweet? = tweetFeedViewModel.getTweetById(key.tweetId)

    private val _tweetState = MutableStateFlow(tweet)
    val tweetState: MutableStateFlow<Tweet?> get() = _tweetState

    private val _comments = MutableStateFlow<List<Tweet>>(emptyList())
    val comments: StateFlow<List<Tweet>> get() = _comments.asStateFlow()

    private fun getTweetFeedVM(): TweetFeedViewModel {
        return ViewModelProvider(viewModelStoreOwner, ViewModelProvider.NewInstanceFactory())[TweetFeedViewModel::class.java]
    }

    fun loadComments(pageNumber: Number = 0) {
        viewModelScope.launch(Dispatchers.Default) {
            tweetState.value?.mid?.let { mid ->
                _comments.value = HproseInstance.getCommentList(mid) ?: emptyList()
            }
        }
    }

    fun updateTweet(tweet: Tweet) {
        _tweetState.value = tweet
    }

    fun getCommentById(commentId: MimeiId): Tweet? {
        return comments.value.find { it.mid == commentId }
    }

    fun uploadComment(comment: Tweet) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // comment is changed within uploadComment()
                val updatedTweet = tweetState.value?.let { HproseInstance.uploadComment(it, comment) }
                addComment(comment)
                if (updatedTweet != null) {
                    updateTweet(updatedTweet)
                    tweetFeedViewModel.updateTweet(updatedTweet)
                }
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
                val updatedTweet = tweetState.value?.let { HproseInstance.likeTweet(it) }
                if (updatedTweet != null) {
                    updateTweet(updatedTweet)
                    tweetFeedViewModel.updateTweet(updatedTweet)
                }
            } catch (e: Exception) {
                // Handle the like error (e.g., show a toast)
            }
        }
    }

    fun bookmarkTweet() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updatedTweet = tweetState.value?.let { HproseInstance.bookmarkTweet(it) }
                if (updatedTweet != null) {
                    updateTweet(updatedTweet)
                    tweetFeedViewModel.updateTweet(updatedTweet)
                }
            } catch (e: Exception) {
                // Handle the bookmark error (e.g., show a toast)
            }
        }
    }
}