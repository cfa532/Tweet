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
     * Check if device has network connectivity
     */
    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return activeNetwork.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               activeNetwork.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

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
     * Creates an ExoPlayer that handles video data blobs with HLS fallback
     * Uses ExoPlayer's built-in caching with improved fallback mechanism
     */
    fun createExoPlayer(context: Context, url: String): ExoPlayer {
        val cache = getCache(context)
        val dataSourceFactory = DefaultDataSource.Factory(context)
        
        // Configure cache strategy based on network availability
        val isOnline = isNetworkAvailable(context)
        val cacheFlags = if (isOnline) {
            CacheDataSource.FLAG_BLOCK_ON_CACHE
        } else {
            // When offline, only use cache, don't try network
            CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
        }
        
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(dataSourceFactory)
            .setFlags(cacheFlags)

        // Use IPFS ID as cache key for all video types
        val ipfsId = url.getMimeiKeyFromUrl()

        // For data blobs, try HLS first, then fallback to original URL
        val baseUrl = if (url.endsWith("/")) url else "$url/"
        val masterUrl = "${baseUrl}master.m3u8"
        val playlistUrl = "${baseUrl}playlist.m3u8"

        Timber.d("SimplifiedVideoCacheManager - Original URL: $url")
        Timber.d("SimplifiedVideoCacheManager - Base URL: $baseUrl")
        Timber.d("SimplifiedVideoCacheManager - Master URL: $masterUrl")
        Timber.d("SimplifiedVideoCacheManager - Playlist URL: $playlistUrl")

        val exoPlayer = ExoPlayer.Builder(context)
            .build()

        // Add listener for video events with HLS fallback mechanism (no retries)
        exoPlayer.addListener(object : Player.Listener {
            private var hasTriedPlaylist = false
            private var hasTriedOriginal = false

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
                Timber.e(error, "Video player error for URL: $url")
                Timber.e("SimplifiedVideoCacheManager - Error cause: ${error.cause}")
                Timber.e("SimplifiedVideoCacheManager - Error code: ${error.errorCode}")
                Timber.e("SimplifiedVideoCacheManager - Has tried playlist: $hasTriedPlaylist")
                Timber.e("SimplifiedVideoCacheManager - Has tried original: $hasTriedOriginal")
                Timber.e("SimplifiedVideoCacheManager - Network available: ${isNetworkAvailable(context)}")

                // If offline, try to use cached data only
                if (!isNetworkAvailable(context)) {
                    Timber.d("SimplifiedVideoCacheManager - Offline mode detected, trying cached data only")
                    tryCachedDataOnly()
                    return
                }

                // HLS fallback sequence: master.m3u8 -> playlist.m3u8 -> original URL
                // No retries, just try each format once
                if (!hasTriedPlaylist) {
                    hasTriedPlaylist = true
                    Timber.d("SimplifiedVideoCacheManager - Trying fallback to playlist URL: $playlistUrl")

                    try {
                        // If master.m3u8 fails, try playlist.m3u8
                        val fallbackMediaItem = MediaItem.Builder()
                            .setUri(playlistUrl)
                            .setCustomCacheKey(ipfsId)
                            .build()

                        val fallbackMediaSource =
                            DefaultMediaSourceFactory(cacheDataSourceFactory)
                                .createMediaSource(fallbackMediaItem)

                        exoPlayer.setMediaSource(fallbackMediaSource)
                        exoPlayer.prepare()
                        Timber.d("SimplifiedVideoCacheManager - Successfully set fallback media source for: $playlistUrl")
                    } catch (e: Exception) {
                        Timber.e("SimplifiedVideoCacheManager - Error setting fallback media source. ${e.message}")
                        // If fallback fails, try original URL immediately
                        hasTriedOriginal = true
                        tryOriginalUrl()
                    }
                } else if (!hasTriedOriginal) {
                    hasTriedOriginal = true
                    tryOriginalUrl()
                } else {
                    // All fallback attempts failed, stop the player to prevent memory leaks
                    Timber.e("SimplifiedVideoCacheManager - All fallback attempts failed for URL: $url")
                    Timber.e("SimplifiedVideoCacheManager - Video playback failed after trying HLS and original URL")
                    exoPlayer.stop()
                }
            }

            private fun tryCachedDataOnly() {
                Timber.d("SimplifiedVideoCacheManager - Trying cached data only for URL: $url")
                
                try {
                    // Create a cache-only data source factory
                    val cacheOnlyDataSourceFactory = CacheDataSource.Factory()
                        .setCache(cache)
                        .setUpstreamDataSourceFactory(dataSourceFactory)
                        .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
                    
                    // Try original URL first (most likely to be cached)
                    val originalMediaItem = MediaItem.Builder()
                        .setUri(url)
                        .setCustomCacheKey(ipfsId)
                        .build()

                    val originalMediaSource = DefaultMediaSourceFactory(cacheOnlyDataSourceFactory)
                        .createMediaSource(originalMediaItem)

                    exoPlayer.setMediaSource(originalMediaSource)
                    exoPlayer.prepare()
                    Timber.d("SimplifiedVideoCacheManager - Successfully set cached-only media source for: $url")
                } catch (e: Exception) {
                    Timber.e("SimplifiedVideoCacheManager - Error setting cached-only media source. ${e.message}")
                    // If cached data fails, stop the player
                    exoPlayer.stop()
                    Timber.e("SimplifiedVideoCacheManager - Cached data not available for URL: $url")
                }
            }

            private fun tryOriginalUrl() {
                Timber.d("SimplifiedVideoCacheManager - Trying original URL as last resort: $url")

                try {
                    // If both HLS attempts fail, try the original URL (progressive video)
                    val originalMediaItem = MediaItem.Builder()
                        .setUri(url)
                        .setCustomCacheKey(ipfsId)
                        .build()

                    val originalMediaSource = DefaultMediaSourceFactory(cacheDataSourceFactory)
                        .createMediaSource(originalMediaItem)

                    exoPlayer.setMediaSource(originalMediaSource)
                    exoPlayer.prepare()
                    Timber.d("SimplifiedVideoCacheManager - Successfully set original media source for: $url")
                } catch (e: Exception) {
                    Timber.e("SimplifiedVideoCacheManager - Error setting original media source. ${e.message}")
                    // If all attempts fail, stop the player to prevent memory leaks
                    exoPlayer.stop()
                    Timber.e("SimplifiedVideoCacheManager - All fallback attempts failed for URL: $url")
                }
            }
        })

        // Always start with HLS (master.m3u8) first, then fallback to playlist.m3u8, then original URL
        val initialMediaItem = MediaItem.Builder()
            .setUri(masterUrl)
            .setCustomCacheKey(ipfsId)
            .build()

        val initialMediaSource = DefaultMediaSourceFactory(cacheDataSourceFactory)
            .createMediaSource(initialMediaItem)
            .also { Timber.d("SimplifiedVideoCacheManager - Created MediaSource for HLS URL: $masterUrl") }

        return exoPlayer.apply {
            setMediaSource(initialMediaSource)
            prepare()
        }
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
                    val connection = java.net.URL(uri.toString()).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 10000 // 10 seconds
                    connection.readTimeout = 30000 // 30 seconds
                    connection.requestMethod = "GET"
                    
                    val tempFile = java.io.File.createTempFile("aspect_ratio_", ".tmp", context.cacheDir)
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
            
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloat()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloat()

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