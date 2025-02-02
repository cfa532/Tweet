package com.fireshare.tweet.widget

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.fireshare.tweet.HproseInstance
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
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
fun MediaBrowser(
    parentEntry: NavBackStackEntry,
    navController: NavController,
    startIndex: Int,
    tweetId: MimeiId,
    authorId: MimeiId
) {
    /**
     *  Create a tweetViewModel with given tweetId to remember the position of this tweet
     *  in the feed list. When user closes the Media browser, goes back to the right position.
     *  The viewModel is also necessary for favorite and retweeting buttons at the bottom.
     * */
    val viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
        parentEntry,
        key = tweetId
    ) { factory ->
        factory.create(Tweet(mid = tweetId, authorId = authorId))
    }
    val tweetAttachments by viewModel.attachments.collectAsState()
    val mediaItems = tweetAttachments?.map {
        MediaItem(HproseInstance.getMediaUrl(it.mid, HproseInstance.appUser.baseUrl)!!, it.type)
    } ?: return

    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { mediaItems.size })
    var showControls by remember { mutableStateOf(false) }  // show control buttons for play/stop
    val animationScope = rememberCoroutineScope()
    val context = LocalContext.current

    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current

    var scaleFactor by remember { mutableFloatStateOf(1f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var initOffsetY by remember { mutableFloatStateOf(0f) }
    var exoPlayer: ExoPlayer? by remember { mutableStateOf(null) }

    // prevent double trigger of popBack event
    val isNavigationTriggered = remember { mutableStateOf(false) }

    /**
     * Keep screen ON when video is playing in full screen mode.
     * Stop playing when screen locked. Also hide system bars.
     * */
    DisposableEffect(Unit) {
        activity?.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // keep screen ON

            // Use WindowInsetsController directly for API level 30 and above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsets.Type.systemBars())
                }
            } else {
                // Fallback for older versions
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    // Pause or stop video playback here
                    exoPlayer?.playWhenReady = false
                }
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> {
                    // Resume video playback here (if needed)
                    exoPlayer?.playWhenReady = true
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.releaseAllPlayers()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        offsetY = initOffsetY
                        isNavigationTriggered.value = false
                    },
                    onDrag = { _, _ ->
                    },
                    onDragStart = {
                        initOffsetY = offsetY
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scaleFactor = if (scaleFactor > 1f) {
                            offsetX = 0f
                            1f
                        } else 2f
                    },
                    onTap = {
                        offsetX = 0f
                        scaleFactor = 1f
                        showControls = !showControls
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scaleFactor *= zoom
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val mediaItem = mediaItems[page]
            when (mediaItem.type) {
                // video preview
                MediaType.Video, MediaType.Audio -> {
                    exoPlayer = remember(mediaItem.url) { viewModel.getExoPlayer(mediaItem.url, context) }
                    exoPlayer?.volume = 1f

                    DisposableEffect(page) {
                        exoPlayer?.playWhenReady = true
                        onDispose {
                            exoPlayer?.let {
                                viewModel.savePlaybackPosition(mediaItem.url, it.currentPosition)
                                viewModel.stopPlayer(mediaItem.url)
                            }
                        }
                    }

                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = true    // otherwise video won't play
                                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                                    showControls = visibility == View.VISIBLE
                                })
                                hideController()    // hide control buttons
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset { IntOffset(0, offsetY.roundToInt()) }
                            .draggable(
                                // horizontal drag to swipe video
                                orientation = Orientation.Horizontal,
                                state = rememberDraggableState { delta ->
                                    if (delta > 20) {
                                        animationScope.launch {
                                            pagerState.animateScrollToPage(
                                                pagerState.currentPage - 1
                                            )
                                        }
                                    } else if (delta < -20) {
                                        animationScope.launch {
                                            pagerState.animateScrollToPage(
                                                pagerState.currentPage + 1
                                            )
                                        }
                                    }
                                }
                            )
                            .draggable(
                                // vertical drag to pop back to previous page.
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta ->
                                    offsetY += delta
                                    if (offsetY > 20f && !isNavigationTriggered.value) {
                                        isNavigationTriggered.value = true
                                        if (navController.previousBackStackEntry != null) {
                                            navController.popBackStack()
                                        } else {
                                            Timber
                                                .tag("MediaBrowser")
                                                .e("No previous back stack entry")
                                        }
                                    }
                                }
                            )
                    )
                }
                // image view
                else -> {
                    ImageViewer(
                        mediaItem.url,
                        isPreview = false,  // show original image
                        modifier = Modifier
                            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                            .draggable(
                                orientation = Orientation.Horizontal,
                                state = rememberDraggableState { delta ->
                                    if (scaleFactor > 1f)
                                        offsetX += delta    // move expanded image
                                    else {
                                        // Do not update offsetX when flipping images
                                        if (delta > 20) {
                                            animationScope.launch {
                                                pagerState.animateScrollToPage(
                                                    pagerState.currentPage - 1
                                                )
                                            }
                                        } else if (delta < -20) {
                                            animationScope.launch {
                                                pagerState.animateScrollToPage(
                                                    pagerState.currentPage + 1
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta ->
                                    offsetY += delta
                                    if (offsetY > 20f && scaleFactor <= 1 && !isNavigationTriggered.value) {
                                        isNavigationTriggered.value = true    // prevent multiple popBack
                                        if (navController.previousBackStackEntry != null) {
                                            navController.popBackStack()
                                        } else {
                                            Timber
                                                .tag("MediaBrowser")
                                                .e("No previous back stack entry")
                                        }
                                    }
                                }
                            )
                            .graphicsLayer(
                                scaleX = scaleFactor,
                                scaleY = scaleFactor
                            )
                            .align(Alignment.Center)
                    )
                }
            }
        }

        if (showControls) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Transparent)
                ) {
                    IconButton(
                        onClick = {
                            activity?.requestedOrientation =
                                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
                            activity?.requestedOrientation = when (configuration.orientation) {
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

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
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
