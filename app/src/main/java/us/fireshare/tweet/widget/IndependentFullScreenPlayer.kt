package us.fireshare.tweet.widget

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import timber.log.Timber
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.service.OrientationManager
import us.fireshare.tweet.tweet.BookmarkButton
import us.fireshare.tweet.tweet.CommentButton
import us.fireshare.tweet.tweet.LikeButton
import us.fireshare.tweet.tweet.RetweetButton
import us.fireshare.tweet.tweet.ShareButton
import us.fireshare.tweet.viewmodel.TweetFeedViewModel
import us.fireshare.tweet.viewmodel.TweetViewModel
import kotlin.math.abs

/**
 * Independent fullscreen video player that automatically progresses through videos in a tweet list.
 * Uses the FullScreenPlayerManager singleton for video management.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun IndependentFullScreenPlayer(
    startIndex: Int,
    modifier: Modifier = Modifier,
    tappedTweet: Tweet? = null, // The tweet that was actually tapped
    parentEntry: NavBackStackEntry,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val sharedViewModel: SharedViewModel = hiltViewModel()
    
    // Observe the video list from TweetListViewModel
    val videoIndexedList by sharedViewModel.tweetListViewModel.videoIndexedList.collectAsState()
    val actualVideoList = videoIndexedList // Use the full video list with MediaType info
    val actualStartIndex = if (tappedTweet != null) {
        // Find the video mid from the tapped tweet's attachments
        val videoMid = tappedTweet.attachments?.firstOrNull { attachment ->
            val mediaType = inferMediaTypeFromAttachment(attachment)
            mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO
        }?.mid
        if (videoMid != null) {
            val foundIndex = sharedViewModel.tweetListViewModel.findStartIndexForVideoMid(videoMid)
            Timber.d("IndependentFullScreenPlayer - Found video $videoMid at index $foundIndex in video list")
            foundIndex
        } else {
            Timber.d("IndependentFullScreenPlayer - No video found in tapped tweet, using startIndex: $startIndex")
            startIndex
        }
    } else {
        Timber.d("IndependentFullScreenPlayer - No tapped tweet, using startIndex: $startIndex")
        startIndex
    }
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var showControls by remember { mutableStateOf(false) }
    var currentTweet by remember { mutableStateOf<Tweet?>(tappedTweet) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var totalVideos by remember { mutableIntStateOf(0) }
    var verticalDragOffset by remember { mutableFloatStateOf(0f) }
    var videoScale by remember { mutableFloatStateOf(1f) }
    var videoOffset by remember { mutableFloatStateOf(0f) }
    var isClosing by remember { mutableStateOf(false) }

    // Show controls (and thus action buttons) briefly when entering fullscreen
    LaunchedEffect(Unit) {
        showControls = true
    }
    
    // Initialize the singleton player
    LaunchedEffect(Unit) {
        Timber.d("IndependentFullScreenPlayer - Initializing with ${actualVideoList.size} videos, start index: $actualStartIndex")
        Timber.d("IndependentFullScreenPlayer - Tapped tweet: ${tappedTweet?.mid}")
        
        FullScreenPlayerManager.initialize(context)
        
        // Set the video list
        Timber.d("IndependentFullScreenPlayer - Setting video list with ${actualVideoList.size} videos")
        FullScreenPlayerManager.setVideoList(actualVideoList, actualStartIndex)
        
        // Set up callbacks
        FullScreenPlayerManager.setOnVideoChanged { videoMid, index ->
            Timber.d("IndependentFullScreenPlayer - Video changed to index $index: $videoMid")
            // For now, keep using tappedTweet for actions if we don't have per-video tweet mapping
            // TODO: Map videoMid to the correct Tweet instance for accurate context
            currentIndex = index
            totalVideos = FullScreenPlayerManager.getTotalVideos()
        }
        
        // Set initial values
        val currentVideoMid = FullScreenPlayerManager.getCurrentVideoMid()
        // Keep using tappedTweet as the current tweet context for now
        currentIndex = FullScreenPlayerManager.getCurrentIndex()
        totalVideos = FullScreenPlayerManager.getTotalVideos()
    }
    
    // Reactively observe player changes via StateFlow instead of polling every 100ms
    val exoPlayer by FullScreenPlayerManager.playerFlow.collectAsState()
    
    // Handle immersive mode and allow rotation during fullscreen
    LaunchedEffect(Unit) {
        activity?.let { act ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = act.window.insetsController
                controller?.hide(android.view.WindowInsets.Type.systemBars())
                controller?.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                @Suppress("DEPRECATION")
                act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
            OrientationManager.allowRotation(act)
        }
    }
    
    // Pause fullscreen playback when app goes to background
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                exoPlayer?.let { player ->
                    Timber.d("IndependentFullScreenPlayer - Lifecycle ${event.name}, pausing fullscreen video")
                    player.playWhenReady = false
                    player.pause()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Cleanup immersive mode and restore portrait on dispose
    DisposableEffect(Unit) {
        onDispose {
            activity?.let { act ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    act.window.insetsController?.show(android.view.WindowInsets.Type.systemBars())
                } else {
                    @Suppress("DEPRECATION")
                    act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                }
                OrientationManager.lockToPortrait(act)
            }
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        // Check for vertical drag gestures
                        if (abs(verticalDragOffset) > 150f && !isClosing) {
                            if (verticalDragOffset > 300f) {
                                // Large drag down - exit player
                                Timber.d("IndependentFullScreenPlayer - Large drag down detected, closing player")
                                isClosing = true
                                onClose()
                            } else if (totalVideos == 1) {
                                // Only one video - any gesture should exit
                                Timber.d("IndependentFullScreenPlayer - Single video detected, exiting player")
                                isClosing = true
                                onClose()
                            } else if (verticalDragOffset < 0) {
                                // Swipe up - next video in list (older video)
                                Timber.d("IndependentFullScreenPlayer - Swipe up detected, playing next video")
                                FullScreenPlayerManager.playNextVideo()
                            } else {
                                // Small drag down - not enough to exit, snap back
                                Timber.d("IndependentFullScreenPlayer - Small drag down detected, not exiting")
                            }
                        } else {
                            // No significant gesture detected
                            Timber.d("IndependentFullScreenPlayer - No significant gesture detected")
                        }
                        // Reset all gesture states
                        verticalDragOffset = 0f
                        videoScale = 1f
                        videoOffset = 0f
                        // Reset isClosing flag for next gesture
                        isClosing = false
                    },
                    onDrag = { change, dragAmount ->
                        // Consume to ensure smooth, dedicated drag handling
                        change.consume()
                        // Track vertical drag for navigation and exit
                        verticalDragOffset += dragAmount.y
                        
                        // Implement video shrinking gesture with better UX
                        if (verticalDragOffset < 0) {
                            // Dragging up - shrink video slightly for visual feedback
                            videoScale = (1f - abs(verticalDragOffset) / 1000f).coerceAtLeast(0.8f)
                            // Move at 0.5x for smoother feel and less jitter
                            videoOffset = verticalDragOffset / 2f
                        } else if (verticalDragOffset > 0) {
                            // Dragging down - moderate shrinking for exit feedback
                            videoScale = (1f - verticalDragOffset / 800f).coerceAtLeast(0.8f)
                            // Move at 0.5x for smoother feel and less jitter
                            videoOffset = verticalDragOffset / 2f
                        }
                    }
                )
            }
    ) {
        // Video player view with gesture-based scaling
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = videoScale,
                    scaleY = videoScale,
                    translationY = videoOffset
                ),
            factory = {
                PlayerView(context).apply {
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            val isVisible = visibility == android.view.View.VISIBLE
                            if (showControls != isVisible) {
                                showControls = isVisible
                            }
                        }
                    )
                }
            },
            update = { playerView ->
                // Ensure shutter background is black when updating player (in case view hierarchy changed)
                playerView.setShutterBackgroundColor(android.graphics.Color.BLACK)
                // Update the player when exoPlayer changes
                playerView.player = exoPlayer
                Timber.d("IndependentFullScreenPlayer - Updated PlayerView with player: $exoPlayer")
            }
        )
        
        // Video information overlay
        val controlsAlpha by animateFloatAsState(
            targetValue = if (showControls) 1f else 0f,
            animationSpec = tween(durationMillis = 300),
            label = "controls_alpha"
        )
        
        if (showControls && currentTweet != null) {
            VideoInfoOverlay(
                tweet = currentTweet!!,
                currentIndex = currentIndex,
                totalVideos = totalVideos,
                onClose = onClose,
                onNext = { FullScreenPlayerManager.playNextVideo() },
                onPrevious = { FullScreenPlayerManager.playPreviousVideo() },
                parentEntry = parentEntry,
                modifier = Modifier.alpha(controlsAlpha)
            )
        }
    }
    
    // Autoplay when entering fullscreen
    LaunchedEffect(exoPlayer) {
        exoPlayer?.let { player ->
            // Wait a bit for player to be ready, then autoplay
            kotlinx.coroutines.delay(200)
            if (player.playbackState == androidx.media3.common.Player.STATE_READY) {
                Timber.d("IndependentFullScreenPlayer - Player ready, autoplaying")
                player.playWhenReady = true
            } else {
                // If not ready yet, wait and try again
                kotlinx.coroutines.delay(300)
                if (player.playbackState == androidx.media3.common.Player.STATE_READY) {
                    Timber.d("IndependentFullScreenPlayer - Player ready after delay, autoplaying")
                    player.playWhenReady = true
                }
            }
        }
    }
    
    // Auto-hide controls (and action buttons) after 3 seconds whenever they are shown
    LaunchedEffect(showControls) {
        if (showControls) {
            kotlinx.coroutines.delay(3000)
            showControls = false
        }
    }
    
    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            Timber.d("IndependentFullScreenPlayer - Disposing, stopping playback and cleaning up manager")
            // Stop playback before cleanup
            exoPlayer?.pause()
            exoPlayer?.playWhenReady = false
            FullScreenPlayerManager.cleanup()
        }
    }
}

@Composable
private fun VideoInfoOverlay(
    tweet: Tweet,
    currentIndex: Int,
    totalVideos: Int,
    onClose: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    parentEntry: NavBackStackEntry,
    modifier: Modifier = Modifier
) {
    // TweetViewModel for action buttons, scoped to the same NavBackStackEntry
    val viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
        parentEntry,
        key = tweet.mid
    ) { factory ->
        factory.create(tweet)
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Tweet action buttons row – floated above the native progress bar
        // Use slightly transparent white so they blend with fullscreen controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 72.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val actionColor = Color.White.copy(alpha = 0.7f)
            CommentButton(viewModel, color = actionColor)
            RetweetButton(viewModel, color = actionColor)
            LikeButton(viewModel, color = actionColor)
            BookmarkButton(viewModel, color = actionColor)
            Spacer(modifier = Modifier.width(20.dp))
            ShareButton(viewModel, color = actionColor)
        }
    }
}

/**
 * Fetch tweets from current context (feed, bookmarks, favorites, etc.)
 * This implementation accesses the current navigation context to get the appropriate tweet list
 */
