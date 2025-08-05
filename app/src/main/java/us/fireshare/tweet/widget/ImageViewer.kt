package us.fireshare.tweet.widget

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import timber.log.Timber
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl
import java.io.File

/**
 * Helper function to save image and show toast
 */
private fun saveImageToGallery(context: android.content.Context, bitmap: android.graphics.Bitmap): Boolean {
    return try {
        val filename = "image_${System.currentTimeMillis()}.jpg"
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Tweet")
        }
        
        val uri = context.contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )
        
        uri?.let { imageUri ->
            context.contentResolver.openOutputStream(imageUri)?.use { outputStream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, outputStream)
            }
        }
        true
    } catch (e: Exception) {
        Timber.e("Failed to save image: $e")
        false
    }
}

/**
 * State object for image loading to reduce recomposition
 */
data class ImageLoadState(
    val bitmap: android.graphics.Bitmap? = null,
    val isLoading: Boolean = false,
    val hasError: Boolean = false
)

/**
 * Advanced ImageViewer using SubsamplingScaleImageView for efficient large image handling
 * Supports local cached images and loads original high-resolution images for full-screen viewing
 */
@Composable
fun AdvancedImageViewer(
    imageUrl: String,
    modifier: Modifier = Modifier,
    enableLongPress: Boolean = true,
    onClose: (() -> Unit)? = null,
    onLoadComplete: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val mid = remember(imageUrl) { imageUrl.getMimeiKeyFromUrl() }
    var loadState by remember(mid) { mutableStateOf(ImageLoadState()) }
    var imageFile by remember { mutableStateOf<File?>(null) }

    // Load image using ImageCacheManager
    LaunchedEffect(mid, imageUrl) {
        try {
            loadState = loadState.copy(isLoading = true, hasError = false)
            
            // Try to load from cache first
            val cachedBitmap = ImageCacheManager.getCachedImage(context, mid)
            
            if (cachedBitmap == null) {
                // If not cached, download and cache
                val downloadedBitmap = ImageCacheManager.loadImage(context, imageUrl, mid)
                
                if (downloadedBitmap == null) {
                    loadState = loadState.copy(isLoading = false, hasError = true)
                    Timber.tag("AdvancedImageViewer").e("Failed to load image: $imageUrl")
                } else {
                    loadState = loadState.copy(bitmap = downloadedBitmap, isLoading = false)
                    onLoadComplete?.invoke()
                }
            } else {
                loadState = loadState.copy(bitmap = cachedBitmap, isLoading = false)
                onLoadComplete?.invoke()
            }
            
            // Get the cached file for SubsamplingScaleImageView
            val cachedFile = ImageCacheManager.getCachedImageFile(context, mid)
            if (cachedFile != null && cachedFile.exists()) {
                imageFile = cachedFile
            }
        } catch (e: Exception) {
            loadState = loadState.copy(isLoading = false, hasError = true)
            Timber.tag("AdvancedImageViewer").e("Error loading image: $e")
        }
    }

    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(enableLongPress) {
                if (enableLongPress) {
                    detectTapGestures(
                        onLongPress = { showMenu = true }
                    )
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { 
                        isDragging = true
                        dragOffset = 0f
                    },
                    onDragEnd = { 
                        isDragging = false
                        // If dragged down more than 100f, close the image viewer
                        if (dragOffset > 100f) {
                            onClose?.invoke()
                        }
                        dragOffset = 0f
                    },
                    onDrag = { _, dragAmount ->
                        // Only allow downward dragging (positive Y values)
                        if (dragAmount.y > 0) {
                            dragOffset += dragAmount.y
                        }
                    }
                )
            }
    ) {
        if (imageFile != null) {
            // Use SubsamplingScaleImageView for efficient large image handling
            AndroidView(
                factory = { context ->
                    SubsamplingScaleImageView(context).apply {
                        setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
                        setDoubleTapZoomScale(3f)
                        setDoubleTapZoomDuration(300)
                        setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
                        setMinimumTileDpi(160)
                    }
                },
                update = { imageView ->
                    imageFile?.let { file ->
                        try {
                            imageView.setImage(com.davemorrissey.labs.subscaleview.ImageSource.uri(file.toUri()))
                        } catch (e: Exception) {
                            Timber.tag("AdvancedImageViewer").e("Failed to load image in SubsamplingScaleImageView: $e")
                            // Fallback to regular Image if SubsamplingScaleImageView fails
                            loadState = loadState.copy(hasError = true)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = dragOffset
                        // Add some scaling effect as the image is dragged down
                        scaleX = 1f - (dragOffset / 1000f).coerceAtMost(0.1f)
                        scaleY = 1f - (dragOffset / 1000f).coerceAtMost(0.1f)
                        // Add alpha effect for fade out
                        alpha = 1f - (dragOffset / 500f).coerceAtMost(0.3f)
                    }
            )
        } else if (loadState.bitmap != null) {
            // Fallback to regular Image if no file is available
            Image(
                bitmap = loadState.bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = dragOffset
                        // Add some scaling effect as the image is dragged down
                        scaleX = 1f - (dragOffset / 1000f).coerceAtMost(0.1f)
                        scaleY = 1f - (dragOffset / 1000f).coerceAtMost(0.1f)
                        // Add alpha effect for fade out
                        alpha = 1f - (dragOffset / 500f).coerceAtMost(0.3f)
                    }
            )
        } else if (loadState.hasError) {
            // Show error state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.failed_to_load_image),
                    color = Color.White
                )
            }
        } else if (loadState.isLoading) {
            // Show loading state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.loading),
                    color = Color.White
                )
            }
        }

        // Close button
        if (onClose != null) {
            IconButton(
                onClick = { onClose.invoke() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Long press menu
        if (enableLongPress && showMenu) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.download)) },
                    onClick = {
                        // Download image to gallery
                        loadState.bitmap?.let { bitmap ->
                            val success = saveImageToGallery(context, bitmap)
                            if (success) {
                                Toast.makeText(context, "Image saved to gallery", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showMenu = false
                    }
                )
            }
        }
    }
}

