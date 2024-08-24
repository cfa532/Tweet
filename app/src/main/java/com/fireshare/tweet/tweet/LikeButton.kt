package com.fireshare.tweet.tweet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.UserFavorites
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.TweetViewModel

@Composable
fun CommentButton(tweet: Tweet, viewModel: TweetViewModel) {
    val t by viewModel.tweet.collectAsState(initial = tweet)

    IconButton(onClick = {
    }) {
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(
                painter = painterResource(id = R.drawable.ic_notice),
                contentDescription = "comments",
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "${t?.commentCount}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun RetweetButton(tweet: Tweet, viewModel: TweetViewModel) {
    val tweetFeedViewModel: TweetFeedViewModel = hiltViewModel()
    val t by viewModel.tweet.collectAsState(initial = tweet)
    val hasRetweeted = t?.favorites?.get(UserFavorites.RETWEET)

    IconButton(onClick = {
        t?.let { tweetFeedViewModel.toggleRetweet(it) }
    }) {
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(
                painter = painterResource(id = if (hasRetweeted==true) R.drawable.ic_squarepath_prim else R.drawable.ic_squarepath),
                contentDescription = "forward",
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = if (hasRetweeted == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${t?.retweetCount}",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun LikeButton(tweet: Tweet, viewModel: TweetViewModel) {
    val t by viewModel.tweet.collectAsState(initial = tweet)
    val hasLiked = t?.favorites?.get(UserFavorites.TWEET) ?: false

    IconButton(onClick = {
        t?.let { viewModel.likeTweet(it) }
    }) {
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(
                painter = painterResource(id = if (hasLiked) R.drawable.ic_heart_fill else R.drawable.ic_heart),
                contentDescription = "Like",
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = if (hasLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${t?.likeCount}",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun BookmarkButton(tweet: Tweet, viewModel: TweetViewModel) {
    val t by viewModel.tweet.collectAsState(initial = tweet)
    val hasBookmarked = t?.favorites?.get(UserFavorites.BOOKMARK) ?: false
    IconButton(onClick = {
        t?.let { viewModel.bookmarkTweet(it) }
    }) {
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(
                painter = painterResource(id = if (hasBookmarked) R.drawable.ic_bookmark_fill else R.drawable.ic_bookmark),
                contentDescription = "Like",
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = if (hasBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "${t?.bookmarkCount}",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}