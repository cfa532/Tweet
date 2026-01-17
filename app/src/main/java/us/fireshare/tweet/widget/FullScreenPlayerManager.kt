package us.fireshare.tweet.widget

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import timber.log.Timber
import us.fireshare.tweet.BuildConfig
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.Tweet

/**
 * Singleton manager for the independent fullscreen video player.
 * Handles automatic video progression and maintains tweet list context.
 */
object FullScreenPlayerManager {
    private var exoPlayer: ExoPlayer? = null
    private var currentVideoList: List<Pair<MimeiId, MediaType>>? = null
    private var videoBaseUrlMap: Map<MimeiId, String> = emptyMap() // Map videoMid to author's baseUrl
    private var currentVideoIndex: Int = 0
    private var onVideoChanged: ((MimeiId, Int) -> Unit)? = null
    private var onPlayerStateChanged: ((PlayerState) -> Unit)? = null
    private var applicationContext: Context? = null // Use Application context to avoid memory leaks
    private var isManualNavigation: Boolean = false // Flag to prevent double progression when user manually skips
    
    /**
     * Initialize the singleton player instance
     */
    fun initialize(context: Context) {
        // Store Application context to avoid memory leaks
        this.applicationContext = context.applicationContext
        if (exoPlayer == null) {
            Timber.d("FullScreenPlayerManager - Initializing singleton player")
            exoPlayer = createExoPlayer(context.applicationContext, "")
            
            // Add listener for automatic video rewinding when finished
            exoPlayer?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    Timber.d("FullScreenPlayerManager - Playback state changed: $playbackState")
                    when (playbackState) {
                        Player.STATE_ENDED -> {
                            // Rewind is handled by CreateExoPlayer listener
                            Timber.d("FullScreenPlayerManager - Video ended")
                        }
                        Player.STATE_READY -> {
                            Timber.d("FullScreenPlayerManager - Video ready to play")
                        }
                        Player.STATE_BUFFERING -> {
                            Timber.d("FullScreenPlayerManager - Video buffering")
                        }
                    }
                }
                
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Timber.e("FullScreenPlayerManager - Player error: ${error.message}")
                }
            })
        }
    }
    
    /**
     * Set the video list context and start playing from the specified index
     */
    fun setVideoList(videoList: List<Pair<MimeiId, MediaType>>, startIndex: Int) {
        Timber.d("FullScreenPlayerManager - Setting video list with ${videoList.size} videos, start index: $startIndex")
        currentVideoList = videoList
        currentVideoIndex = startIndex.coerceIn(0, videoList.size - 1)
        // Use runBlocking to call suspend function from non-suspend context
        kotlinx.coroutines.runBlocking {
            playCurrentVideo()
        }
    }
    
    /**
     * Update video list from VideoPlaybackCoordinator (consolidated tracking)
     * Similar to iOS FullScreenVideoManager.updateVideoList()
     * This allows VideoPlaybackCoordinator to share its video list with FullScreenPlayerManager
     */
    fun updateVideoList(videoList: List<Pair<MimeiId, MediaType>>, tweets: List<Tweet>) {
        Timber.d("FullScreenPlayerManager - Updating video list from VideoPlaybackCoordinator: ${videoList.size} videos, ${tweets.size} tweets")
        
        // Build map from videoMid to author's baseUrl
        videoBaseUrlMap = buildBaseUrlMap(videoList, tweets)
        
        // Only update the list if we don't have a current list or if the current video is no longer in the new list
        if (currentVideoList == null || getCurrentVideoMid()?.let { currentMid ->
            !videoList.any { it.first == currentMid }
        } == true) {
            // Current video not in new list, update but don't auto-play
            currentVideoList = videoList
            if (currentVideoIndex >= videoList.size) {
                currentVideoIndex = 0
            }
        } else {
            // Current video still in list, just update the list reference
            currentVideoList = videoList
        }
    }
    
    /**
     * Build a map from videoMid to author's baseUrl by finding the tweet containing each video
     */
    private fun buildBaseUrlMap(videoList: List<Pair<MimeiId, MediaType>>, tweets: List<Tweet>): Map<MimeiId, String> {
        val baseUrlMap = mutableMapOf<MimeiId, String>()
        
        for ((videoMid, _) in videoList) {
            val tweet = findTweetContainingVideo(videoMid, tweets)
            val baseUrl = tweet?.author?.baseUrl ?: HproseInstance.appUser.baseUrl
            if (baseUrl != null) {
                baseUrlMap[videoMid] = baseUrl
                Timber.d("FullScreenPlayerManager - Mapped video $videoMid to baseUrl: $baseUrl (from tweet ${tweet?.mid})")
            }
        }
        
        return baseUrlMap
    }
    
    /**
     * Find the tweet containing the given video, checking both own attachments and original tweet's attachments (for retweets/quotes)
     */
    private fun findTweetContainingVideo(videoMid: MimeiId, tweets: List<Tweet>): Tweet? {
        for (tweet in tweets) {
            // Check if video is in this tweet's own attachments
            if (tweet.attachments?.any { it.mid == videoMid } == true) {
                return tweet
            }
            // Check if video is in this tweet's original tweet (for retweets/quotes)
            tweet.originalTweetId?.let { originalId ->
                val originalTweet = tweets.find { it.mid == originalId }
                if (originalTweet?.attachments?.any { it.mid == videoMid } == true) {
                    return originalTweet // Use original tweet's author baseUrl
                }
            }
        }
        return null
    }
    
    /**
     * Play the next video in the list
     */
    fun playNextVideo() {
        val videoList = currentVideoList ?: return
        Timber.d("FullScreenPlayerManager - Playing next video, current index: $currentVideoIndex, total videos: ${videoList.size}")
        
        // Mark that this change was initiated by the user/gesture
        isManualNavigation = true
        
        if (currentVideoIndex < videoList.size - 1) {
            val nextIndex = currentVideoIndex + 1
            val (nextVideoMid, nextMediaType) = videoList[nextIndex]
            Timber.d("FullScreenPlayerManager - Moving from index $currentVideoIndex to $nextIndex, next video: $nextVideoMid, type: $nextMediaType")
            currentVideoIndex = nextIndex
            // Use runBlocking to call suspend function from non-suspend context
            kotlinx.coroutines.runBlocking {
                playCurrentVideo()
            }
        } else {
            // End of list - stop playing
            Timber.d("FullScreenPlayerManager - End of list, stopping playback")
            exoPlayer?.stop()
        }
    }
    
    /**
     * Play the previous video in the list
     */
    fun playPreviousVideo() {
        val videoList = currentVideoList ?: return
        Timber.d("FullScreenPlayerManager - Playing previous video, current index: $currentVideoIndex")
        
        // Mark that this change was initiated by the user/gesture
        isManualNavigation = true
        
        if (currentVideoIndex > 0) {
            currentVideoIndex--
        } else {
            // Beginning of list - loop to end
            Timber.d("FullScreenPlayerManager - Beginning of list, looping to end")
            currentVideoIndex = videoList.size - 1
        }
        // Use runBlocking to call suspend function from non-suspend context
        kotlinx.coroutines.runBlocking {
            playCurrentVideo()
        }
    }
    
    /**
     * Play the video at the current index
     */
    private suspend fun playCurrentVideo() {
        val videoList = currentVideoList ?: return
        val (videoMid, mediaType) = videoList[currentVideoIndex]
        
        Timber.d("FullScreenPlayerManager - Playing video at index $currentVideoIndex: $videoMid, type: $mediaType")
        
        // Get baseUrl from map (author's baseUrl), with fallback chain: author -> appUser -> BuildConfig
        val baseUrl = videoBaseUrlMap[videoMid] 
            ?: HproseInstance.appUser.baseUrl 
            ?: "http://${BuildConfig.BASE_URL}"
        Timber.d("FullScreenPlayerManager - Using baseUrl for video $videoMid: $baseUrl")
        
        val videoUrl = HproseInstance.getMediaUrl(videoMid, baseUrl)
        if (videoUrl != null) {
            Timber.d("FullScreenPlayerManager - Loading video: $videoUrl, type: $mediaType")
            loadVideo(videoUrl, mediaType)
            onVideoChanged?.invoke(videoMid, currentVideoIndex)
        } else {
            Timber.w("FullScreenPlayerManager - Could not generate video URL for attachment: $videoMid")
        }
    }
    
    /**
     * Load a video into the player
     */
    private fun loadVideo(videoUrl: String, mediaType: MediaType) {
        val player = exoPlayer ?: return
        val ctx = applicationContext ?: return
        
        try {
            // Create a new ExoPlayer with the video URL (like VideoPreview does when videoMid is null)
            Timber.d("FullScreenPlayerManager - Creating new ExoPlayer for video")
            val newPlayer = createExoPlayer(ctx, videoUrl, mediaType)
            
            // Release the old player and set the new one
            player.release()
            exoPlayer = newPlayer

            // Reset manual navigation flag when starting a fresh video
            // so that natural video completion can auto-advance
            isManualNavigation = false
            
            // Add listener for automatic video rewinding when finished
            newPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    Timber.d("FullScreenPlayerManager - Playback state changed: $playbackState")
                    when (playbackState) {
                        Player.STATE_ENDED -> {
                            Timber.d("FullScreenPlayerManager - Video ended")
                            // If this wasn't a manual skip, automatically play the next video
                            if (!isManualNavigation) {
                                Timber.d("FullScreenPlayerManager - Auto-playing next video after completion")
                                playNextVideo()
                            } else {
                                // Clear the manual flag so the next natural end can auto-advance
                                Timber.d("FullScreenPlayerManager - Manual navigation flag cleared after end")
                                isManualNavigation = false
                            }
                        }
                        Player.STATE_READY -> {
                            Timber.d("FullScreenPlayerManager - Video ready to play")
                        }
                        Player.STATE_BUFFERING -> {
                            Timber.d("FullScreenPlayerManager - Video buffering")
                        }
                    }
                }
                
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Timber.e("FullScreenPlayerManager - Player error: ${error.message}")
                }
            })
            
            // Start playing the video
            newPlayer.playWhenReady = true
            
            Timber.d("FullScreenPlayerManager - Video player created and ready, starting playback")
        } catch (e: Exception) {
            Timber.e("FullScreenPlayerManager - Error loading video: ${e.message}")
        }
    }
    
    /**
     * Get the current ExoPlayer instance
     */
    fun getCurrentPlayer(): ExoPlayer? = exoPlayer
    
    /**
     * Get the current video mid
     */
    fun getCurrentVideoMid(): MimeiId? = currentVideoList?.getOrNull(currentVideoIndex)?.first
    
    /**
     * Get the current video index
     */
    fun getCurrentIndex(): Int = currentVideoIndex
    
    /**
     * Get the total number of videos in the current list
     */
    fun getTotalVideos(): Int = currentVideoList?.size ?: 0
    
    /**
     * Get the current video list (from VideoPlaybackCoordinator)
     */
    fun getVideoList(): List<Pair<MimeiId, MediaType>>? = currentVideoList
    
    /**
     * Set callback for when video changes
     */
    fun setOnVideoChanged(callback: (MimeiId, Int) -> Unit) {
        onVideoChanged = callback
    }
    
    /**
     * Set callback for player state changes
     */
    fun setOnPlayerStateChanged(callback: (PlayerState) -> Unit) {
        onPlayerStateChanged = callback
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        Timber.d("FullScreenPlayerManager - Cleaning up resources")
        // Stop playback before releasing
        exoPlayer?.pause()
        exoPlayer?.playWhenReady = false
        exoPlayer?.release()
        exoPlayer = null
        currentVideoList = null
        videoBaseUrlMap = emptyMap()
        currentVideoIndex = 0
        applicationContext = null
        onVideoChanged = null
        onPlayerStateChanged = null
    }
    
    /**
     * Check if the player is initialized
     */
    fun isInitialized(): Boolean = exoPlayer != null
}

/**
 * Player state for callbacks
 */
data class PlayerState(
    val isPlaying: Boolean,
    val currentPosition: Long,
    val duration: Long,
    val bufferedPosition: Long
)
