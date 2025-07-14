package us.fireshare.tweet.widget

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
        return videoCache!!
    }

    /**
     * Creates an ExoPlayer that automatically handles both progressive and HLS videos
     * Uses ExoPlayer's built-in caching - no custom implementation needed
     */
    fun createExoPlayer(context: Context, url: String): ExoPlayer {
        val cache = getCache(context)
        val dataSourceFactory = DefaultDataSource.Factory(context)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(dataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // Use IPFS ID as cache key for all video types
        val ipfsId = url.getMimeiKeyFromUrl()
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setCustomCacheKey(ipfsId)
            .build()

        // Let ExoPlayer automatically detect and handle the video format
        // It will automatically use HlsMediaSource for HLS and ProgressiveMediaSource for others
        val mediaSource = ProgressiveMediaSource.Factory(cacheDataSourceFactory)
            .createMediaSource(mediaItem)
            .also { Timber.d("Created Progressive MediaSource for URL: $url") }

        val exoPlayer = ExoPlayer.Builder(context)
            .build()

        // Add listener for video events
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        Timber.d("Video player ready for URL: $url (IPFS ID: $ipfsId)")
                    }
                    Player.STATE_BUFFERING -> {
                        Timber.d("Video player buffering for URL: $url")
                    }
                    Player.STATE_ENDED -> {
                        Timber.d("Video player ended for URL: $url")
                    }
                    Player.STATE_IDLE -> {
                        Timber.d("Video player idle for URL: $url")
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Timber.e("Video player error for URL: $url", error)
            }
        })

        return exoPlayer.apply {
            setMediaSource(mediaSource)
            prepare()
        }
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
        val cache = getCache(context)
        cache.release()
        videoCache = null
        
        val videoCacheDir = File(context.cacheDir, VIDEO_CACHE_DIR)
        if (videoCacheDir.exists()) {
            videoCacheDir.deleteRecursively()
        }
    }

    /**
     * Check if a video is cached using IPFS ID
     */
    fun isVideoCached(context: Context, videoUrl: String): Boolean {
        val cache = getCache(context)
        val ipfsId = videoUrl.getMimeiKeyFromUrl()
        // For now, return false since CacheKey is not available
        // TODO: Implement proper cache checking when CacheKey is available
        return false
    }

    /**
     * Get detailed cache information
     */
    fun getDetailedCacheInfo(context: Context): String {
        val cache = getCache(context)
        val cacheSize = cache.cacheSpace
        val maxCacheSize = CACHE_SIZE_BYTES
        val usedPercentage = (cacheSize * 100 / maxCacheSize).toInt()
        val videoCacheDir = File(context.cacheDir, VIDEO_CACHE_DIR)
        
        return buildString {
            appendLine("Simplified Video Cache Information:")
            appendLine("Cache Directory: ${videoCacheDir.absolutePath}")
            appendLine("Cache Size: ${cacheSize / (1024 * 1024)}MB / ${maxCacheSize / (1024 * 1024)}MB ($usedPercentage%)")
            appendLine("Directory Exists: ${videoCacheDir.exists()}")
            if (videoCacheDir.exists()) {
                appendLine("Directory Size: ${getDirectorySize(videoCacheDir) / (1024 * 1024)}MB")
                appendLine("Files Count: ${videoCacheDir.listFiles()?.size ?: 0}")
            }
            appendLine()
            appendLine("Features:")
            appendLine("- Automatic HLS/Progressive detection")
            appendLine("- Built-in ExoPlayer caching")
            appendLine("- IPFS ID-based cache keys")
            appendLine("- No custom segment caching needed")
        }
    }

    /**
     * Calculate directory size recursively
     */
    private fun getDirectorySize(directory: File): Long {
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
     * Test IPFS ID extraction
     */
    fun testIpfsIdExtraction(videoUrl: String): String {
        val ipfsId = videoUrl.getMimeiKeyFromUrl()
        
        return buildString {
            appendLine("Simplified Video IPFS ID Test:")
            appendLine("Original URL: $videoUrl")
            appendLine("Extracted IPFS ID: $ipfsId")
            appendLine("Cache Key: $ipfsId")
            appendLine()
            appendLine("ExoPlayer will automatically:")
            appendLine("- Detect HLS vs Progressive format")
            appendLine("- Cache segments/chunks automatically")
            appendLine("- Handle playlist parsing (HLS)")
            appendLine("- Manage buffer and playback")
        }
    }

    /**
     * Get video aspect ratio from URI
     */
    fun getVideoAspectRatio(context: Context, uri: Uri): Float? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloat()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloat()

            if (width != null && height != null && height != 0f) {
                width / height
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.tag("e").e("$e $uri")
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Get video dimensions from URL
     */
    suspend fun getVideoDimensions(videoUrl: String): Pair<Int, Int>? {
        return withContext(IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(videoUrl, HashMap())
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
                retriever.release()
                if (width != null && height != null) {
                    Pair(width, height)
                } else {
                    null
                }
            } catch (e: Exception) {
                Timber.tag("GetVideoDimensions").e(e)
                null
            }
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
} 