package com.fireshare.tweet.tweet

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.fireshare.tweet.BottomNavigationBar
import com.fireshare.tweet.MainTopAppBar
import com.fireshare.tweet.viewmodel.TweetFeedViewModel

@Composable
fun TweetFeedScreen(navController: NavHostController)
{
    Scaffold(
        topBar = { MainTopAppBar(navController) },
        bottomBar = { BottomNavigationBar(navController) }
    ) { innerPadding ->
        val viewModel: TweetFeedViewModel = hiltViewModel()
        val tweets = viewModel.tweets.collectAsState().value
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
        {
            items(tweets) { tweet ->
                if (!tweet.isPrivate) TweetItem(tweet)
            }
        }
    }
}
