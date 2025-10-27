package us.fireshare.tweet.widget

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.Tweet

/**
 * Singleton manager for the independent fullscreen video player.
 * Handles automatic video progression and maintains tweet list context.
 */
object FullScreenPlayerManager {
    private var exoPlayer: ExoPlayer? = null
    private var currentTweetList: List<Tweet>? = null
    private var currentVideoIndex: Int = 0
    private var onVideoChanged: ((Tweet, Int) -> Unit)? = null
    private var onPlayerStateChanged: ((PlayerState) -> Unit)? = null
    private var context: Context? = null
    
    /**
     * Initialize the singleton player instance
     */
    fun initialize(context: Context) {
        this.context = context
        if (exoPlayer == null) {
            Timber.d("FullScreenPlayerManager - Initializing singleton player")
            exoPlayer = createExoPlayer(context, "")
            
            // Add listener for automatic video progression
            exoPlayer?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    Timber.d("FullScreenPlayerManager - Playback state changed: $playbackState")
                    when (playbackState) {
                        Player.STATE_ENDED -> {
                            Timber.d("FullScreenPlayerManager - Video ended, auto-playing next")
                            // Auto-play next video when current video ends
                            playNextVideo()
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
     * Set the tweet list context and start playing from the specified index
     */
    fun setTweetList(tweets: List<Tweet>, startIndex: Int) {
        Timber.d("FullScreenPlayerManager - Setting tweet list with ${tweets.size} tweets, start index: $startIndex")
        currentTweetList = tweets
        currentVideoIndex = startIndex.coerceIn(0, tweets.size - 1)
        playCurrentVideo()
    }
    
    /**
     * Play the next video in the list
     */
    fun playNextVideo() {
        val tweets = currentTweetList ?: return
        Timber.d("FullScreenPlayerManager - Playing next video, current index: $currentVideoIndex")
        
        if (currentVideoIndex < tweets.size - 1) {
            currentVideoIndex++
            playCurrentVideo()
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
        val tweets = currentTweetList ?: return
        Timber.d("FullScreenPlayerManager - Playing previous video, current index: $currentVideoIndex")
        
        if (currentVideoIndex > 0) {
            currentVideoIndex--
        } else {
            // Beginning of list - loop to end
            Timber.d("FullScreenPlayerManager - Beginning of list, looping to end")
            currentVideoIndex = tweets.size - 1
        }
        playCurrentVideo()
    }
    
    /**
     * Play the video at the current index
     */
    private fun playCurrentVideo() {
        val tweets = currentTweetList ?: return
        val tweet = tweets[currentVideoIndex]
        
        Timber.d("FullScreenPlayerManager - Playing video at index $currentVideoIndex for tweet: ${tweet.mid}")
        
        // Find video attachment
        val videoAttachment = tweet.attachments?.find { attachment ->
            val mediaType = inferMediaTypeFromAttachment(attachment)
            mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO
        }
        
        if (videoAttachment != null) {
            val videoUrl = HproseInstance.getMediaUrl(videoAttachment.mid, tweet.author?.baseUrl.orEmpty())
            if (videoUrl != null) {
                val mediaType = inferMediaTypeFromAttachment(videoAttachment)
                Timber.d("FullScreenPlayerManager - Loading video: $videoUrl, type: $mediaType")
                loadVideo(videoUrl, mediaType)
                onVideoChanged?.invoke(tweet, currentVideoIndex)
            } else {
                Timber.w("FullScreenPlayerManager - Could not generate video URL for attachment: ${videoAttachment.mid}")
            }
        } else {
            Timber.w("FullScreenPlayerManager - No video attachment found in tweet: ${tweet.mid}")
        }
    }
    
    /**
     * Load a video into the player
     */
    private fun loadVideo(videoUrl: String, mediaType: MediaType) {
        val player = exoPlayer ?: return
        val ctx = context ?: return
        
        try {
            // Create a new ExoPlayer with the video URL (like VideoPreview does when videoMid is null)
            Timber.d("FullScreenPlayerManager - Creating new ExoPlayer for video")
            val newPlayer = createExoPlayer(ctx, videoUrl, mediaType)
            
            // Release the old player and set the new one
            player.release()
            exoPlayer = newPlayer
            
            // Add listener for automatic video progression to the new player
            newPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    Timber.d("FullScreenPlayerManager - Playback state changed: $playbackState")
                    when (playbackState) {
                        Player.STATE_ENDED -> {
                            Timber.d("FullScreenPlayerManager - Video ended, auto-playing next")
                            // Auto-play next video when current video ends
                            playNextVideo()
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
     * Get the current tweet
     */
    fun getCurrentTweet(): Tweet? = currentTweetList?.getOrNull(currentVideoIndex)
    
    /**
     * Get the current video index
     */
    fun getCurrentIndex(): Int = currentVideoIndex
    
    /**
     * Get the total number of videos in the current list
     */
    fun getTotalVideos(): Int = currentTweetList?.size ?: 0
    
    /**
     * Set callback for when video changes
     */
    fun setOnVideoChanged(callback: (Tweet, Int) -> Unit) {
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
        exoPlayer?.release()
        exoPlayer = null
        currentTweetList = null
        currentVideoIndex = 0
        context = null
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
