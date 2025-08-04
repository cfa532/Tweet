package us.fireshare.tweet.widget

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl

import java.io.File

/**
 * Simplified Video Cache Manager
 * 
 * This manager leverages ExoPlayer's built-in caching for both progressive and HLS videos.
 * No custom segment caching needed - ExoPlayer handles everything automatically.
 * 
 * Key Benefits:
 * - Uses ExoPlayer's native HLS support
 * - Automatic segment caching via CacheDataSource
 * - No manual playlist parsing
 * - No manual segment downloading
 * - Consistent IPFS ID-based cache keys
 */
@UnstableApi
object SimplifiedVideoCacheManager {
    private var videoCache: SimpleCache? = null
    private const val CACHE_SIZE_BYTES = 1000L * 1024 * 1024 // 1GB cache size
    private const val VIDEO_CACHE_DIR = "video_cache"

    /**
     * Get the shared video cache for both progressive and HLS videos
     */
    fun getCache(context: Context): Cache {
        if (videoCache == null) {
            val cacheDir = File(context.cacheDir, VIDEO_CACHE_DIR)
            val evictor = LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES)
            val databaseProvider = StandaloneDatabaseProvider(context)
            videoCache = SimpleCache(cacheDir, evictor, databaseProvider)
        }
        return videoCache ?: throw IllegalStateException("Video cache was not initialized")
    }

    /**
     * Calculate directory size recursively
     */
    fun getDirectorySize(directory: File): Long {
        var size = 0L
        directory.listFiles()?.forEach { file ->
            size += if (file.isDirectory) {
                getDirectorySize(file)
            } else {
                file.length()
            }
        }
        return size
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(context: Context): String {
        val cache = getCache(context)
        val cacheSize = cache.cacheSpace
        val maxCacheSize = CACHE_SIZE_BYTES
        val usedPercentage = (cacheSize * 100 / maxCacheSize).toInt()

        return "${cacheSize / (1024 * 1024)}MB / ${maxCacheSize / (1024 * 1024)}MB ($usedPercentage%)"
    }

    /**
     * Clear video cache
     */
    fun clearVideoCache(context: Context) {
        try {
            val cache = getCache(context)
            cache.release()
            videoCache = null

            val videoCacheDir = File(context.cacheDir, VIDEO_CACHE_DIR)
            if (videoCacheDir.exists()) {
                videoCacheDir.deleteRecursively()
            }

            Timber.d("SimplifiedVideoCacheManager - Video cache cleared")
        } catch (e: Exception) {
            Timber.e("SimplifiedVideoCacheManager - Error clearing video cache. ${e.message}")
        }
    }

    /**
     * Clear old cached videos
     */
    fun clearOldCachedVideos(context: Context, maxAgeInMillis: Long) {
        val videoCacheDir = File(context.cacheDir, VIDEO_CACHE_DIR)

        if (videoCacheDir.exists() && videoCacheDir.isDirectory) {
            val files = videoCacheDir.listFiles() ?: return
            for (file in files) {
                if (file.isFile && System.currentTimeMillis() - file.lastModified() > maxAgeInMillis) {
                    file.delete()
                }
            }
        }
    }

    /**
     * Get video aspect ratio from URI
     */
    suspend fun getVideoAspectRatio(context: Context, uri: Uri): Float? = withContext(IO) {
        val retriever = MediaMetadataRetriever()
        return@withContext try {
            Timber.d("SimplifiedVideoCacheManager - Extracting aspect ratio from URI: $uri")

            // Try to access the data source
            when {
                uri.scheme == "http" || uri.scheme == "https" -> {
                    // For HTTP/HTTPS URLs, download the data first to a temporary file
                    Timber.d("SimplifiedVideoCacheManager - Downloading video data for aspect ratio extraction")
                    val connection =
                        java.net.URL(uri.toString()).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 10000 // 10 seconds
                    connection.readTimeout = 30000 // 30 seconds
                    connection.requestMethod = "GET"

                    val tempFile =
                        java.io.File.createTempFile("aspect_ratio_", ".tmp", context.cacheDir)
                    tempFile.deleteOnExit()

                    connection.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    connection.disconnect()

                    Timber.d("SimplifiedVideoCacheManager - Downloaded video to temp file: ${tempFile.absolutePath}")
                    retriever.setDataSource(tempFile.absolutePath)
                }

                else -> {
                    // For local files, use the context method
                    retriever.setDataSource(context, uri)
                }
            }

            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toFloat()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toFloat()

            Timber.d("SimplifiedVideoCacheManager - Extracted width: $width, height: $height")

            if (width != null && height != null && height != 0f) {
                val aspectRatio = width / height
                Timber.d("SimplifiedVideoCacheManager - Calculated aspect ratio: $aspectRatio")
                aspectRatio
            } else {
                Timber.w("SimplifiedVideoCacheManager - Invalid width/height: width=$width, height=$height")
                null
            }
        } catch (e: Exception) {
            Timber.e("SimplifiedVideoCacheManager - Error extracting aspect ratio from $uri: $e")
            null
        } finally {
            retriever.release()
        }
    }
}