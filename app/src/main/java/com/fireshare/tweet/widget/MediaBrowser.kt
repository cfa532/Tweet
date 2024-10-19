package com.fireshare.tweet.widget

import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.tweet.BookmarkButton
import com.fireshare.tweet.tweet.CommentButton
import com.fireshare.tweet.tweet.LikeButton
import com.fireshare.tweet.tweet.RetweetButton
import com.fireshare.tweet.tweet.ShareButton
import com.fireshare.tweet.viewmodel.TweetViewModel
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
fun MediaBrowser(
    parentEntry: NavBackStackEntry,
    navController: NavController,
    mediaItems: List<MediaItem>,
    startIndex: Int,
    tweetId: MimeiId?
) {
    /**
     *  Create a tweetViewModel of tweetId to remember the position of this tweet in the feed list.
     *  When user closes the Media browser, goes back to the right position.
     * */
    val viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(parentEntry, key = tweetId) { factory ->
        factory.create(Tweet(authorId = "default", content = "nothing"))
    }
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { mediaItems.size })
    var showControls by remember { mutableStateOf(false) }
    val animationScope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val previousRoute = navController.previousBackStackEntry?.destination?.route
        if (previousRoute != null) {
            viewModel.savePreviousRoute(previousRoute)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showControls = !showControls }
                )
                detectHorizontalDragGestures { _, dragAmount ->
                    if (dragAmount > 0) {
                        animationScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    } else {
                        animationScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                }
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val mediaItem = mediaItems[page]
            when (mediaItem.type) {
                MediaType.Video, MediaType.Audio -> {
                    val exoPlayer = remember { createExoPlayer(context, mediaItem.url) }
                    exoPlayer.playWhenReady = true
                    exoPlayer.volume = 1f

                    DisposableEffect(Unit) {
                        onDispose {
                            exoPlayer.release()
                        }
                    }
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = true // Disable default controls
                                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                                    showControls = visibility == View.VISIBLE
                                })
                                hideController()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    ImageViewer(mediaItem.url, isPreview = false)
                }
            }
        }

        if (showControls) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(Color.Black)
                ) {
                    IconButton(
                        onClick = {
                            val previousRoute = viewModel.getPreviousRoute()
                            if (previousRoute != null) {
                                navController.navigate(previousRoute) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = false
                                }
                            } else {
                                navController.navigate(NavTweet.TweetFeed)
                            }
                        },
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.TopStart)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (tweetId != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .background(Color.Black)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, bottom = 32.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            LikeButton(viewModel)
                            BookmarkButton(viewModel)
                            CommentButton(viewModel)
                            RetweetButton(viewModel)
                            Spacer(modifier = Modifier.width(20.dp))
                            ShareButton(viewModel)
                        }
                    }
                }
            }
        }
    }
}
