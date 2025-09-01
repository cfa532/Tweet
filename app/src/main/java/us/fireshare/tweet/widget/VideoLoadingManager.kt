package us.fireshare.tweet.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import timber.log.Timber
import us.fireshare.tweet.datamodel.MimeiId

/**
 * VideoLoadingManager provides Compose hooks for video loading management.
 * All actual functionality is delegated to the unified VideoManager.
 * 
 * This class serves as a bridge between Compose UI and VideoManager,
 * providing convenient hooks for visibility tracking and preloading.
 */
object VideoLoadingManager {
    
    /**
     * Mark a video as visible (user is currently viewing it)
     * Delegates to VideoManager.markVideoVisible()
     */
    fun markVideoVisible(videoMid: MimeiId) {
        VideoManager.markVideoVisible(videoMid)
    }
    
    /**
     * Mark a video as not visible (user has scrolled past it)
     * Delegates to VideoManager.markVideoNotVisible()
     */
    fun markVideoNotVisible(videoMid: MimeiId) {
        VideoManager.markVideoNotVisible(videoMid)
    }
    
    /**
     * Preload videos from upcoming tweets
     * Delegates to VideoManager.preloadUpcomingVideos()
     */
    fun preloadUpcomingVideos(
        context: Context,
        currentTweetIndex: Int,
        tweets: List<us.fireshare.tweet.datamodel.Tweet>,
        baseUrl: String
    ) {
        VideoManager.preloadUpcomingVideos(context, currentTweetIndex, tweets, baseUrl)
    }
    
    /**
     * Stop preloading all videos
     * Delegates to VideoManager.stopAllPreloading()
     */
    fun stopAllPreloading() {
        VideoManager.stopAllPreloading()
    }
    
    /**
     * Get currently visible videos
     * Delegates to VideoManager.getVisibleVideos()
     */
    fun getVisibleVideos(): Set<MimeiId> = VideoManager.getVisibleVideos()
    
    /**
     * Get currently preloading videos
     * Delegates to VideoManager.getPreloadingVideos()
     */
    fun getPreloadingVideos(): Set<MimeiId> = VideoManager.getPreloadingVideos()
    
    /**
     * Clear all tracking data
     * Delegates to VideoManager.clear()
     */
    fun clear() {
        VideoManager.clear()
    }
}

/**
 * Composable hook to manage video loading for a specific video
 * @param videoMid Video's unique identifier
 * @param isVisible Whether the video is currently visible
 * @param onVisibilityChanged Callback when visibility changes
 */
@Composable
fun rememberVideoLoadingManager(
    videoMid: MimeiId,
    isVisible: Boolean,
    onVisibilityChanged: ((Boolean) -> Unit)? = null
) {
    // Track previous visibility state
    var wasVisible by remember { mutableStateOf(false) }
    
    LaunchedEffect(isVisible) {
        if (isVisible != wasVisible) {
            if (isVisible) {
                VideoLoadingManager.markVideoVisible(videoMid)
            } else {
                VideoLoadingManager.markVideoNotVisible(videoMid)
            }
            onVisibilityChanged?.invoke(isVisible)
            wasVisible = isVisible
        }
    }
    
    // Cleanup when component is disposed
    DisposableEffect(Unit) {
        onDispose {
            VideoLoadingManager.markVideoNotVisible(videoMid)
        }
    }
}

/**
 * Composable hook to manage preloading for a list of tweets
 * @param tweets List of tweets
 * @param currentVisibleIndex Current visible tweet index
 * @param baseUrl Base URL for media
 */
@Composable
fun rememberTweetVideoPreloader(
    tweets: List<us.fireshare.tweet.datamodel.Tweet>,
    currentVisibleIndex: Int,
    baseUrl: String
) {
    val context = LocalContext.current
    
    LaunchedEffect(currentVisibleIndex, tweets.size) {
        if (tweets.isNotEmpty() && currentVisibleIndex >= 0) {
            VideoLoadingManager.preloadUpcomingVideos(
                context = context,
                currentTweetIndex = currentVisibleIndex,
                tweets = tweets,
                baseUrl = baseUrl
            )
        }
    }
    
    // Stop preloading when component is disposed
    DisposableEffect(Unit) {
        onDispose {
            VideoLoadingManager.stopAllPreloading()
        }
    }
}
