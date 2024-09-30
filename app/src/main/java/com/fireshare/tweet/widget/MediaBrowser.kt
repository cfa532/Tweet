package com.fireshare.tweet.widget

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
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
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.tweet.BookmarkButton
import com.fireshare.tweet.tweet.CommentButton
import com.fireshare.tweet.tweet.LikeButton
import com.fireshare.tweet.tweet.RetweetButton
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.Gadget.detectMimeTypeFromHeader
import com.fireshare.tweet.widget.Gadget.downloadFileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MediaBrowser(
    parentEntry: NavBackStackEntry,
    navController: NavController,
    mediaItems: List<MediaItem>,
    startIndex: Int,
    tweetId: MimeiId
) {
    val viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(parentEntry, key = tweetId) { factory ->
        factory.create(Tweet(authorId = "default", content = "nothing"))
    }
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { mediaItems.size })
    val mediaType = remember { mutableStateOf(MediaType.Image) }
    var showControls by remember { mutableStateOf(false) } // State for showing/hiding controls
    val animationScope = rememberCoroutineScope()

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
                        // Swipe right
                        animationScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    } else {
                        // Swipe left
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
            if (mediaItem.type == MediaType.Unknown) {
                val coroutineScope = rememberCoroutineScope()
                LaunchedEffect(mediaItem.url.substringAfterLast('/')) {
                    coroutineScope.launch {
                        val header = withContext(Dispatchers.IO) { downloadFileHeader(mediaItem.url) }
                        detectMimeTypeFromHeader(header)?.let {
                            mediaItem.type = when (it.substringBefore('/')) {
                                "image" -> MediaType.Image
                                "audio" -> MediaType.Audio
                                "video" -> MediaType.Video
                                else -> MediaType.Unknown
                            }
                            mediaType.value = mediaItem.type
                        }
                    }
                }
            }
            // Display the media item based on its type and recompose when mediaType.value changes
            when (mediaType.value) {
                MediaType.Video, MediaType.Audio -> {
                    // Consider caching for larger videos
                    VideoPlayer(uri = Uri.parse(mediaItem.url))
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
                    // Top control box content
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
                            .align(Alignment.Center)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f)) // Push bottom box to the bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(Color.Black)
                ) {
                    // Bottom control box content
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, bottom = 32.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        // State hoist
                        LikeButton(viewModel, Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        BookmarkButton(viewModel, Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        CommentButton(viewModel, Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        RetweetButton(viewModel, Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun VideoPlayer(uri: Uri) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            VideoView(context).apply {
                setVideoURI(uri)
                setOnPreparedListener { mediaPlayer: MediaPlayer ->
                    mediaPlayer.isLooping = false
                    start()
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}