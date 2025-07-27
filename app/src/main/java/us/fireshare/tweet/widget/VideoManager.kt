package us.fireshare.tweet.widget

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    
    // Sequential playback management
    private val videoPlaylist = mutableListOf<MimeiId>()
    private var currentPlaylistIndex = -1
    private var isSequentialPlaybackEnabled = false
    
    // Preload management
    private val preloadedVideos = mutableSetOf<MimeiId>()
    private val preloadQueue = mutableListOf<MimeiId>()
    
    /**
     * Get or create an ExoPlayer instance for a video
     * @param context Android context
     * @param videoMid Video's unique identifier
     * @param videoUrl Video URL (for creating new instances)
     * @return ExoPlayer instance
     */
    fun getVideoPlayer(context: Context, videoMid: MimeiId, videoUrl: String): ExoPlayer {
        // Mark as preloaded if it was in the preload queue
        preloadedVideos.add(videoMid)
        preloadQueue.remove(videoMid)
        
        Timber.d("VideoManager - getVideoPlayer called for videoMid: $videoMid, videoUrl: $videoUrl")
        Timber.d("VideoManager - Existing players: ${videoPlayers.keys}")
        
        val isReusing = videoPlayers.containsKey(videoMid)
        
        return videoPlayers.getOrPut(videoMid) {
            Timber.d("VideoManager - Creating new ExoPlayer for video: $videoMid")
            Timber.d("VideoManager - Video URL for new player: $videoUrl")
            
            try {
                val player = createExoPlayer(context, videoUrl, MediaType.Video)
                Timber.d("VideoManager - New player created successfully: ${player != null}")
                player
            } catch (e: Exception) {
                Timber.e("VideoManager - Error creating ExoPlayer for video: $videoMid", e)
                // If creation fails, remove from map and rethrow
                videoPlayers.remove(videoMid)
                throw e
            }
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
            
            // Don't stop if player is already ready - just pause
            if (player.playbackState == androidx.media3.common.Player.STATE_READY) {
                player.playWhenReady = false
                Timber.d("VideoManager - Player already ready, just paused")
                return
            }
            
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
                // Stop playback before releasing
                player.stop()
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
                // Stop playback before releasing
                player.stop()
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
        return "Cached videos: ${getCachedVideoCount()}, Active videos: ${getActiveVideoCount()}, Preloaded: ${preloadedVideos.size}"
    }
    
    /**
     * Preload a video in the background
     * @param context Android context
     * @param videoMid Video's unique identifier
     * @param videoUrl Video URL
     */
    fun preloadVideo(context: Context, videoMid: MimeiId, videoUrl: String) {
        if (videoPlayers.containsKey(videoMid) || preloadedVideos.contains(videoMid)) {
            return // Already cached or preloaded
        }
        
        if (!preloadQueue.contains(videoMid)) {
            preloadQueue.add(videoMid)
            Timber.d("VideoManager - Added video to preload queue: $videoMid")
            
            // Start preloading in background
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val player = createExoPlayer(context, videoUrl, MediaType.Video)
                    // Add to cache on main thread
                    withContext(Dispatchers.Main) {
                        videoPlayers[videoMid] = player
                        preloadedVideos.add(videoMid)
                        preloadQueue.remove(videoMid)
                        Timber.d("VideoManager - Successfully preloaded video: $videoMid")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        preloadQueue.remove(videoMid)
                        Timber.e("VideoManager - Failed to preload video: $videoMid, error: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * Check if a video is preloaded
     */
    fun isVideoPreloaded(videoMid: MimeiId): Boolean {
        return preloadedVideos.contains(videoMid) || videoPlayers.containsKey(videoMid)
    }
    
    /**
     * Get preload queue size
     */
    fun getPreloadQueueSize(): Int = preloadQueue.size
    
    /**
     * Set up sequential playback for a list of videos
     * @param videoMids List of video MIDs to play in sequence
     */
    fun setupSequentialPlayback(videoMids: List<MimeiId>) {
        videoPlaylist.clear()
        videoPlaylist.addAll(videoMids)
        currentPlaylistIndex = if (videoMids.isNotEmpty()) 0 else -1
        isSequentialPlaybackEnabled = videoMids.isNotEmpty()
        
        Timber.d("VideoManager - Sequential playback setup: ${videoMids.size} videos")
        Timber.d("VideoManager - Playlist: $videoMids")
        
        // Start playing the first video if available
        if (currentPlaylistIndex >= 0) {
            val firstVideo = videoPlaylist[currentPlaylistIndex]
            videoPlayers[firstVideo]?.let { player ->
                player.playWhenReady = true
                Timber.d("VideoManager - Started sequential playback with video: $firstVideo")
            }
        }
    }
    
    /**
     * Stop sequential playback
     */
    fun stopSequentialPlayback() {
        isSequentialPlaybackEnabled = false
        currentPlaylistIndex = -1
        videoPlaylist.clear()
        pauseAllVideos()
        Timber.d("VideoManager - Sequential playback stopped")
    }
    
    /**
     * Handle video completion for sequential playback
     * @param completedVideoMid The video that just finished
     */
    fun onVideoCompleted(completedVideoMid: MimeiId) {
        if (!isSequentialPlaybackEnabled || videoPlaylist.isEmpty()) {
            return
        }
        
        // Find the completed video in the playlist
        val completedIndex = videoPlaylist.indexOf(completedVideoMid)
        if (completedIndex == -1 || completedIndex != currentPlaylistIndex) {
            return
        }
        
        // Move to next video
        currentPlaylistIndex++
        if (currentPlaylistIndex < videoPlaylist.size) {
            val nextVideo = videoPlaylist[currentPlaylistIndex]
            videoPlayers[nextVideo]?.let { player ->
                player.playWhenReady = true
                Timber.d("VideoManager - Sequential playback: moving to next video: $nextVideo")
            }
        } else {
            // Playlist completed
            Timber.d("VideoManager - Sequential playback completed")
            stopSequentialPlayback()
        }
    }
    
    /**
     * Check if sequential playback is enabled
     */
    fun isSequentialPlaybackEnabled(): Boolean = isSequentialPlaybackEnabled
    
    /**
     * Get current playlist index
     */
    fun getCurrentPlaylistIndex(): Int = currentPlaylistIndex
    
    /**
     * Get current playlist
     */
    fun getCurrentPlaylist(): List<MimeiId> = videoPlaylist.toList()
    
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
    
    /**
     * Limit the number of cached videos to prevent memory issues
     * Note: This method must be called on the main thread
     */
    fun limitCachedVideos(maxCached: Int = 8) {
        // Ensure we're on the main thread
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            Timber.e("VideoManager - limitCachedVideos() called on wrong thread. Current: ${Thread.currentThread().name}, Expected: main")
            throw IllegalStateException("VideoManager.limitCachedVideos() must be called on the main thread")
        }
        
        if (videoPlayers.size <= maxCached) {
            return
        }
        
        // Keep active videos, remove oldest inactive ones
        val inactiveVideos = videoPlayers.keys.filter { !activeVideos.containsKey(it) }
        val videosToRemove = inactiveVideos.take(videoPlayers.size - maxCached)
        
        videosToRemove.forEach { videoMid ->
            releaseVideo(videoMid)
        }
        
        if (videosToRemove.isNotEmpty()) {
            Timber.d("VideoManager - Limited cached videos to $maxCached, removed ${videosToRemove.size} videos")
        }
    }
    
    /**
     * Mark a video as failed and release its player to prevent memory leaks
     * @param videoMid Video's unique identifier
     * Note: This method must be called on the main thread
     */
    fun markVideoAsFailed(videoMid: MimeiId) {
        // Ensure we're on the main thread
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            Timber.e("VideoManager - markVideoAsFailed() called on wrong thread. Current: ${Thread.currentThread().name}, Expected: main")
            throw IllegalStateException("VideoManager.markVideoAsFailed() must be called on the main thread")
        }
        
        Timber.w("VideoManager - Marking video as failed: $videoMid")
        releaseVideo(videoMid)
    }
    
    /**
     * Clean up failed or problematic video players
     * Note: This method must be called on the main thread
     */
    fun cleanupFailedVideos() {
        // Ensure we're on the main thread
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            Timber.e("VideoManager - cleanupFailedVideos() called on wrong thread. Current: ${Thread.currentThread().name}, Expected: main")
            throw IllegalStateException("VideoManager.cleanupFailedVideos() must be called on the main thread")
        }
        
        val videosToRemove = videoPlayers.keys.filter { videoMid ->
            val player = videoPlayers[videoMid]
            // Remove players that are in error state or have been idle for too long
            player?.playbackState == androidx.media3.common.Player.STATE_IDLE ||
            player?.hasNextMediaItem() == false && player.playbackState == androidx.media3.common.Player.STATE_ENDED
        }
        
        videosToRemove.forEach { videoMid ->
            Timber.d("VideoManager - Cleaning up failed video: $videoMid")
            releaseVideo(videoMid)
        }
        
        if (videosToRemove.isNotEmpty()) {
            Timber.d("VideoManager - Cleaned up ${videosToRemove.size} failed videos")
        }
    }
} 