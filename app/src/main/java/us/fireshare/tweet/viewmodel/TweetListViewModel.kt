package us.fireshare.tweet.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.Tweet
import javax.inject.Inject

@HiltViewModel
class TweetListViewModel @Inject constructor() : ViewModel() {
    
    private val _videoIndexedList = MutableStateFlow<List<Pair<MimeiId, MediaType>>>(emptyList())
    val videoIndexedList: StateFlow<List<Pair<MimeiId, MediaType>>> get() = _videoIndexedList.asStateFlow()
    
    private val _tweetList = MutableStateFlow<List<Tweet>>(emptyList())

    fun setVideoIndexedList(videoList: List<Pair<MimeiId, MediaType>>) {
        _videoIndexedList.value = videoList
    }
    
    fun setTweetList(tweets: List<Tweet>) {
        _tweetList.value = tweets
    }
    
    fun findStartIndexForVideoMid(videoMid: MimeiId): Int {
        val index = _videoIndexedList.value.indexOfFirst { (mid, _) -> mid == videoMid }
        // Return 0 instead of -1 to prevent IndexOutOfBoundsException in pager
        return if (index >= 0) index else 0
    }
}
