package com.fireshare.tweet.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.widget.Gadget.detectMimeTypeFromHeader
import com.fireshare.tweet.widget.Gadget.downloadFileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream

@Serializable
data class MediaItem( val url: String )

@Composable
fun MediaPreviewGrid(mediaItems: List<MediaItem>, containerWidth: Dp = 400.dp) {
    val navController = LocalNavController.current
    val gridCells = if (mediaItems.size>1) 2 else 1
    val maxItems = when(mediaItems.size) {
        1 -> 1
        in 2..3 -> 2
        else -> 4
    }
    val limitedMediaList = mediaItems.take(maxItems)

    LazyVerticalGrid(
        columns = GridCells.Fixed(gridCells),
    ) {
        items(limitedMediaList) { mediaItem ->
            MediaItemPreview(mediaItem,
                Modifier.size(containerWidth/gridCells)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        val index = mediaItems.indexOf(mediaItem)
                        navController.navigate(NavTweet.MediaViewer(
                            mediaItems.map {it.url}, index))
                    },
                isLastItem = mediaItem == limitedMediaList.last() && mediaItems.size > maxItems
            )
        }
    }
}

@Composable
fun MediaItemPreview(mediaItem: MediaItem, modifier: Modifier = Modifier, isLastItem: Boolean = false) {
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
                ImagePreview(mediaItem.url, modifier)
            }
            "video/mp4" -> {
                // Implement video player here
                VideoPreview(url = mediaItem.url, modifier)
            }
            "audio/mpeg", "audio/ogg", "audio/flac", "audio/wav" ->  {
                // Implement audio player here
                VideoPreview(url = mediaItem.url, modifier)
            }
            else -> {
                // Handle unknown file type
                Log.d("MediaItemPreview", "unknown file type ${fileType.value} ${mediaItem.url}")
            }
        }
        if (isLastItem) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0x40FFFFFF)), // Lighter shaded background
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(100.dp)
                        .alpha(0.7f)
                )
            }
        }
    }
}

@Composable
fun ImagePreview(imageUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cachedMid = imageUrl.substringAfterLast('/') + "_preview"

    // In-memory cache for image URLs and their cached paths
    val cachedImageUrls = remember { mutableStateMapOf<String, String>() }
    val cachedPath = cachedImageUrls[cachedMid]

    // Check if image is already cached and use it directly
    if (cachedPath != null) {
        Box(contentAlignment = Alignment.Center) {
            loadImageFromCache(cachedPath)?.let {
                Image(
                    painter = BitmapPainter(it),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = modifier
//                        .size(200.dp)
//                        .clip(RoundedCornerShape(4.dp))
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
                    cachedImageUrls[cachedMid] = downloadedPath
                }
            }
        }
        // Display placeholder while image is downloading
        Box(contentAlignment = Alignment.Center,
            modifier = modifier) {
            CircularProgressIndicator()
        }
    }
}

@Composable
fun VideoPreview(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = ExoPlayer.Builder(context).build().apply {
        setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.parse(url)))
        prepare()
    }

    AndroidView(
        factory = { PlayerView(context).apply { player = exoPlayer } },
        modifier = modifier
    )
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
            val fileName = imageUrl.substringAfterLast('/') + "_preview.jpg" // Use JPEG for better compression
            val cacheFile = File(cacheDir, fileName)

            result?.let { drawable ->
                val bitmap = (drawable as BitmapDrawable).bitmap
                var quality = 80
                var fileSize: Long

                do {
                    FileOutputStream(cacheFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    }
                    fileSize = cacheFile.length()
                    quality -= 10
                } while (fileSize > 200 * 1024 && quality > 0) // 200KB = 200 * 1024 bytes
            }

            cacheFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

suspend fun downloadFullImageToCache(context: Context, imageUrl: String): String? {
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
            val fileName = imageUrl.substringAfterLast('/') + ".png"
            val cacheFile = File(cacheDir, fileName)

            result?.let { drawable ->
                val bitmap = (drawable as BitmapDrawable).bitmap
                FileOutputStream(cacheFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) // Use PNG format to avoid compression
                }
            }

            cacheFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}