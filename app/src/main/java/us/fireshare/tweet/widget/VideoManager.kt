package us.fireshare.tweet.widget

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import timber.log.Timber
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId
import java.util.concurrent.ConcurrentHashMap

/**
 * VideoManager handles ExoPlayer instances for videos using video mid as keys.
 * Similar to iOS VideoCacheManager, this provides centralized video instance management.
 */
@OptIn(UnstableApi::class)
object VideoManager {
    
    // Thread-safe map to store ExoPlayer instances by video mid
    private val videoPlayers = ConcurrentHashMap<MimeiId, ExoPlayer>()
    
    // Track which videos are currently being used
    private val activeVideos = ConcurrentHashMap<MimeiId, Int>()
    
    /**
     * Get or create an ExoPlayer instance for a video
     * @param context Android context
     * @param videoMid Video's unique identifier
     * @param videoUrl Video URL (for creating new instances)
     * @return ExoPlayer instance
     */
    fun getVideoPlayer(context: Context, videoMid: MimeiId, videoUrl: String): ExoPlayer {
        Timber.d("VideoManager - getVideoPlayer called for videoMid: $videoMid, videoUrl: $videoUrl")
        Timber.d("VideoManager - Existing players: ${videoPlayers.keys}")
        
        val isReusing = videoPlayers.containsKey(videoMid)
        
        return videoPlayers.getOrPut(videoMid) {
            Timber.d("VideoManager - Creating new ExoPlayer for video: $videoMid")
            Timber.d("VideoManager - Video URL for new player: $videoUrl")
            val player = createExoPlayer(context, videoUrl, MediaType.Video)
            Timber.d("VideoManager - New player created successfully: ${player != null}")
            player
        }.also { player ->
            // Reset player state when reusing an existing player
            if (isReusing) {
                Timber.d("VideoManager - Resetting reused player for video: $videoMid")
                resetPlayerState(player)
            }
        }
    }
    
    /**
     * Reset player state to ensure proper playback when reused
     * @param player ExoPlayer instance to reset
     */
    private fun resetPlayerState(player: ExoPlayer) {
        try {
            Timber.d("VideoManager - Resetting player state. Current state: ${getPlayerStateName(player.playbackState)}")
            
            // Stop playback and reset to beginning
            player.stop()
            player.seekTo(0)
            player.playWhenReady = false
            
            // Clear any error state
            if (player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                Timber.d("VideoManager - Player was in IDLE state, preparing again")
                player.prepare()
            }
            
            Timber.d("VideoManager - Player state reset successfully. New state: ${getPlayerStateName(player.playbackState)}")
        } catch (e: Exception) {
            Timber.e("VideoManager - Error resetting player state: $e")
        }
    }
    
    /**
     * Get human-readable player state name
     */
    private fun getPlayerStateName(state: Int): String {
        return when (state) {
            androidx.media3.common.Player.STATE_IDLE -> "IDLE"
            androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
            androidx.media3.common.Player.STATE_READY -> "READY"
            androidx.media3.common.Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN"
        }
    }
    
    /**
     * Force reset a specific video player (for debugging/testing)
     * @param videoMid Video's unique identifier
     */
    fun forceResetVideo(videoMid: MimeiId) {
        videoPlayers[videoMid]?.let { player ->
            Timber.d("VideoManager - Force resetting video: $videoMid")
            resetPlayerState(player)
        }
    }
    
    /**
     * Mark a video as active (being used by a composable)
     * @param videoMid Video's unique identifier
     */
    fun markVideoActive(videoMid: MimeiId) {
        val currentCount = activeVideos.getOrDefault(videoMid, 0)
        activeVideos[videoMid] = currentCount + 1
        Timber.d("VideoManager - Video $videoMid marked active (count: ${currentCount + 1})")
    }
    
    /**
     * Mark a video as inactive (no longer being used by a composable)
     * @param videoMid Video's unique identifier
     */
    fun markVideoInactive(videoMid: MimeiId) {
        val currentCount = activeVideos.getOrDefault(videoMid, 0)
        if (currentCount > 0) {
            val newCount = currentCount - 1
            if (newCount == 0) {
                activeVideos.remove(videoMid)
                Timber.d("VideoManager - Video $videoMid no longer active, keeping in memory")
            } else {
                activeVideos[videoMid] = newCount
                Timber.d("VideoManager - Video $videoMid still active (count: $newCount)")
            }
        }
    }
    