@Composable
private fun fetchTweetsFromCurrentContext(
    currentTweet: Tweet,
    navController: NavController
): List<Tweet> {
    val context = LocalContext.current
    
    // Get current navigation route to determine context
    val currentRoute = navController.currentBackStackEntry?.destination?.route
    
    Timber.d("IndependentFullScreenPlayer - Current route: $currentRoute")
    Timber.d("IndependentFullScreenPlayer - Fetching context for tweet: ${currentTweet.mid}")
    
    return when {
        // If we're in MediaViewer, we need to get the parent context
        currentRoute?.contains("MediaViewer") == true -> {
            fetchTweetsFromParentContext(currentTweet, navController)
        }
        // If we're in a specific screen, try to get tweets from that context
        else -> {
            fetchTweetsFromScreenContext(currentTweet, currentRoute, context)
        }
    }
}

/**
 * Fetch tweets from parent context when in MediaViewer
 */
@Composable
private fun fetchTweetsFromParentContext(
    currentTweet: Tweet,
    navController: NavController
): List<Tweet> {
    // Get the previous entry to understand where we came from
    val previousEntry = navController.previousBackStackEntry
    val previousRoute = previousEntry?.destination?.route
    
    Timber.d("IndependentFullScreenPlayer - Previous route: $previousRoute")
    
    return when {
        previousRoute?.contains("TweetFeed") == true -> {
            // We came from tweet feed - get tweets from TweetFeedViewModel
            fetchTweetsFromFeed(currentTweet)
        }
        previousRoute?.contains("Bookmarks") == true -> {
            // We came from bookmarks - get tweets from UserViewModel bookmarks
            fetchTweetsFromBookmarks(currentTweet)
        }
        previousRoute?.contains("Favorites") == true -> {
            // We came from favorites - get tweets from UserViewModel favorites
            fetchTweetsFromFavorites(currentTweet)
        }
        previousRoute?.contains("UserProfile") == true -> {
            // We came from user profile - get tweets from UserViewModel tweets
            fetchTweetsFromUserProfile(currentTweet)
        }
        else -> {
            // Default fallback - return just current tweet
            Timber.d("IndependentFullScreenPlayer - Unknown context, returning single tweet")
            listOf(currentTweet)
        }
    }
}

