package us.fireshare.tweet.widget

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaItem
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl
import us.fireshare.tweet.tweet.BookmarkButton
import us.fireshare.tweet.tweet.CommentButton
import us.fireshare.tweet.tweet.LikeButton
import us.fireshare.tweet.tweet.RetweetButton
import us.fireshare.tweet.tweet.ShareButton
import us.fireshare.tweet.viewmodel.TweetViewModel
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
    Timber.d("MediaBrowser - Composable called with startIndex: $startIndex, tweetId: $tweetId, authorId: $authorId")
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
    val tweet by viewModel.tweetState.collectAsState()
    val tweetAttachments by viewModel.attachments.collectAsState()
    val mediaItems = tweetAttachments?.map {
        val mediaUrl = HproseInstance.getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty())!!
        val inferredType = inferMediaTypeFromAttachment(it)
        Timber.d("MediaBrowser - Creating MediaItem: mid=${it.mid}, type=$inferredType, url=$mediaUrl")
        MediaItem(mediaUrl, inferredType)
    } ?: return

    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { mediaItems.size })
    var showControls by remember { mutableStateOf(false) }  // show control buttons for play/stop
    val animationScope = rememberCoroutineScope()

    // Handle page changes to pause/resume videos
    LaunchedEffect(pagerState.currentPage) {
        Timber.d("MediaBrowser - Page changed to: ${pagerState.currentPage}")
        // Pause all videos except the current one
        mediaItems.forEachIndexed { index, mediaItem ->
            if (mediaItem.type == MediaType.Video) {
                val videoMid = mediaItem.url.getMimeiKeyFromUrl()
                if (index == pagerState.currentPage) {
                    // Resume current video
                    VideoManager.resumeVideo(videoMid, true)
                } else {
                    // Pause other videos
                    VideoManager.pauseVideo(videoMid)
                }
            }
        }
    }
    val context = LocalContext.current

    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current

    var scaleFactor by remember { mutableFloatStateOf(1f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var exoPlayer: ExoPlayer? by remember { mutableStateOf(null) }

    // prevent double trigger of popBack event
    val isNavigationTriggered = remember { mutableStateOf(false) }

    // Animate the drag offset for smooth exit animation
    val animatedOffsetY by animateFloatAsState(
        targetValue = offsetY,
        animationSpec = tween(if (isDragging) 0 else 300),
        label = "offsetY"
    )

    // Calculate visual effects based on drag for images
    val maxDragDistance = 800f
    val dragProgress = (animatedOffsetY / maxDragDistance).coerceIn(0f, 1f)

    // Translation effect - content moves down with finger
    val translationY = animatedOffsetY * 0.5f

    // Scale effect - content gets slightly smaller as it's dragged
    val scale = 1f - (dragProgress * 0.1f)

    // Alpha effect - content fades out as it's dragged
    val alpha = 1f - (dragProgress * 0.3f)

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

            // Show system bars when exiting full screen
            activity?.window?.let { window ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.let { controller ->
                        controller.show(WindowInsets.Type.systemBars())
                    }
                } else {
                    // Fallback for older versions
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.releaseAllPlayers()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
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
                // video preview - use new FullScreenVideoPlayer
                MediaType.Video -> {
                    Timber.d("MediaBrowser - Processing video item: ${mediaItem.url}")
                    // Extract video mid from URL for VideoManager using the same method as VideoPreview
                    val videoMid = mediaItem.url.getMimeiKeyFromUrl()
                    Timber.d("MediaBrowser - Video URL: ${mediaItem.url}, extracted videoMid: $videoMid")

                    // Add more debugging
                    Timber.d("MediaBrowser - Creating FullScreenVideoPlayer for video: $videoMid")
                    Timber.d("MediaBrowser - Video type: ${mediaItem.type}")
                    Timber.d("MediaBrowser - Current page: ${pagerState.currentPage}, Video page: $page")

                    // Only activate video if it's the current page
                    val isCurrentPage = pagerState.currentPage == page

                    FullScreenVideoPlayer(
                        videoUrl = mediaItem.url,
                        onClose = {
                            Timber.d("MediaBrowser - FullScreenVideoPlayer onClose called")
                            navController.popBackStack()
                        },
                        enableImmersiveMode = false, // MediaBrowser already handles immersive mode
                        onHorizontalSwipe = { direction ->
                            Timber.tag("MediaBrowser").d("Horizontal swipe detected: $direction")
                            animationScope.launch {
                                if (direction > 0) {
                                    // Swipe right, go to next page
                                    pagerState.animateScrollToPage(
                                        pagerState.currentPage + 1
                                    )
                                } else {
                                    // Swipe left, go to previous page
                                    pagerState.animateScrollToPage(
                                        pagerState.currentPage - 1
                                    )
                                }
                            }
                        }
                    )
                }
                // audio preview - keep existing implementation
                MediaType.Audio -> {
                    exoPlayer =
                        remember(mediaItem.url) { viewModel.getExoPlayer(mediaItem.url, context) }
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
//                                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
//                                    showControls = visibility == View.VISIBLE
//                                })
                                controllerShowTimeoutMs = 2000
                                controllerAutoShow = true
                                // Force hardware acceleration and proper clipping for Media3 1.7.1
                                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                hideController()    // hide control buttons
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clipToBounds() // Ensure content is clipped to bounds
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
                                        navController.popBackStack()
                                    }
                                }
                            )
                    )
                }
                // image view with enhanced drag effects
                else -> {
                    // Get the preview URL for this image to use as placeholder
                    val previewUrl =
                        mediaItem.url // The same URL is used for both preview and full-size

                    AdvancedImageViewer(
                        imageUrl = mediaItem.url,
                        onClose = { navController.popBackStack() },
                        modifier = Modifier
                            .offset { IntOffset(offsetX.roundToInt(), 0) }
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
                                    isDragging = true
                                    offsetY += delta
                                    if (offsetY > 200f && scaleFactor <= 1 && !isNavigationTriggered.value) {
                                        isNavigationTriggered.value =
                                            true    // prevent multiple popBack
                                        navController.popBackStack()
                                    }
                                }
                            )
                            .graphicsLayer(
                                scaleX = scaleFactor * scale,
                                scaleY = scaleFactor * scale,
                                translationY = translationY,
                                alpha = alpha
                            )
                            .align(Alignment.Center)
                    )
                }
            }
        }

        // Controls with enhanced visual effects
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
                            .graphicsLayer(
                                translationY = translationY,
                                scaleX = scale,
                                scaleY = scale,
                                alpha = alpha
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.close),
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
                            .graphicsLayer(
                                translationY = translationY,
                                scaleX = scale,
                                scaleY = scale,
                                alpha = alpha
                            )
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_rotate),
                            contentDescription = stringResource(R.string.orientation),
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
