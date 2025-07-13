package us.fireshare.tweet.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Environment
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class CacheManager(private val context: Context) {

    private val cacheDir: File = File(context.cacheDir, "image_cache")

    /**
     * Generates the file path for a cached preview image.
     * @param imageUrl The URL of the image. http//ip/mm/mimeiId
     * @return The absolute path of the cached preview image file.
     */
    fun getCachedImagePath(imageUrl: String): String {
        val fileName = imageUrl.getMimeiKeyFromUrl() + "_preview.jpg"
        return File(cacheDir, fileName).absolutePath
    }

    /**
     * Loads a preview image from the cache.
     * @param cachedPath The path of the cached image file.
     * @return The loaded ImageBitmap, or null if the file does not exist.
     */
    fun loadImageFromCache(cachedPath: String): ImageBitmap? {
        val file = File(cachedPath)
        val bitmap = if (file.exists()) {
            BitmapFactory.decodeFile(file.path)
        } else {
            null
        }
        return bitmap?.asImageBitmap()
    }

    /**
     * Downloads an image from the given URL and caches it as a compressed preview.
     * Only previews are cached, full-size images are loaded directly from backend.
     * @param imageUrl The URL of the image to download.
     * @param imageSize The target size of the preview in kilobytes (default 200KB).
     * @return The absolute path of the cached preview image file, or null if an error occurs.
     */
    suspend fun downloadImageToCache(imageUrl: String, imageSize: Int = 200): String? {
        return withContext(Dispatchers.IO) {
            try {
                val imageLoader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .memoryCachePolicy(CachePolicy.READ_ONLY)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
                val result = (imageLoader.execute(request) as? SuccessResult)?.drawable

                val fileName = imageUrl.getMimeiKeyFromUrl() + "_preview.jpg"
                val cacheFile = File(cacheDir, fileName)

                result?.let { drawable ->
                    val bitmap = (drawable as BitmapDrawable).bitmap
                    
                    // Compress the image to target size
                    var quality = 100
                    var fileSize: Long
                    val targetSize = imageSize * 1024
                    do {
                        FileOutputStream(cacheFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                        }
                        fileSize = cacheFile.length()

                        // Calculate the difference between current file size and target size
                        val sizeDifference = fileSize - targetSize

                        // Adjust quality reduction rate based on the size difference
                        quality -= when {
                            sizeDifference > targetSize * 0.5 -> 20 // Reduce quality faster when far from target
                            sizeDifference > targetSize * 0.2 -> 10 // Moderate reduction
                            else -> 5 // Slow down reduction as we approach the target size
                        }

                        // Ensure quality does not go below 0
                        if (quality < 0) quality = 0

                    } while (fileSize > targetSize && quality > 0)
                    
                    Timber.tag("CacheManager").d("Cached preview: ${cacheFile.name}, size: ${fileSize / 1024}KB, quality: $quality")
                }

                cacheFile.absolutePath
            } catch (e: Exception) {
                Timber.tag("CacheManager").e("Error downloading image: $e")
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Loads a full-size image directly from the backend without caching.
     * This method is used when displaying full-size images.
     * @param imageUrl The URL of the image to load.
     * @return The loaded ImageBitmap, or null if an error occurs.
     */
    suspend fun loadFullSizeImage(imageUrl: String): ImageBitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val imageLoader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .memoryCachePolicy(CachePolicy.READ_ONLY)
                    .diskCachePolicy(CachePolicy.DISABLED) // Don't cache full-size images
                    .build()
                val result = (imageLoader.execute(request) as? SuccessResult)?.drawable

                result?.let { drawable ->
                    val bitmap = (drawable as BitmapDrawable).bitmap
                    bitmap.asImageBitmap()
                }
            } catch (e: Exception) {
                Timber.tag("CacheManager").e("Error loading full-size image: $e")
                null
            }
        }
    }

    fun clearOldCachedImages(maxAgeInMillis: Long) {
        if (cacheDir.exists() && cacheDir.isDirectory) {
            val files = cacheDir.listFiles() ?: return
            for (file in files) {
                if (file.isFile && System.currentTimeMillis() - file.lastModified() > maxAgeInMillis) {
                    file.delete()
                }
            }
        }
    }

    /**
     * Checks if a preview image is already cached.
     * @param imageUrl The URL of the image.
     * @return True if the preview is cached, false otherwise.
     */
    fun isCached(imageUrl: String): Boolean {
        val cachedPath = getCachedImagePath(imageUrl)
        val file = File(cachedPath)
        return file.exists()
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
