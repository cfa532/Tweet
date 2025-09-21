package us.fireshare.tweet.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.Continuation
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ImageCacheManager compresses and caches images by their mid (unique id).
 * - Uses LRU memory cache for fast access
 * - Stores compressed images in app's cache directory for persistence
 * - Provides suspend functions for cache operations
 * - Supports downloading images from URLs
 * - Improved memory management with proper bitmap recycling
 * - Connection pooling and concurrent download limits
 * - Loading prioritization for visible images
 */
object ImageCacheManager {
    private const val CACHE_DIR = "image_cache"
    private const val MAX_MEMORY_CACHE_SIZE =
        150 * 1024 * 1024 // Reduced to 150MB for better memory management
    private const val COMPRESS_QUALITY = 80 // JPEG quality
    private const val MAX_IMAGE_DIMENSION = 1024 // Maximum image dimension
    private const val CONNECTION_TIMEOUT = 5000 // 5 seconds - faster failure detection
    private const val READ_TIMEOUT = 15000 // 15 seconds - allow for large files but not too long
    private const val MAX_CONCURRENT_DOWNLOADS = 8 // Limit concurrent downloads
    private const val MAX_RETRY_ATTEMPTS = 2
    private const val PROGRESSIVE_LOAD_CHUNK_SIZE = 64 * 1024 // 64KB chunks for progressive loading

    // Coroutine scope for image loading that can be cancelled when screen is disposed
    private val imageLoadingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Track active downloads by screen/context to allow selective cancellation
    private val activeDownloadsByContext = ConcurrentHashMap<String, MutableSet<String>>()
    
    // Priority queue for downloads (visible images get higher priority)
    private val downloadPriorityQueue = ConcurrentHashMap<String, Boolean>() // mid -> isVisible
    
    // True priority system: visible images get priority, but invisible images can use all slots when visible ones aren't running
    private val priorityMutex = java.util.concurrent.locks.ReentrantLock()
    private val priorityCondition = priorityMutex.newCondition()
    private var activeVisibleDownloads = 0
    private var activeInvisibleDownloads = 0
    
    /**
     * Acquire download slot with smart priority - visible images get priority, but invisible images can use all slots when no visible ones are running
     */
    private suspend fun acquireDownloadSlotWithSmartPriority(mid: String, isVisible: Boolean) {
        priorityMutex.lock()
        try {
            if (isVisible) {
                // Visible images: can always start if there are slots available
                while (activeVisibleDownloads + activeInvisibleDownloads >= MAX_CONCURRENT_DOWNLOADS) {
                    priorityCondition.await()
                }
                activeVisibleDownloads++
                downloadSemaphore.acquire()
            } else {
                // Invisible images: can start if there are slots available AND no visible images are waiting
                while (activeVisibleDownloads + activeInvisibleDownloads >= MAX_CONCURRENT_DOWNLOADS || 
                       (activeVisibleDownloads > 0 && activeVisibleDownloads + activeInvisibleDownloads >= MAX_CONCURRENT_DOWNLOADS - 2)) {
                    priorityCondition.await()
                }
                activeInvisibleDownloads++
                downloadSemaphore.acquire()
            }
        } finally {
            priorityMutex.unlock()
        }
    }
    
    /**
     * Release download slot and signal waiting requests
     */
    private fun releaseDownloadSlotWithSmartPriority(isVisible: Boolean) {
        priorityMutex.lock()
        try {
            if (isVisible) {
                activeVisibleDownloads--
            } else {
                activeInvisibleDownloads--
            }
            downloadSemaphore.release()
            priorityCondition.signalAll()
        } finally {
            priorityMutex.unlock()
        }
    }

    // Connection pool for better performance
    private val connectionPool = ConcurrentHashMap<String, HttpURLConnection>()
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    private val activeDownloads = AtomicInteger(0)
    private val downloadQueue = ConcurrentHashMap<String, Boolean>()
    private val downloadResults = ConcurrentHashMap<String, Bitmap?>()
    private val resultTimestamps = ConcurrentHashMap<String, Long>()

    // Simple memory cache (mid -> Bitmap) - using ConcurrentHashMap for thread safety
    private val memoryCache = ConcurrentHashMap<String, Bitmap>()
    private var currentMemoryUsage = AtomicInteger(0)
    
