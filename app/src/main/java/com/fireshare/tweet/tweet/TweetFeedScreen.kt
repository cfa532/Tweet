package com.fireshare.tweet.tweet

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.fireshare.tweet.BottomNavigationBar
import com.fireshare.tweet.MainTopAppBar
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.viewmodel.TweetFeedViewModel


@Composable
fun TweetFeedScreen(
    navController: NavHostController,
    viewModel: TweetFeedViewModel,
    parentEntry: NavBackStackEntry)
{
    val tweets by viewModel.tweets.collectAsState()
    Scaffold(
        topBar = { MainTopAppBar(navController) },
        bottomBar = { BottomNavigationBar(navController) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
        {
            items(tweets) { tweet ->
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 1.dp),
                    thickness = 0.5.dp,
                    color = Color.LightGray
                )
                if (!tweet.isPrivate) TweetItem(tweet, parentEntry)
            }
        }
    }
}
