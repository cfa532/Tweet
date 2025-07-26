package us.fireshare.tweet.performance

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.widget.ImageCacheManager
import us.fireshare.tweet.widget.SimplifiedVideoCacheManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * LazyLoadingManager: Optimizes image and media loading to prevent UI freezing
 * 
 * Features:
 * 1. Prioritized loading queue
 * 2. Memory-aware loading
 * 3. Batch processing
 * 4. Loading throttling
 */
object LazyLoadingManager {
    
    private const val TAG = "LazyLoadingManager"
    private const val MAX_CONCURRENT_LOADS = 3
    private const val BATCH_SIZE = 5
    private const val LOAD_DELAY_MS = 50L
    
    private val loadingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadingQueue = ConcurrentHashMap<String, LoadingTask>()
    private val activeLoads = AtomicInteger(0)
    private val processedItems = ConcurrentHashMap<String, Long>()
    
    // Loading priorities
    enum class Priority {
        HIGH,    // Visible items
        MEDIUM,  // Near visible items
        LOW      // Off-screen items
    }
    
    data class LoadingTask(
        val id: String,
        val priority: Priority,
        val loadAction: suspend () -> Unit,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Add a loading task to the queue
     */
    fun queueLoad(
        id: String,
        priority: Priority = Priority.MEDIUM,
        loadAction: suspend () -> Unit
    ) {
        // Skip if already processed recently
        val lastProcessed = processedItems[id]
        if (lastProcessed != null && System.currentTimeMillis() - lastProcessed < 5000) {
            return
        }
        
        loadingQueue[id] = LoadingTask(id, priority, loadAction)
        Timber.tag(TAG).d("Queued load task: $id with priority: $priority")
        
        // Start processing if not already running
        if (activeLoads.get() < MAX_CONCURRENT_LOADS) {
            processQueue()
        }
    }
    
    /**
     * Process the loading queue
     */
    private fun processQueue() {
        if (loadingQueue.isEmpty() || activeLoads.get() >= MAX_CONCURRENT_LOADS) {
            return
        }
        
        loadingScope.launch {
            try {
                activeLoads.incrementAndGet()
                
                while (loadingQueue.isNotEmpty() && activeLoads.get() <= MAX_CONCURRENT_LOADS) {
                    // Get highest priority tasks
                    val tasks = loadingQueue.values
                        .sortedBy { it.priority.ordinal }
                        .take(BATCH_SIZE)
                    
                    // Process tasks in batch
                    tasks.forEach { task ->
                        loadingQueue.remove(task.id)
                        try {
                            task.loadAction()
                            processedItems[task.id] = System.currentTimeMillis()
                            Timber.tag(TAG).d("Completed load task: ${task.id}")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Error in load task: ${task.id}")
                        }
                        
                        // Small delay between tasks to prevent overwhelming
                        delay(LOAD_DELAY_MS)
                    }
                    
                    // Check memory usage before continuing
                    if (isMemoryPressureHigh()) {
                        Timber.tag(TAG).w("High memory pressure detected, pausing loading")
                        delay(1000) // Wait longer under memory pressure
                    }
                }
            } finally {
                activeLoads.decrementAndGet()
            }
        }
    }
    
    /**
     * Check if memory pressure is high
     */
    private fun isMemoryPressureHigh(): Boolean {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryUsage = usedMemory.toFloat() / maxMemory.toFloat()
        
        return memoryUsage > 0.75f
    }
    
    /**
     * Preload images with priority
     */
    fun preloadImages(context: Context, imageIds: List<String>, priority: Priority = Priority.LOW) {
        imageIds.forEach { imageId ->
            queueLoad(imageId, priority) {
                try {
                    ImageCacheManager.getCachedImage(context, imageId)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error preloading image: $imageId")
                }
            }
        }
    }
    
    /**
     * Preload videos with priority
     */
    fun preloadVideos(context: Context, videoIds: List<String>, priority: Priority = Priority.LOW) {
        videoIds.forEach { videoId ->
            queueLoad(videoId, priority) {
                try {
                    // Convert video ID to URL and then to URI
                    val videoUrl = us.fireshare.tweet.HproseInstance.getMediaUrl(videoId, us.fireshare.tweet.HproseInstance.appUser.baseUrl)
                    if (videoUrl != null) {
                        val uri = Uri.parse(videoUrl)
                        SimplifiedVideoCacheManager.getVideoAspectRatio(context, uri)
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error preloading video: $videoId")
                }
            }
        }
    }
    
    /**
     * Clear the loading queue
     */
    fun clearQueue() {
        loadingQueue.clear()
        Timber.tag(TAG).d("Cleared loading queue")
    }
    
    /**
     * Get queue statistics
     */
    fun getQueueStats(): QueueStats {
        val priorities = loadingQueue.values.groupBy { it.priority }
        return QueueStats(
            totalQueued = loadingQueue.size,
            activeLoads = activeLoads.get(),
            highPriority = priorities[Priority.HIGH]?.size ?: 0,
            mediumPriority = priorities[Priority.MEDIUM]?.size ?: 0,
            lowPriority = priorities[Priority.LOW]?.size ?: 0
        )
    }
    
    /**
     * Stop the loading manager
     */
    fun stop() {
        clearQueue()
        loadingScope.cancel()
        Timber.tag(TAG).d("Stopped LazyLoadingManager")
    }
}

/**
 * Queue statistics
 */
data class QueueStats(
    val totalQueued: Int,
    val activeLoads: Int,
    val highPriority: Int,
    val mediumPriority: Int,
    val lowPriority: Int
) 