/**
 * Fetch tweets from specific screen context
 */
@Composable
private fun fetchTweetsFromScreenContext(
    currentTweet: Tweet,
    currentRoute: String?,
    context: Context
): List<Tweet> {
    return when {
        currentRoute?.contains("TweetFeed") == true -> {
            fetchTweetsFromFeed(currentTweet)
        }
        currentRoute?.contains("Bookmarks") == true -> {
            fetchTweetsFromBookmarks(currentTweet)
        }
        currentRoute?.contains("Favorites") == true -> {
            fetchTweetsFromFavorites(currentTweet)
        }
        currentRoute?.contains("UserProfile") == true -> {
            fetchTweetsFromUserProfile(currentTweet)
        }
        else -> {
            Timber.d("IndependentFullScreenPlayer - Unknown screen context, returning single tweet")
            listOf(currentTweet)
        }
    }
}

/**
 * Fetch tweets from TweetFeedViewModel
 */
@Composable
private fun fetchTweetsFromFeed(currentTweet: Tweet): List<Tweet> {
    // Get activity-scoped TweetFeedViewModel to ensure single instance
    val activity = LocalActivity.current as ComponentActivity
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>(viewModelStoreOwner = activity)
    val tweets by tweetFeedViewModel.tweets.collectAsState()
    
    Timber.d("IndependentFullScreenPlayer - Fetched ${tweets.size} tweets from feed")
    
    // Filter for tweets with videos and return
    val videoTweets = tweets.filter { tweet ->
        tweet.attachments?.any { attachment ->
            attachment.type == MediaType.Video || 
            attachment.type == MediaType.HLS_VIDEO
        } == true
    }
    
    Timber.d("IndependentFullScreenPlayer - Found ${videoTweets.size} video tweets in feed")
    return if (videoTweets.isEmpty()) listOf(currentTweet) else videoTweets
}

