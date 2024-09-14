package com.fireshare.tweet.tweet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.viewmodel.TweetViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweetDetailScreen(
    viewModel: TweetViewModel,
    parentEntry: NavBackStackEntry
) {
    val navController = LocalNavController.current
    val tweet by viewModel.tweetState.collectAsState()
    viewModel.loadComments( tweet )
    val comments by viewModel.comments.collectAsState()

    Scaffold(
        topBar = { TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.primary,
            ),
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
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        )},
        bottomBar = { BottomNavigationBar(navController, 0) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // main body of the parent Tweet.
            TweetDetailHead(tweet, viewModel)

            // divider between tweet and its comment list
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 1.dp),
                thickness = 0.5.dp,
                color = Color.LightGray
            )
            // user comment list
            LazyColumn{
                items(comments) { comment ->
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 1.dp),
                        thickness = 0.5.dp,
                        color = Color.LightGray
                    )
                    CommentItem(comment, viewModel, parentEntry)
                }
            }
        }
    }
}