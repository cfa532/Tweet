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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
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
    private const val CONNECTION_TIMEOUT = 8000 // 8 seconds
    private const val READ_TIMEOUT = 12000 // 12 seconds
    private const val MAX_CONCURRENT_DOWNLOADS = 3 // Limit concurrent downloads
    private const val MAX_RETRY_ATTEMPTS = 2

    // Connection pool for better performance
    private val connectionPool = ConcurrentHashMap<String, HttpURLConnection>()
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    private val activeDownloads = AtomicInteger(0)
    private val downloadQueue = ConcurrentHashMap<String, Boolean>()

    // LRU memory cache (mid -> Bitmap)
    private val memoryCache = object : LruCache<String, Bitmap>(MAX_MEMORY_CACHE_SIZE) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }

        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?
        ) {
            super.entryRemoved(evicted, key, oldValue, newValue)
            // Recycle bitmap when removed from cache
            if (evicted && !oldValue.isRecycled) {
                try {
                    oldValue.recycle()
                } catch (e: Exception) {
                    Timber.tag("ImageCacheManager").d("Error recycling bitmap: $e")
                }
            }
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
                            memoryCache.put(mid, bitmap)
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
                Timber.tag("ImageCacheManager")
                    .d("Saved original image data (${imageSize} bytes) for: $mid")

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
     */
    suspend fun loadOriginalImage(context: Context, imageUrl: String, mid: String): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                // Use separate cache key for original images
                val originalMid = "${mid}_original"

                // Check if original image is already cached first
                getCachedImage(context, originalMid)?.let { return@withContext it }

                // Check if already downloading this image
                if (downloadQueue.containsKey(originalMid)) {
                    return@withContext null
                }

                // Acquire semaphore to limit concurrent downloads
                downloadSemaphore.acquire()
                downloadQueue[originalMid] = true
                activeDownloads.incrementAndGet()

                try {
                    var bitmap: Bitmap? = null
                    var attempt = 0

                    while (bitmap == null && attempt < MAX_RETRY_ATTEMPTS) {
                        attempt++
                        try {
                            bitmap = performDownloadOriginal(imageUrl, originalMid, context)
                            if (bitmap != null && !bitmap.isRecycled) {
                                // Store original bitmap in memory cache with original key
                                memoryCache.put(originalMid, bitmap)
                                return@withContext bitmap
                            }
                        } catch (e: Exception) {
                            Timber.tag("ImageCacheManager")
                                .d("Original download attempt $attempt failed for $mid: $e")
                            if (attempt < MAX_RETRY_ATTEMPTS) {
                                delay(1000L * attempt) // Exponential backoff
                            }
                        }
                    }

                    return@withContext null

                } finally {
                    downloadQueue.remove(originalMid)
                    activeDownloads.decrementAndGet()
                    downloadSemaphore.release()
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
                    memoryCache.put(mid, compressed)

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
            memoryCache.evictAll()
        } catch (e: Exception) {
            Timber.d("ImageCacheManager - Error clearing memory cache: $e")
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
        val maxSize = memoryCache.maxSize()
        val currentSize = memoryCache.size()
        val hitCount = memoryCache.hitCount()
        val missCount = memoryCache.missCount()
        val hitRate = if (hitCount + missCount > 0) {
            (hitCount * 100.0 / (hitCount + missCount)).toInt()
        } else 0

        return "Memory: ${currentSize}/${maxSize}, Hit Rate: ${hitRate}%, Active Downloads: ${activeDownloads.get()}"
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
            val byteArrayInputStream = java.io.ByteArrayInputStream(byteArray)

            // Create options to decode bounds first
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            // Mark the stream so we can reset it
            byteArrayInputStream.mark(byteArray.size)
            BitmapFactory.decodeStream(byteArrayInputStream, null, options)
            byteArrayInputStream.reset()

            // Decode the actual bitmap with original quality
            val decodeOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888 // Use full quality for original images
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
                    .d("Successfully decoded original bitmap from byte array: ${correctedBitmap.width}x${correctedBitmap.height}")
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