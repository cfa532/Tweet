package us.fireshare.tweet.widget

import android.content.Context
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
        return videoPlayers.getOrPut(videoMid) {
            Timber.d("VideoManager - Creating new ExoPlayer for video: $videoMid")
            createExoPlayer(context, videoUrl, MediaType.Video)
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
     */
    fun releaseVideo(videoMid: MimeiId) {
        videoPlayers.remove(videoMid)?.let { player ->
            player.release()
            activeVideos.remove(videoMid)
            Timber.d("VideoManager - Released video: $videoMid")
        }
    }
    
    /**
     * Release all video players
     */
    fun releaseAllVideos() {
        videoPlayers.values.forEach { player ->
            player.release()
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
     */
    fun cleanupUnusedVideos() {
        val unusedVideos = videoPlayers.keys.filter { !activeVideos.containsKey(it) }
        unusedVideos.forEach { videoMid ->
            releaseVideo(videoMid)
        }
        if (unusedVideos.isNotEmpty()) {
            Timber.d("VideoManager - Cleaned up ${unusedVideos.size} unused videos")
        }
    }
} 