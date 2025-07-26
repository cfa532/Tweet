package us.fireshare.tweet.performance

import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.widget.VideoManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * VideoMemoryLeakFix: Specialized fix for video memory leaks caused by HLS fallback mechanism
 * 
 * This class addresses the specific issue where:
 * 1. Progressive videos are tried as HLS videos first
 * 2. HLS attempts fail with 500 errors
 * 3. ExoPlayer instances are not properly released
 * 4. Memory leaks accumulate over time
 */
object VideoMemoryLeakFix {
    
    private const val TAG = "VideoMemoryLeakFix"
    private const val VIDEO_TIMEOUT_MS = 10000L // 10 seconds timeout
    private const val CLEANUP_INTERVAL_MS = 30000L // 30 seconds
    
    private val fixScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Track problematic videos
    private val failedVideos = ConcurrentHashMap<MimeiId, Long>()
    private val videoTimeouts = ConcurrentHashMap<MimeiId, Long>()
    private val retryCounts = ConcurrentHashMap<MimeiId, AtomicInteger>()
    
    // Monitoring state
    private var isMonitoring = false
    
    /**
     * Start monitoring for video memory leaks
     */
    fun startMonitoring() {
        if (isMonitoring) return
        
        isMonitoring = true
        Timber.tag(TAG).d("Starting video memory leak monitoring")
        
        fixScope.launch {
            while (isMonitoring) {
                cleanupFailedVideos()
                checkVideoTimeouts()
                delay(CLEANUP_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Stop monitoring
     */
    fun stopMonitoring() {
        isMonitoring = false
        fixScope.cancel()
        Timber.tag(TAG).d("Stopped video memory leak monitoring")
    }
    
    /**
     * Mark a video as failed (called when all fallback attempts are exhausted)
     */
    fun markVideoAsFailed(videoMid: MimeiId) {
        // Ensure we're on the main thread for VideoManager operations
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            markVideoAsFailedInternal(videoMid)
        } else {
            mainHandler.post {
                markVideoAsFailedInternal(videoMid)
            }
        }
    }
    
    /**
     * Internal method to mark video as failed (must be called on main thread)
     */
    @MainThread
    private fun markVideoAsFailedInternal(videoMid: MimeiId) {
        failedVideos[videoMid] = System.currentTimeMillis()
        retryCounts[videoMid] = AtomicInteger(0)
        
        Timber.tag(TAG).w("Marked video as failed after all attempts: $videoMid")
        
        // Release the failed video
        VideoManager.markVideoAsFailed(videoMid)
    }
    
    /**
     * Start timeout tracking for a video
     */
    fun startVideoTimeout(videoMid: MimeiId) {
        videoTimeouts[videoMid] = System.currentTimeMillis() + VIDEO_TIMEOUT_MS
        Timber.tag(TAG).d("Started timeout tracking for video: $videoMid")
    }
    
    /**
     * Clear timeout for a video (when it loads successfully)
     */
    fun clearVideoTimeout(videoMid: MimeiId) {
        videoTimeouts.remove(videoMid)
        retryCounts.remove(videoMid)
        Timber.tag(TAG).d("Cleared timeout for video: $videoMid")
    }
    

    
    /**
     * Clean up failed videos that are older than 5 minutes
     */
    private fun cleanupFailedVideos() {
        val cutoffTime = System.currentTimeMillis() - (5 * 60 * 1000) // 5 minutes
        val oldFailedVideos = failedVideos.filter { (_, timestamp) ->
            timestamp < cutoffTime
        }
        
        oldFailedVideos.keys.forEach { videoMid ->
            failedVideos.remove(videoMid)
            retryCounts.remove(videoMid)
            Timber.tag(TAG).d("Cleaned up old failed video: $videoMid")
        }
        
        if (oldFailedVideos.isNotEmpty()) {
            Timber.tag(TAG).d("Cleaned up ${oldFailedVideos.size} old failed videos")
        }
    }
    
    /**
     * Check for videos that have timed out
     */
    private fun checkVideoTimeouts() {
        val currentTime = System.currentTimeMillis()
        val timedOutVideos = videoTimeouts.filter { (_, timeout) ->
            currentTime > timeout
        }
        
        if (timedOutVideos.isNotEmpty()) {
            Timber.tag(TAG).w("Found ${timedOutVideos.size} timed out videos")
            
            mainHandler.post {
                timedOutVideos.keys.forEach { videoMid ->
                    Timber.tag(TAG).w("Video timed out, marking as failed: $videoMid")
                    VideoManager.markVideoAsFailed(videoMid)
                    videoTimeouts.remove(videoMid)
                    retryCounts.remove(videoMid)
                }
            }
        }
    }
    
    /**
     * Get statistics about failed videos
     */
    fun getFailedVideoStats(): FailedVideoStats {
        return FailedVideoStats(
            totalFailed = failedVideos.size,
            totalTimeouts = videoTimeouts.size,
            totalRetries = retryCounts.values.sumOf { it.get() }
        )
    }
    
    /**
     * Force cleanup all failed videos
     */
    fun forceCleanup() {
        // Ensure we're on the main thread for VideoManager operations
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            forceCleanupInternal()
        } else {
            mainHandler.post {
                forceCleanupInternal()
            }
        }
    }
    
    /**
     * Internal method to force cleanup (must be called on main thread)
     */
    @MainThread
    private fun forceCleanupInternal() {
        Timber.tag(TAG).d("Forcing cleanup of all failed videos")
        
        failedVideos.clear()
        videoTimeouts.clear()
        retryCounts.clear()
        
        // Also cleanup failed videos in VideoManager
        VideoManager.cleanupFailedVideos()
    }
}

/**
 * Statistics about failed videos
 */
data class FailedVideoStats(
    val totalFailed: Int,
    val totalTimeouts: Int,
    val totalRetries: Int
) 