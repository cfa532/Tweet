package com.fireshare.tweet.tweet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.viewmodel.TweetViewModel

@Composable
fun TweetItem(
    tweet: Tweet,
    tweetViewModel: TweetViewModel = TweetViewModel(tweet)
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {

        // Content body
        if (tweet.originalTweetId != null) {
            tweet.originalTweet?.let { tweetViewModel.setTweet(it) }
            if (tweet.content == "") {
                // this is a retweet of another tweet.
                Text(
                    text = "Forwarded by you",
                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 2.dp)
                )
                tweet.originalTweet?.let {
                    TweetBody(it, tweetViewModel)
                }
            } else {
                // retweet with comments
                TweetHeader(tweet, tweetViewModel)
                Text(
                    text = tweet.content,
                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 12.dp)
                )
                tweet.originalTweet?.let {
                    TweetBody(it, tweetViewModel)
                }
            }
        } else {
            // original tweet by current user.
            tweetViewModel.setTweet(tweet)
            TweetBody(tweet, tweetViewModel)
        }
    }
}