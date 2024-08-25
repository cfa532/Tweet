package com.fireshare.tweet.tweet

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.TweetViewModel

@Composable
fun TweetDetailScreen(tweetId: MimeiId) {
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()
    val tweet = tweetFeedViewModel.getTweetById(tweetId)

    val viewModel = hiltViewModel<TweetViewModel>()
    tweet?.let {viewModel.setTweet(tweet) }?: return
    viewModel.loadComments()

    TweetItem(tweet)
    CommentFeed(tweet)
}