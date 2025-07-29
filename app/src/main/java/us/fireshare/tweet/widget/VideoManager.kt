package us.fireshare.tweet.widget

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.isActive
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
    
    // Memory management
    private const val MEMORY_THRESHOLD_BYTES = 1024L * 1024 * 1024 // 1GB

    /**
     * Get or create an ExoPlayer instance for a video
     * @param context Android context
     * @param videoMid Video's unique identifier
     * @param videoUrl Video URL (for creating new instances)
     * @return ExoPlayer instance
     */
    fun getVideoPlayer(context: Context, videoMid: MimeiId, videoUrl: String): ExoPlayer {
        // Check memory usage before creating new player
        checkMemoryAndReleaseVideos()
        
        // Mark as preloaded if it was in the preload queue
        preloadedVideos.add(videoMid)
        preloadQueue.remove(videoMid)
        
        val isReusing = videoPlayers.containsKey(videoMid)
        
        return videoPlayers.getOrPut(videoMid) {
            Timber.tag("getVideoPlayer").d("Creating new player for $videoMid")
            
            try {
                val player = createExoPlayer(context, videoUrl, MediaType.Video)
                player
            } catch (e: Exception) {
                Timber.tag("getVideoPlayer").e("VideoManager - Error creating ExoPlayer for video: $videoMid")
                // If creation fails, remove from map and rethrow
                videoPlayers.remove(videoMid)
                throw e
            }
        }.also { player ->
            // Reset player state when reusing an existing player
            if (isReusing) {
                Timber.tag("getVideoPlayer").d("Resetting reused player")
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
            // Don't stop if player is already ready - just pause
            if (player.playbackState == androidx.media3.common.Player.STATE_READY) {
                player.playWhenReady = false
                return
            }
            
            // Stop playback and reset to beginning
            player.stop()
            player.seekTo(0)
            player.playWhenReady = false
            
            // Clear any error state
            if (player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                player.prepare()
            }
        } catch (e: Exception) {
            Timber.e("VideoManager - Error resetting player state: $e")
        }
    }
    
    /**
     * Mark a video as active (being used by a composable)
     * @param videoMid Video's unique identifier
     */
    fun markVideoActive(videoMid: MimeiId) {
        val currentCount = activeVideos.getOrDefault(videoMid, 0)
        activeVideos[videoMid] = currentCount + 1
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
            } else {
                activeVideos[videoMid] = newCount
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
        }
    }
    
    /**
     * Pause all videos
     */
    fun pauseAllVideos() {
        videoPlayers.values.forEach { player ->
            player.playWhenReady = false
        }
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
     * Get the active count for a specific video
     */
    fun getVideoActiveCount(videoMid: MimeiId): Int = activeVideos.getOrDefault(videoMid, 0)

    /**
     * Get cache statistics
     */
    fun getCacheStats(): String {
        val memoryUsage = getCurrentMemoryUsage()
        return "Cached videos: ${getCachedVideoCount()}, Active videos: ${getActiveVideoCount()}, Preloaded: ${preloadedVideos.size}, Memory: ${memoryUsage / (1024 * 1024)}MB"
    }
    
    /**
     * Get current memory usage in bytes
     */
    private fun getCurrentMemoryUsage(): Long {
        return try {
            val runtime = Runtime.getRuntime()
            runtime.totalMemory() - runtime.freeMemory()
        } catch (e: Exception) {
            Timber.e("VideoManager - Error getting memory usage: ${e.message}")
            0L
        }
    }
    
    /**
     * Check if memory usage is above threshold
     */
    private fun isMemoryUsageHigh(): Boolean {
        return getCurrentMemoryUsage() > MEMORY_THRESHOLD_BYTES
    }
    
    /**
     * Monitor memory usage and release videos if needed
     */
    private fun checkMemoryAndReleaseVideos() {
        if (!isMemoryUsageHigh()) {
            return
        }
        
        Timber.w("VideoManager - Memory usage high (${getCurrentMemoryUsage() / (1024 * 1024)}MB), releasing inactive videos")
        
        // Get inactive videos (not currently being used)
        val inactiveVideos = videoPlayers.keys.filter { !activeVideos.containsKey(it) }
        
        if (inactiveVideos.isNotEmpty()) {
            // Release oldest inactive videos first
            val videosToRelease = inactiveVideos.take(inactiveVideos.size / 2) // Release half of inactive videos
            
            videosToRelease.forEach { videoMid ->
                releaseVideo(videoMid)
            }
            
            Timber.tag("checkMemoryAndReleaseVideos").d("Released ${videosToRelease.size} videos due to memory pressure")
        } else {
            Timber.w("VideoManager - No inactive videos to release, memory pressure remains high")
        }
    }
    
    /**
     * Preload a video in the background
     * @param context Android context
     * @param videoMid Video's unique identifier
     * @param videoUrl Video URL
     */
    @kotlin.OptIn(DelicateCoroutinesApi::class)
    fun preloadVideo(context: Context, videoMid: MimeiId, videoUrl: String) {
        // Check memory usage before preloading
        checkMemoryAndReleaseVideos()
        
        if (videoPlayers.containsKey(videoMid) || preloadedVideos.contains(videoMid)) {
            return // Already cached or preloaded
        }
        
        if (!preloadQueue.contains(videoMid)) {
            preloadQueue.add(videoMid)
            
            // Start preloading in background
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val player = createExoPlayer(context, videoUrl, MediaType.Video)
                    // Add to cache on main thread
                    withContext(Dispatchers.Main) {
                        // Check memory again before adding to cache
                        checkMemoryAndReleaseVideos()
                        videoPlayers[videoMid] = player
                        preloadedVideos.add(videoMid)
                        preloadQueue.remove(videoMid)
                        Timber.tag("preloadVideo").d("Successfully preloaded $videoMid")
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

    // Memory monitoring job that can be cancelled
    private var memoryMonitoringJob: kotlinx.coroutines.Job? = null

    /**
     * Start periodic memory monitoring
     * This should be called from the application class
     */
    @kotlin.OptIn(DelicateCoroutinesApi::class)
    fun startMemoryMonitoring() {
        // Cancel existing job if any
        memoryMonitoringJob?.cancel()
        
        memoryMonitoringJob = GlobalScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    withContext(Dispatchers.Main) {
                        checkMemoryAndReleaseVideos()
                    }
                    kotlinx.coroutines.delay(30000) // Check every 30 seconds
                } catch (e: Exception) {
                    Timber.e("VideoManager - Error in memory monitoring: ${e.message}")
                    kotlinx.coroutines.delay(60000) // Wait longer on error
                }
            }
        }
        Timber.tag("startMemoryMonitoring").d("Started periodic memory monitoring")
    }

    /**
     * Stop memory monitoring
     * This should be called when the application is being destroyed
     */
    fun stopMemoryMonitoring() {
        memoryMonitoringJob?.cancel()
        memoryMonitoringJob = null
        Timber.tag("stopMemoryMonitoring").d("Stopped periodic memory monitoring")
    }
    
    /**
     * Get detailed memory statistics
     */
    fun getMemoryStats(): String {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        val maxMemory = runtime.maxMemory()
        
        return "Memory: ${usedMemory / (1024 * 1024)}MB used, ${freeMemory / (1024 * 1024)}MB free, ${totalMemory / (1024 * 1024)}MB total, ${maxMemory / (1024 * 1024)}MB max"
    }
    
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
            }
        } else {
            // Playlist completed
            stopSequentialPlayback()
        }
    }
} 