package com.fireshare.tweet.tweet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fireshare.tweet.LocalNavController
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.network.HproseInstance
import com.fireshare.tweet.viewmodel.TweetFeedViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweetDetailScreen(tweetId: MimeiId) {
    val navController = LocalNavController.current

    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()
    val tweet = tweetFeedViewModel.getTweetById(tweetId) ?: return

    Column {
        TopAppBar(
            title = {
                Text(
                    text = "Tweet",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_back),
                        contentDescription = "Back",
                        modifier = Modifier
                            .size(18.dp)
                    )
                }
            },
            modifier = Modifier.height(70.dp)
        )
        CommentHead(tweet)
        // divider between tweet and comments
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 1.dp),
            thickness = 0.5.dp,
            color = Color.LightGray
        )
        CommentFeed(tweet)
    }
}