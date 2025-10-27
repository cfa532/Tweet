package us.fireshare.tweet.widget

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
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
import androidx.compose.material3.Text
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import us.fireshare.tweet.service.OrientationManager
import us.fireshare.tweet.tweet.BookmarkButton
import us.fireshare.tweet.tweet.CommentButton
import us.fireshare.tweet.tweet.LikeButton
import us.fireshare.tweet.tweet.RetweetButton
import us.fireshare.tweet.tweet.ShareButton
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.viewmodel.TweetFeedViewModel
import us.fireshare.tweet.viewmodel.UserViewModel
import us.fireshare.tweet.widget.inferMediaTypeFromAttachment
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

@RequiresApi(Build.VERSION_CODES.R)
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
    
    // Get tweet list from current context for automatic video progression
    Timber.d("MediaBrowser - About to call getTweetListFromContext for tweet: ${tweet.mid}")
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()
    val fullTweetList = runBlocking { getTweetListFromContext(tweet, navController, tweetFeedViewModel) }
    
    // Use the video-indexed list from TweetListView if available
    // For now, fallback to simple approach until TweetListView integration is complete
    val tweetList = fullTweetList
    val startIndex = if (fullTweetList.contains(tweet)) {
        val foundIndex = fullTweetList.indexOfFirst { it.mid == tweet.mid }
        Timber.d("MediaBrowser - Tweet found in list at index: $foundIndex")
        foundIndex.coerceAtLeast(0)
    } else {
        Timber.d("MediaBrowser - Tweet not found in list, starting from beginning")
        0
    }
    
    Timber.d("MediaBrowser - Using tweet list with ${tweetList.size} tweets, start index: $startIndex")
    
    // Handle retweets by fetching the original tweet's content
    val actualTweet = if (tweet.originalTweetId != null && tweet.originalAuthorId != null && 
                         tweet.content.isNullOrEmpty() && tweet.attachments.isNullOrEmpty()) {
        // This is a pure retweet - fetch the original tweet
        Timber.d("MediaBrowser - Pure retweet detected, fetching original tweet: ${tweet.originalTweetId}")
        val originalTweet = runBlocking { 
            HproseInstance.refreshTweet(tweet.originalTweetId!!, tweet.originalAuthorId!!)
        }
        if (originalTweet != null) {
            Timber.d("MediaBrowser - Fetched original tweet: ${originalTweet.mid}, attachments: ${originalTweet.attachments?.size}")
            originalTweet
        } else {
            Timber.w("MediaBrowser - Failed to fetch original tweet: ${tweet.originalTweetId}")
            tweet
        }
    } else {
        tweet
    }
    
    Timber.d("MediaBrowser - Using tweet: ${actualTweet.mid}, attachments: ${actualTweet.attachments?.size}")
    Timber.d("MediaBrowser - Current tweet content: ${actualTweet.content}")
    Timber.d("MediaBrowser - Calculated startIndex: $startIndex")
    
    // Use actualTweet for getting attachments
    val tweetAttachments = actualTweet.attachments
    Timber.d("MediaBrowser - tweetAttachments: $tweetAttachments")
    Timber.d("MediaBrowser - tweetAttachments size: ${tweetAttachments?.size}")
    Timber.d("MediaBrowser - Tweet list size: ${tweetList.size}, start index: $startIndex")
    Timber.d("MediaBrowser - Tweet list IDs: ${tweetList.map { it.mid }}")
    
    val mediaItems = tweetAttachments?.map {
        val mediaUrl = HproseInstance.getMediaUrl(it.mid, actualTweet.author?.baseUrl.orEmpty())!!
        val inferredType = inferMediaTypeFromAttachment(it)
        Timber.d("MediaBrowser - Creating MediaItem: mid=${it.mid}, type=$inferredType, url=$mediaUrl")
        MediaItem(mediaUrl, inferredType)
    }

    // Show loading state if tweetAttachments is null
    if (tweetAttachments == null) {
        Timber.d("MediaBrowser - Showing loading state because tweetAttachments is null")
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(48.dp)
                )
                androidx.compose.material3.Text(
                    text = "Loading...",
                    color = Color.White,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
        return
    }
    
    Timber.d("MediaBrowser - tweetAttachments loaded, size: ${tweetAttachments?.size}")

    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { mediaItems?.size ?: 0 })
    var showControls by remember { mutableStateOf(false) }  // show control buttons for play/stop
    val animationScope = rememberCoroutineScope()

    // Handle page changes to pause/resume videos
    LaunchedEffect(pagerState.currentPage) {
        Timber.d("MediaBrowser - Page changed to: ${pagerState.currentPage}")
        // Pause all videos except the current one
        mediaItems?.forEachIndexed { index, mediaItem ->
            if (mediaItem.type == MediaType.Video || mediaItem.type == MediaType.HLS_VIDEO) {
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
                    window.insetsController?.show(WindowInsets.Type.systemBars())
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
            val mediaItem = mediaItems?.get(page) ?: return@HorizontalPager
            when (mediaItem.type) {
                // video preview - use independent fullscreen player
                MediaType.Video, MediaType.HLS_VIDEO -> {
                    Timber.d("MediaBrowser - Processing video item: ${mediaItem.url}")
                    
                    // Use the new independent fullscreen player with full tweet list
                    // Always use the full tweet list for navigation, even if current tweet isn't found
                    Timber.d("MediaBrowser - Using full tweet list for navigation (${tweetList.size} tweets)")
                    
                    IndependentFullScreenPlayer(
                        tweetList = tweetList, // Always use full tweet list
                        startIndex = startIndex,
                        tappedTweet = tweet, // Pass the current tweet for reference
                        onClose = {
                            Timber.d("MediaBrowser - IndependentFullScreenPlayer onClose called")
                            navController.popBackStack()
                        }
                    )
                }
                // audio preview - keep existing implementation
                MediaType.Audio -> {
                    exoPlayer =
                        remember(mediaItem.url) { viewModel.getAudioPlayer(mediaItem.url, context) }
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
                                    if (offsetY > 100f && !isNavigationTriggered.value) {
                                        isNavigationTriggered.value = true
                                        navController.popBackStack()
                                    }
                                }
                            )
                    )
                }
                // image view with enhanced drag effects
                else -> {
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
                                    if (offsetY > 300f && scaleFactor <= 1 && !isNavigationTriggered.value) {
                                        isNavigationTriggered.value = true
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
                            activity?.let { OrientationManager.lockToPortrait(it) }
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
                            activity?.let { act ->
                                when (configuration.orientation) {
                                    Configuration.ORIENTATION_LANDSCAPE -> OrientationManager.lockToPortrait(
                                        act
                                    )

                                    Configuration.ORIENTATION_PORTRAIT -> OrientationManager.lockToLandscape(
                                        act
                                    )

                                    else -> OrientationManager.allowRotation(act)
                                }
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
                        CommentButton(viewModel)
                        LikeButton(viewModel)
                        BookmarkButton(viewModel)
                        RetweetButton(viewModel)
                        Spacer(modifier = Modifier.width(20.dp))
                        ShareButton(viewModel)
                    }
                }
            }
        }
    }
}

