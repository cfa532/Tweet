package com.fireshare.tweet.viewmodel

import androidx.lifecycle.ViewModel
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
    val tweetState: StateFlow<Tweet> get() = _tweetState.asStateFlow()

    private val _comments = MutableStateFlow<List<Tweet>>(emptyList())
    val comments: StateFlow<List<Tweet>> get() = _comments.asStateFlow()

    fun loadComments(tweet: Tweet, pageNumber: Number = 0) {
        viewModelScope.launch(Dispatchers.Default) {
            _comments.value = HproseInstance.getComments(tweet).map {
                it.author = HproseInstance.getUserBase(it.authorId)
                it
            }
        }
    }

    fun updateTweet(tweet: Tweet) {
        _tweetState.value = tweet
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
//                updateTweetFeed(updatedTweet)
                addComment(comment)
            } catch (e: Exception) {
                println(e.message)
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
//                updateTweetFeed(updatedTweet)
            } catch (e: Exception) {
                // Handle the like error (e.g., show a toast)
                println(e.message)
            }
        }
    }

    fun bookmarkTweet(updateTweetFeed: (Tweet) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updatedTweet = HproseInstance.bookmarkTweet(tweetState.value)
                updateTweet(updatedTweet)
//                updateTweetFeed(updatedTweet)
            } catch (e: Exception) {
                // Handle the bookmark error (e.g., show a toast)
                println(e.message)
            }
        }
    }
}

//    private val viewModelStoreOwner = requireNotNull(
//        LocalViewModelStoreOwner.current as? HasDefaultViewModelProviderFactory
//    )
