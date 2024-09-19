package com.fireshare.tweet.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class CacheManager(private val context: Context) {

    // Directory where cached images will be stored
    private val cacheDir: File = context.cacheDir

    /**
     * Generates the file path for a cached image based on the image URL and whether it's a preview.
     * @param imageUrl The URL of the image.
     * @param isPreview Boolean indicating if the image is a preview.
     * @return The absolute path of the cached image file.
     */
    fun getCachedImagePath(imageUrl: String, isPreview: Boolean = true): String {
        val fileName = if (isPreview) {
            imageUrl.substringAfterLast('/') + "_preview.jpg"
        } else {
            imageUrl.substringAfterLast('/') + ".png"
        }
        return File(cacheDir, fileName).absolutePath
    }

    /**
     * Loads an image from the cache.
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
     * Downloads an image from the given URL and caches it.
     * @param imageUrl The URL of the image to download.
     * @param isPreview Boolean indicating if the image is a preview.
     * @param imageSize The target size of the image in kilobytes (for previews).
     * @return The absolute path of the cached image file, or null if an error occurs.
     */
    suspend fun downloadImageToCache(imageUrl: String, isPreview: Boolean = true, imageSize: Int): String? {
        return withContext(Dispatchers.IO) {
            try {
                val imageLoader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .memoryCachePolicy(CachePolicy.READ_ONLY)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
                val result = (imageLoader.execute(request) as? SuccessResult)?.drawable

                val fileName = if (isPreview) {
                    imageUrl.substringAfterLast('/') + "_preview.jpg"
                } else {
                    imageUrl.substringAfterLast('/') + ".png"
                }
                val cacheFile = File(cacheDir, fileName)

                result?.let { drawable ->
                    val bitmap = (drawable as BitmapDrawable).bitmap
                    if (isPreview) {
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
                    } else {
                        FileOutputStream(cacheFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                }

                cacheFile.absolutePath
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}