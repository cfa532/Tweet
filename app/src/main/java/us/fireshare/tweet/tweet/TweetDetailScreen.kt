package us.fireshare.tweet.tweet

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Column
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweetDetailScreen(
    viewModel: TweetViewModel,
    parentEntry: NavBackStackEntry
) {
    val navController = LocalNavController.current
    val tweet by viewModel.tweetState.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // State for header collapse
    var headerOffsetHeightPx by remember { mutableStateOf(0f) }
    var headerOffsetHeightDp by remember { mutableStateOf(0.dp) }

    // Nested scroll connection for coordinating scroll between tweet and comments
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val newOffset = (headerOffsetHeightPx + delta).coerceIn(0f, 200f) // Max collapse of 200dp
                val consumed = newOffset - headerOffsetHeightPx
                headerOffsetHeightPx = newOffset
                headerOffsetHeightDp = with(density) { newOffset.toDp() }
                return Offset(0f, consumed)
            }
        }
    }

    // Initial load
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            viewModel.refreshTweetAndOriginal()
            viewModel.loadComments(tweet)
            Timber.tag("TweetDetailScreen").d("$tweet")
        }
    }
    
    // Refresh handler: initial refresh after 3 seconds, then every 5 minutes
    LaunchedEffect(Unit) {
        // Initial refresh after 3 seconds
        delay(3000L)
        withContext(Dispatchers.IO) {
            viewModel.refreshTweetAndOriginal()
            viewModel.loadComments(tweet)
            Timber.tag("TweetDetailScreen").d("Initial refresh completed after 3 seconds")
        }
        
        // Subsequent refreshes every 5 minutes
        while (true) {
            delay(5 * 60 * 1000) // refresh every 5 minutes
            withContext(Dispatchers.IO) {
                viewModel.refreshTweetAndOriginal()
                viewModel.loadComments(tweet)
                Timber.tag("TweetDetailScreen").d("Periodic refresh completed")
            }
        }
    }
    
    // Start listening to tweet and comment notifications
    LaunchedEffect(Unit) {
        viewModel.startListeningToNotifications()
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
                IconButton(onClick = { navController.popBackStack() } )
                {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        )},
        bottomBar = { BottomNavigationBar(navController = navController, selectedIndex = 0)},
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(nestedScrollConnection)
        ) {
            // Tweet content that collapses when scrolling
            Column(
                modifier = Modifier
                    .offset(y = -headerOffsetHeightDp)
                    .animateContentSize()
            ) {
                TweetDetailBody(viewModel, parentEntry, gridColumns)
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 1.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            
            // Comments list that takes remaining space
            CommentListView(
                comments = comments,
                getComments = { pageNumber ->
                    coroutineScope.launch {
                        viewModel.loadComments(tweet, pageNumber)
                    }
                },
                parentEntry = parentEntry
            )
        }
    }
}