package us.fireshare.tweet.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ImageCacheManager compresses and caches images by their mid (unique id).
 * - Uses LRU memory cache for fast access
 * - Stores compressed images in app's cache directory for persistence
 * - Provides suspend functions for cache operations
 * - Supports downloading images from URLs
 * - Improved memory management with proper bitmap recycling
 */
object ImageCacheManager {
    private const val CACHE_DIR = "image_cache"
    private const val MAX_MEMORY_CACHE_SIZE = 200 * 1024 * 1024 // Reduced to 200MB
    private const val COMPRESS_QUALITY = 80 // JPEG quality
    private const val MAX_IMAGE_DIMENSION = 1024 // Maximum image dimension
    private const val CONNECTION_TIMEOUT = 10000 // 10 seconds
    private const val READ_TIMEOUT = 15000 // 15 seconds

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
                    Timber.tag("ImageCacheManager").e("Error recycling bitmap: $e")
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
                Timber.tag("ImageCacheManager").e("Error in getCachedImageFile: $e")
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
                memoryCache.get(mid)?.let {
                    if (!it.isRecycled) {
                        return@withContext it
                    } else {
                        // Remove recycled bitmap from cache
                        memoryCache.remove(mid)
                    }
                }

                // Check disk cache
                val file = File(context.cacheDir, "$CACHE_DIR/$mid.jpg")
                if (file.exists()) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null && !bitmap.isRecycled) {
                            memoryCache.put(mid, bitmap)
                            return@withContext bitmap
                        }
                    } catch (e: OutOfMemoryError) {
                        Timber.tag("ImageCacheManager")
                            .e("OutOfMemoryError loading cached image: $e")
                        clearMemoryCache()
                        return@withContext null
                    } catch (e: Exception) {
                        Timber.tag("ImageCacheManager").e("Error loading cached image: $e")
                    }
                }
                null
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").e("Error in getCachedImage: $e")
                null
            }
        }


    /**
     * Download and cache image from URL
     */
    suspend fun downloadAndCacheImage(context: Context, imageUrl: String, mid: String): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                // Check if already cached first
                getCachedImage(context, mid)?.let { return@withContext it }

                val url = URL(imageUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECTION_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.requestMethod = "GET"

                var inputStream: InputStream? = null
                try {
                    inputStream = connection.inputStream
                    val bitmap = BitmapFactory.decodeStream(inputStream)

                    if (bitmap != null && !bitmap.isRecycled) {
                        // Cache the downloaded image
                        cacheImage(context, mid, bitmap)
                        return@withContext bitmap
                    } else {
                        Timber.tag("ImageCacheManager")
                            .e("Failed to decode image from URL: $imageUrl")
                        return@withContext null
                    }
                } catch (e: OutOfMemoryError) {
                    Timber.tag("ImageCacheManager").e("OutOfMemoryError downloading image: $e")
                    clearMemoryCache()
                    return@withContext null
                } catch (e: Exception) {
                    Timber.tag("ImageCacheManager").e("Error downloading image: $e")
                    return@withContext null
                } finally {
                    inputStream?.close()
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").e("Error in downloadAndCacheImage: $e")
                null
            }
        }

    /**
     * Load image from URL or cache
     */
    suspend fun loadImage(context: Context, imageUrl: String, mid: String): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                // First try to get from cache
                getCachedImage(context, mid)?.let { return@withContext it }

                // If not in cache, download and cache
                downloadAndCacheImage(context, imageUrl, mid)
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").e("Error in loadImage: $e")
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
                        Timber.tag("ImageCacheManager").e("Error saving image to disk: $e")
                    }
                }
            } catch (e: OutOfMemoryError) {
                Timber.tag("ImageCacheManager").e("OutOfMemoryError caching image: $e")
                clearMemoryCache()
            } catch (e: Exception) {
                Timber.tag("ImageCacheManager").e("Error caching image: $e")
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
            Timber.tag("ImageCacheManager").e("OutOfMemoryError compressing bitmap: $e")
            clearMemoryCache()
            return null
        } catch (e: Exception) {
            Timber.tag("ImageCacheManager").e("Error compressing bitmap: $e")
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
            Timber.e("ImageCacheManager - Error clearing memory cache: $e")
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
            
            Timber.tag("ImageCacheManager").d("Cleared cached image for mid: $mid")
        } catch (e: Exception) {
            Timber.tag("ImageCacheManager").e("Error clearing cached image for mid $mid: $e")
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
            Timber.tag("ImageCacheManager").e("Error clearing all cached images: $e")
        }
    }

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

        return "Memory: ${currentSize}/${maxSize}, Hit Rate: ${hitRate}%"
    }
}