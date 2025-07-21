package us.fireshare.tweet.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * ImageCacheManager compresses and caches images by their mid (unique id).
 * - Uses LRU memory cache for fast access
 * - Stores compressed images in app's cache directory for persistence
 * - Provides suspend functions for cache operations
 */
object ImageCacheManager {
    private const val CACHE_DIR = "image_cache"
    private const val MAX_MEMORY_CACHE_SIZE = 8 * 1024 * 1024 // 8MB
    private const val COMPRESS_QUALITY = 80 // JPEG quality

    // LRU memory cache (mid -> Bitmap)
    private val memoryCache = object : LruCache<String, Bitmap>(MAX_MEMORY_CACHE_SIZE) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    /**
     * Get cached image by mid, or null if not cached
     */
    suspend fun getCachedImage(context: Context, mid: String): Bitmap? = withContext(Dispatchers.IO) {
        memoryCache.get(mid)?.let { return@withContext it }
        val file = File(context.cacheDir, "$CACHE_DIR/$mid.jpg")
        if (file.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) memoryCache.put(mid, bitmap)
            return@withContext bitmap
        }
        null
    }

    /**
     * Cache and compress a bitmap by mid
     */
    suspend fun cacheImage(context: Context, mid: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        // Compress bitmap
        val compressed = compressBitmap(bitmap)
        memoryCache.put(mid, compressed)
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$mid.jpg")
        try {
            FileOutputStream(file).use { out ->
                compressed.compress(Bitmap.CompressFormat.JPEG, COMPRESS_QUALITY, out)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /**
     * Compress a bitmap to JPEG with quality
     */
    private fun compressBitmap(bitmap: Bitmap): Bitmap {
        // Optionally resize if too large (e.g., max 1024px)
        val maxDim = 1024
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDim && height <= maxDim) return bitmap
        val scale = maxDim / width.toFloat().coerceAtMost(maxDim / height.toFloat())
        val newW = (width * scale).toInt()
        val newH = (height * scale).toInt()
        val resized = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resized)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, newW, newH), paint)
        return resized
    }

    /**
     * Clear all cached images
     */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        memoryCache.evictAll()
        val dir = File(context.cacheDir, CACHE_DIR)
        if (dir.exists()) dir.deleteRecursively()
    }
} 