    /**
     * Add bitmap to memory cache with size management
     */
    private fun addToMemoryCache(mid: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            // Check if we need to free memory
            val bitmapSize = bitmap.byteCount
            if (currentMemoryUsage.get() + bitmapSize > MAX_MEMORY_CACHE_SIZE) {
                // Remove some entries to free memory (simple FIFO approach)
                // Don't recycle bitmaps here to avoid crashes - let Android handle memory management
                val iterator = memoryCache.entries.iterator()
                while (iterator.hasNext() && currentMemoryUsage.get() + bitmapSize > MAX_MEMORY_CACHE_SIZE) {
                    val entry = iterator.next()
                    val oldBitmap = entry.value
                    // Just remove from cache, don't recycle to avoid crashes
                    iterator.remove()
                    currentMemoryUsage.addAndGet(-oldBitmap.byteCount)
                }
            }
            
            memoryCache[mid] = bitmap
            currentMemoryUsage.addAndGet(bitmapSize)
        }
    }

    /**
     * Get cached image file by mid, or null if not cached
     */
    suspend fun getCachedImageFile(context: Context, mid: String): File? =
        withContext(Dispatchers.IO) {
            try {
                val file = File(context.cacheDir, "$CACHE_DIR/$mid.jpg")
                return@withContext if (file.exists()) file else null
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").d("Error in getCachedImageFile: $e")
                null
            }
        }

    /**
     * Get cached image by mid, or null if not cached
     */
    suspend fun getCachedImage(context: Context, mid: String): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                // Check memory cache first
                memoryCache.get(mid)?.let { bitmap ->
                    if (!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                        return@withContext bitmap
                    } else {
                        // Remove invalid bitmap from cache
                        memoryCache.remove(mid)
                        if (bitmap.isRecycled) {
                            Timber.tag("ImageCacheManager").w("Found recycled bitmap in cache for: $mid")
                        }
                    }
                }

                // Check disk cache
                val file = File(context.cacheDir, "$CACHE_DIR/$mid.jpg")
                if (file.exists()) {
                    try {
                        val bitmap = decodeBitmapFromFileWithCorrectOrientation(file.absolutePath)
                        if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                            addToMemoryCache(mid, bitmap)
                            return@withContext bitmap
                        } else if (bitmap != null && bitmap.isRecycled) {
                            Timber.tag("ImageCacheManager").w("Loaded recycled bitmap from disk for: $mid")
                        }
                    } catch (e: OutOfMemoryError) {
                        Timber.tag("ImageCacheManager")
                            .d("OutOfMemoryError loading cached image: $e")
                        clearMemoryCache()
                        return@withContext null
                    } catch (e: Exception) {
                        Timber.tag("ImageCacheManager").d("Error loading cached image: $e")
                    }
                }
                null
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").d("Error in getCachedImage: $e")
                null
            }
        }

    /**
     * Non-suspend version of getCachedImage for use within global scope to avoid cancellation issues
     */
    private fun getCachedImageSync(context: Context, mid: String): Bitmap? {
        try {
            // Check memory cache first
            memoryCache.get(mid)?.let { bitmap ->
                if (!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                    return bitmap
                } else {
                    // Remove invalid bitmap from cache
                    memoryCache.remove(mid)
                    if (bitmap.isRecycled) {
                        Timber.tag("ImageCacheManager").w("Found recycled bitmap in cache for: $mid")
                    }
                }
            }

            // Check disk cache
            val file = File(context.cacheDir, "$CACHE_DIR/$mid.jpg")
            if (file.exists()) {
                try {
                    val bitmap = decodeBitmapFromFileWithCorrectOrientation(file.absolutePath)
                    if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                        addToMemoryCache(mid, bitmap)
                        return bitmap
                    } else if (bitmap != null && bitmap.isRecycled) {
                        Timber.tag("ImageCacheManager").w("Loaded recycled bitmap from disk for: $mid")
                    }
                } catch (e: OutOfMemoryError) {
                    Timber.tag("ImageCacheManager")
                        .d("OutOfMemoryError loading cached image: $e")
                    clearMemoryCache()
                    return null
                } catch (e: Exception) {
                    Timber.tag("ImageCacheManager").d("Error loading cached image: $e")
                }
            }
            return null
        } catch (e: Exception) {
            Timber.tag("ImageCacheManager").d("Error in getCachedImageSync: $e")
            return null
        }
    }

    /**
     * Download and cache image from URL with improved error handling and retry logic
     */
    suspend fun downloadAndCacheImage(context: Context, imageUrl: String, mid: String): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                // Check if already cached first
                getCachedImage(context, mid)?.let { return@withContext it }

                // Check if already downloading this image
                if (downloadQueue.containsKey(mid)) {
                    return@withContext null
                }

                // Acquire semaphore to limit concurrent downloads
                downloadSemaphore.acquire()
                downloadQueue[mid] = true
                activeDownloads.incrementAndGet()

                try {
                    var bitmap: Bitmap? = null
                    var attempt = 0

                    while (bitmap == null && attempt < MAX_RETRY_ATTEMPTS) {
                        attempt++
                        try {
                            bitmap = performDownload(imageUrl, mid)
                            if (bitmap != null && !bitmap.isRecycled) {
                                // Cache the downloaded image
                                cacheImage(context, mid, bitmap)
                                return@withContext bitmap
                            }
                        } catch (e: Exception) {
                            Timber.tag("ImageCacheManager")
                                .d("Download attempt $attempt failed for $mid: $e")
                            if (attempt < MAX_RETRY_ATTEMPTS) {
                                delay(1000L * attempt) // Exponential backoff
                            }
                        }
                    }

                    return@withContext null

                } finally {
                    downloadQueue.remove(mid)
                    activeDownloads.decrementAndGet()
                    downloadSemaphore.release()
                }
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").d("Error in downloadAndCacheImage: $e")
                null
            }
        }

    /**
     * Perform the actual download with connection pooling
     */
    private suspend fun performDownload(imageUrl: String, mid: String): Bitmap? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null

            try {
                val url = URL(imageUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECTION_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "TweetApp/1.0")
                // Remove gzip compression request to avoid decompression issues
                // connection.setRequestProperty("Accept-Encoding", "gzip, deflate")
                connection.setRequestProperty("Connection", "keep-alive")
                connection.setRequestProperty("Cache-Control", "no-cache")

                // Add connection to pool
                connectionPool[mid] = connection

                inputStream = connection.inputStream
                val bitmap = decodeBitmapFromStreamWithCorrectOrientation(inputStream)

                if (bitmap != null && !bitmap.isRecycled) {
                    return@withContext bitmap
                } else {
                    Timber.tag("ImageCacheManager")
                        .d("Failed to decode image from URL: $imageUrl")
                    return@withContext null
                }
            } catch (e: OutOfMemoryError) {
                Timber.tag("ImageCacheManager").d("OutOfMemoryError downloading image: $e")
                clearMemoryCache()
                return@withContext null
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").d("Error downloading image: $e")
                return@withContext null
            } finally {
                inputStream?.close()
                connection?.disconnect()
                connectionPool.remove(mid)
            }
        }

    /**
     * Perform download for original quality images (no compression)
     */
    private suspend fun performDownloadOriginal(
        imageUrl: String,
        mid: String,
        context: Context
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null

            try {
                val url = URL(imageUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECTION_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "TweetApp/1.0")
                // Remove gzip compression request to avoid decompression issues
                // connection.setRequestProperty("Accept-Encoding", "gzip, deflate")
                connection.setRequestProperty("Connection", "keep-alive")
                connection.setRequestProperty("Cache-Control", "no-cache")

                // Add connection to pool
                connectionPool[mid] = connection

                inputStream = connection.inputStream

                // Read the image data to check size
                val imageData = inputStream.readBytes()
                val imageSize = imageData.size

                // Save original data directly (for original images, always save original data)
                val dir = File(context.cacheDir, CACHE_DIR)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "$mid.jpg")

                FileOutputStream(file).use { out ->
                    out.write(imageData)
                }

                
                // Log first few bytes to check image format

                // Decode bitmap from the data with original quality
                val bitmap = decodeBitmapFromByteArrayWithCorrectOrientation(imageData)

                if (bitmap != null && !bitmap.isRecycled) {
                    return@withContext bitmap
                } else {
                    Timber.tag("ImageCacheManager")
                        .d("Failed to decode original image from URL: $imageUrl")
                    return@withContext null
                }
            } catch (e: OutOfMemoryError) {
                Timber.tag("ImageCacheManager").d("OutOfMemoryError downloading original image: $e")
                clearMemoryCache()
                return@withContext null
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").d("Error downloading original image: $e")
                return@withContext null
            } finally {
                inputStream?.close()
                connection?.disconnect()
                connectionPool.remove(mid)
            }
        }

    /**
     * Load image from URL or cache with prioritization
     */
    suspend fun loadImage(context: Context, imageUrl: String, mid: String): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                // First try to get from cache
                getCachedImage(context, mid)?.let { return@withContext it }

                // If not in cache, download and cache
                downloadAndCacheImage(context, imageUrl, mid)
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").d("Error in loadImage: $e")
                null
            }
        }

    /**
     * Load original image without compression for high-quality display
     * Uses GlobalScope to avoid cancellation when UI changes
     */
    suspend fun loadOriginalImage(context: Context, imageUrl: String, mid: String, isVisible: Boolean = true): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                // Use separate cache key for original images
                val originalMid = "${mid}_original"

                // Check if original image is already cached first (use sync version to avoid cancellation)
                getCachedImageSync(context, originalMid)?.let { return@withContext it }

                // Add to priority queue
                downloadPriorityQueue[originalMid] = isVisible
                
                // Use smart priority-based slot acquisition - visible images get priority, but invisible images can use all slots when no visible ones are running
                val currentActive = activeDownloads.get()
                val currentQueued = downloadQueue.size
                val priority = if (isVisible) "HIGH" else "LOW"
                
                Timber.tag("ImageCacheManager").d("Requesting download slot for $mid (priority: $priority, active: $currentActive/${MAX_CONCURRENT_DOWNLOADS}, queued: $currentQueued)")
                
                acquireDownloadSlotWithSmartPriority(mid, isVisible)
                
                Timber.tag("ImageCacheManager").d("Acquired download slot for $mid (priority: $priority, active: ${activeDownloads.get()}/${MAX_CONCURRENT_DOWNLOADS}, queued: ${downloadQueue.size})")
                
                // Check if already downloading this image (inside semaphore to prevent race condition)
                if (downloadQueue.containsKey(originalMid)) {
                    Timber.tag("ImageCacheManager").d("Duplicate request blocked for $mid - already downloading")
                    releaseDownloadSlotWithSmartPriority(isVisible)
                    
                    // Wait for the download to complete and return the result
                    var attempts = 0
                    while (attempts < 30 && downloadQueue.containsKey(originalMid)) { // Wait up to 3 seconds
                        delay(100L)
                        attempts++
                    }
                    
                    // Clean up old results before checking
                    cleanupOldResults()
                    
                    // Return the result if available
                    return@withContext downloadResults[originalMid]
                }
                
                downloadQueue[originalMid] = true
                activeDownloads.incrementAndGet()
                
                Timber.tag("ImageCacheManager").d("Starting download for $mid (active: ${activeDownloads.get()}/${MAX_CONCURRENT_DOWNLOADS}, queued: ${downloadQueue.size})")
                
                // Add delay to spread out requests and avoid overwhelming server
                delay(200L)

                try {
                    var bitmap: Bitmap? = null
                    var attempt = 0

                    while (bitmap == null && attempt < MAX_RETRY_ATTEMPTS) {
                        attempt++
                        try {
                            bitmap = performDownloadOriginal(imageUrl, originalMid, context)
                            if (bitmap != null && !bitmap.isRecycled) {
                                // Store original bitmap in memory cache with original key
                                addToMemoryCache(originalMid, bitmap)
                                // Store result for waiting requests
                                downloadResults[originalMid] = bitmap
                                resultTimestamps[originalMid] = System.currentTimeMillis()
                                return@withContext bitmap
                            }
                        } catch (e: Exception) {
                            Timber.tag("ImageCacheManager")
                                .d("Original download attempt $attempt failed for $mid: $e")
                            if (attempt < MAX_RETRY_ATTEMPTS) {
                                // Extended backoff delays to avoid overwhelming server
                                delay(3000L * attempt) // 3s, 6s, 9s...
                            }
                        }
                    }

                    // Store failure result for waiting requests
                    downloadResults[originalMid] = null
                    resultTimestamps[originalMid] = System.currentTimeMillis()
                    return@withContext null

                } finally {
                    // Always release the download slot and clean up
                    val wasVisible = downloadPriorityQueue[originalMid] ?: false
                    releaseDownloadSlotWithSmartPriority(wasVisible)
                    
                    downloadQueue.remove(originalMid)
                    activeDownloads.decrementAndGet()
                    downloadResults.remove(originalMid)
                    downloadPriorityQueue.remove(originalMid)
                    
                    val priority = if (wasVisible) "HIGH" else "LOW"
                    Timber.tag("ImageCacheManager").d("Completed download for $mid (priority: $priority, active: ${activeDownloads.get()}/${MAX_CONCURRENT_DOWNLOADS}, queued: ${downloadQueue.size})")
                    
                    // Clean up download results after a delay to allow other requests to get the result
                    GlobalScope.launch {
                        delay(5000L)
                        downloadResults.remove(originalMid)
                    }
                }
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").d("Error in loadOriginalImage: $e")
                null
            }
        }

    /**
     * Cache and compress a bitmap by mid
     */
    suspend fun cacheImage(context: Context, mid: String, bitmap: Bitmap) =
        withContext(Dispatchers.IO) {
            try {
                if (bitmap.isRecycled) {
                    Timber.tag("ImageCacheManager")
                        .w("Attempting to cache recycled bitmap for: $mid")
                    return@withContext
                }

                // Compress bitmap
                val compressed = compressBitmap(bitmap)
                if (compressed != null && !compressed.isRecycled) {
                    addToMemoryCache(mid, compressed)

                    // Save to disk
                    val dir = File(context.cacheDir, CACHE_DIR)
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, "$mid.jpg")

                    try {
                        var quality = COMPRESS_QUALITY
                        var byteArray: ByteArray
                        do {
                            val stream = java.io.ByteArrayOutputStream()
                            compressed.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                            byteArray = stream.toByteArray()
                            stream.close()
                            quality -= 5
                        } while (byteArray.size > 200 * 1024 && quality >= 50)

                        FileOutputStream(file).use { out ->
                            out.write(byteArray)
                        }
                    } catch (e: IOException) {
                        Timber.tag("ImageCacheManager").d("Error saving image to disk: $e")
                    }
                }
            } catch (e: OutOfMemoryError) {
                Timber.tag("ImageCacheManager").d("OutOfMemoryError caching image: $e")
                clearMemoryCache()
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").d("Error caching image: $e")
            }
        }

    /**
     * Compress a bitmap to JPEG with quality
     */
    private fun compressBitmap(bitmap: Bitmap): Bitmap? {
        try {
            if (bitmap.isRecycled) return null

            val width = bitmap.width
            val height = bitmap.height

            // Check if resizing is needed
            if (width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION) {
                return bitmap
            }

            val scale = MAX_IMAGE_DIMENSION / maxOf(width, height).toFloat()
            val newW = (width * scale).toInt().coerceAtLeast(1)
            val newH = (height * scale).toInt().coerceAtLeast(1)

            if (newW <= 0 || newH <= 0 || newW > 4096 || newH > 4096) {
                return bitmap
            }

            val resized = createBitmap(newW, newH)
            val canvas = Canvas(resized)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, newW, newH), paint)

            return resized
        } catch (e: OutOfMemoryError) {
            Timber.tag("ImageCacheManager").d("OutOfMemoryError compressing bitmap: $e")
            clearMemoryCache()
            return null
        } catch (e: Exception) {
            Timber.tag("ImageCacheManager").d("Error compressing bitmap: $e")
            return null
        }
    }

    /**
     * Clear memory cache only
     */
    fun clearMemoryCache() {
        try {
            // Just clear the cache, don't recycle bitmaps to avoid crashes
            // Android will handle bitmap cleanup when they're no longer referenced
            memoryCache.clear()
            currentMemoryUsage.set(0)
        } catch (e: Exception) {
            Timber.d("ImageCacheManager - Error clearing memory cache: $e")
        }
    }

    /**
     * Load original image using a dedicated scope that can be cancelled when screen is disposed
     * This avoids cancellation during UI recomposition but allows cancellation when leaving screen
     */
    fun loadOriginalImageWithScope(context: Context, imageUrl: String, mid: String, isVisible: Boolean = true, onComplete: (Bitmap?) -> Unit) {
        val contextKey = context.toString()
        val originalMid = "${mid}_original"
        
        // Quick sync cache check first - check both regular and original cache
        val originalCachedBitmap = getCachedImageSync(context, originalMid)
        if (originalCachedBitmap != null) {
            try {
                onComplete(originalCachedBitmap)
                return
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").d("Error in immediate original cache callback: $e")
            }
        }
        
        // Check regular cache for placeholder
        val regularCachedBitmap = getCachedImageSync(context, mid)
        if (regularCachedBitmap != null) {
            try {
                onComplete(regularCachedBitmap)
                Timber.tag("ImageCacheManager").d("Showing regular cached image as placeholder for: $mid")
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").d("Error in immediate regular cache callback: $e")
            }
        }
        
        // Track this download for the context
        activeDownloadsByContext.computeIfAbsent(contextKey) { ConcurrentHashMap.newKeySet() }.add(mid)
        
        // Check if we already showed a placeholder (regular cached image)
        val showedPlaceholder = regularCachedBitmap != null
        
        imageLoadingScope.launch {
            try {
                val result = loadOriginalImage(context, imageUrl, mid)
                // Use withContext to ensure callback runs on main thread safely
                withContext(Dispatchers.Main) {
                    try {
                        // Only call callback again if we didn't show a placeholder, or if we successfully loaded the original
                        if (!showedPlaceholder || result != null) {
                            onComplete(result)
                        }
                        // If we showed placeholder and failed to load original, don't call callback again
                        // (keep showing the placeholder)
                    } catch (e: Exception) {
                        Timber.tag("ImageCacheManager").d("Error in callback: $e")
                    }
                }
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").d("Error in loadOriginalImageWithScope: $e")
                // Only call error callback if we didn't show a placeholder
                if (!showedPlaceholder) {
                    withContext(Dispatchers.Main) {
                        try {
                            onComplete(null)
                        } catch (e: Exception) {
                            Timber.tag("ImageCacheManager").d("Error in error callback: $e")
                        }
                    }
                }
            } finally {
                // Remove from tracking when done
                activeDownloadsByContext[contextKey]?.remove(mid)
                if (activeDownloadsByContext[contextKey]?.isEmpty() == true) {
                    activeDownloadsByContext.remove(contextKey)
                }
            }
        }
    }

    /**
     * Cancel image loading operations for a specific context (screen)
     * Call this when leaving a screen to avoid wasting resources
     */
    fun cancelImageLoadingForContext(context: Context) {
        val contextKey = context.toString()
        val downloadsToCancel = activeDownloadsByContext[contextKey]
        
        if (downloadsToCancel != null) {
            Timber.tag("ImageCacheManager").d("Cancelling ${downloadsToCancel.size} image loading operations for context: $contextKey")
            // Remove from download queue to prevent new downloads
            downloadsToCancel.forEach { mid ->
                downloadQueue.remove(mid)
                downloadResults.remove(mid)
            }
            // Clear the tracking for this context
            activeDownloadsByContext.remove(contextKey)
        }
    }

    /**
     * Clean up old results from the download results cache
     */
    private fun cleanupOldResults() {
        val currentTime = System.currentTimeMillis()
        val fiveSecondsAgo = currentTime - 5000L
        
        val iterator = resultTimestamps.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value < fiveSecondsAgo) {
                downloadResults.remove(entry.key)
                iterator.remove()
            }
        }
    }

    /**
     * Progressive image loading - downloads and displays image in chunks
     * This allows images to appear progressively like in web browsers
     */
    suspend fun loadImageProgressive(
        context: Context,
        imageUrl: String,
        mid: String,
        onProgress: (ByteArray) -> Unit
    ): Bitmap? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        
        try {
            val url = URL(imageUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECTION_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "TweetApp/1.0")
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate")
            connection.setRequestProperty("Connection", "keep-alive")
            connection.setRequestProperty("Cache-Control", "no-cache")
            
            inputStream = connection.inputStream
            val buffer = ByteArray(PROGRESSIVE_LOAD_CHUNK_SIZE)
            val outputStream = java.io.ByteArrayOutputStream()
            var totalBytesRead = 0
            var bytesRead: Int
            
            Timber.tag("ImageCacheManager").d("Starting progressive download for $mid")
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                
                // Send progress update every chunk
                onProgress(buffer.copyOf(bytesRead))
                
                // Log progress every 1MB
                if (totalBytesRead % (1024 * 1024) == 0) {
                    Timber.tag("ImageCacheManager").d("Progressive download progress for $mid: ${totalBytesRead / 1024}KB")
                }
            }
            
            val imageData = outputStream.toByteArray()
            Timber.tag("ImageCacheManager").d("Progressive download completed for $mid: ${imageData.size / 1024}KB")
            
            // Decode the complete image
            val bitmap = decodeBitmapFromByteArrayWithCorrectOrientation(imageData)
            if (bitmap != null && !bitmap.isRecycled) {
                // Cache the complete image
                addToMemoryCache(mid, bitmap)
                
                // Save to disk cache
                try {
                    val dir = File(context.cacheDir, CACHE_DIR)
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, "$mid.jpg")
                    FileOutputStream(file).use { out ->
                        out.write(imageData)
                    }
                    Timber.tag("ImageCacheManager").d("Saved progressive image data (${imageData.size} bytes) for: $mid")
                } catch (e: Exception) {
                    Timber.tag("ImageCacheManager").d("Error saving progressive image to disk: $e")
                }
                
                return@withContext bitmap
            }
            
            null
        } catch (e: Exception) {
            Timber.tag("ImageCacheManager").e(e, "Progressive download failed for $mid")
            null
        } finally {
            try {
                inputStream?.close()
                connection?.disconnect()
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").d("Error closing connection: $e")
            }
        }
    }

    /**
     * Clear a specific cached image by mid
     */
    suspend fun clearCachedImage(context: Context, mid: String) = withContext(Dispatchers.IO) {
        try {
            // Remove from memory cache
            memoryCache.remove(mid)

            // Remove from disk cache
            val file = File(context.cacheDir, "$CACHE_DIR/$mid.jpg")
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Timber.tag("ImageCacheManager").d("Error clearing cached image for mid $mid: $e")
        }
    }

    /**
     * Clear all cached images
     */
    suspend fun clearAllCachedImages(context: Context) = withContext(Dispatchers.IO) {
        try {
            clearMemoryCache()
            val dir = File(context.cacheDir, CACHE_DIR)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        } catch (e: Exception) {
            Timber.tag("ImageCacheManager").d("Error clearing all cached images: $e")
        }
    }

    // Note: clearMinimalCachedImages() removed as modern Android (API 34+) 
    // only sends UI_HIDDEN and BACKGROUND memory levels

    // Note: clearPartialCachedImages() removed as it's no longer used
    // Modern Android (API 34+) only sends UI_HIDDEN and BACKGROUND memory levels

    // Note: clearSignificantCachedImages() removed as modern Android (API 34+) 
    // only sends UI_HIDDEN and BACKGROUND memory levels

    /**
     * Get memory cache statistics
     */
    fun getMemoryCacheStats(): String {
        val maxSize = MAX_MEMORY_CACHE_SIZE
        val currentSize = memoryCache.size
        val currentMemory = currentMemoryUsage.get()
        val hitRate = 0 // Simple cache doesn't track hit/miss rates

        return "Memory: ${currentSize} items (${currentMemory / 1024 / 1024}MB/${maxSize / 1024 / 1024}MB), Active Downloads: ${activeDownloads.get()}"
    }

    /**
     * Decode bitmap from stream with correct EXIF orientation handling
     */
    private fun decodeBitmapFromStreamWithCorrectOrientation(inputStream: InputStream): Bitmap? {
        return try {
            // Read the entire stream into a byte array to handle mark/reset issues
            val byteArray = inputStream.readBytes()
            val byteArrayInputStream = java.io.ByteArrayInputStream(byteArray)

            // Create options to decode bounds first
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            // Mark the stream so we can reset it
            byteArrayInputStream.mark(byteArray.size)
            BitmapFactory.decodeStream(byteArrayInputStream, null, options)
            byteArrayInputStream.reset()

            // Decode the actual bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565 // Use less memory
            }

            val bitmap = BitmapFactory.decodeStream(byteArrayInputStream, null, decodeOptions)
            if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                // Apply EXIF orientation correction
                val correctedBitmap = applyExifOrientation(byteArray, bitmap)
                if (correctedBitmap != bitmap) {
                    // If we created a new bitmap, recycle the original
                    bitmap.recycle()
                }
                Timber.tag("ImageCacheManager")
                    .d("Successfully decoded bitmap with orientation correction: ${correctedBitmap.width}x${correctedBitmap.height}")
                return correctedBitmap
            } else {
                Timber.tag("ImageCacheManager")
                    .d("Failed to decode bitmap from stream - bitmap: $bitmap, recycled: ${bitmap?.isRecycled}, dimensions: ${bitmap?.width}x${bitmap?.height}")
            }
            null
        } catch (e: Exception) {
            Timber.tag("ImageCacheManager").d("Error decoding bitmap with orientation: $e")
            null
        }
    }

    /**
     * Decode bitmap from byte array with correct EXIF orientation handling
     */
    private fun decodeBitmapFromByteArrayWithCorrectOrientation(byteArray: ByteArray): Bitmap? {
        return try {
            // Try decodeByteArray first (more reliable than decodeStream)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            // Check bounds first
            BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size, options)
            
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Timber.tag("ImageCacheManager")
                    .d("Invalid image dimensions: ${options.outWidth}x${options.outHeight}")
                return null
            }

            // Decode the actual bitmap with original quality
            val decodeOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888 // Use full quality for original images
                inJustDecodeBounds = false
            }

            val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size, decodeOptions)
            if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                // Apply EXIF orientation correction
                val correctedBitmap = applyExifOrientation(byteArray, bitmap)
                if (correctedBitmap != bitmap) {
                    // If we created a new bitmap, recycle the original
                    bitmap.recycle()
                }
                correctedBitmap
            } else {
                Timber.tag("ImageCacheManager")
                    .d("Failed to decode original bitmap from byte array - bitmap: $bitmap, recycled: ${bitmap?.isRecycled}, dimensions: ${bitmap?.width}x${bitmap?.height}")
                null
            }
        } catch (e: Exception) {
            Timber.tag("ImageCacheManager").d("Error decoding original bitmap from byte array: $e")
            null
        }
    }

    /**
     * Decode bitmap from file with correct EXIF orientation handling
     */
    private fun decodeBitmapFromFileWithCorrectOrientation(filePath: String): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565 // Use less memory
            }

            val bitmap = BitmapFactory.decodeFile(filePath, options)
            if (bitmap != null) {
                // Apply EXIF orientation correction
                val correctedBitmap = applyExifOrientation(filePath, bitmap)
                if (correctedBitmap != bitmap) {
                    // If we created a new bitmap, recycle the original
                    bitmap.recycle()
                }
                Timber.tag("ImageCacheManager")
                    .d("Successfully decoded bitmap from file with orientation correction: ${correctedBitmap.width}x${correctedBitmap.height}")
                return correctedBitmap
            }
            bitmap
        } catch (e: Exception) {
            Timber.tag("ImageCacheManager")
                .d("Error decoding bitmap from file with orientation: $e")
            null
        }
    }

    /**
     * Apply EXIF orientation to bitmap from byte array
     */
    private fun applyExifOrientation(byteArray: ByteArray, bitmap: Bitmap): Bitmap {
        return try {
            // Create a temporary file to use with ExifInterface
            val tempFile = File.createTempFile("exif_temp", ".jpg")
            tempFile.writeBytes(byteArray)

            val exif = ExifInterface(tempFile.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            // Clean up temp file
            tempFile.delete()

            return applyOrientationMatrix(bitmap, orientation)
        } catch (e: Exception) {
            Timber.tag("ImageCacheManager").d("Error applying EXIF orientation from byte array: $e")
            bitmap
        }
    }

    /**
     * Apply EXIF orientation to bitmap from file path
     */
    private fun applyExifOrientation(filePath: String, bitmap: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(filePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            return applyOrientationMatrix(bitmap, orientation)
        } catch (e: Exception) {
            Timber.tag("ImageCacheManager").d("Error applying EXIF orientation from file: $e")
            bitmap
        }
    }

    /**
     * Apply orientation matrix to bitmap
     */
    private fun applyOrientationMatrix(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> {
                matrix.postRotate(90f)
                Timber.tag("ImageCacheManager").d("Applying 90 degree rotation")
            }

            ExifInterface.ORIENTATION_ROTATE_180 -> {
                matrix.postRotate(180f)
                Timber.tag("ImageCacheManager").d("Applying 180 degree rotation")
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> {
                matrix.postRotate(270f)
                Timber.tag("ImageCacheManager").d("Applying 270 degree rotation")
            }

            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                matrix.postScale(-1f, 1f)
                Timber.tag("ImageCacheManager").d("Applying horizontal flip")
            }

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.postScale(1f, -1f)
                Timber.tag("ImageCacheManager").d("Applying vertical flip")
            }

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
                Timber.tag("ImageCacheManager").d("Applying transpose")
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
                Timber.tag("ImageCacheManager").d("Applying transverse")
            }

            else -> {
                // No rotation needed
                return bitmap
            }
        }

        // Create new bitmap with applied transformation
        val rotatedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )

        if (rotatedBitmap != bitmap) {
            Timber.tag("ImageCacheManager")
                .d("Created rotated bitmap: ${rotatedBitmap.width}x${rotatedBitmap.height}")
        }

        return rotatedBitmap
    }
}