/**
 * Get tweet list from current navigation context for automatic video progression
 */
private suspend fun getTweetListFromContext(
    currentTweet: Tweet,
    navController: NavController,
    tweetFeedViewModel: TweetFeedViewModel
): List<Tweet> {
    Timber.d("MediaBrowser - getTweetListFromContext called for tweet: ${currentTweet.mid}")
    
    // Get current navigation route to determine context
    val currentRoute = navController.currentBackStackEntry?.destination?.route
    val previousRoute = navController.previousBackStackEntry?.destination?.route
    
    Timber.d("MediaBrowser - Current route: $currentRoute, Previous route: $previousRoute")
    
    return when {
        // If we're in MediaViewer, check where we came from
        currentRoute?.contains("MediaViewer") == true -> {
            Timber.d("MediaBrowser - In MediaViewer, checking previous route")
            when {
                previousRoute?.contains("TweetFeed") == true -> {
                    Timber.d("MediaBrowser - Came from TweetFeed, fetching feed tweets")
                    // We came from tweet feed - get tweets from TweetFeedViewModel
                    val tweets = tweetFeedViewModel.tweets.value
                    
                    Timber.d("MediaBrowser - Raw feed tweets count: ${tweets.size}")
                    
                    // Filter for tweets with videos
                    val videoTweets = tweets.filter { tweet ->
                        tweet.attachments?.any { attachment ->
                            val mediaType = inferMediaTypeFromAttachment(attachment)
                            mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO
                        } == true
                    }
                    
                    Timber.d("MediaBrowser - Found ${videoTweets.size} video tweets in feed")
                    if (videoTweets.isEmpty()) listOf(currentTweet) else videoTweets
                }
                previousRoute?.contains("Bookmarks") == true -> {
                    Timber.d("MediaBrowser - Came from Bookmarks, fetching bookmark tweets")
                    // We came from bookmarks - get tweets from HproseInstance
                    val bookmarks = us.fireshare.tweet.HproseInstance.getUserTweetsByType(
                        us.fireshare.tweet.HproseInstance.appUser,
                        us.fireshare.tweet.datamodel.UserContentType.BOOKMARKS,
                        0,
                        100 // Get more tweets for better video progression
                    ).filterNotNull()
                    
                    // Filter for tweets with videos
                    val videoTweets = bookmarks.filter { tweet ->
                        tweet.attachments?.any { attachment ->
                            val mediaType = inferMediaTypeFromAttachment(attachment)
                            mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO
                        } == true
                    }
                    
                    Timber.d("MediaBrowser - Found ${videoTweets.size} video tweets in bookmarks")
                    if (videoTweets.isEmpty()) listOf(currentTweet) else videoTweets
                }
                previousRoute?.contains("Favorites") == true -> {
                    Timber.d("MediaBrowser - Came from Favorites, fetching favorite tweets")
                    // We came from favorites - get tweets from HproseInstance
                    val favorites = us.fireshare.tweet.HproseInstance.getUserTweetsByType(
                        us.fireshare.tweet.HproseInstance.appUser,
                        us.fireshare.tweet.datamodel.UserContentType.FAVORITES,
                        0,
                        100 // Get more tweets for better video progression
                    ).filterNotNull()
                    
                    // Filter for tweets with videos
                    val videoTweets = favorites.filter { tweet ->
                        tweet.attachments?.any { attachment ->
                            val mediaType = inferMediaTypeFromAttachment(attachment)
                            mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO
                        } == true
                    }
                    
                    Timber.d("MediaBrowser - Found ${videoTweets.size} video tweets in favorites")
                    if (videoTweets.isEmpty()) listOf(currentTweet) else videoTweets
                }
                previousRoute?.contains("UserProfile") == true -> {
                    Timber.d("MediaBrowser - Came from UserProfile, fetching user tweets")
                    // We came from user profile - get tweets from HproseInstance
                    val userTweets = us.fireshare.tweet.HproseInstance.getTweetsByUser(
                        us.fireshare.tweet.HproseInstance.appUser,
                        0,
                        100 // Get more tweets for better video progression
                    ).filterNotNull()
                    
                    // Filter for tweets with videos
                    val videoTweets = userTweets.filter { tweet ->
                        tweet.attachments?.any { attachment ->
                            val mediaType = inferMediaTypeFromAttachment(attachment)
                            mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO
                        } == true
                    }
                    
                    Timber.d("MediaBrowser - Found ${videoTweets.size} video tweets in user profile")
                    if (videoTweets.isEmpty()) listOf(currentTweet) else videoTweets
                }
                else -> {
                    // Default fallback - return just current tweet
                    Timber.d("MediaBrowser - Unknown previous route: $previousRoute, returning single tweet")
                    listOf(currentTweet)
                }
            }
        }
        else -> {
            // Default fallback - return just current tweet
            Timber.d("MediaBrowser - Not in MediaViewer (current route: $currentRoute), returning single tweet")
            listOf(currentTweet)
        }
    }
}
