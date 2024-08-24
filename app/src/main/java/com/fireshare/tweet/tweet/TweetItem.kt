package com.fireshare.tweet.tweet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.viewmodel.TweetViewModel

@Composable
fun TweetItem(
    tweet: Tweet,
) {
    var viewModel: TweetViewModel = hiltViewModel(key = tweet.mid)
    viewModel.setTweet(tweet)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {

        // Content body
        if (tweet.originalTweetId != null) {
            if (tweet.content == "") {
                // this is a retweet of another tweet.
                Text(
                    text = "Forwarded by you",
                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 2.dp)
                )
                tweet.originalTweet?.let {
                    viewModel.setTweet(it)
                    // retweet shares the same viewModel
                    viewModel = hiltViewModel(key = tweet.originalTweetId)
                    TweetBody(it, viewModel)
                }
            } else {
                // retweet with comments
                TweetHeader(tweet)
                Text(
                    text = tweet.content,
                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 12.dp)
                )
                tweet.originalTweet?.let {
                    TweetBody(it, viewModel)
                }
            }
        } else {
            // original tweet by current user.
            viewModel.setTweet(tweet)
            TweetBody(tweet, viewModel)
        }
    }
}