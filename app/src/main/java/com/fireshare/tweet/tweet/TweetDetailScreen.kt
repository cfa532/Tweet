package com.fireshare.tweet.tweet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.fireshare.tweet.LocalNavController
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.TweetViewModel

@Composable
fun TweetDetailScreen(tweetId: MimeiId) {
    val navController = LocalNavController.current

    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()
    val tweet = tweetFeedViewModel.getTweetById(tweetId)

    Column {
        TweetTopBar(navController)
        if (tweet != null) {
            TweetItem(tweet)
            CommentFeed(tweet)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweetTopBar(navController: NavController) {
    TopAppBar(
        title = {
            Text(
                text="Tweet",
                style = MaterialTheme.typography.bodyLarge
            ) },
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
}