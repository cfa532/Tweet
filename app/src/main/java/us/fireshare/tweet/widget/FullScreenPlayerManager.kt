package us.fireshare.tweet.widget

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId

/**
 * Singleton manager for the independent fullscreen video player.
 * Handles automatic video progression and maintains tweet list context.
 */
object FullScreenPlayerManager {
    private var exoPlayer: ExoPlayer? = null
    private var currentVideoList: List<Pair<MimeiId, MediaType>>? = null
    private var currentVideoIndex: Int = 0
    private var onVideoChanged: ((MimeiId, Int) -> Unit)? = null
    private var onPlayerStateChanged: ((PlayerState) -> Unit)? = null
    private var applicationContext: Context? = null // Use Application context to avoid memory leaks
    private var isManualNavigation: Boolean = false // Flag to prevent double progression
    
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
     * Play the next video in the list
     */
    fun playNextVideo() {
        val videoList = currentVideoList ?: return
        Timber.d("FullScreenPlayerManager - Playing next video, current index: $currentVideoIndex, total videos: ${videoList.size}")
        
        // Set manual navigation flag to prevent double progression
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
        
        // Set manual navigation flag to prevent double progression
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
        
        // Generate video URL using the video mid with a default base URL
        // TODO: Get base URL from TweetListViewModel or pass it as parameter
        val baseUrl = "http://125.229.161.122:8080" // Default base URL for now
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
            
            // Add listener for automatic video rewinding when finished
            newPlayer.addListener(object : Player.Listener {
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
