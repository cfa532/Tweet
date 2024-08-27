package com.fireshare.tweet.tweet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import com.fireshare.tweet.LocalNavController
import com.fireshare.tweet.R
import com.fireshare.tweet.UserProfile
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.UserAvatar

@Composable
fun TweetItem(
    tweet: Tweet,
    parentEntry: NavBackStackEntry
) {
    var viewModel = hiltViewModel<TweetViewModel>(parentEntry, key = tweet.mid)
    viewModel.setTweet(tweet)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(0.dp)
    ) {
        // Content body
        if (tweet.originalTweetId != null) {
            if (tweet.content == "") {
                // this is a retweet of another tweet.
                Spacer(modifier = Modifier.padding(8.dp))

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

@Composable
fun TweetHeader(tweet: Tweet) {
    // Use a Row to align author name and potential verification badge
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        val navController = LocalNavController.current
        val author = tweet.author
        IconButton(onClick = { navController.navigate(UserProfile(tweet.authorId)) })
        {
            UserAvatar(author, 40)
        }
        Spacer(modifier = Modifier.padding(horizontal = 2.dp))
        Text(text = author?.name ?: "No One", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.padding(horizontal = 2.dp))
        Text(text = "@${author?.username}", style = MaterialTheme.typography.bodySmall)
    }
}