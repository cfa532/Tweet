package com.fireshare.tweet.tweet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import com.fireshare.tweet.LocalNavController
import com.fireshare.tweet.LocalViewModelProvider
import com.fireshare.tweet.R
import com.fireshare.tweet.SharedTweetViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweetDetailScreen(
    parentEntry: NavBackStackEntry
)
{
    val navController = LocalNavController.current
    val viewModelProvider = LocalViewModelProvider.current
    val sharedViewModel = viewModelProvider?.get(SharedTweetViewModel::class)
    val viewModel = sharedViewModel?.sharedTVMInstance ?: return
    val tweet by viewModel.tweetState.collectAsState()

    viewModel.loadComments( tweet )
    val comments by viewModel.comments.collectAsState()

    Column {
        TopAppBar(
            title = {
                Text(
                    text = "Tweet",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() })
                {
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

        // main body of the parent Tweet.
        TweetDetailHead(tweet, viewModel)

        // divider between tweet and its comment list
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 1.dp),
            thickness = 0.5.dp,
            color = Color.LightGray
        )
        // user comment list
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 8.dp)
        ) {
            items(comments) { comment ->
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 1.dp),
                    thickness = 0.5.dp,
                    color = Color.LightGray
                )
                CommentItem(comment, parentEntry)
            }
        }
    }
}