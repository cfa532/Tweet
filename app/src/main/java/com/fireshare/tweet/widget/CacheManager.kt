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

    private val cacheDir: File = context.cacheDir

    fun getCachedImagePath(imageUrl: String, isPreview: Boolean = true): String {
        val fileName = if (isPreview) {
            imageUrl.substringAfterLast('/') + "_preview.jpg"
        } else {
            imageUrl.substringAfterLast('/') + ".png"
        }
        return File(cacheDir, fileName).absolutePath
    }

    fun loadImageFromCache(cachedPath: String): ImageBitmap? {
        val file = File(cachedPath)
        val bitmap = if (file.exists()) {
            BitmapFactory.decodeFile(file.path)
        } else {
            null
        }
        return bitmap?.asImageBitmap()
    }

    suspend fun downloadImageToCache(imageUrl: String, isPreview: Boolean = true, imageSize: Int = 200): String? {
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