/**
 * Unified ImageViewer that handles both preview and full-screen modes
 * @param imageUrl URL of the image to display
 * @param modifier Modifier for the image container
 * @param isFullScreen Whether to display the image in full screen mode
 * @param enableLongPress Whether to enable long press menu
 * @param useFillMode Whether to use Fill mode instead of Fit mode in full screen
 * @param onClose Callback for closing full screen mode
 * @param onLoadComplete Callback when image loading is complete
 */
@Composable
fun ImageViewer(
    imageUrl: String,
    modifier: Modifier = Modifier,
    isFullScreen: Boolean = false,
    enableLongPress: Boolean = true,
    useFillMode: Boolean = false,
    onClose: (() -> Unit)? = null,
    onLoadComplete: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val mid = remember(imageUrl) { imageUrl.getMimeiKeyFromUrl() }
    var loadState by remember(mid) { mutableStateOf(ImageLoadState()) }

    // Load image using ImageCacheManager
    LaunchedEffect(mid, imageUrl) {
        try {
            loadState = loadState.copy(isLoading = true, hasError = false)

            // Try to load from cache first
            val cachedBitmap = ImageCacheManager.getCachedImage(context, mid)

            // If not cached, download and cache
            if (cachedBitmap == null) {
                val downloadedBitmap = ImageCacheManager.loadImage(context, imageUrl, mid)

                if (downloadedBitmap == null) {
                    loadState = loadState.copy(isLoading = false, hasError = true)
                    Timber.tag("ImageViewer").e("Failed to load image: $imageUrl")
                } else {
                    loadState = loadState.copy(bitmap = downloadedBitmap, isLoading = false)
                    onLoadComplete?.invoke()
                }
            } else {
                loadState = loadState.copy(bitmap = cachedBitmap, isLoading = false)
                onLoadComplete?.invoke()
            }
        } catch (e: Exception) {
            loadState = loadState.copy(isLoading = false, hasError = true)
            Timber.tag("ImageViewer").e("Error loading image: $e")
        }
    }

    // Base modifier with different behaviors for full screen vs preview
    val baseModifier = if (isFullScreen) {
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(enableLongPress) {
                if (enableLongPress) {
                    detectTapGestures(
                        onLongPress = { showMenu = true }
                    )
                }
            }
    } else {
        modifier
            .fillMaxSize()
    }

    Box(
        modifier = baseModifier,
        contentAlignment = if (isFullScreen) Alignment.Center else Alignment.Center
    ) {
        if (loadState.bitmap != null) {
            // Show image
            Image(
                bitmap = loadState.bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = when {
                    isFullScreen && useFillMode -> ContentScale.Crop
                    isFullScreen -> ContentScale.Fit
                    else -> ContentScale.Crop
                },
                modifier = Modifier.fillMaxSize()
            )
        } else if (loadState.hasError) {
            // Show error state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.failed_to_load_image),
                    color = if (isFullScreen) Color.White else Color.Unspecified
                )
            }
        } else if (loadState.isLoading) {
            // Show loading state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.loading),
                    color = if (isFullScreen) Color.White else Color.Unspecified
                )
            }
        }

        // Close button (only for full screen mode)
        if (isFullScreen && onClose != null) {
            IconButton(
                onClick = { onClose.invoke() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Long press menu (only for full screen mode)
        if (isFullScreen && enableLongPress && showMenu) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.download)) },
                    onClick = {
                        // Download image to gallery
                        loadState.bitmap?.let { bitmap ->
                            val success = saveImageToGallery(context, bitmap)
                            if (success) {
                                Toast.makeText(
                                    context,
                                    "Image saved to gallery",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                        showMenu = false
                    }
                )
            }
        }
    }
}
