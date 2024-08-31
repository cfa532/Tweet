package com.fireshare.tweet.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.network.HproseInstance
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

@HiltViewModel(assistedFactory = TweetViewModel.TweetViewModelFactory::class)
class TweetViewModel @AssistedInject constructor(
    @Assisted private val tweet: Tweet,
) : ViewModel()
{
    @AssistedFactory
    interface TweetViewModelFactory {
        fun create(tweet: Tweet): TweetViewModel
    }
    private val _tweetState = MutableStateFlow<Tweet>(tweet)
    val tweetState: StateFlow<Tweet> get() = _tweetState

    private val _comments = MutableStateFlow<List<Tweet>>(emptyList())
    val comments: StateFlow<List<Tweet>> get() = _comments.asStateFlow()

    fun loadComments(pageNumber: Number = 0) {
        viewModelScope.launch(Dispatchers.Default) {
            tweetState.value.mid?.let { mid ->
                _comments.value = HproseInstance.getCommentList(mid)
            }
        }
    }

    fun updateTweet(tweet: Tweet) {
        _tweetState.value = tweet.copy()
    }

    fun getCommentById(commentId: MimeiId): Tweet? {
        return comments.value.find { it.mid == commentId }
    }

    // add new Comment object to its parent Tweet
    fun uploadComment(comment: Tweet, updateTweetFeed: (Tweet) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // comment is changed within uploadComment()
                val updatedTweet = HproseInstance.uploadComment(tweetState.value, comment)
                updateTweet(updatedTweet)
                updateTweetFeed(updatedTweet)
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

    fun likeTweet(updateTweetFeed: (Tweet) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updatedTweet = HproseInstance.likeTweet(tweetState.value)
                updateTweet(updatedTweet)
                updateTweetFeed(updatedTweet)
            } catch (e: Exception) {
                // Handle the like error (e.g., show a toast)
            }
        }
    }

    fun bookmarkTweet(updateTweetFeed: (Tweet) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updatedTweet = HproseInstance.bookmarkTweet(tweetState.value)
                updateTweet(updatedTweet)
                updateTweetFeed(updatedTweet)
            } catch (e: Exception) {
                // Handle the bookmark error (e.g., show a toast)
            }
        }
    }
}

//    private val viewModelStoreOwner = requireNotNull(
//        LocalViewModelStoreOwner.current as? HasDefaultViewModelProviderFactory
//    )
