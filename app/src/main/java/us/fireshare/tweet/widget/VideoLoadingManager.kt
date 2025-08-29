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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId

/**
 * VideoLoadingManager handles intelligent video loading based on scroll position.
 * It stops loading videos that are scrolled past and preloads videos from upcoming tweets.
 */
object VideoLoadingManager {
    
    // Track which videos are currently visible
    private val visibleVideos = mutableSetOf<MimeiId>()
    
    // Track which videos are being preloaded
    private val preloadingVideos = mutableSetOf<MimeiId>()
    
    // Configuration
    private const val PRELOAD_AHEAD_COUNT = 3 // Number of upcoming tweets to preload videos from
    private const val PRELOAD_DELAY_MS = 500L // Delay before starting preload to avoid excessive loading
    
    /**
     * Mark a video as visible (user is currently viewing it)
     */
    fun markVideoVisible(videoMid: MimeiId) {
        visibleVideos.add(videoMid)
        Timber.d("VideoLoadingManager - Video marked visible: $videoMid")
    }
    
    /**
     * Mark a video as not visible (user has scrolled past it)
     */
    fun markVideoNotVisible(videoMid: MimeiId) {
        visibleVideos.remove(videoMid)
        Timber.d("VideoLoadingManager - Video marked not visible: $videoMid")
        
        // Note: Video stopping will be handled by VideoManager's memory management
        // when the video becomes inactive
    }
    
    /**
     * Preload videos from upcoming tweets
     * @param context Android context
     * @param currentTweetIndex Current tweet index in the list
     * @param tweets List of all tweets
     * @param baseUrl Base URL for media
     */
    fun preloadUpcomingVideos(
        context: Context,
        currentTweetIndex: Int,
        tweets: List<us.fireshare.tweet.datamodel.Tweet>,
        baseUrl: String
    ) {
        val coroutineScope = CoroutineScope(Dispatchers.IO)
        
        coroutineScope.launch {
            // Add delay to avoid excessive preloading during rapid scrolling
            delay(PRELOAD_DELAY_MS)
            
            // Calculate range of tweets to preload from
            val startIndex = currentTweetIndex + 1
            val endIndex = minOf(startIndex + PRELOAD_AHEAD_COUNT, tweets.size)
            
            for (i in startIndex until endIndex) {
                val tweet = tweets[i]
                val videoAttachments = tweet.attachments?.filter { 
                    it.type == us.fireshare.tweet.datamodel.MediaType.Video || 
                    it.type == us.fireshare.tweet.datamodel.MediaType.HLS_VIDEO 
                } ?: emptyList()
                
                for (attachment in videoAttachments) {
                    if (!VideoManager.isVideoPreloaded(attachment.mid) && 
                        !preloadingVideos.contains(attachment.mid)) {
                        
                        preloadingVideos.add(attachment.mid)
                        
                        try {
                            val mediaUrl = us.fireshare.tweet.HproseInstance.getMediaUrl(
                                attachment.mid, 
                                baseUrl
                            ).toString()
                            
                            VideoManager.preloadVideo(context, attachment.mid, mediaUrl)
                            Timber.d("VideoLoadingManager - Preloading video: ${attachment.mid} from tweet $i")
                        } catch (e: Exception) {
                            Timber.e("VideoLoadingManager - Failed to preload video: ${attachment.mid}, error: ${e.message}")
                        } finally {
                            preloadingVideos.remove(attachment.mid)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Stop preloading all videos
     */
    fun stopAllPreloading() {
        preloadingVideos.clear()
        Timber.d("VideoLoadingManager - Stopped all preloading")
    }
    
    /**
     * Get currently visible videos
     */
    fun getVisibleVideos(): Set<MimeiId> = visibleVideos.toSet()
    
    /**
     * Get currently preloading videos
     */
    fun getPreloadingVideos(): Set<MimeiId> = preloadingVideos.toSet()
    
    /**
     * Clear all tracking data (useful for testing or reset)
     */
    fun clear() {
        visibleVideos.clear()
        preloadingVideos.clear()
        Timber.d("VideoLoadingManager - Cleared all tracking data")
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
    val context = LocalContext.current
    
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
