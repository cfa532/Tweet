package us.fireshare.tweet.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
    private const val MAX_CONCURRENT_DOWNLOADS = 16 // Increase concurrent downloads for faster loading
    private const val MAX_RETRY_ATTEMPTS = 2

    // Coroutine scope for image loading that can be cancelled when screen is disposed
    private val imageLoadingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Track active downloads by screen/context to allow selective cancellation
    private val activeDownloadsByContext = ConcurrentHashMap<String, MutableSet<String>>()
    
    // Priority queue for downloads (visible images get higher priority)
    private val downloadPriorityQueue = ConcurrentHashMap<String, Boolean>() // mid -> isVisible
    
    // Simple priority tracking for monitoring
    private var activeVisibleDownloads = 0
    private var activeInvisibleDownloads = 0

    // OkHttp client with connection pooling for efficient network operations
    private val okHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(
            maxIdleConnections = MAX_CONCURRENT_DOWNLOADS,
            keepAliveDuration = 5,
            timeUnit = TimeUnit.MINUTES
        ))
        .connectTimeout(CONNECTION_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    private val downloadQueue = ConcurrentHashMap<String, Boolean>()
    private val downloadResults = ConcurrentHashMap<String, Bitmap>()
    private val resultTimestamps = ConcurrentHashMap<String, Long>()
    
    // Mutex to prevent race conditions in download queue
    private val downloadQueueMutex = Any()
    
    // Deduplication: Track ongoing downloads to prevent duplicate requests
    // Maps image mid to the timestamp when download started
    private val ongoingDownloads = ConcurrentHashMap<String, Long>()
    
    // Pause/resume mechanism
    private val pausedDownloads = ConcurrentHashMap<String, Boolean>() // mid -> isPaused
    private val pausedDownloadMutex = Any()

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
     * Remove bitmap from memory cache with proper memory tracking
     */
    private fun removeFromMemoryCache(mid: String): Bitmap? {
        val bitmap = memoryCache.remove(mid)
        if (bitmap != null) {
            // Update memory usage counter
            val bitmapSize = try {
                bitmap.byteCount
            } catch (_: Exception) {
                // If bitmap is already recycled, we can't get the size
                // This is fine, just log it for debugging
                Timber.tag("ImageCacheManager").d("Could not get byteCount for removed bitmap: $mid")
                0
            }
            currentMemoryUsage.addAndGet(-bitmapSize)
        }
        return bitmap
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
                        // Remove invalid bitmap from cache with proper memory tracking
                        removeFromMemoryCache(mid)
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
     * Wait for concurrent download to complete with timeout
     * @return Cached image if download completed, null otherwise
     */
    private suspend fun waitForConcurrentDownload(context: Context, mid: String): Bitmap? {
        val maxWaitTime = 15000L // 15 seconds
        val startTime = System.currentTimeMillis()
        
        while (true) {
            delay(100) // Check every 100ms
            
            if (System.currentTimeMillis() - startTime > maxWaitTime) {
                Timber.tag("ImageCacheManager").w("Timeout waiting for concurrent download: $mid")
                return null
            }
            
            val isStillDownloading = synchronized(downloadQueueMutex) {
                ongoingDownloads.contains(mid)
            }
            
            if (!isStillDownloading) {
                // Download completed, try to get from cache
                return getCachedImage(context, mid)
            }
        }
    }

    /**
     * Download and cache image from URL with improved error handling and retry logic
     * Includes deduplication to prevent multiple concurrent downloads of the same image
     */
    suspend fun downloadAndCacheImage(context: Context, imageUrl: String, mid: String): Bitmap? =
        withContext(Dispatchers.IO) {
            var semaphoreAcquired = false
            try {
                // Check if already cached first
                getCachedImage(context, mid)?.let { return@withContext it }

                // Deduplication: Check if already downloading this image
                val shouldProceed = synchronized(downloadQueueMutex) {
                    if (ongoingDownloads.contains(mid)) {
                        false // Another thread is downloading
                    } else {
                        ongoingDownloads[mid] = System.currentTimeMillis()
                        true // This thread will download
                    }
                }

                if (!shouldProceed) {
                    // Wait for the concurrent download to complete
                    Timber.tag("ImageCacheManager").d("Image $mid already downloading, waiting for completion")
                    return@withContext waitForConcurrentDownload(context, mid)
                }

                // Acquire semaphore to limit concurrent downloads
                downloadSemaphore.acquire()
                semaphoreAcquired = true
                downloadQueue[mid] = true
                activeInvisibleDownloads++ // Assume invisible for compressed images

                try {
                    var bitmap: Bitmap? = null
                    var attempt = 0

                    while (bitmap == null && attempt < MAX_RETRY_ATTEMPTS) {
                        attempt++
                        try {
                            bitmap = performDownload(imageUrl)
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
                    // Remove from ongoing downloads to signal completion
                    synchronized(downloadQueueMutex) {
                        ongoingDownloads.remove(mid)
                    }
                    activeInvisibleDownloads--
                    downloadSemaphore.release()
                    semaphoreAcquired = false
                }
            } catch (e: Exception) {
                // Handle cancellation by cleaning up download queue
                if (e is kotlinx.coroutines.CancellationException) {
                    Timber.tag("ImageCacheManager").d("Download cancelled for $mid - cleaning up")
                    
                    // Clean up download queue and release resources
                    synchronized(downloadQueueMutex) {
                        if (downloadQueue.containsKey(mid)) {
                            downloadQueue.remove(mid)
                            activeInvisibleDownloads--
                        }
                        // Remove from ongoing downloads
                        ongoingDownloads.remove(mid)
                    }
                    
                    // Release the download slot only if we acquired it and haven't released it yet
                    if (semaphoreAcquired) {
                        downloadSemaphore.release()
                    }
                    
                    Timber.tag("ImageCacheManager").d("Cleaned up cancelled download for $mid")
                } else {
                    Timber.tag("ImageCacheManager").d("Error in downloadAndCacheImage: $e")
                    // Remove from ongoing downloads on error too
                    synchronized(downloadQueueMutex) {
                        ongoingDownloads.remove(mid)
                    }
                }
                null
            }
        }

    /**
     * Perform the actual download with OkHttp connection pooling
     */
    private suspend fun performDownload(imageUrl: String): Bitmap? =
        withContext(Dispatchers.IO) {
            var inputStream: InputStream? = null

            try {
                val request = Request.Builder()
                    .url(imageUrl)
                    .header("User-Agent", "TweetApp/1.0")
                    .header("Accept", "image/*,*/*;q=0.8")
                    .header("Cache-Control", "no-cache")
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Timber.tag("ImageCacheManager").d("Failed to download image: HTTP ${response.code}")
                    response.close()
                    return@withContext null
                }

                inputStream = response.body.byteStream()

                val bitmap = decodeBitmapFromStreamWithCorrectOrientation(inputStream)
                response.close()

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
            }
        }

    /**
     * Perform download for original quality images (no compression) using OkHttp
     */
    private suspend fun performDownloadOriginal(
        imageUrl: String,
        mid: String,
        context: Context
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            var inputStream: InputStream? = null

            try {
                // Check if download is paused before starting
                if (isDownloadPaused(mid)) {
                    Timber.tag("ImageCacheManager").d("Download paused for $mid, skipping")
                    return@withContext null
                }
                
                val request = Request.Builder()
                    .url(imageUrl)
                    .header("User-Agent", "TweetApp/1.0")
                    .header("Accept", "image/*,*/*;q=0.8")
                    .header("Cache-Control", "no-cache")
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Timber.tag("ImageCacheManager").d("Failed to download original image: HTTP ${response.code}")
                    response.close()
                    return@withContext null
                }

                inputStream = response.body.byteStream()

                // Read the image data to check size
                val imageData = inputStream.readBytes()
                response.close()
                
                // Check if download was paused during reading
                if (isDownloadPaused(mid)) {
                    Timber.tag("ImageCacheManager").d("Download paused during read for $mid, aborting")
                    return@withContext null
                }
                
                // Save original data directly (for original images, always save original data)
                val dir = File(context.cacheDir, CACHE_DIR)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "$mid.jpg")

                FileOutputStream(file).use { out ->
                    out.write(imageData)
                }

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
                Timber.tag("ImageCacheManager").e(e, "OutOfMemoryError downloading original image from $imageUrl")
                clearMemoryCache()
                return@withContext null
            } catch (_: java.net.SocketTimeoutException) {
                // Timeout is expected on slow networks, log without stack trace
                Timber.tag("ImageCacheManager").d("Image download timeout for $imageUrl")
                return@withContext null
            } catch (e: Exception) {
                // Log other exceptions with stack trace for debugging
                Timber.tag("ImageCacheManager").w(e, "Error downloading original image from $imageUrl")
                return@withContext null
            } finally {
                inputStream?.close()
            }
        }

    /**
     * Load original image without compression for high-quality display
     * Handles cancellation properly by cleaning up download queue
     */
    @OptIn(DelicateCoroutinesApi::class)
    suspend fun loadOriginalImage(context: Context, imageUrl: String, mid: String, isVisible: Boolean = true): Bitmap? =
        withContext(Dispatchers.IO) {
            var semaphoreAcquired = false
            try {
                // Use separate cache key for original images
                val originalMid = "${mid}_original"

                // Check if original image is already cached first
                getCachedImage(context, originalMid)?.let { return@withContext it }

                // Atomically check if downloading and mark as downloading to prevent race conditions
                val shouldDownload = synchronized(downloadQueueMutex) {
                    if (downloadQueue.containsKey(originalMid)) {
                        Timber.tag("ImageCacheManager").d("🔄 Dedup: $mid already downloading, waiting for result")
                        false // Already downloading, wait for result
                    } else {
                        // Mark as downloading immediately to prevent other threads from starting
                        downloadQueue[originalMid] = true
                        downloadPriorityQueue[originalMid] = isVisible
                        ongoingDownloads[originalMid] = System.currentTimeMillis()
                        true // This thread will download
                    }
                }
                
                if (!shouldDownload) {
                    // Another thread is downloading, wait for the result
                    var attempts = 0
                    var shouldContinue = true
                    while (attempts < 300 && shouldContinue) { // Wait up to 30 seconds (300 * 100ms)
                        delay(100L)
                        attempts++
                        
                        synchronized(downloadQueueMutex) {
                            shouldContinue = downloadQueue.containsKey(originalMid)
                        }
                    }
                    
                    // Clean up old results before checking
                    cleanupOldResults()
                    
                    // Return the result if available
                    return@withContext downloadResults[originalMid]
                }
                
                // This thread won - it will perform the download
                Timber.tag("ImageCacheManager").d("⬇️ Winner: $mid will download (visible=$isVisible)")
                
                // Acquire semaphore
                Timber.tag("ImageCacheManager").d("⏳ Acquiring semaphore for $mid (available=${downloadSemaphore.availablePermits})")
                downloadSemaphore.acquire()
                semaphoreAcquired = true
                Timber.tag("ImageCacheManager").d("✅ Semaphore acquired for $mid, starting download")
                
                // Track priority for monitoring
                if (isVisible) {
                    activeVisibleDownloads++
                } else {
                    activeInvisibleDownloads++
                }
                
                // Remove artificial delays for visible images to improve loading speed
                // Only add minimal delay for invisible images to avoid overwhelming server
                if (!isVisible) {
                    delay(100L) // Minimal delay only for background downloads
                }

                try {
                    var bitmap: Bitmap? = null
                    var attempt = 0

                    while (bitmap == null && attempt < MAX_RETRY_ATTEMPTS) {
                        attempt++
                        try {
                            Timber.tag("ImageCacheManager").d("📥 Downloading $mid (attempt $attempt)")
                            bitmap = performDownloadOriginal(imageUrl, originalMid, context)
                            if (bitmap != null && !bitmap.isRecycled) {
                                // Store original bitmap in memory cache with original key
                                addToMemoryCache(originalMid, bitmap)
                                // Store result for waiting requests
                                downloadResults[originalMid] = bitmap
                                resultTimestamps[originalMid] = System.currentTimeMillis()
                                
                                Timber.tag("ImageCacheManager").d("✅ Download complete for $mid, stored in cache")
                                
                                // Clean up old results periodically to prevent memory buildup
                                if (downloadResults.size > 50) {
                                    cleanupOldResults()
                                }
                                
                                return@withContext bitmap
                            } else {
                                Timber.tag("ImageCacheManager").w("⚠️ Download returned null/recycled bitmap for $mid")
                            }
                        } catch (e: Exception) {
                            Timber.tag("ImageCacheManager")
                                .w("❌ Download attempt $attempt failed for $mid: ${e.message}")
                            if (attempt < MAX_RETRY_ATTEMPTS) {
                                // Extended backoff delays to avoid overwhelming server
                                delay(3000L * attempt) // 3s, 6s, 9s...
                            }
                        }
                    }

                    // All attempts failed
                    Timber.tag("ImageCacheManager").e("💥 All download attempts failed for $mid after $attempt tries")
                    // Don't store null in ConcurrentHashMap (not allowed)
                    // Waiting requests will timeout or get null from missing entry
                    return@withContext null

                } finally {
                    // Always release the download slot and clean up
                    val wasVisible = downloadPriorityQueue[originalMid] ?: false
                    
                    // Update priority counters
                    if (wasVisible) {
                        activeVisibleDownloads--
                    } else {
                        activeInvisibleDownloads--
                    }
                    
                    synchronized(downloadQueueMutex) {
                        downloadQueue.remove(originalMid)
                        downloadPriorityQueue.remove(originalMid)
                        ongoingDownloads.remove(originalMid)
                        // Don't remove downloadResults immediately - let other waiting requests get the result
                    }
                    
                    // Release semaphore (we always acquire it in the normal path)
                    downloadSemaphore.release()
                    semaphoreAcquired = false
                    
                    // Clean up download results after a delay to allow other requests to get the result
                    GlobalScope.launch {
                        delay(10000L) // Increased delay to 10 seconds
                        downloadResults.remove(originalMid)
                        resultTimestamps.remove(originalMid)
                    }
                }
            } catch (e: Exception) {
                // Handle cancellation by cleaning up download queue
                if (e is kotlinx.coroutines.CancellationException) {
                    val originalMid = "${mid}_original"
                    
                    // Clean up download queue and release resources
                    synchronized(downloadQueueMutex) {
                        if (downloadQueue.containsKey(originalMid)) {
                            // Get visibility BEFORE removing from queue
                            val wasVisible = downloadPriorityQueue[originalMid] ?: false
                            
                            downloadQueue.remove(originalMid)
                            downloadResults.remove(originalMid)
                            downloadPriorityQueue.remove(originalMid)
                            
                            // Update priority counters
                            if (wasVisible) {
                                activeVisibleDownloads--
                            } else {
                                activeInvisibleDownloads--
                            }
                        }
                    }
                    
                    // Release the download slot only if we acquired it and haven't released it yet
                    if (semaphoreAcquired) {
                        downloadSemaphore.release()
                    }
                } else {
                    Timber.tag("ImageCacheManager").e(e, "Error in loadOriginalImage for $mid")
                }
                null
            }
        }

    /**
     * Check if there are available download slots
     */
    fun hasAvailableDownloadSlots(): Boolean {
        return downloadSemaphore.availablePermits > 0
    }

    /**
     * Get current download status for debugging
     */
    fun getDownloadStatus(): String {
        return "Available: ${downloadSemaphore.availablePermits}/${MAX_CONCURRENT_DOWNLOADS}, Queued: ${downloadQueue.size}, Paused: ${pausedDownloads.size}"
    }
    
    /**
     * Pause a download by marking it as paused
     */
    fun pauseDownload(mid: String) {
        synchronized(pausedDownloadMutex) {
            pausedDownloads[mid] = true
        }
    }
    
    /**
     * Resume a download by removing it from paused state
     */
    fun resumeDownload(mid: String) {
        synchronized(pausedDownloadMutex) {
            pausedDownloads.remove(mid)
        }
    }
    
    /**
     * Check if a download is paused
     */
    fun isDownloadPaused(mid: String): Boolean {
        synchronized(pausedDownloadMutex) {
            return pausedDownloads.containsKey(mid)
        }
    }
    
    /**
     * Start background task to periodically resume paused downloads and clean up stuck downloads
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun startPausedDownloadResumer() {
        GlobalScope.launch(Dispatchers.IO) {
            while (true) {
                delay(5000L) // Check every 5 seconds
                
                synchronized(pausedDownloadMutex) {
                    if (pausedDownloads.isNotEmpty()) {
                        // Resume paused downloads if we have available slots
                        if (downloadSemaphore.availablePermits > 0) {
                            val pausedMids = pausedDownloads.keys.toList()
                            for (mid in pausedMids) {
                                if (downloadSemaphore.availablePermits > 0) {
                                    pausedDownloads.remove(mid)
                                } else {
                                    break // No more slots available
                                }
                            }
                        }
                    }
                }
                
                // Clean up stuck downloads
                cleanupStuckDownloads()
            }
        }
    }
    
    // Initialize the paused download resumer
    init {
        startPausedDownloadResumer()
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
    fun loadOriginalImageWithScope(context: Context, imageUrl: String, mid: String,
                                   onComplete: (Bitmap?) -> Unit) {
        val contextKey = context.toString()
        
        // Track this download for the context
        activeDownloadsByContext.computeIfAbsent(contextKey) { ConcurrentHashMap.newKeySet() }.add(mid)
        
        imageLoadingScope.launch {
            try {
                val result = loadOriginalImage(context, imageUrl, mid)
                // Use withContext to ensure callback runs on main thread safely
                withContext(Dispatchers.Main) {
                    try {
                        onComplete(result)
                    } catch (e: Exception) {
                        Timber.tag("ImageCacheManager").d("Error in callback: $e")
                    }
                }
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").d("Error in loadOriginalImageWithScope: $e")
                withContext(Dispatchers.Main) {
                    try {
                        onComplete(null)
                    } catch (e: Exception) {
                        Timber.tag("ImageCacheManager").d("Error in error callback: $e")
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
     * Clean up stuck downloads that have been in the queue too long
     */
    private fun cleanupStuckDownloads() {
        val currentTime = System.currentTimeMillis()
        val thirtySecondsAgo = currentTime - 30000L // 30 seconds timeout
        
        synchronized(downloadQueueMutex) {
            val stuckDownloads = downloadQueue.keys.filter { mid ->
                // Check when download started (ongoingDownloads) not when it completed (resultTimestamps)
                val startTimestamp = ongoingDownloads[mid]
                if (startTimestamp != null) {
                    // Download is actively running - check if it's been too long
                    startTimestamp < thirtySecondsAgo
                } else {
                    // No start timestamp - something is wrong, consider it stuck
                    true
                }
            }
            
            if (stuckDownloads.isNotEmpty()) {
                stuckDownloads.forEach { mid ->
                    downloadQueue.remove(mid)
                    downloadResults.remove(mid)
                    downloadPriorityQueue.remove(mid)
                    resultTimestamps.remove(mid)
                    ongoingDownloads.remove(mid)
                }
            }
        }
    }

    /**
     * Clear a specific cached image by mid
     */
    suspend fun clearCachedImage(context: Context, mid: String) = withContext(Dispatchers.IO) {
        try {
            // Remove from memory cache with proper memory tracking
            removeFromMemoryCache(mid)

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

    /**
     * Preload images for faster loading (similar to video preloading)
     */
    suspend fun preloadImages(context: Context, mid: String, imageUrl: String) = withContext(Dispatchers.IO) {
        try {
            // Check if already cached
            if (getCachedImage(context, mid) != null) {
                return@withContext
            }
            
            // Start downloading in background
            downloadAndCacheImage(context, imageUrl, mid)
        } catch (e: Exception) {
            Timber.tag("ImageCacheManager").d("Error preloading image: $e")
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

        return "Memory: $currentSize items (${currentMemory / 1024 / 1024}MB/${maxSize / 1024 / 1024}MB), Available Slots: ${downloadSemaphore.availablePermits}/${MAX_CONCURRENT_DOWNLOADS}"
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