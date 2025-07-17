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
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class CacheManager(private val context: Context) {

    private val cacheDir: File = File(context.cacheDir, "image_cache")

    /**
     * Generates the file path for a cached preview image.
     * @param imageMid The MimeiId of the image.
     * @return The absolute path of the cached preview image file.
     */
    fun getCachedImagePath(imageMid: String): String {
        val fileName = "${imageMid}_preview.jpg"
        return File(cacheDir, fileName).absolutePath
    }

    /**
     * Loads a preview image from the cache.
     * @param cachedPath The path of the cached image file.
     * @return The loaded ImageBitmap, or null if the file does not exist.
     */
    fun loadImageFromCache(cachedPath: String): ImageBitmap? {
        val file = File(cachedPath)
        val bitmap = if (file.exists() && file.length() > 0) {
            try {
                val bitmap = BitmapFactory.decodeFile(file.path)
                if (bitmap == null) {
                    Timber.tag("CacheManager").w("Failed to decode cached image (null bitmap): $cachedPath")
                    // Remove corrupted file
                    file.delete()
                    return null
                }
                
                if (bitmap.isRecycled) {
                    Timber.tag("CacheManager").w("Attempted to load recycled bitmap from: $cachedPath")
                    bitmap.recycle()
                    return null
                }
                
                bitmap
            } catch (e: Exception) {
                Timber.tag("CacheManager").e("Error decoding cached image: $cachedPath", e)
                // Remove corrupted file
                try {
                    file.delete()
                } catch (deleteException: Exception) {
                    Timber.tag("CacheManager").e("Error deleting corrupted file: $cachedPath", deleteException)
                }
                null
            }
        } else {
            if (file.exists() && file.length() == 0L) {
                Timber.tag("CacheManager").w("Cached file is empty, removing: $cachedPath")
                file.delete()
            }
            null
        }
        return bitmap?.asImageBitmap()
    }

    /**
     * Downloads an image from the given URL and caches it as a compressed preview.
     * Only previews are cached, full-size images are loaded directly from backend.
     * @param imageUrl The URL of the image to download.
     * @param imageMid The MimeiId of the image to use as cache key.
     * @param imageSize The target size of the preview in kilobytes (default 200KB).
     * @return The absolute path of the cached preview image file, or null if an error occurs.
     */
    suspend fun downloadImageToCache(imageUrl: String, imageSize: Int = 200): String? {
        val imageMid = imageUrl.getMimeiKeyFromUrl()
        return withContext(Dispatchers.IO) {
            try {
                // Ensure cache directory exists
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                    Timber.tag("CacheManager").d("Created cache directory: ${cacheDir.absolutePath}")
                }

                val imageLoader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .memoryCachePolicy(CachePolicy.READ_ONLY)
                    .diskCachePolicy(CachePolicy.DISABLED) // Disable Coil's disk cache since we handle it manually
                    .error(R.drawable.ic_user_avatar) // Fallback image on error
                    .fallback(R.drawable.ic_user_avatar) // Fallback image when data is null
                    .build()
                
                val result = try {
                    (imageLoader.execute(request) as? SuccessResult)?.drawable
                } catch (e: Exception) {
                    Timber.tag("CacheManager").e("Error loading image with Coil: $imageUrl", e)
                    null
                }

                if (result == null) {
                    Timber.tag("CacheManager").w("Failed to load image from URL: $imageUrl")
                    return@withContext null
                }

                val fileName = "${imageMid}_preview.jpg"
                val cacheFile = File(cacheDir, fileName)

                val drawable = result
                val bitmap = (drawable as? BitmapDrawable)?.bitmap
                
                if (bitmap == null) {
                    Timber.tag("CacheManager").w("Failed to get bitmap from drawable for: $imageUrl")
                    return@withContext null
                }
                
                // Check if bitmap is valid
                if (bitmap.isRecycled) {
                    Timber.tag("CacheManager").w("Attempted to cache recycled bitmap from: $imageUrl")
                    return@withContext null
                }
                
                // Compress the image to target size
                var quality = 100
                var fileSize: Long = 0L
                val targetSize = imageSize * 1024
                var compressionSuccess = false
                var shouldContinue = true
                while (shouldContinue) {
                    try {
                        var compressResult = false
                        FileOutputStream(cacheFile).use { out ->
                            compressResult = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                        }
                        
                        if (!compressResult) {
                            Timber.tag("CacheManager").w("Failed to compress bitmap with quality $quality")
                            shouldContinue = false
                            continue
                        }
                        
                        fileSize = cacheFile.length()
                        // Validate that file was actually written
                        if (fileSize == 0L) {
                            Timber.tag("CacheManager").w("Compressed file is empty, quality: $quality")
                            shouldContinue = false
                            continue
                        }
                        compressionSuccess = true
                        // Check if we've reached target size
                        if (fileSize <= targetSize || quality <= 0) {
                            shouldContinue = false
                            continue
                        }
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
                    } catch (e: Exception) {
                        Timber.tag("CacheManager").e("Error during compression: $e")
                        shouldContinue = false
                    }
                }
                
                if (!compressionSuccess) {
                    Timber.tag("CacheManager").e("Failed to compress and save image: $imageUrl")
                    // Clean up failed file
                    if (cacheFile.exists()) {
                        cacheFile.delete()
                    }
                    return@withContext null
                }
                
                // Validate the saved file can be read back
                try {
                    val testBitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                    if (testBitmap == null) {
                        Timber.tag("CacheManager").e("Saved file cannot be decoded: ${cacheFile.absolutePath}")
                        cacheFile.delete()
                        return@withContext null
                    }
                    testBitmap.recycle() // Clean up test bitmap
                } catch (e: Exception) {
                    Timber.tag("CacheManager").e("Error validating saved file: $e")
                    cacheFile.delete()
                    return@withContext null
                }
                
                Timber.tag("CacheManager").d("Successfully cached preview: ${cacheFile.name}, size: ${fileSize / 1024}KB, quality: $quality")
                cacheFile.absolutePath
                
            } catch (e: Exception) {
                Timber.tag("CacheManager").e("Error downloading image: $e")
                e.printStackTrace()
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
     * Validates if an image URL is accessible and returns a valid image
     */
    suspend fun validateImageUrl(imageUrl: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val imageLoader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .memoryCachePolicy(CachePolicy.READ_ONLY)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build()
                
                val result = imageLoader.execute(request)
                result is SuccessResult
            } catch (e: Exception) {
                Timber.tag("CacheManager").e("Error validating image URL: $imageUrl", e)
                false
            }
        }
    }

    /**
     * Safely loads an image with retry logic and fallback handling
     */
    suspend fun loadImageSafely(imageUrl: String, maxRetries: Int = 2): ImageBitmap? {
        repeat(maxRetries) { attempt ->
            try {
                val imageLoader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .memoryCachePolicy(CachePolicy.READ_ONLY)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .error(R.drawable.ic_user_avatar)
                    .fallback(R.drawable.ic_user_avatar)
                    .build()
                
                val result = imageLoader.execute(request)
                if (result is SuccessResult) {
                    val drawable = result.drawable
                    val bitmap = (drawable as? BitmapDrawable)?.bitmap
                    
                    if (bitmap != null && !bitmap.isRecycled) {
                        return bitmap.asImageBitmap()
                    }
                }
                
                if (attempt < maxRetries - 1) {
                    Timber.tag("CacheManager").w("Image load attempt ${attempt + 1} failed for: $imageUrl, retrying...")
                    kotlinx.coroutines.delay(1000) // Wait 1 second before retry
                }
            } catch (e: Exception) {
                Timber.tag("CacheManager").e("Error loading image (attempt ${attempt + 1}): $imageUrl", e)
                if (attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(1000) // Wait 1 second before retry
                }
            }
        }
        
        Timber.tag("CacheManager").w("Failed to load image after $maxRetries attempts: $imageUrl")
        return null
    }

    /**
     * Validates and cleans up corrupted cache files
     */
    fun validateAndCleanCache() {
        if (!cacheDir.exists() || !cacheDir.isDirectory) {
            return
        }
        
        val files = cacheDir.listFiles() ?: return
        var corruptedCount = 0
        var emptyCount = 0
        
        for (file in files) {
            if (!file.isFile) continue
            
            try {
                if (file.length() == 0L) {
                    file.delete()
                    emptyCount++
                    continue
                }
                
                // Try to decode the file to check if it's valid
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap == null) {
                    file.delete()
                    corruptedCount++
                } else {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                Timber.tag("CacheManager").w("Error validating cache file ${file.name}: $e")
                file.delete()
                corruptedCount++
            }
        }
        
        if (corruptedCount > 0 || emptyCount > 0) {
            Timber.tag("CacheManager").d("Cache cleanup: removed $corruptedCount corrupted files, $emptyCount empty files")
        }
    }

    /**
     * Checks if a preview image is already cached.
     * @param imageMid The MimeiId of the image.
     * @return True if the preview is cached, false otherwise.
     */
    fun isCached(imageMid: String): Boolean {
        val cachedPath = getCachedImagePath(imageMid)
        val file = File(cachedPath)
        return file.exists()
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
                    .error(R.drawable.ic_user_avatar) // Fallback image on error
                    .fallback(R.drawable.ic_user_avatar) // Fallback image when data is null
                    .build()
                
                val result = try {
                    (imageLoader.execute(request) as? SuccessResult)?.drawable
                } catch (e: Exception) {
                    Timber.tag("CacheManager").e("Error loading full-size image with Coil: $imageUrl", e)
                    null
                }

                result?.let { drawable ->
                    val bitmap = (drawable as BitmapDrawable).bitmap
                    
                    // Check if bitmap is valid
                    if (bitmap.isRecycled) {
                        Timber.tag("CacheManager").w("Attempted to load recycled bitmap from: $imageUrl")
                        return@withContext null
                    }
                    
                    bitmap.asImageBitmap()
                }
            } catch (e: Exception) {
                Timber.tag("CacheManager").e("Error loading full-size image: $e")
                null
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
            val bitmap = try {
                BitmapFactory.decodeStream(inputStream)?.also { bitmap ->
                    if (bitmap.isRecycled) {
                        Timber.tag("CacheManager").w("Attempted to load recycled bitmap from stream")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Download failed: Invalid image data", Toast.LENGTH_SHORT).show()
                        }
                        return@withContext
                    }
                }
            } catch (e: Exception) {
                Timber.tag("CacheManager").e("Error decoding image stream", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download failed: Invalid image format", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }

            if (bitmap == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download failed: Could not decode image", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }

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
            Timber.tag("CacheManager").e("Error downloading image: $imageUrl", e)
            withContext(Dispatchers.Main) {
                // Show an error message
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
