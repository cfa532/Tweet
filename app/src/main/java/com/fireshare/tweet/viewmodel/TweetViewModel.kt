package com.fireshare.tweet.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    fun uploadComment(comment: Tweet) {
        viewModelScope.launch(Dispatchers.Default) {
            tweet.value?.let {
                _tweet.value = HproseInstance.addComment(it, comment)
            }
        }
    }

    fun addComment(comment: Tweet) {
        _comments.update { newComments ->
            listOf(comment) + newComments
        }
    }

    fun likeTweet(tweet: Tweet) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _tweet.value = HproseInstance.likeTweet(tweet)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun bookmarkTweet(tweet: Tweet) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _tweet.value = HproseInstance.bookmarkTweet(tweet)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setTweet(tweet: Tweet) {
        _tweet.value = tweet
    }
}
