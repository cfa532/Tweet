package us.fireshare.tweet.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.Protocol
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
    private const val MAX_MEMORY_CACHE_SIZE = 100 * 1024 * 1024 // 100MB
    private const val MAX_MEMORY_CACHE_COUNT = 200 // Maximum number of images in memory
    private const val COMPRESS_QUALITY = 80 // JPEG quality
    private const val MAX_IMAGE_DIMENSION = 1024 // Maximum image dimension
    private const val CONNECTION_TIMEOUT = 5000 // 5 seconds - balanced timeout
    private const val READ_TIMEOUT = 20000 // 20 seconds - IPFS can be slow, give it more time
    // Reduced from 24 to 8 for better memory management (iOS uses 4 for avatars, no explicit limit for regular images)
    private const val MAX_CONCURRENT_DOWNLOADS = 8 // Reduced for better memory management
    private const val MAX_RETRY_ATTEMPTS = 2 // 2 retries for slow IPFS servers
    private const val PROGRESSIVE_SAMPLE_SIZE = 4 // Initial low-quality preview (1/4 resolution)
    private const val MAX_DOWNLOAD_RESULTS = 20 // FIX P1-4: Limit size of downloadResults map
    
    // Memory checking thresholds (similar to iOS MemoryCapManager)
    // iOS blocks duplicate requests at 60% of 2GB limit (1.2GB)
    // Android heap is typically smaller, so we use 60% of max heap as threshold
    private const val MEMORY_BLOCK_THRESHOLD_PERCENT = 60 // Block new downloads when memory usage > 60%
    
    // Separate timeout for avatar images (smaller, should be faster)
    private const val AVATAR_READ_TIMEOUT = 15000 // 15 seconds for avatars

    // Coroutine scope for image loading that can be cancelled when screen is disposed
    private val imageLoadingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Track active downloads by screen/context to allow selective cancellation
    private val activeDownloadsByContext = ConcurrentHashMap<String, MutableSet<String>>()
    
    // Priority queue for downloads (visible images get higher priority)
    private val downloadPriorityQueue = ConcurrentHashMap<String, Boolean>() // mid -> isVisible
    
    // Simple priority tracking for monitoring
    private var activeVisibleDownloads = 0
    private var activeInvisibleDownloads = 0

    // Ktor HTTP client for image downloads with OkHttp engine
    private val imageHttpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(CONNECTION_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
                readTimeout(READ_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)  // 20s for IPFS
                writeTimeout(READ_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
                protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                followRedirects(true)
            }
        }
    }
    
    // Separate client for avatars with shorter timeout
    private val avatarHttpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(CONNECTION_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
                readTimeout(AVATAR_READ_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)  // 15s for avatars
                writeTimeout(AVATAR_READ_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
                protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                followRedirects(true)
            }
        }
    }
    
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
    private var resumerJob: kotlinx.coroutines.Job? = null

    // Simple memory cache (mid -> Bitmap) - using ConcurrentHashMap for thread safety
    private val memoryCache = ConcurrentHashMap<String, Bitmap>()
    private var currentMemoryUsage = AtomicInteger(0)

    /**
     * Synchronous memory cache lookup - no coroutine overhead.
     * Use this from composable initialization to avoid blank frames during LazyColumn scroll.
     */
    fun getMemoryCachedBitmap(mid: String): Bitmap? {
        return memoryCache[mid]?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
    }

    /**
     * Synchronous cache lookup: memory first, then disk.
     * Called from composable remember{} to prevent blank frames when LazyColumn recycles items.
     * Disk reads are acceptable here because cached files are small compressed JPEGs (< 200KB).
     */
    fun getCachedBitmapSync(context: Context, mid: String): Bitmap? {
        // Memory cache (fastest)
        getMemoryCachedBitmap(mid)?.let { return it }

        // Disk cache fallback (synchronous file decode)
        try {
            val file = File(context.cacheDir, "$CACHE_DIR/$mid.jpg")
            if (file.exists()) {
                val bitmap = decodeBitmapFromFileWithCorrectOrientation(file.absolutePath)
                if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                    addToMemoryCache(mid, bitmap)
                    return bitmap
                }
            }
        } catch (_: Exception) {
            // Ignore decode errors; LaunchedEffect will retry asynchronously
        }
        return null
    }
    
    /**
     * Add bitmap to memory cache with size and count management (matches iOS NSCache limits)
     */
    private fun addToMemoryCache(mid: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            val bitmapSize = bitmap.byteCount
            
            // Enforce count limit first (iOS uses countLimit = 100)
            if (memoryCache.size >= MAX_MEMORY_CACHE_COUNT && !memoryCache.containsKey(mid)) {
                // Remove oldest entries to make room (simple FIFO approach)
                val iterator = memoryCache.entries.iterator()
                while (iterator.hasNext() && memoryCache.size >= MAX_MEMORY_CACHE_COUNT) {
                    val entry = iterator.next()
                    val oldBitmap = entry.value
                    iterator.remove()
                    currentMemoryUsage.addAndGet(-oldBitmap.byteCount)
                }
            }
            
            // Enforce size limit (iOS uses totalCostLimit = 35MB)
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
            
            // Remove existing entry if present (to update)
            val existingBitmap = memoryCache.remove(mid)
            if (existingBitmap != null) {
                currentMemoryUsage.addAndGet(-existingBitmap.byteCount)
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
                0 // Bitmap already recycled
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
                        removeFromMemoryCache(mid)
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
                        }
                    } catch (e: OutOfMemoryError) {
                        Timber.tag("ImageCacheManager").e(e, "OutOfMemoryError loading cached image")
                        clearMemoryCache()
                        return@withContext null
                    } catch (e: Exception) {
                        // Ignore decode errors
                    }
                }
                null
            } catch (e: Exception) {
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
     * Check memory usage and wait if necessary (similar to iOS waitForMemoryWindow)
     * Returns true if memory is available, false if we should skip the download
     */
    private suspend fun waitForMemoryWindow(cacheKey: String, retryLabel: String): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val memoryUsagePercent = (usedMemory * 100.0 / maxMemory).toInt()
        
        // Fast-path if we're comfortably below the threshold (iOS uses 60%)
        if (memoryUsagePercent < MEMORY_BLOCK_THRESHOLD_PERCENT) {
            return true
        }
        
        // Memory is high, wait and retry (similar to iOS exponential backoff)
        val maxAttempts = 3
        for (attempt in 0 until maxAttempts) {
            val currentUsed = runtime.totalMemory() - runtime.freeMemory()
            val currentPercent = (currentUsed * 100.0 / maxMemory).toInt()
            
            if (currentPercent < MEMORY_BLOCK_THRESHOLD_PERCENT) {
                if (attempt > 0) {
                    Timber.tag("ImageCacheManager").d("✅ Memory cooled down after $attempt backoff attempts for $retryLabel $cacheKey")
                }
                return true
            }
            
            val delaySeconds = Math.pow(2.0, attempt.toDouble()) * 0.4
            Timber.tag("ImageCacheManager").w("⏳ Memory at $currentPercent% (threshold $MEMORY_BLOCK_THRESHOLD_PERCENT%) - delaying new image download $retryLabel $cacheKey by ${String.format("%.1f", delaySeconds)}s")
            delay((delaySeconds * 1000).toLong())
        }
        
        val finalUsed = runtime.totalMemory() - runtime.freeMemory()
        val finalPercent = (finalUsed * 100.0 / maxMemory).toInt()
        if (finalPercent >= MEMORY_BLOCK_THRESHOLD_PERCENT) {
            Timber.tag("ImageCacheManager").w("🚫 Aborting new image download $retryLabel $cacheKey - memory still high at $finalPercent%")
            return false
        }
        
        return true
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
                return@withContext waitForConcurrentDownload(context, mid)
            }

                // Check memory availability before starting download (similar to iOS)
                if (!waitForMemoryWindow(mid, "[thumbnail]")) {
                    synchronized(downloadQueueMutex) {
                        ongoingDownloads.remove(mid)
                    }
                    return@withContext null
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
                            bitmap = performDownload(imageUrl, context)
                            if (bitmap != null && !bitmap.isRecycled) {
                                // Cache the downloaded image
                                cacheImage(context, mid, bitmap)
                                return@withContext bitmap
                            }
                        } catch (e: Exception) {
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
                // FIX P2-7: Handle cancellation with proper cleanup and race condition prevention
                if (e is kotlinx.coroutines.CancellationException) {
                    // Clean up download queue and release resources atomically
                    synchronized(downloadQueueMutex) {
                        val wasInQueue = downloadQueue.containsKey(mid)
                        
                        // Recycle any cached result for this download
                        downloadResults[mid]?.let { bitmap ->
                            if (!bitmap.isRecycled) {
                                try {
                                    bitmap.recycle()
                                } catch (ex: Exception) {
                                    Timber.tag("ImageCacheManager").w(ex, "Error recycling bitmap during cancellation")
                                }
                            }
                        }
                        
                        downloadQueue.remove(mid)
                        downloadResults.remove(mid)
                        downloadPriorityQueue.remove(mid)
                        resultTimestamps.remove(mid)
                        ongoingDownloads.remove(mid)
                        
                        // Update counter ONLY if we were in the queue
                        if (wasInQueue) {
                            activeInvisibleDownloads = maxOf(0, activeInvisibleDownloads - 1)
                        }
                    }
                    
                    if (semaphoreAcquired) {
                        downloadSemaphore.release()
                    }
                } else {
                    synchronized(downloadQueueMutex) {
                        ongoingDownloads.remove(mid)
                    }
                }
                null
            }
        }

    /**
     * Perform the actual download with OkHttp connection pooling
     * FIX: Now streams to disk to avoid OOM for large compressed images
     */
    private suspend fun performDownload(imageUrl: String, context: Context): Bitmap? =
        withContext(Dispatchers.IO) {
            if (!us.fireshare.tweet.HproseInstance.isOnline.value) return@withContext null
            var inputStream: InputStream? = null

            try {
                val response = imageHttpClient.get(imageUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, "TweetApp/1.0")
                        append(HttpHeaders.Accept, "image/*,*/*;q=0.8")
                        append(HttpHeaders.CacheControl, "no-cache")
                    }
                }

                if (response.status.value !in 200..299) {
                    return@withContext null
                }

                inputStream = response.bodyAsChannel().toInputStream()

                val bitmap = decodeBitmapFromStreamWithCorrectOrientation(inputStream, context)

                // FIX P1-5: Validate bitmap before returning
                if (bitmap != null) {
                    if (!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                        return@withContext bitmap
                    } else {
                        // Invalid or corrupt bitmap - recycle it
                        if (!bitmap.isRecycled) {
                            try {
                                bitmap.recycle()
                            } catch (e: Exception) {
                                // Ignore recycle errors
                            }
                        }
                    }
                }
                return@withContext null
            } catch (e: OutOfMemoryError) {
                // FIX P2-6: Aggressive cleanup on OOM to prevent cascading failures
                Timber.tag("ImageCacheManager").e(e, "OutOfMemoryError downloading image")
                
                // Clear all caches and intermediate results
                clearMemoryCache()
                synchronized(downloadQueueMutex) {
                    // Recycle all cached download results
                    downloadResults.values.forEach { bitmap ->
                        if (!bitmap.isRecycled) {
                            try {
                                bitmap.recycle()
                            } catch (ex: Exception) {
                                // Ignore recycle errors
                            }
                        }
                    }
                    downloadResults.clear()
                    resultTimestamps.clear()
                }
                System.gc()  // Suggest immediate garbage collection
                
                return@withContext null
            } catch (e: Exception) {
                return@withContext null
            } finally {
                inputStream?.close()
            }
        }

    /**
     * Perform download for original quality images with progressive loading
     * Shows low-quality preview first, then full quality
     * FIX: Streams directly to disk to avoid OOM (matches iOS URLSession.shared.download behavior)
     */
    private suspend fun performDownloadOriginalProgressive(
        imageUrl: String,
        mid: String,
        context: Context,
        onProgressiveLoad: ((Bitmap) -> Unit)? = null
    ): Bitmap? =
        withContext(Dispatchers.IO) {
            if (!us.fireshare.tweet.HproseInstance.isOnline.value) return@withContext null
            var inputStream: InputStream? = null
            var tempFile: File? = null

            try {
                // Check if download is paused before starting
                if (isDownloadPaused(mid)) {
                    return@withContext null
                }
                
                // Use appropriate client based on URL (avatar vs regular image)
                val isAvatar = imageUrl.contains("/avatar/") || mid.contains("avatar")
                val client = if (isAvatar) avatarHttpClient else imageHttpClient
                
                val response = client.get(imageUrl) {
                    headers {
                        append(HttpHeaders.UserAgent, "TweetApp/1.0")
                        append(HttpHeaders.Accept, "image/*,*/*;q=0.8")
                        append(HttpHeaders.CacheControl, "no-cache")
                    }
                }

                if (response.status.value !in 200..299) {
                    return@withContext null
                }

                inputStream = response.bodyAsChannel().toInputStream()

                // FIX OOM: Stream directly to temporary file instead of loading into memory
                // This matches iOS URLSession.shared.download behavior
                val dir = File(context.cacheDir, CACHE_DIR)
                if (!dir.exists()) dir.mkdirs()
                tempFile = File.createTempFile("img_${mid}_", ".tmp", dir)
                
                // Progressive: stream in chunks and try to show partial preview during download
                val bufferSize = 8192
                val buffer = ByteArray(bufferSize)
                var progressivePreviewSent = false
                val progressiveThreshold = 80 * 1024L // Try first preview after 80KB
                val previewOptions = BitmapFactory.Options().apply {
                    inSampleSize = PROGRESSIVE_SAMPLE_SIZE
                    inPreferredConfig = Bitmap.Config.RGB_565
                }

                FileOutputStream(tempFile).use { out ->
                    var len: Int
                    while (inputStream.read(buffer).also { len = it } != -1) {
                        if (isDownloadPaused(mid)) break
                        out.write(buffer, 0, len)
                        // Try to decode and show partial preview during download (once we have enough bytes)
                        if (onProgressiveLoad != null && !progressivePreviewSent && tempFile.length() >= progressiveThreshold) {
                            var preview: Bitmap? = null
                            try {
                                preview = BitmapFactory.decodeFile(tempFile.absolutePath, previewOptions)
                                if (preview != null && !preview.isRecycled && preview.width > 0 && preview.height > 0) {
                                    progressivePreviewSent = true
                                    withContext(Dispatchers.Main) {
                                        try {
                                            onProgressiveLoad(preview)
                                        } catch (e: Exception) {
                                            Timber.tag("ImageCacheManager").w(e, "Progressive preview callback failed")
                                        } finally {
                                            preview = null // Callback was given the bitmap; must not recycle
                                        }
                                    }
                                }
                            } catch (_: Exception) { /* incomplete file, keep downloading */ }
                            preview?.let {
                                if (!it.isRecycled) try { it.recycle() } catch (_: Exception) { }
                            }
                        }
                    }
                }
                
                // Check if download was paused during streaming
                if (isDownloadPaused(mid)) {
                    tempFile?.delete()
                    return@withContext null
                }
                
                // Decode final low-quality preview (1/4 resolution) from complete file and show before full decode
                if (onProgressiveLoad != null && tempFile != null && tempFile.length() > 50 * 1024) {
                    var preview: Bitmap? = null
                    try {
                        preview = BitmapFactory.decodeFile(tempFile.absolutePath, previewOptions)
                        if (preview != null && !preview.isRecycled) {
                            withContext(Dispatchers.Main) {
                                try {
                                    onProgressiveLoad(preview)
                                } catch (e: Exception) {
                                    Timber.tag("ImageCacheManager").w(e, "Preview callback failed")
                                } finally {
                                    preview = null // Callback was given the bitmap; must not recycle
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag("ImageCacheManager").w(e, "Preview decode failed")
                    } finally {
                        preview?.let {
                            if (!it.isRecycled) try { it.recycle() } catch (_: Exception) { }
                        }
                    }
                }
                
                // Move temp file to final location
                val finalFile = File(dir, "$mid.jpg")
                if (tempFile != null && tempFile.exists()) {
                    tempFile.renameTo(finalFile)
                }

                // Decode full quality bitmap from file (no memory load of raw bytes)
                val bitmap = decodeBitmapFromFileWithCorrectOrientation(finalFile.absolutePath)

                // FIX P1-5: Validate bitmap before returning
                if (bitmap != null) {
                    if (!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                        return@withContext bitmap
                    } else {
                        // Invalid or corrupt bitmap - recycle it
                        if (!bitmap.isRecycled) {
                            try {
                                bitmap.recycle()
                            } catch (e: Exception) {
                                // Ignore recycle errors
                            }
                        }
                    }
                }
                return@withContext null
            } catch (e: OutOfMemoryError) {
                // FIX P0-2 & P2-6: Aggressive cleanup on OOM to prevent cascading failures
                Timber.tag("ImageCacheManager").e(e, "OutOfMemoryError downloading original image from $imageUrl")
                
                // Clear all caches and intermediate results
                clearMemoryCache()
                synchronized(downloadQueueMutex) {
                    // Recycle all cached download results
                    downloadResults.values.forEach { bitmap ->
                        if (!bitmap.isRecycled) {
                            try {
                                bitmap.recycle()
                            } catch (ex: Exception) {
                                // Ignore recycle errors
                            }
                        }
                    }
                    downloadResults.clear()
                    resultTimestamps.clear()
                }
                System.gc()  // Suggest immediate garbage collection
                
                return@withContext null
            } catch (e: java.net.SocketTimeoutException) {
                // Timeout - let caller handle retry logic
                val timeoutType = if (e.message?.contains("connect") == true) "connection" else "read"
                Timber.tag("ImageCacheManager").w("⏱️ Image download $timeoutType timeout for $imageUrl")
                throw e // Re-throw to allow retry logic to handle it
            } catch (e: java.io.IOException) {
                // Network errors - let caller retry
                Timber.tag("ImageCacheManager").w("🌐 Network error downloading image from $imageUrl: ${e.message}")
                throw e // Re-throw to allow retry logic to handle it
            } catch (e: Exception) {
                // Log other exceptions with stack trace for debugging
                Timber.tag("ImageCacheManager").w(e, "Error downloading original image from $imageUrl")
                return@withContext null
            } finally {
                // Clean up resources: close stream and delete temp file if it exists
                try {
                    inputStream?.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
                // Clean up temp file if download failed or was cancelled
                tempFile?.let { file ->
                    if (file.exists()) {
                        try {
                            file.delete()
                        } catch (e: Exception) {
                            // Ignore delete errors
                        }
                    }
                }
            }
        }
    
    /**
     * Legacy method for backward compatibility - calls progressive version without callback
     */
    private suspend fun performDownloadOriginal(
        imageUrl: String,
        mid: String,
        context: Context
    ): Bitmap? = performDownloadOriginalProgressive(imageUrl, mid, context, null)

    /**
     * Load original image with progressive loading support
     * First returns low-quality preview, then full quality
     * Handles cancellation properly by cleaning up download queue
     */
    suspend fun loadOriginalImage(
        context: Context, 
        imageUrl: String, 
        mid: String, 
        isVisible: Boolean = true,
        onProgressiveLoad: ((Bitmap) -> Unit)? = null
    ): Bitmap? =
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
                    while (attempts < 100 && shouldContinue) { // Wait up to 10 seconds (100 * 100ms) - reduced timeout
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
                // Check memory availability before starting download (similar to iOS)
                if (!waitForMemoryWindow(originalMid, "[original]")) {
                    synchronized(downloadQueueMutex) {
                        downloadQueue.remove(originalMid)
                        ongoingDownloads.remove(originalMid)
                    }
                    return@withContext null
                }
                
                downloadSemaphore.acquire()
                semaphoreAcquired = true
                
                // Track priority for monitoring
                if (isVisible) {
                    activeVisibleDownloads++
                } else {
                    activeInvisibleDownloads++
                }
                
                // No artificial delays - let semaphore handle concurrency control

                try {
                    var bitmap: Bitmap? = null
                    var attempt = 0

                    while (bitmap == null && attempt < MAX_RETRY_ATTEMPTS) {
                        attempt++
                        try {
                            bitmap = performDownloadOriginalProgressive(imageUrl, originalMid, context, onProgressiveLoad)
                            if (bitmap != null && !bitmap.isRecycled) {
                                // Store original bitmap in memory cache with original key
                                addToMemoryCache(originalMid, bitmap)
                                // FIX P1-4: Store result with size limit enforcement
                                storeDownloadResult(originalMid, bitmap)
                                
                                return@withContext bitmap
                            }
                        } catch (e: Exception) {
                            val isTimeout = e is java.net.SocketTimeoutException
                            if (attempt < MAX_RETRY_ATTEMPTS) {
                                // For timeouts, retry immediately; for other errors, use backoff
                                val delayMs = if (isTimeout) 100L else (1000L * attempt)
                                delay(delayMs)
                            } else {
                                Timber.tag("ImageCacheManager").w("Download failed for $mid: ${e.message}")
                            }
                        }
                    }

                    // All attempts failed
                    Timber.tag("ImageCacheManager").w("All download attempts failed for $mid")
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
                    }
                    
                    // Release semaphore (we always acquire it in the normal path)
                    downloadSemaphore.release()
                    semaphoreAcquired = false
                    
                    // FIX P1-4: Use scheduled cleanup instead of GlobalScope
                    // Clean up download results after a delay to allow other requests to get the result
                    imageLoadingScope.launch {
                        delay(5000L) // Reduced to 5 seconds
                        synchronized(downloadQueueMutex) {
                            downloadResults.remove(originalMid)
                            resultTimestamps.remove(originalMid)
                        }
                    }
                }
            } catch (e: Exception) {
                // FIX P2-7: Handle cancellation with proper cleanup and race condition prevention
                if (e is kotlinx.coroutines.CancellationException) {
                    val originalMid = "${mid}_original"
                    
                    // Clean up download queue and release resources atomically
                    synchronized(downloadQueueMutex) {
                        // Check if we're actually in the download queue
                        val wasInQueue = downloadQueue.containsKey(originalMid)
                        val wasVisible = downloadPriorityQueue[originalMid] ?: false
                        
                        // Recycle bitmap before removing from results
                        downloadResults[originalMid]?.let { bitmap ->
                            if (!bitmap.isRecycled) {
                                try {
                                    bitmap.recycle()
                                } catch (ex: Exception) {
                                    Timber.tag("ImageCacheManager").w(ex, "Error recycling bitmap during cancellation")
                                }
                            }
                        }
                        
                        // Remove all references atomically
                        downloadQueue.remove(originalMid)
                        downloadResults.remove(originalMid)
                        downloadPriorityQueue.remove(originalMid)
                        resultTimestamps.remove(originalMid)
                        ongoingDownloads.remove(originalMid)
                        
                        // Update priority counters ONLY if we were in the queue
                        if (wasInQueue) {
                            if (wasVisible) {
                                activeVisibleDownloads = maxOf(0, activeVisibleDownloads - 1)
                            } else {
                                activeInvisibleDownloads = maxOf(0, activeInvisibleDownloads - 1)
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
     * Schedule a pause for a download after a delay. Used to debounce cancellation:
     * when the UI is cancelled we let the download continue for [delayMs] then pause it.
     * @param pauseKey The key used for the download (e.g. mid for compressed, "${mid}_original" for original)
     */
    fun schedulePauseDownload(pauseKey: String, delayMs: Long = 3000L) {
        imageLoadingScope.launch {
            delay(delayMs)
            pauseDownload(pauseKey)
            Timber.tag("ImageCacheManager").d("Scheduled pause applied for $pauseKey after ${delayMs}ms")
        }
    }

    /**
     * Run loadOriginalImage in the manager's scope so it is not cancelled when the caller is cancelled.
     * Caller should await() and on CancellationException call schedulePauseDownload to debounce cancellation.
     */
    fun loadOriginalImageDeferred(
        context: Context,
        imageUrl: String,
        mid: String,
        isVisible: Boolean = true,
        onProgressiveLoad: ((Bitmap) -> Unit)? = null
    ): Deferred<Bitmap?> = imageLoadingScope.async {
        loadOriginalImage(context, imageUrl, mid, isVisible, onProgressiveLoad)
    }

    /**
     * Pause a download by marking it as paused
     */
    fun pauseDownload(mid: String) {
        synchronized(pausedDownloadMutex) {
            pausedDownloads[mid] = true
        }
        ensureResumerRunning()
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
     * Start an on-demand background task to resume paused downloads and clean up stuck downloads.
     * Runs only while there are paused downloads, then stops automatically.
     */
    private fun ensureResumerRunning() {
        if (resumerJob?.isActive == true) return
        resumerJob = imageLoadingScope.launch {
            while (true) {
                delay(5000L)

                synchronized(pausedDownloadMutex) {
                    if (pausedDownloads.isNotEmpty() && downloadSemaphore.availablePermits > 0) {
                        val pausedMids = pausedDownloads.keys.toList()
                        for (mid in pausedMids) {
                            if (downloadSemaphore.availablePermits > 0) {
                                pausedDownloads.remove(mid)
                            } else {
                                break
                            }
                        }
                    }
                }

                cleanupStuckDownloads()

                // Stop the resumer when there's nothing left to manage
                val hasPaused = synchronized(pausedDownloadMutex) { pausedDownloads.isNotEmpty() }
                val hasOngoing = ongoingDownloads.isNotEmpty()
                if (!hasPaused && !hasOngoing) {
                    Timber.d("ImageCacheManager - Resumer stopping: no paused or ongoing downloads")
                    break
                }
            }
        }
    }

    /**
     * Cache a local image file from Uri directly by mid
     * This is used when sending an image - cache it immediately without downloading
     * 
     * @param context Android context
     * @param mid The media ID to cache under
     * @param uri The local file Uri
     * @return Cached bitmap if successful, null otherwise
     */
    suspend fun cacheLocalImageFile(context: Context, mid: String, uri: Uri): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                // Load bitmap from local Uri with orientation correction
                val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    decodeBitmapFromStreamWithOrientation(inputStream, uri, context)
                }
                
                if (bitmap != null && !bitmap.isRecycled) {
                    // Cache the bitmap
                    cacheImage(context, mid, bitmap)
                    Timber.tag("ImageCacheManager").d("Cached local image file for mid: $mid")
                    return@withContext bitmap
                } else {
                    Timber.tag("ImageCacheManager").w("Failed to load bitmap from local Uri: $uri")
                    return@withContext null
                }
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").e(e, "Error caching local image file")
                return@withContext null
            }
        }

    /**
     * Decode bitmap from stream with EXIF orientation correction
     */
    private fun decodeBitmapFromStreamWithOrientation(
        inputStream: InputStream,
        uri: Uri,
        context: Context
    ): Bitmap? {
        try {
            // First, decode without orientation to get bitmap
            val bitmap = BitmapFactory.decodeStream(inputStream) ?: return null
            
            // Try to read EXIF data for orientation
            val orientation = try {
                context.contentResolver.openInputStream(uri)?.use { exifStream ->
                    val exif = ExifInterface(exifStream)
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            
            // Apply orientation correction if needed
            return when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
                else -> bitmap
            }
        } catch (e: Exception) {
            Timber.tag("ImageCacheManager").e(e, "Error decoding bitmap with orientation")
            return null
        }
    }

    /**
     * Rotate bitmap by degrees
     */
    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Cache and compress a bitmap by mid
     */
    suspend fun cacheImage(context: Context, mid: String, bitmap: Bitmap) =
        withContext(Dispatchers.IO) {
            try {
                if (bitmap.isRecycled) {
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
                        // Ignore disk write errors
                    }
                }
            } catch (e: OutOfMemoryError) {
                Timber.tag("ImageCacheManager").e(e, "OutOfMemoryError caching image")
                clearMemoryCache()
            } catch (e: Exception) {
                // Ignore other caching errors
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
            Timber.tag("ImageCacheManager").e(e, "OutOfMemoryError compressing bitmap")
            clearMemoryCache()
            return null
        } catch (e: Exception) {
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
     * Load original image using a dedicated scope with progressive loading support
     * This avoids cancellation during UI recomposition but allows cancellation when leaving screen
     * @param onProgressiveLoad Called with low-quality preview first (optional)
     * @param onComplete Called with final full-quality image
     */
    fun loadOriginalImageWithScope(
        context: Context, 
        imageUrl: String, 
        mid: String,
        onProgressiveLoad: ((Bitmap) -> Unit)? = null,
        onComplete: (Bitmap?) -> Unit
    ) {
        val contextKey = context.toString()
        
        // Track this download for the context
        activeDownloadsByContext.computeIfAbsent(contextKey) { ConcurrentHashMap.newKeySet() }.add(mid)
        
        imageLoadingScope.launch {
            try {
                val result = loadOriginalImage(context, imageUrl, mid, true, onProgressiveLoad)
                // Use withContext to ensure callback runs on main thread safely
                withContext(Dispatchers.Main) {
                    try {
                        onComplete(result)
                    } catch (e: Exception) {
                        // Ignore callback errors
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    try {
                        onComplete(null)
                    } catch (e: Exception) {
                        // Ignore callback errors
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
    /**
     * FIX P1-4: Store download result with size limit enforcement
     * Prevents unbounded growth of downloadResults map
     */
    private fun storeDownloadResult(mid: String, bitmap: Bitmap?) {
        synchronized(downloadQueueMutex) {
            // Enforce size limit
            if (downloadResults.size >= MAX_DOWNLOAD_RESULTS) {
                // Remove oldest entries (by timestamp)
                val oldestEntries = resultTimestamps.entries
                    .sortedBy { it.value }
                    .take(5)  // Remove 5 oldest at a time
                
                oldestEntries.forEach { entry ->
                    // Recycle bitmap before removing
                    downloadResults[entry.key]?.let { oldBitmap ->
                        if (!oldBitmap.isRecycled) {
                            try {
                                oldBitmap.recycle()
                            } catch (e: Exception) {
                                // Ignore recycle errors
                            }
                        }
                    }
                    downloadResults.remove(entry.key)
                    resultTimestamps.remove(entry.key)
                }
                
                Timber.tag("ImageCacheManager").d(
                    "Cleaned up ${oldestEntries.size} old download results, size now: ${downloadResults.size}"
                )
            }
            
            // Store new result (only if non-null)
            if (bitmap != null) {
                downloadResults[mid] = bitmap
                resultTimestamps[mid] = System.currentTimeMillis()
            }
        }
    }

    private fun cleanupOldResults() {
        val currentTime = System.currentTimeMillis()
        val fiveSecondsAgo = currentTime - 5000L

        val iterator = resultTimestamps.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value < fiveSecondsAgo) {
                // Recycle bitmap before removing
                downloadResults[entry.key]?.let { bitmap ->
                    if (!bitmap.isRecycled) {
                        try {
                            bitmap.recycle()
                        } catch (e: Exception) {
                            // Ignore recycle errors
                        }
                    }
                }
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
     * FIX: Streams to temp file to avoid OOM for large compressed images
     */
    private suspend fun decodeBitmapFromStreamWithCorrectOrientation(inputStream: InputStream, context: Context): Bitmap? {
        return withContext(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                // FIX OOM: Stream to temp file instead of loading into memory
                // This prevents OOM for unexpectedly large compressed images
                val dir = File(context.cacheDir, CACHE_DIR)
                if (!dir.exists()) dir.mkdirs()
                tempFile = File.createTempFile("img_compressed_", ".tmp", dir)
                
                // Stream download directly to file (avoids loading entire image into memory)
                FileOutputStream(tempFile).use { out ->
                    inputStream.copyTo(out, bufferSize = 8192) // 8KB buffer for efficient streaming
                }
                
                // Decode from file (supports multiple reads for bounds checking)
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                
                // Check bounds first
                BitmapFactory.decodeFile(tempFile.absolutePath, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    return@withContext null
                }
                
                // Decode the actual bitmap
                val decodeOptions = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565 // Use less memory
                }
                
                val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOptions)
                if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                    // Apply EXIF orientation correction from file
                    val correctedBitmap = applyExifOrientation(tempFile.absolutePath, bitmap)
                    if (correctedBitmap != bitmap) {
                        // If we created a new bitmap, recycle the original
                        bitmap.recycle()
                    }
                    return@withContext correctedBitmap
                }
                null
            } catch (e: Exception) {
                null
            } finally {
                // Clean up temp file
                tempFile?.let { file ->
                    if (file.exists()) {
                        try {
                            file.delete()
                        } catch (e: Exception) {
                            // Ignore delete errors
                        }
                    }
                }
            }
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
                return null
            }

            // Decode the actual bitmap - use RGB_565 for better memory efficiency (2x less memory)
            val decodeOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565 // 2 bytes per pixel instead of 4 - much faster!
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
                null
            }
        } catch (e: Exception) {
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
            null
        }
    }

    /**
     * Apply EXIF orientation to bitmap from byte array
     * Optimized to read directly from stream instead of creating temp file
     */
    private fun applyExifOrientation(byteArray: ByteArray, bitmap: Bitmap): Bitmap {
        return try {
            // Use ByteArrayInputStream directly - no temp file needed (faster!)
            val exif = ExifInterface(java.io.ByteArrayInputStream(byteArray))
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            return applyOrientationMatrix(bitmap, orientation)
        } catch (e: Exception) {
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
            bitmap
        }
    }

    /**
     * Apply orientation matrix to bitmap
     */
    private fun applyOrientationMatrix(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap // No rotation needed
        }

        // Create new bitmap with applied transformation
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}