    /**
     * Pause a specific video
     * @param videoMid Video's unique identifier
     */
    fun pauseVideo(videoMid: MimeiId) {
        videoPlayers[videoMid]?.let { player ->
            player.playWhenReady = false
            Timber.d("VideoManager - Paused video: $videoMid")
        }
    }
    
    /**
     * Resume a specific video
     * @param videoMid Video's unique identifier
     * @param shouldPlay Whether the video should start playing
     */
    fun resumeVideo(videoMid: MimeiId, shouldPlay: Boolean = true) {
        videoPlayers[videoMid]?.let { player ->
            player.playWhenReady = shouldPlay
            Timber.d("VideoManager - Resumed video: $videoMid (shouldPlay: $shouldPlay)")
        }
    }
    
    /**
     * Pause all videos
     */
    fun pauseAllVideos() {
        videoPlayers.values.forEach { player ->
            player.playWhenReady = false
        }
        Timber.d("VideoManager - Paused all videos")
    }
    
    /**
     * Resume all active videos
     */
    fun resumeAllActiveVideos() {
        activeVideos.keys.forEach { videoMid ->
            resumeVideo(videoMid, true)
        }
        Timber.d("VideoManager - Resumed all active videos")
    }
    
    /**
     * Release a specific video player
     * @param videoMid Video's unique identifier
     * Note: This method must be called on the main thread
     */
    fun releaseVideo(videoMid: MimeiId) {
        // Ensure we're on the main thread
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            Timber.e("VideoManager - releaseVideo() called on wrong thread. Current: ${Thread.currentThread().name}, Expected: main")
            throw IllegalStateException("VideoManager.releaseVideo() must be called on the main thread")
        }
        
        videoPlayers.remove(videoMid)?.let { player ->
            try {
                player.release()
                activeVideos.remove(videoMid)
                Timber.d("VideoManager - Released video: $videoMid")
            } catch (e: Exception) {
                Timber.e("VideoManager - Error releasing video $videoMid: $e")
            }
        }
    }
    
    /**
     * Release all video players
     * Note: This method must be called on the main thread
     */
    fun releaseAllVideos() {
        // Ensure we're on the main thread
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            Timber.e("VideoManager - releaseAllVideos() called on wrong thread. Current: ${Thread.currentThread().name}, Expected: main")
            throw IllegalStateException("VideoManager.releaseAllVideos() must be called on the main thread")
        }
        
        videoPlayers.values.forEach { player ->
            try {
                player.release()
            } catch (e: Exception) {
                Timber.e("VideoManager - Error releasing player: $e")
            }
        }
        videoPlayers.clear()
        activeVideos.clear()
        Timber.d("VideoManager - Released all videos")
    }
    
    /**
     * Get the number of cached video players
     */
    fun getCachedVideoCount(): Int = videoPlayers.size
    
    /**
     * Get the number of active videos
     */
    fun getActiveVideoCount(): Int = activeVideos.size
    
    /**
     * Check if a specific video is active
     */
    fun isVideoActive(videoMid: MimeiId): Boolean = activeVideos.containsKey(videoMid)
    
    /**
     * Get the active count for a specific video
     */
    fun getVideoActiveCount(videoMid: MimeiId): Int = activeVideos.getOrDefault(videoMid, 0)
    
    /**
     * Check if a video is cached
     */
    fun isVideoCached(videoMid: MimeiId): Boolean = videoPlayers.containsKey(videoMid)
    
    /**
     * Get cache statistics
     */
    fun getCacheStats(): String {
        return "Cached videos: ${getCachedVideoCount()}, Active videos: ${getActiveVideoCount()}"
    }
    
    /**
     * Clean up unused video players (optional - for memory management)
     * This can be called periodically to free up memory
     * Note: This method must be called on the main thread
     */
    fun cleanupUnusedVideos() {
        // Ensure we're on the main thread
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            Timber.e("VideoManager - cleanupUnusedVideos() called on wrong thread. Current: ${Thread.currentThread().name}, Expected: main")
            throw IllegalStateException("VideoManager.cleanupUnusedVideos() must be called on the main thread")
        }
        
        val unusedVideos = videoPlayers.keys.filter { !activeVideos.containsKey(it) }
        unusedVideos.forEach { videoMid ->
            releaseVideo(videoMid)
        }
        if (unusedVideos.isNotEmpty()) {
            Timber.d("VideoManager - Cleaned up ${unusedVideos.size} unused videos")
        }
    }
} 