/**
 * Fetch tweets from UserViewModel bookmarks
 */
@Composable
private fun fetchTweetsFromBookmarks(currentTweet: Tweet): List<Tweet> {
    // Get appUserViewModel from SharedViewModel
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val userViewModel = sharedViewModel.appUserViewModel
    val bookmarks by userViewModel.bookmarks.collectAsState()
    
    Timber.d("IndependentFullScreenPlayer - Fetched ${bookmarks.size} bookmarks")
    
    // Filter for tweets with videos and return
    val videoTweets = bookmarks.filter { tweet ->
        tweet.attachments?.any { attachment ->
            attachment.type == MediaType.Video || 
            attachment.type == MediaType.HLS_VIDEO
        } == true
    }
    
    Timber.d("IndependentFullScreenPlayer - Found ${videoTweets.size} video tweets in bookmarks")
    return if (videoTweets.isEmpty()) listOf(currentTweet) else videoTweets
}

/**
 * Fetch tweets from UserViewModel favorites
 */
@Composable
private fun fetchTweetsFromFavorites(currentTweet: Tweet): List<Tweet> {
    // Get appUserViewModel from SharedViewModel
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val userViewModel = sharedViewModel.appUserViewModel
    val favorites by userViewModel.favorites.collectAsState()
    
    Timber.d("IndependentFullScreenPlayer - Fetched ${favorites.size} favorites")
    
    // Filter for tweets with videos and return
    val videoTweets = favorites.filter { tweet ->
        tweet.attachments?.any { attachment ->
            attachment.type == MediaType.Video || 
            attachment.type == MediaType.HLS_VIDEO
        } == true
    }
    
    Timber.d("IndependentFullScreenPlayer - Found ${videoTweets.size} video tweets in favorites")
    return if (videoTweets.isEmpty()) listOf(currentTweet) else videoTweets
}

/**
 * Fetch tweets from UserViewModel user profile tweets
 */
@Composable
private fun fetchTweetsFromUserProfile(currentTweet: Tweet): List<Tweet> {
    // Get appUserViewModel from SharedViewModel
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val userViewModel = sharedViewModel.appUserViewModel
    val tweets by userViewModel.tweets.collectAsState()
    
    Timber.d("IndependentFullScreenPlayer - Fetched ${tweets.size} tweets from user profile")
    
    // Filter for tweets with videos and return
    val videoTweets = tweets.filter { tweet ->
        tweet.attachments?.any { attachment ->
            attachment.type == MediaType.Video || 
            attachment.type == MediaType.HLS_VIDEO
        } == true
    }
    
    Timber.d("IndependentFullScreenPlayer - Found ${videoTweets.size} video tweets in user profile")
    return if (videoTweets.isEmpty()) listOf(currentTweet) else videoTweets
}