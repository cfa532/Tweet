package com.fireshare.tweet.tweet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.viewmodel.TweetViewModel

@Composable
fun CommentHead(tweet: Tweet) {
    var viewModel = hiltViewModel<TweetViewModel>(key = tweet.mid)
    viewModel.setTweet(tweet)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(0.dp)
    ) {
        if (tweet.originalTweetId != null) {
            if (tweet.content == "") {
                // this is a retweet of another tweet.
                Box {
                    // The tweet area
                    tweet.originalTweet?.let {
                        // retweet shares the same viewModel
                        viewModel = hiltViewModel(key = tweet.originalTweetId)
                        viewModel.setTweet(it)
                        TweetBlock(it, viewModel)
                    }

                    // The Text() that you want to move downward
                    Box {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_squarepath),
                            contentDescription = "Forward",
                            modifier = Modifier.size(40.dp)
                                .padding(start = 50.dp)
                                .offset(y = (-4).dp) // Adjust the offset value as needed
                                .zIndex(1f) // Ensure it appears above the tweet area
                        )
                        Text(
                            text = "Forwarded by you",
                            fontSize = MaterialTheme.typography.labelSmall.fontSize,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .padding(start = 60.dp)
                                .offset(y = (-4).dp) // Adjust the offset value as needed
                                .zIndex(1f) // Ensure it appears above the tweet area
                        )
                    }
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
                    TweetBlock(it, viewModel)
                }
            }
        } else {
            // original tweet by current user.
            TweetBlock(tweet, viewModel)
        }
    }
}