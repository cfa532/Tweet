package com.fireshare.tweet.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.fireshare.tweet.network.Gadget.detectMimeTypeFromHeader
import com.fireshare.tweet.network.Gadget.downloadFileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.request.SuccessResult

//sealed class MediaItem {
//    data class Image(val url: String) : MediaItem()
//    data class Video(val thumbnailUrl: String) : MediaItem()
//    data object Audio : MediaItem()
//}
data class MediaItem( val url: String )

@Composable
fun MediaPreviewGrid(mediaItems: List<MediaItem?>) {
    val maxItems = 4 // 2 rows * 2 columns = 4 items
    val limitedMediaList = mediaItems.take(maxItems)

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.padding(0.dp, 8.dp, 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        items(limitedMediaList) { mediaItem ->
            if (mediaItem != null) {
                MediaItemPreview(mediaItem)
            }
        }
    }
}

@Composable
fun ImagePreview(imageUrl: String) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // In-memory cache for image URLs and their cached paths
    val cachedImageUrls = remember { mutableStateMapOf<String, String>() }

    val cachedPath = cachedImageUrls[imageUrl]

    // Check if image is already cached and use it directly
    if (cachedPath != null) {
        Box(contentAlignment = Alignment.Center) {
            loadImageFromCache(cachedPath)?.let {
                Image(
                    painter = BitmapPainter(it),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        }
    } else {
        // Download and cache image if not already cached
        LaunchedEffect(imageUrl) {
            coroutineScope.launch {
                val downloadedPath = try {
                    withContext(Dispatchers.IO) {
                        downloadImageToCache(context, imageUrl) // New function
                    }
                } catch (e: Exception) {
                    // Handle download error gracefully (e.g., log error, show placeholder image)
                    null
                }
                if (downloadedPath != null) {
                    cachedImageUrls[imageUrl] = downloadedPath
                }
            }
        }
        // Display placeholder while image is downloading
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

// Function to load image from cache
fun loadImageFromCache(cachedPath: String): ImageBitmap? {
    val file = File(cachedPath) // Use the absolute path directly
    val bitmap = if (file.exists()) {
        BitmapFactory.decodeFile(file.path)
    } else {
        null
    }
    return bitmap?.asImageBitmap()
}

// Function to download image to cache
suspend fun downloadImageToCache(context: Context, imageUrl: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val imageLoader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .memoryCachePolicy(CachePolicy.READ_ONLY)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
            val result = (imageLoader.execute(request) as? SuccessResult)?.drawable

            // Save the drawable to a file in the cache directory
            val cacheDir = context.cacheDir
            val fileName = hashString("SHA-256", imageUrl) + ".png"
            val cacheFile = File(cacheDir, fileName)

            result?.let { drawable ->
                val bitmap = (drawable as BitmapDrawable).bitmap
                FileOutputStream(cacheFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 50, out)
                }
            }

            cacheFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
fun hashString(type: String, input: String): String {
    val bytes = MessageDigest.getInstance(type).digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

@Composable
fun MediaItemPreview(mediaItem: MediaItem) {
    val fileType = remember(mediaItem.url) {
        mutableStateOf<String?>(null)
    }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(mediaItem.url) {
        coroutineScope.launch {
            val header = withContext(Dispatchers.IO) {
                downloadFileHeader(mediaItem.url)
            }
            val detectedFileType = detectMimeTypeFromHeader(header)
            fileType.value = detectedFileType
        }
    }

    Box(
        contentAlignment = Alignment.Center
    ) {
        when (fileType.value) {
            "image/jpeg", "image/png" -> {
                ImagePreview(mediaItem.url)
            }
            "video/mp4" -> {
                // Implement video player here
                VideoPreview(url = mediaItem.url)
            }
            "audio/mpeg", "audio/ogg", "audio/flac", "audio/wav" ->  {
                // Implement audio player here
                VideoPreview(url = mediaItem.url)
            }
            else -> {
                // Handle unknown file type
                println("unknown file type ${fileType.value}")
            }
        }
    }
}

@Composable
fun VideoPreview(url: String) {
    val context = LocalContext.current
    val exoPlayer = ExoPlayer.Builder(context).build().apply {
        setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.parse(url)))
        prepare()
    }

    AndroidView(
        factory = { PlayerView(context).apply { player = exoPlayer } },
        modifier = Modifier
            .size(200.dp)
            .clip(RoundedCornerShape(8.dp))
    )
}
