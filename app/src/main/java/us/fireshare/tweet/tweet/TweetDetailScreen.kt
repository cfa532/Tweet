package us.fireshare.tweet.tweet

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.R
import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.tweet.ReplyEditorBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweetDetailScreen(
    viewModel: TweetViewModel,
    parentEntry: NavBackStackEntry
) {
    val navController = LocalNavController.current
    val tweet by viewModel.tweetState.collectAsState()
    val comments by viewModel.comments.collectAsState()
    var gridColumns by remember { mutableIntStateOf(
        tweet.attachments?.let{
            if (it.size>4) 2 else 1
        } ?: 1
    ) }    // # of columns to display in the grid
    var fabOffset by remember { mutableStateOf(Offset(0f, 0f)) }   // position of float action button
    fun Offset.toIntOffset(): IntOffset {
        return IntOffset(x.toInt(), y.toInt())
    }

    val coroutineScope = rememberCoroutineScope()

    // Initial load
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            viewModel.refreshTweetAndOriginal()
            viewModel.loadComments(tweet)
            Timber.tag("TweetDetailScreen").d(" 24tweet")
        }
    }
    // Refresh handler: initial refresh after 3 seconds, then every 5 minutes
    LaunchedEffect(Unit) {
        delay(3000L)
        withContext(Dispatchers.IO) {
            viewModel.refreshTweetAndOriginal()
            viewModel.loadComments(tweet)
            Timber.tag("TweetDetailScreen").d("Initial refresh completed after 3 seconds")
        }
        while (true) {
            delay(5 * 60 * 1000)
            withContext(Dispatchers.IO) {
                viewModel.refreshTweetAndOriginal()
                viewModel.loadComments(tweet)
                Timber.tag("TweetDetailScreen").d("Periodic refresh completed")
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.startListeningToNotifications()
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
                IconButton(onClick = { navController.popBackStack() } )
                {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        )},
        bottomBar = { 
            Column {
                ReplyEditorBox(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    onReplySubmit = { replyText ->
                        // TODO: Handle reply submission
                        // viewModel.submitReply(replyText)
                    }
                )
                BottomNavigationBar(navController = navController, selectedIndex = 0)
            }
        },
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
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item {
                TweetDetailBody(viewModel, parentEntry, gridColumns)
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 1.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            items(comments.size) { index ->
                val comment = comments[index]
                CommentItem(
                    comment = comment,
                    parentTweetViewModel = null,
                    parentEntry = parentEntry
                )
            }
        }
    }
}