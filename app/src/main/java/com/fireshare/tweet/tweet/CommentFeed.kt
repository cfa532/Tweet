package com.fireshare.tweet.tweet

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.viewmodel.TweetViewModel

@Composable
fun CommentFeed(tweet: Tweet) {
    // given tweetId, load all its comments
    val viewModel = hiltViewModel<TweetViewModel>(key = tweet.mid)
    val comments = viewModel.comments.collectAsState().value

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
    ) {
        items(comments) { comment ->
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 1.dp),
                thickness = 0.5.dp,
                color = Color.LightGray
            )
            TweetItem(tweet = comment)
        }
    }
}