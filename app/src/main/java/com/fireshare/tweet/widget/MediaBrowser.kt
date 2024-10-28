package com.fireshare.tweet.widget

import android.app.Activity
import android.content.ComponentCallbacks
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.tweet.BookmarkButton
import com.fireshare.tweet.tweet.CommentButton
import com.fireshare.tweet.tweet.LikeButton
import com.fireshare.tweet.tweet.RetweetButton
import com.fireshare.tweet.tweet.ShareButton
import com.fireshare.tweet.viewmodel.TweetViewModel
import kotlinx.coroutines.launch
import timber.log.Timber

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

    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val orientation by remember { mutableIntStateOf(configuration.orientation) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    // Handle horizontal drag
                    if (change.positionChange().x > 20) {
                        animationScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    } else if (change.positionChange().x < -20) {
                        animationScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }

                    // Handle vertical drag (swipe down)
                    if (dragAmount.y > 20) {
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        } else {
                            Timber
                                .tag("MediaBrowser")
                                .d("No previous back stack entry")
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures {
                    showControls = !showControls
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
                            viewModel.playbackPosition = exoPlayer.currentPosition
                            exoPlayer.release()
                        }
                    }
                    LaunchedEffect(viewModel.playbackPosition) {
                        exoPlayer.seekTo(viewModel.playbackPosition)
                        exoPlayer.playWhenReady = true
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
                        modifier = Modifier.fillMaxWidth()
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
                        .background(Color.Transparent)
                ) {
                    IconButton(
                        onClick = {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            navController.popBackStack()
                        },
                        modifier = Modifier
                            .padding(24.dp)
                            .align(Alignment.TopStart)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            activity?.requestedOrientation = when(orientation) {
                                Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                        },
                        modifier = Modifier
                            .padding(24.dp)
                            .align(Alignment.TopEnd)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_rotate),
                            contentDescription = "Orientation",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                val mediaItem = mediaItems[pagerState.currentPage]
                if (tweetId != null && mediaItem.type != MediaType.Video) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .background(Color.Transparent)
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
