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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fireshare.tweet.ComposeComment
import com.fireshare.tweet.LocalNavController
import com.fireshare.tweet.LocalViewModelProvider
import com.fireshare.tweet.R
import com.fireshare.tweet.SharedTweetViewModel
import com.fireshare.tweet.datamodel.UserFavorites
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.TweetViewModel

@Composable
fun CommentButton(viewModel: TweetViewModel) {
    val tweet by viewModel.tweetState.collectAsState()
    val count by remember {
        derivedStateOf { tweet.commentCount }
    }
    val navController = LocalNavController.current
    val viewModelProvider = LocalViewModelProvider.current

    IconButton(onClick = {
        viewModelProvider?.get(SharedTweetViewModel::class)?.let {
            it.sharedTVMInstance = viewModel
            tweet.mid?.let { navController.navigate(ComposeComment(it)) }
        }
    }) {
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(
                painter = painterResource(id = R.drawable.ic_notice),
                contentDescription = "comments",
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "$count", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun RetweetButton(viewModel: TweetViewModel) {
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()
    val tweet by viewModel.tweetState.collectAsState()
    val count by remember {
        derivedStateOf { tweet.retweetCount }
    }
    val hasRetweeted = tweet.favorites?.get(UserFavorites.RETWEET) ?: false

    IconButton(onClick = {
        tweet.let { tweetFeedViewModel.toggleRetweet(it) { updatedTweet ->
            viewModel.updateTweet(updatedTweet)
        } }
    } ) {
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(
                painter = painterResource(id = if (hasRetweeted) R.drawable.ic_squarepath_prim else R.drawable.ic_squarepath),
                contentDescription = "forward",
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = if (hasRetweeted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun LikeButton(viewModel: TweetViewModel) {
    val tweet by viewModel.tweetState.collectAsState()
    val count by remember { derivedStateOf { tweet.likeCount } }
    val hasLiked = tweet.favorites?.get(UserFavorites.LIKE_TWEET) ?: false
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()

    IconButton(onClick = {
        viewModel.likeTweet { updatedTweet ->
            tweetFeedViewModel.updateTweet(updatedTweet)
        }
    })
    {
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(
                painter = painterResource(id = if (hasLiked) R.drawable.ic_heart_fill else R.drawable.ic_heart),
                contentDescription = "Like",
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = if (hasLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun BookmarkButton(viewModel: TweetViewModel) {
    val tweet by viewModel.tweetState.collectAsState()
    val count by remember { derivedStateOf { tweet.bookmarkCount } }
    val hasBookmarked = tweet.favorites?.get(UserFavorites.BOOKMARK) ?: false
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()
    IconButton(onClick = {
        viewModel.bookmarkTweet { updatedTweet ->
            tweetFeedViewModel.updateTweet(updatedTweet)
        }
    })
    {
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(
                painter = painterResource(id = if (hasBookmarked) R.drawable.ic_bookmark_fill else R.drawable.ic_bookmark),
                contentDescription = "Like",
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = if (hasBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}