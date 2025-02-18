package com.fireshare.tweet.widget

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.fireshare.tweet.datamodel.getMimeiKeyFromUrl
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

@UnstableApi
object VideoCacheManager {
    private var simpleCache: SimpleCache? = null

    fun getCache(context: Context): Cache {
        if (simpleCache == null) {
            val cacheDir = File(context.cacheDir, "video_cache")
            val evictor = LeastRecentlyUsedCacheEvictor(1000L * 1024 * 1024)
            val databaseProvider = StandaloneDatabaseProvider(context)
            simpleCache = SimpleCache(cacheDir, evictor, databaseProvider)
        }
        return simpleCache!!
    }

    fun clearOldCachedVideos(context: Context, maxAgeInMillis: Long) {
        val videoCacheDir = File(context.cacheDir, "video_cache")

        if (videoCacheDir.exists() && videoCacheDir.isDirectory) {
            val files = videoCacheDir.listFiles() ?: return
            for (file in files) {
                if (file.isFile && System.currentTimeMillis() - file.lastModified() > maxAgeInMillis) {
                    file.delete()
                }
            }
        }
    }

    suspend fun getVideoDimensions(videoUrl: String): Pair<Int, Int>? {
        return withContext(IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(videoUrl, HashMap())
                val width =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toInt()
                val height =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toInt()
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
}

object VideoDimensionsCache {
    private val dimensionsCache = mutableMapOf<String, Pair<Int, Int>>()

    fun getDimensions(key: String): Pair<Int, Int>? {
        return dimensionsCache[key]
    }

    fun putDimensions(key: String, dimensions: Pair<Int, Int>) {
        dimensionsCache[key] = dimensions
    }
}

fun getCachedVideoDimensions(url: String): Pair<Int, Int>? {
    val key = url.getMimeiKeyFromUrl()
    return VideoDimensionsCache.getDimensions(key)
}

fun cacheVideoDimensions(url: String, dimensions: Pair<Int, Int>) {
    val key = url.getMimeiKeyFromUrl()
    VideoDimensionsCache.putDimensions(key, dimensions)
}
