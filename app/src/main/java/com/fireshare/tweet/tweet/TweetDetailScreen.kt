package com.fireshare.tweet.tweet

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import com.fireshare.tweet.R
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.viewmodel.TweetViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweetDetailScreen(
    viewModel: TweetViewModel,
    parentEntry: NavBackStackEntry
) {
    val navController = LocalNavController.current
    val tweet by viewModel.tweetState.collectAsState()
    val comments by viewModel.comments.collectAsState()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            viewModel.refreshTweet()
            viewModel.loadComments(tweet)
            Timber.tag("TweetDetailScreen").d("$tweet")
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60 * 1000) // Delay for 5 minutes
            withContext(Dispatchers.IO) {
                viewModel.refreshTweet()
                viewModel.loadComments(tweet)
            }
        }
    }
    var gridColumns by remember { mutableIntStateOf(
        tweet.attachments?.let{
            if (it.size>4) 2 else 1
        } ?: 1
    ) }    // # of columns to display in the grid
    var fabOffset by remember { mutableStateOf(Offset(0f, 0f)) }   // position of float action button
    fun Offset.toIntOffset(): IntOffset {
        return IntOffset(x.toInt(), y.toInt())
    }
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
        bottomBar = { BottomNavigationBar(navController, 0)},
        floatingActionButton = {
            FloatingActionButton(
                onClick = { gridColumns = if (gridColumns == 1) 2 else 1 },
                modifier = Modifier
                    .offset { fabOffset.toIntOffset() }
                    .draggable(
                        state = rememberDraggableState { delta ->
                            fabOffset = fabOffset.copy(y = fabOffset.y + delta)
                        },
                        orientation = Orientation.Vertical
                    )
                    .size(40.dp),
                shape = CircleShape,
                containerColor = Color.White.copy(alpha = 0.7f)
            ) {
                Icon(
                    painter = if (gridColumns != 1) painterResource(R.drawable.ic_list_layout) else painterResource(R.drawable.ic_grid_layout),
                    contentDescription = "Switch layout",
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        floatingActionButtonPosition = FabPosition.End

    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item {
                /**
                 * Tweet content and attachments. This is the main body.
                 * */
                TweetDetailBody(tweet, viewModel, parentEntry, gridColumns)

                // divider between tweet and its comment list
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 1.dp),
                    thickness = 0.5.dp,
                    color = Color.LightGray
                )
            }
            /**
             * Comment list of this tweet. Need to add pagination later.
             * */
            items(comments, key = { it.mid })
            { comment ->
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