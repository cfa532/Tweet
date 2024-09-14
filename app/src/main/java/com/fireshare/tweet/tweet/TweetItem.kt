package com.fireshare.tweet.tweet

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.UserAvatar

@Composable
fun TweetItem(
    tweet: Tweet,
    parentEntry: NavBackStackEntry,      // navGraph scoped
) {
    var viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
        parentEntry,    // keep the tweetViewModel NavGraph scoped.
        key = tweet.mid
    ) { factory ->
        factory.create(tweet)
    }
    // Log the tweet ID to see if the component is being recomposed
    Log.d("TweetItem", "Displaying tweet with ID: ${tweet.mid}")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(0.dp)
    ) {
        // Content body
        if (tweet.originalTweet != null) {
            if (tweet.content == "") {
                // this is a retweet of another tweet.
                Spacer(modifier = Modifier.padding(8.dp))
                Box {
                    // The tweet area
                    viewModel =
                        hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                            parentEntry,
                            key = tweet.originalTweetId
                        )
                        { factory -> factory.create(tweet.originalTweet!!) }
                    TweetBlock(viewModel)

                    // Label: Forward by user, on top of original tweet
                    Box {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_squarepath),
                            contentDescription = "Forward",
                            modifier = Modifier
                                .size(40.dp)
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
                Column(modifier = Modifier.padding(start = 8.dp))
                {
                    TweetItemHeader(tweet)
                    tweet.content?. let {
                        Text(
                            modifier = Modifier.padding(start = 16.dp),
                            text = it,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Surface(
                        border = BorderStroke(0.8.dp, color = Color.LightGray),
                        tonalElevation = 1.dp,
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp)
                    ) {
                        TweetBlock( viewModel  =
                        hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                            parentEntry,
                            key = tweet.originalTweetId
                        )
                        { factory -> factory.create(tweet.originalTweet!!) }, true)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp)
                    ) {
                        // State hoist
                        LikeButton(viewModel)
                        Spacer(modifier = Modifier.width(8.dp))
                        BookmarkButton(viewModel)
                        Spacer(modifier = Modifier.width(8.dp))
                        CommentButton(viewModel)
                        Spacer(modifier = Modifier.width(8.dp))
                        RetweetButton(viewModel)
                    }
                }
            }
        } else {
            // original tweet by current user.
            TweetBlock(viewModel)
        }
    }
}

// Tweet header when displayed as an item in a list.
@Composable
fun TweetItemHeader(tweet: Tweet) {
    // Use a Row to align author name and potential verification badge
    val viewModel = hiltViewModel<TweetFeedViewModel>()
    val navController = LocalNavController.current
    val author = tweet.author

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.navigate(NavTweet.UserProfile(tweet.authorId)) })
            {
                UserAvatar(author, 36)
            }
            Spacer(modifier = Modifier.padding(horizontal = 2.dp))
            Text(text = author?.name ?: "No One", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.padding(horizontal = 2.dp))
            Text(text = "@${author?.username}", style = MaterialTheme.typography.bodySmall)
        }

        // the 3 dots at the right end
        TweetDropdownMenu(tweet, viewModel, navController)
    }
}