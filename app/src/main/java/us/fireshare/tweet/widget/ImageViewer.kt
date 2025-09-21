package us.fireshare.tweet.widget

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.delay
import timber.log.Timber
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl
import java.io.File

/**
 * State object for image loading to reduce recomposition
 */
data class ImageLoadState(
    val bitmap: android.graphics.Bitmap? = null,
    val isLoading: Boolean = false,
    val hasError: Boolean = false,
    val retryCount: Int = 0,
    val isVisible: Boolean = false
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

    // Load image using ImageCacheManager with proper cache checking: compressed first, then original, then server
    LaunchedEffect(mid, imageUrl, loadState.retryCount) {
        try {
            // Step 1: Check for compressed image in disk cache first
            val compressedBitmap = ImageCacheManager.getCachedImage(context, mid)
            
            if (compressedBitmap != null) {
                // Show compressed image immediately - no loading state needed
                loadState = loadState.copy(bitmap = compressedBitmap, isLoading = false, hasError = false)
                
                // Get the cached file for SubsamplingScaleImageView
                val cachedFile = ImageCacheManager.getCachedImageFile(context, mid)
                if (cachedFile != null && cachedFile.exists()) {
                    imageFile = cachedFile
                }
                
                Timber.tag("AdvancedImageViewer").d("Showing compressed cached image as placeholder: $imageUrl")
            } else {
                // No cached image, set loading state
                loadState = loadState.copy(isLoading = true, hasError = false)
            }
            
            // Step 2: Check for original image in cache
            val originalMid = "${mid}_original"
            val originalCachedFile = ImageCacheManager.getCachedImageFile(context, originalMid)
            
            if (originalCachedFile != null && originalCachedFile.exists()) {
                try {
                    val originalBitmap = ImageCacheManager.getCachedImage(context, originalMid)
                    if (originalBitmap != null) {
                        loadState = loadState.copy(bitmap = originalBitmap, isLoading = false, hasError = false)
                        imageFile = originalCachedFile
                        onLoadComplete?.invoke()
                        Timber.tag("AdvancedImageViewer").d("Using cached original image: $imageUrl")
                        return@LaunchedEffect
                    }
                } catch (e: Exception) {
                    Timber.tag("AdvancedImageViewer").w("Failed to load cached original image: $e")
                }
            }
            
            // Step 3: Load original image from server (always attempt this if not cached)
            try {
                val downloadedBitmap = ImageCacheManager.loadOriginalImage(context, imageUrl, mid)
                
                if (downloadedBitmap != null) {
                    // Update with original high-resolution image
                    loadState = loadState.copy(bitmap = downloadedBitmap, isLoading = false)
                    
                    // Update file for SubsamplingScaleImageView (use original cache key)
                    val updatedCachedFile = ImageCacheManager.getCachedImageFile(context, originalMid)
                    if (updatedCachedFile != null && updatedCachedFile.exists()) {
                        imageFile = updatedCachedFile
                    }
                    
                    onLoadComplete?.invoke()
                    Timber.tag("AdvancedImageViewer").d("Successfully loaded original image from server: $imageUrl")
                } else {
                    // Server load failed - show error or fallback to compressed
                    if (compressedBitmap == null) {
                        loadState = loadState.copy(
                            isLoading = false, 
                            hasError = true,
                            retryCount = loadState.retryCount + 1
                        )
                        Timber.tag("AdvancedImageViewer").e("Failed to load original image from server: $imageUrl")
                    } else {
                        // Keep showing compressed version if original loading fails
                        Timber.tag("AdvancedImageViewer").w("Failed to load original image from server, keeping compressed version: $imageUrl")
                    }
                }
            } catch (e: Exception) {
                Timber.tag("AdvancedImageViewer").e(e, "Error loading original image from server: $imageUrl")
                if (compressedBitmap == null) {
                    loadState = loadState.copy(
                        isLoading = false, 
                        hasError = true,
                        retryCount = loadState.retryCount + 1
                    )
                } else {
                    // Keep showing compressed version if original loading fails
                    Timber.tag("AdvancedImageViewer").w("Error loading original image from server, keeping compressed version: $imageUrl")
                }
            }
        } catch (e: Exception) {
            loadState = loadState.copy(
                isLoading = false, 
                hasError = true,
                retryCount = loadState.retryCount + 1
            )
            Timber.tag("AdvancedImageViewer").e("Error loading image: $e")
        }
    }

    var dragOffset by remember { mutableFloatStateOf(0f) }

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
                        dragOffset = 0f
                    },
                    onDragEnd = { 
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
        if (loadState.bitmap != null && !loadState.bitmap!!.isRecycled) {
            // Always use SubsamplingScaleImageView for consistent rendering and built-in operations
            AndroidView(
                factory = { context ->
                    SubsamplingScaleImageView(context).apply {
                        setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
                        setDoubleTapZoomScale(2f)
                        setDoubleTapZoomDuration(300)
                        setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
                        setMinimumTileDpi(160)
                        maxScale = 4f // Maximum zoom for pinch-to-zoom
                        
                        // Add long press listener for the third-party view
                        setOnLongClickListener {
                            showMenu = true
                            true
                        }
                    }
                },
                update = { imageView ->
                    loadState.bitmap?.let { bitmap ->
                        try {
                            // Use cached file if available, otherwise create temp file from bitmap
                            val fileToUse = imageFile ?: run {
                                val tempFile = File.createTempFile("temp_image", ".jpg", context.cacheDir)
                                tempFile.outputStream().use { out ->
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, out)
                                }
                                tempFile
                            }
                            imageView.setImage(com.davemorrissey.labs.subscaleview.ImageSource.uri(fileToUse.toUri()))
                        } catch (e: Exception) {
                            Timber.tag("AdvancedImageViewer").d("Failed to load image in SubsamplingScaleImageView: $e")
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
        } else if (loadState.hasError) {
            // Show error state with retry option
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.failed_to_load_image),
                        color = Color.White
                    )
                    if (loadState.retryCount < 3) {
                        androidx.compose.material3.Button(
                            onClick = {
                                loadState = loadState.copy(
                                    hasError = false,
                                    retryCount = loadState.retryCount + 1
                                )
                            }
                        ) {
                            Text("Retry")
                        }
                    }
                }
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

        // Custom long press menu
        if (enableLongPress && showMenu) {
            // Semi-transparent overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable { showMenu = false }
            ) {
                // Custom menu card
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp)
                        .width(200.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White
                    ),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = 6.dp
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Menu title
                        Text(
                            text = "Image Options",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.Black,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        // Reload option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    loadState = loadState.copy(
                                        isLoading = true,
                                        hasError = false,
                                        retryCount = loadState.retryCount + 1
                                    )
                                    showMenu = false
                                }
                                .padding(vertical = 8.dp, horizontal = 12.dp)
                                .background(
                                    color = Color(0xFFF5F5F5),
                                    shape = RoundedCornerShape(6.dp)
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = Color(0xFF2196F3),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Reload Image",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Black
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        // Download option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
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
                                .padding(vertical = 8.dp, horizontal = 12.dp)
                                .background(
                                    color = Color(0xFFF5F5F5),
                                    shape = RoundedCornerShape(6.dp)
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Save to Gallery",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Black
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Cancel button
                        TextButton(
                            onClick = { showMenu = false },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            Text(
                                text = "Cancel",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
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
    inPreviewGrid: Boolean = true,
    isVisible: Boolean = true,
    onClose: (() -> Unit)? = null,
    onLoadComplete: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val mid = remember(imageUrl) { imageUrl.getMimeiKeyFromUrl() }
    var loadState by remember(mid) { mutableStateOf(ImageLoadState(isVisible = isVisible)) }

    // Update visibility state when it changes
    LaunchedEffect(isVisible) {
        loadState = loadState.copy(isVisible = isVisible)
    }

    // Load image using two-tier system: cached placeholder first, then original quality
    LaunchedEffect(mid, imageUrl, loadState.retryCount, isVisible) {
        try {
            loadState = loadState.copy(isLoading = true, hasError = false)

            // Try to load from cache first as placeholder
            val cachedBitmap = ImageCacheManager.getCachedImage(context, mid)
            
            if (cachedBitmap != null) {
                // Show cached image immediately as placeholder
                loadState = loadState.copy(bitmap = cachedBitmap, isLoading = false, hasError = false)
                onLoadComplete?.invoke()
                Timber.tag("ImageViewer").d("Showing cached image as placeholder: $imageUrl")
                
                // Load original high-quality image in background using dedicated scope
                ImageCacheManager.loadOriginalImageWithScope(
                    context = context,
                    imageUrl = imageUrl,
                    mid = mid,
                    isVisible = isVisible
                ) { originalBitmap ->
                    if (originalBitmap != null) {
                        loadState = loadState.copy(bitmap = originalBitmap)
                        Timber.tag("ImageViewer").d("Replaced cached image with original: $imageUrl")
                    } else {
                        Timber.tag("ImageViewer").d("Failed to load original image in background: $imageUrl")
                        // Keep showing cached image if original loading fails
                    }
                }
            } else {
                // No cached image available, use dedicated scope to avoid UI cancellation issues
                loadState = loadState.copy(isLoading = true)
                
                ImageCacheManager.loadOriginalImageWithScope(
                    context = context,
                    imageUrl = imageUrl,
                    mid = mid,
                    isVisible = isVisible
                ) { downloadedBitmap ->
                    if (downloadedBitmap == null) {
                        loadState = loadState.copy(
                            isLoading = false, 
                            hasError = true,
                            retryCount = loadState.retryCount + 1
                        )
                        Timber.tag("ImageViewer").d("Failed to load original image: $imageUrl")
                    } else {
                        loadState = loadState.copy(bitmap = downloadedBitmap, isLoading = false)
                        // Don't call onLoadComplete from callback to avoid coroutine conflicts
                    }
                }
            }
        } catch (e: Exception) {
            loadState = loadState.copy(
                isLoading = false, 
                hasError = true,
                retryCount = loadState.retryCount + 1
            )
            Timber.tag("ImageViewer").d("Error loading image: $e")
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
        if (loadState.bitmap != null && !loadState.bitmap!!.isRecycled) {
            // Show image with safety check
            Image(
                bitmap = loadState.bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = when {
                    isFullScreen && useFillMode -> ContentScale.Crop
                    isFullScreen -> ContentScale.Fit
                    inPreviewGrid -> ContentScale.Crop
                    else -> ContentScale.Fit
                },
                modifier = Modifier.fillMaxSize()
            )
        } else if (loadState.hasError) {
            // Show error state with retry option for full screen
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (isFullScreen) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.failed_to_load_image),
                            color = Color.White
                        )
                        if (loadState.retryCount < 2) {
                            androidx.compose.material3.Button(
                                onClick = {
                                    loadState = loadState.copy(
                                        hasError = false,
                                        retryCount = loadState.retryCount + 1
                                    )
                                }
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.failed_to_load_image),
                        color = Color.Unspecified
                    )
                }
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
