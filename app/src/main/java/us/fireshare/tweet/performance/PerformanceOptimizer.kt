package us.fireshare.tweet.performance

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.widget.VideoManager
import us.fireshare.tweet.widget.ImageCacheManager
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.widget.SimplifiedVideoCacheManager

/**
 * PerformanceOptimizer: Centralized performance monitoring and optimization utility
 * 
 * This class helps prevent UI freezing by:
 * 1. Monitoring main thread performance
 * 2. Managing memory usage
 * 3. Optimizing background operations
 * 4. Providing performance metrics
 */
object PerformanceOptimizer {
    
    private const val TAG = "PerformanceOptimizer"
    private const val MEMORY_WARNING_THRESHOLD = 0.8f // 80% of max memory
    private const val PERFORMANCE_CHECK_INTERVAL = 30000L // 30 seconds
    
    private val performanceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Performance monitoring state
    private var isMonitoring = false
    private var lastMemoryCheck = 0L
    private var memoryWarningCount = 0
    
    /**
     * Start performance monitoring
     */
    fun startMonitoring(context: Context) {
        if (isMonitoring) return
        
        isMonitoring = true
        Timber.tag(TAG).d("Starting performance monitoring")
        
        // Start video memory leak monitoring
        VideoMemoryLeakFix.startMonitoring()
        
        performanceScope.launch {
            while (isMonitoring) {
                checkMemoryUsage(context)
                checkVideoManagerHealth()
                delay(PERFORMANCE_CHECK_INTERVAL)
            }
        }
    }
    
    /**
     * Stop performance monitoring
     */
    fun stopMonitoring() {
        isMonitoring = false
        performanceScope.cancel()
        LazyLoadingManager.stop()
        VideoMemoryLeakFix.stopMonitoring()
        Timber.tag(TAG).d("Stopped performance monitoring")
    }
    
    /**
     * Check memory usage and trigger cleanup if needed
     */
    private fun checkMemoryUsage(context: Context) {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryUsage = usedMemory.toFloat() / maxMemory.toFloat()
        
        if (memoryUsage > MEMORY_WARNING_THRESHOLD) {
            memoryWarningCount++
            Timber.tag(TAG).w("High memory usage detected: ${(memoryUsage * 100).toInt()}%")
            
            // Trigger aggressive cleanup
            triggerMemoryCleanup(context)
        }
        
        lastMemoryCheck = System.currentTimeMillis()
    }
    
    /**
     * Check VideoManager health and cleanup if needed
     */
    private fun checkVideoManagerHealth() {
        val cachedVideos = VideoManager.getCachedVideoCount()
        val activeVideos = VideoManager.getActiveVideoCount()
        
        if (cachedVideos > 10 || activeVideos > 5) {
            Timber.tag(TAG).w("VideoManager has too many cached/active videos: cached=$cachedVideos, active=$activeVideos")
            
            // Cleanup unused videos on main thread
            mainHandler.post {
                try {
                    VideoManager.cleanupUnusedVideos()
                    VideoManager.cleanupFailedVideos()
                    VideoManager.limitCachedVideos(maxCached = 8)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error cleaning up videos")
                }
            }
        }
    }
    
    /**
     * Trigger memory cleanup operations
     */
    @OptIn(UnstableApi::class)
    private fun triggerMemoryCleanup(context: Context) {
        performanceScope.launch {
            try {
                // Clear image cache
                ImageCacheManager.clearCache(context)
                
                // Clear video cache
                SimplifiedVideoCacheManager.clearVideoCache(context)
                
                // Clear loading queue to reduce memory pressure
                LazyLoadingManager.clearQueue()
                
                // Clear tweet cache if memory is critically high
                if (memoryWarningCount > 2) {
                    TweetCacheManager.cleanupExpiredTweets()
                }
                
                // Force garbage collection (use sparingly)
                if (memoryWarningCount > 5) {
                    System.gc()
                }
                
                Timber.tag(TAG).d("Memory cleanup completed")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error during memory cleanup")
            }
        }
    }
    
    /**
     * Get current performance metrics
     */
    fun getPerformanceMetrics(context: Context): PerformanceMetrics {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        
        return PerformanceMetrics(
            memoryUsage = usedMemory.toFloat() / maxMemory.toFloat(),
            cachedVideos = VideoManager.getCachedVideoCount(),
            activeVideos = VideoManager.getActiveVideoCount(),
            memoryWarningCount = memoryWarningCount
        )
    }
    
    /**
     * Force cleanup all caches (use for debugging or when app is backgrounded)
     */
    @MainThread
    fun forceCleanup(context: Context) {
        Timber.tag(TAG).d("Forcing cleanup of all caches")
        
        performanceScope.launch {
            try {
                // Clear all caches
                ImageCacheManager.clearCache(context)
                SimplifiedVideoCacheManager.clearVideoCache(context)
                TweetCacheManager.clearAllCachedTweets()
                
                // Clear loading queue
                LazyLoadingManager.clearQueue()
                
                // Release all video players
                VideoManager.releaseAllVideos()
                
                // Force cleanup failed videos
                VideoMemoryLeakFix.forceCleanup()
                
                // Reset counters
                memoryWarningCount = 0
                
                Timber.tag(TAG).d("Force cleanup completed")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error during force cleanup")
            }
        }
    }
    
    /**
     * Check if main thread is blocked
     */
    fun checkMainThreadHealth(): Boolean {
        return try {
            // Try to post a simple task to main thread
            mainHandler.post { }
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Main thread appears to be blocked")
            false
        }
    }
}

/**
 * Performance metrics data class
 */
data class PerformanceMetrics(
    val memoryUsage: Float,
    val cachedVideos: Int,
    val activeVideos: Int,
    val memoryWarningCount: Int
) {
    val isHealthy: Boolean
        get() = memoryUsage < 0.8f && cachedVideos < 10 && activeVideos < 5 && memoryWarningCount < 3
} 