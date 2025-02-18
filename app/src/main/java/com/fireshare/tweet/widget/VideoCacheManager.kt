package com.fireshare.tweet.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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
}

/**
 * Allow app users to download Tweet image to local photo directory.
 * */
suspend fun downloadImage(context: Context, imageUrl: String) {
    withContext(Dispatchers.IO) {
        try {
            val url = URL(imageUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connect()

            val inputStream = connection.inputStream
            val bitmap = BitmapFactory.decodeStream(inputStream)

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, "image_${System.currentTimeMillis()}.jpg")

            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)

            outputStream.flush()
            outputStream.close()

            withContext(Dispatchers.Main) {
                // Show a toast or notification indicating download success
                Toast.makeText(context, "Image downloaded to ${file.absolutePath}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                // Show an error message
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
