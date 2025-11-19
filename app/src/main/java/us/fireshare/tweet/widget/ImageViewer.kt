package us.fireshare.tweet.widget

import android.graphics.PointF
import android.view.View
import android.widget.Toast
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.delay
import timber.log.Timber
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl
import java.io.File
import kotlin.math.min
import androidx.core.graphics.scale

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
 * Animated loading text with moving dots
 */
@Composable
fun AnimatedLoadingText(
    modifier: Modifier = Modifier,
    text: String = "Loading",
    color: Color = Color.Gray
) {
    val infiniteTransition = rememberInfiniteTransition(label = "loading_dots")
    
    // Animate the number of dots (0 to 5)
    val dotCount by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "dot_count"
    )
    
    val dots = "•".repeat(dotCount.toInt())
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = color
        )
        // Fixed width container for dots to prevent shifting
        Box(
            modifier = Modifier.width(80.dp), // Fixed width for 5 dots
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = dots,
                color = color,
                fontSize = 12.sp // Larger dots
            )
        }
    }
}

/**
 * Advanced ImageViewer using SubsamplingScaleImageView for efficient large image handling
 * Supports local cached images and loads original high-resolution images for full-screen viewing
 */
@Composable
fun AdvancedImageViewer(
    imageUrl: String,
    modifier: Modifier = Modifier,
    enableLongPress: Boolean = true,
    initialBitmap: android.graphics.Bitmap? = null,
    onClose: (() -> Unit)? = null,
    onLoadComplete: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val mid = remember(imageUrl) { imageUrl.getMimeiKeyFromUrl() }
    var loadState by remember(mid) { 
        mutableStateOf(
            if (initialBitmap != null) {
                ImageLoadState(bitmap = initialBitmap, isLoading = false)
            } else {
                ImageLoadState()
            }
        )
    }
    var imageFile by remember { mutableStateOf<File?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }
    var isVisible by remember { mutableStateOf(true) }
    var lastRetryTime by remember { mutableLongStateOf(0L) }
    var currentImageUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // Function to check if retry should be attempted (debounced and with available slots)
    fun shouldRetry(): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastRetry = currentTime - lastRetryTime
        val minRetryInterval = 1000L // 1 second minimum between retries
        
        // Check if enough time has passed since last retry
        if (timeSinceLastRetry < minRetryInterval) {
            Timber.tag("ImageViewer").d("Retry debounced: ${minRetryInterval - timeSinceLastRetry}ms remaining")
            return false
        }
        
        // Check if there are available download slots
        val hasSlots = ImageCacheManager.hasAvailableDownloadSlots()
        if (!hasSlots) {
            Timber.tag("ImageViewer").d("Retry blocked: no available download slots. Status: ${ImageCacheManager.getDownloadStatus()}")
            return false
        }
        
        Timber.tag("ImageViewer").d("Retry allowed: debounced and slots available. Status: ${ImageCacheManager.getDownloadStatus()}")
        return true
    }

    // Load image using ImageCacheManager with proper cache checking: compressed first, then original, then server
    LaunchedEffect(mid, imageUrl, retryCount) {
        // Store the current job for potential cancellation
        // Only attempt loading if retry count is within limit
        if (retryCount > 3) {
            return@LaunchedEffect
        }
        
        try {
            var compressedBitmap: android.graphics.Bitmap? = null
            
            // If we already have an initial bitmap, use it immediately
            if (initialBitmap != null) {
                Timber.tag("ImageViewer").d("Using initial bitmap from preview: $imageUrl")
                loadState = loadState.copy(bitmap = initialBitmap, isLoading = false, hasError = false)
                
                // Try to get the cached file for the compressed image to avoid creating temp file
                val cachedFile = ImageCacheManager.getCachedImageFile(context, mid)
                if (cachedFile != null && cachedFile.exists()) {
                    imageFile = cachedFile
                }
                
                onLoadComplete?.invoke()
            } else {
                // Step 1: Check for compressed image in cache first (memory + disk)
                compressedBitmap = ImageCacheManager.getCachedImage(context, mid)
                
                if (compressedBitmap != null) {
                    // Show compressed image immediately - no loading state needed
                    loadState = loadState.copy(bitmap = compressedBitmap, isLoading = false, hasError = false)
                    
                    // Get the cached file for SubsamplingScaleImageView
                    val cachedFile = ImageCacheManager.getCachedImageFile(context, mid)
                    if (cachedFile != null && cachedFile.exists()) {
                        imageFile = cachedFile
                    }
                    
                    Timber.tag("ImageViewer").d("Showing compressed cached image as placeholder: $imageUrl")
                } else {
                    // No compressed cache found, check original cache before showing loading
                    val originalMid = "${mid}_original"
                    val originalCachedFile = ImageCacheManager.getCachedImageFile(context, originalMid)
                    
                    if (originalCachedFile != null && originalCachedFile.exists()) {
                        try {
                            val originalBitmap = ImageCacheManager.getCachedImage(context, originalMid)
                            if (originalBitmap != null) {
                                loadState = loadState.copy(bitmap = originalBitmap, isLoading = false, hasError = false)
                                imageFile = originalCachedFile
                                onLoadComplete?.invoke()
                                Timber.tag("ImageViewer").d("Using cached original image: $imageUrl")
                                return@LaunchedEffect
                            }
                        } catch (e: Exception) {
                            Timber.tag("ImageViewer").w("Failed to load cached original image: $e")
                        }
                    }
                    
                    // No cached images found, now set loading state
                    loadState = loadState.copy(isLoading = true, hasError = false)
                }
            }
            
            // Step 2: Load original image from server (only if no cached images found and no initial bitmap)
            // Skip loading original if we have an initialBitmap to prevent flicker
            if (initialBitmap == null) {
                try {
                    val originalMid = "${mid}_original"
                    val downloadedBitmap = ImageCacheManager.loadOriginalImage(context, imageUrl, mid, isVisible)
                    
                    if (downloadedBitmap != null) {
                        // Update with original high-resolution image
                        loadState = loadState.copy(bitmap = downloadedBitmap, isLoading = false, hasError = false)
                        
                        // Reset retry count on successful load
                        retryCount = 0
                        
                        // Update file for SubsamplingScaleImageView (use original cache key)
                        val updatedCachedFile = ImageCacheManager.getCachedImageFile(context, originalMid)
                        if (updatedCachedFile != null && updatedCachedFile.exists()) {
                            imageFile = updatedCachedFile
                        }
                        
                        onLoadComplete?.invoke()
                        Timber.tag("ImageViewer").d("Successfully loaded original image from server: $imageUrl")
                        // Server load failed - show error or fallback to compressed
                        if (compressedBitmap == null) {
                            loadState = loadState.copy(
                                isLoading = false, 
                                hasError = true
                            )
                            Timber.tag("ImageViewer").e("Failed to load original image from server: $imageUrl")
                            
                            // Auto-retry if within limit and debounced
                            if (retryCount < 3 && shouldRetry()) {
                                delay(2000L) // Wait 2 seconds before retry
                                retryCount++
                                lastRetryTime = System.currentTimeMillis()
                            }
                        } else {
                            // Keep showing compressed version if original loading fails
                            Timber.tag("ImageViewer").w("Failed to load original image from server, keeping compressed version: $imageUrl")
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("ImageViewer").e(e, "Error loading original image from server: $imageUrl")
                    if (compressedBitmap == null) {
                        loadState = loadState.copy(
                            isLoading = false, 
                            hasError = true
                        )
                        
                        // Auto-retry if within limit and debounced
                        if (retryCount < 3 && shouldRetry()) {
                            delay(2000L) // Wait 2 seconds before retry
                            retryCount++
                            lastRetryTime = System.currentTimeMillis()
                        }
                    } else {
                        // Keep showing compressed version if original loading fails
                        Timber.tag("ImageViewer").w("Error loading original image from server, keeping compressed version: $imageUrl")
                    }
                }
            } else {
                // We have an initialBitmap, so skip loading original to prevent flicker
                Timber.tag("ImageViewer").d("Skipping original image load because initialBitmap is provided: $imageUrl")
            }
        } catch (e: Exception) {
            // Handle cancellation gracefully - don't retry on cancellation
            if (e is kotlinx.coroutines.CancellationException) {
                Timber.tag("ImageViewer").d("Image loading cancelled due to composition change: $imageUrl")
                return@LaunchedEffect
            }
            
            loadState = loadState.copy(
                isLoading = false, 
                hasError = true
            )
            Timber.tag("ImageViewer").e("Error loading image: $e")
            
            // Auto-retry if within limit and debounced
            if (retryCount < 3 && shouldRetry()) {
                delay(2000L) // Wait 2 seconds before retry
                retryCount++
                lastRetryTime = System.currentTimeMillis()
            }
        }
    }

    // Update visibility state when it changes and retry if needed
    LaunchedEffect(isVisible) {
        loadState = loadState.copy(isVisible = isVisible)
        
        if (isVisible) {
            // If image becomes visible again, resume any paused download
            ImageCacheManager.resumeDownload(mid)
            
            // If previous load failed, retry
            if (loadState.hasError && retryCount <= 3) {
                Timber.tag("ImageViewer").d("AdvancedImageViewer reappeared with error, attempting retry: $imageUrl, retryCount: $retryCount")
                retryCount++
            }
        } else {
            // If image becomes invisible, pause the download instead of cancelling
            ImageCacheManager.pauseDownload(mid)
            Timber.tag("ImageViewer").d("AdvancedImageViewer became invisible, paused download: $imageUrl")
        }
    }

    var dragOffset by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { 
                isVisible = true 
            }
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
                        setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)
                        setDoubleTapZoomDuration(300)
                        setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
                        setMinimumTileDpi(160)
                        
                        // Enable EXIF orientation handling
                        setOrientation(SubsamplingScaleImageView.ORIENTATION_USE_EXIF)
                        
                        // Add long press listener for the third-party view
                        setOnLongClickListener {
                            showMenu = true
                            true
                        }
                        
                        configureConsistentZoomScaling(
                            doubleTapFactor = 2f,
                            maxScaleFactor = 4f
                        )
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
                            val imageUri = fileToUse.toUri()
                            
                            // Only update if the URI has changed to prevent flicker
                            if (currentImageUri != imageUri) {
                                currentImageUri = imageUri
                                imageView.setImage(com.davemorrissey.labs.subscaleview.ImageSource.uri(imageUri))
                            }
                        } catch (e: Exception) {
                            Timber.tag("ImageViewer").d("Failed to load image in SubsamplingScaleImageView: $e")
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
        } else if (loadState.isLoading) {
            // Show loading state only when actually loading
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedLoadingText(
                    text = stringResource(R.string.loading),
                    color = Color.Gray
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
                    contentDescription = stringResource(R.string.close),
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
                        
                        
                        // Download option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    loadState.bitmap?.let { bitmap ->
                                        val success = saveImageToGallery(context, bitmap)
                                        if (success) {
                                            Toast.makeText(context, context.getString(R.string.image_saved_to_gallery), Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, context.getString(R.string.failed_to_save_image), Toast.LENGTH_SHORT).show()
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
    initialBitmap: android.graphics.Bitmap? = null,
    loadOriginalImage: Boolean = false, // force load original high-res image instead of compressed
    onClose: (() -> Unit)? = null,
    onLoadComplete: (() -> Unit)? = null,
    onBitmapLoaded: ((android.graphics.Bitmap?) -> Unit)? = null
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val mid = remember(imageUrl) { imageUrl.getMimeiKeyFromUrl() }
    var loadState by remember(mid) { 
        mutableStateOf(
            if (initialBitmap != null) {
                ImageLoadState(bitmap = initialBitmap, isLoading = false, isVisible = isVisible)
            } else {
                ImageLoadState(isVisible = isVisible)
            }
        )
    }
    var imageFile by remember { mutableStateOf<File?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var retryCount by remember { mutableIntStateOf(0) }
    var lastRetryTime by remember { mutableLongStateOf(0L) }

    // Function to check if retry should be attempted (debounced and with available slots)
    fun shouldRetry(): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastRetry = currentTime - lastRetryTime
        val minRetryInterval = 1000L // 1 second minimum between retries
        
        // Check if enough time has passed since last retry
        if (timeSinceLastRetry < minRetryInterval) {
            Timber.tag("ImageViewer").d("Retry debounced: ${minRetryInterval - timeSinceLastRetry}ms remaining")
            return false
        }
        
        // Check if there are available download slots
        val hasSlots = ImageCacheManager.hasAvailableDownloadSlots()
        if (!hasSlots) {
            Timber.tag("ImageViewer").d("Retry blocked: no available download slots. Status: ${ImageCacheManager.getDownloadStatus()}")
            return false
        }
        
        Timber.tag("ImageViewer").d("Retry allowed: debounced and slots available. Status: ${ImageCacheManager.getDownloadStatus()}")
        return true
    }

    // Update visibility state when it changes and retry if needed
    LaunchedEffect(isVisible) {
        loadState = loadState.copy(isVisible = isVisible)
        
        if (isVisible) {
            // If image becomes visible again, resume any paused download
            ImageCacheManager.resumeDownload(mid)
            
            // If previous load failed, retry
            if (loadState.hasError && retryCount <= 3) {
                Timber.tag("ImageViewer").d("Image reappeared with error, attempting retry: $imageUrl, retryCount: $retryCount")
                retryCount++
            }
        } else {
            // If image becomes invisible, pause the download instead of cancelling
            ImageCacheManager.pauseDownload(mid)
            Timber.tag("ImageViewer").d("Image became invisible, paused download: $imageUrl")
        }
    }

    // Load image using proper cache checking: compressed first, then original, then server
    LaunchedEffect(mid, imageUrl, retryCount) {
        // Store the current job for potential cancellation
        try {
            // If we already have an initial bitmap, use it immediately
            if (initialBitmap != null) {
                Timber.tag("ImageViewer").d("Using initial bitmap from preview: $imageUrl")
                loadState = loadState.copy(bitmap = initialBitmap, isLoading = false, hasError = false)
                onLoadComplete?.invoke()
            } else {
                if (loadOriginalImage) {
                    // Skip compressed image check, go directly to original image logic
                    val originalMid = "${mid}_original"
                    val originalCachedFile = ImageCacheManager.getCachedImageFile(context, originalMid)
                    
                    if (originalCachedFile != null && originalCachedFile.exists()) {
                        try {
                            val originalBitmap = ImageCacheManager.getCachedImage(context, originalMid)
                            if (originalBitmap != null) {
                                loadState = loadState.copy(bitmap = originalBitmap, isLoading = false, hasError = false)
                                onBitmapLoaded?.invoke(originalBitmap)
                                imageFile = originalCachedFile
                                onLoadComplete?.invoke()
                                Timber.tag("ImageViewer").d("Using cached original image (forced): $imageUrl")
                                return@LaunchedEffect
                            }
                        } catch (e: Exception) {
                            Timber.tag("ImageViewer").w("Failed to load cached original image: $e")
                        }
                    }
                    
                    // No cached original found, set loading state and load from server
                    loadState = loadState.copy(isLoading = true, hasError = false)
                } else {
                    // Step 1: Check for compressed image in cache first (memory + disk)
                    val compressedBitmap = ImageCacheManager.getCachedImage(context, mid)
                    
                    if (compressedBitmap != null) {
                        // Show compressed image immediately - no loading state needed
                        loadState = loadState.copy(bitmap = compressedBitmap, isLoading = false, hasError = false)
                        onBitmapLoaded?.invoke(compressedBitmap)
                        
                        // Get the cached file for SubsamplingScaleImageView
                        val cachedFile = ImageCacheManager.getCachedImageFile(context, mid)
                        if (cachedFile != null && cachedFile.exists()) {
                            imageFile = cachedFile
                        }
                        
                        Timber.tag("ImageViewer").d("Showing compressed cached image as placeholder: $imageUrl")
                    } else {
                        // No compressed cache found, set loading state to load compressed from server
                        loadState = loadState.copy(isLoading = true, hasError = false)
                    }
                }
            }
            
            // Step 2: Load image from server (only if no cached images found)
            try {
                if (loadOriginalImage) {
                    // Load original high-res image from server
                    val originalMid = "${mid}_original"
                    val downloadedBitmap = ImageCacheManager.loadOriginalImage(context, imageUrl, mid, isVisible)
                    
                    if (downloadedBitmap != null) {
                        // Update with loaded original image
                        loadState = loadState.copy(bitmap = downloadedBitmap, isLoading = false, hasError = false)
                        onBitmapLoaded?.invoke(downloadedBitmap)
                        
                        // Reset retry count on successful load
                        retryCount = 0
                        
                        // Update file for SubsamplingScaleImageView
                        val updatedCachedFile = ImageCacheManager.getCachedImageFile(context, originalMid)
                        if (updatedCachedFile != null && updatedCachedFile.exists()) {
                            imageFile = updatedCachedFile
                        }
                        
                        onLoadComplete?.invoke()
                        Timber.tag("ImageViewer").d("Successfully loaded original image from server: $imageUrl - bitmap: ${downloadedBitmap.width}x${downloadedBitmap.height}, hasError: false")
                    } else {
                        // Server load failed - show error or fallback
                        if (initialBitmap == null) {
                            loadState = loadState.copy(
                                isLoading = false, 
                                hasError = true
                            )
                            Timber.tag("ImageViewer").e("Failed to load original image from server: $imageUrl - hasError: true, retryCount: $retryCount")
                            
                            // Auto-retry if within limit, visible, and debounced
                            if (retryCount < 3 && loadState.isVisible && shouldRetry()) {
                                delay(2000L) // Wait 2 seconds before retry
                                retryCount++
                                lastRetryTime = System.currentTimeMillis()
                            }
                        } else {
                            // Keep showing initial bitmap if original loading fails
                            Timber.tag("ImageViewer").w("Failed to load original image from server, keeping initial bitmap: $imageUrl")
                        }
                    }
                } else {
                    // Load compressed image from server (downloads and compresses automatically)
                    val downloadedBitmap = ImageCacheManager.downloadAndCacheImage(context, imageUrl, mid)
                    
                    if (downloadedBitmap != null) {
                        // Update with loaded compressed image
                        loadState = loadState.copy(bitmap = downloadedBitmap, isLoading = false, hasError = false)
                        onBitmapLoaded?.invoke(downloadedBitmap)
                        
                        // Reset retry count on successful load
                        retryCount = 0
                        
                        // Update file for SubsamplingScaleImageView (use compressed cache key)
                        val updatedCachedFile = ImageCacheManager.getCachedImageFile(context, mid)
                        if (updatedCachedFile != null && updatedCachedFile.exists()) {
                            imageFile = updatedCachedFile
                        }
                        
                        onLoadComplete?.invoke()
                        Timber.tag("ImageViewer").d("Successfully loaded compressed image from server: $imageUrl - bitmap: ${downloadedBitmap.width}x${downloadedBitmap.height}, hasError: false")
                    } else {
                        // Server load failed - show error
                        if (initialBitmap == null) {
                            loadState = loadState.copy(
                                isLoading = false, 
                                hasError = true
                            )
                            Timber.tag("ImageViewer").e("Failed to load compressed image from server: $imageUrl - hasError: true, retryCount: $retryCount")
                            
                            // Auto-retry if within limit, visible, and debounced
                            if (retryCount < 3 && loadState.isVisible && shouldRetry()) {
                                delay(2000L) // Wait 2 seconds before retry
                                retryCount++
                                lastRetryTime = System.currentTimeMillis()
                            }
                        } else {
                            // Keep showing initial bitmap if loading fails
                            Timber.tag("ImageViewer").w("Failed to load compressed image from server, keeping initial bitmap: $imageUrl")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag("ImageViewer").e(e, "Error loading image from server: $imageUrl")
                if (initialBitmap == null) {
                    loadState = loadState.copy(
                        isLoading = false, 
                        hasError = true
                    )
                    
                    // Auto-retry if within limit, visible, and debounced
                    if (retryCount < 3 && loadState.isVisible && shouldRetry()) {
                        delay(2000L) // Wait 2 seconds before retry
                        retryCount++
                        lastRetryTime = System.currentTimeMillis()
                    }
                } else {
                    // Keep showing initial bitmap if loading fails
                    Timber.tag("ImageViewer").w("Error loading image from server, keeping initial bitmap: $imageUrl")
                }
            }
        } catch (e: Exception) {
            // Handle cancellation gracefully - don't retry on cancellation
            if (e is kotlinx.coroutines.CancellationException) {
                Timber.tag("ImageViewer").d("Image loading cancelled due to composition change: $imageUrl")
                return@LaunchedEffect
            }
            
            loadState = loadState.copy(
                isLoading = false, 
                hasError = true
            )
            Timber.tag("ImageViewer").d("Error loading image: $e")
            
            // Auto-retry if within limit, visible, and debounced
            if (retryCount < 3 && loadState.isVisible && shouldRetry()) {
                delay(2000L) // Wait 2 seconds before retry
                retryCount++
                lastRetryTime = System.currentTimeMillis()
            }
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
    } else {
        modifier
            .fillMaxSize()
    }

    Box(
        modifier = baseModifier,
        contentAlignment = if (isFullScreen) Alignment.Center else Alignment.Center
    ) {
        if (loadState.bitmap != null && !loadState.bitmap!!.isRecycled) {
            Timber.tag("ImageViewer").d("Rendering image: ${loadState.bitmap!!.width}x${loadState.bitmap!!.height}, hasError: ${loadState.hasError}, isLoading: ${loadState.isLoading}")
            if (isFullScreen) {
                // Use SubsamplingScaleImageView for fullscreen with built-in zoom/pan operations
                AndroidView(
                    factory = { context ->
                        SubsamplingScaleImageView(context).apply {
                            setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)
                            setDoubleTapZoomDuration(300)
                            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
                            setMinimumTileDpi(160)
                            
                            // Add long press listener for the third-party view
                            setOnLongClickListener {
                                showMenu = true
                                true
                            }
                            
                            configureConsistentZoomScaling(
                                doubleTapFactor = 2f,
                                maxScaleFactor = 4f
                            )
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
                                Timber.tag("ImageViewer").d("Failed to load image in SubsamplingScaleImageView: $e")
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
            } else {
                // Use regular Image for preview mode
                // Downscale bitmap if it's too large for canvas drawing (Android limit ~100MB)
                val previewBitmap = remember(loadState.bitmap) {
                    loadState.bitmap?.let { bitmap ->
                        // Calculate bitmap size in bytes (ARGB_8888 = 4 bytes per pixel)
                        val bitmapSizeBytes = bitmap.width * bitmap.height * 4L
                        val maxSizeBytes = 50 * 1024 * 1024L // 50MB safe limit (well below 100MB canvas limit)
                        val maxDimension = 2048 // Maximum dimension for preview
                        
                        // Check if downscaling is needed
                        if (bitmapSizeBytes > maxSizeBytes || bitmap.width > maxDimension || bitmap.height > maxDimension) {
                            // Calculate scale factor
                            val widthScale = maxDimension.toFloat() / bitmap.width
                            val heightScale = maxDimension.toFloat() / bitmap.height
                            val sizeScale = kotlin.math.sqrt(maxSizeBytes.toFloat() / bitmapSizeBytes)
                            
                            // Use the most restrictive scale factor
                            val scale = minOf(widthScale, heightScale, sizeScale).coerceAtMost(1f)
                            
                            if (scale < 1f) {
                                val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
                                val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
                                
                                Timber.tag("ImageViewer").d("Downscaling bitmap for preview: ${bitmap.width}x${bitmap.height} -> ${newWidth}x${newHeight} (scale: $scale)")
                                
                                try {
                                    bitmap.scale(newWidth, newHeight)
                                } catch (e: Exception) {
                                    Timber.tag("ImageViewer").e(e, "Failed to downscale bitmap, using original")
                                    bitmap
                                }
                            } else {
                                bitmap
                            }
                        } else {
                            bitmap
                        }
                    }
                }
                
                previewBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = when {
                            inPreviewGrid -> ContentScale.Crop
                            else -> ContentScale.Fit
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else if (loadState.isLoading) {
            // Show loading state only when actually loading
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedLoadingText(
                    text = stringResource(R.string.loading),
                    color = if (isFullScreen) Color.Gray else Color.Gray
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
                    contentDescription = stringResource(R.string.close),
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
                                Toast.makeText(context, context.getString(R.string.failed_to_save_image), Toast.LENGTH_SHORT)
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

private fun SubsamplingScaleImageView.configureConsistentZoomScaling(
    doubleTapFactor: Float,
    maxScaleFactor: Float
) {
    setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)

    val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        updateScaleBounds(doubleTapFactor, maxScaleFactor)
    }

    addOnLayoutChangeListener(layoutChangeListener)
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}

        override fun onViewDetachedFromWindow(v: View) {
            removeOnLayoutChangeListener(layoutChangeListener)
            removeOnAttachStateChangeListener(this)
        }
    })

    setOnImageEventListener(object : SubsamplingScaleImageView.OnImageEventListener {
        override fun onReady() = updateScaleBounds(doubleTapFactor, maxScaleFactor)
        override fun onImageLoaded() = updateScaleBounds(doubleTapFactor, maxScaleFactor)
        override fun onPreviewReleased() {}
        override fun onImageLoadError(e: Exception) {}
        override fun onPreviewLoadError(e: Exception) {}
        override fun onTileLoadError(e: Exception) {}
    })

    post { updateScaleBounds(doubleTapFactor, maxScaleFactor) }
}

private fun SubsamplingScaleImageView.updateScaleBounds(
    doubleTapFactor: Float,
    maxScaleFactor: Float
) {
    val contentWidth = width - paddingLeft - paddingRight
    val contentHeight = height - paddingTop - paddingBottom
    if (contentWidth <= 0 || contentHeight <= 0) return

    val (sourceWidth, sourceHeight) = orientedSourceSize()
    if (sourceWidth <= 0 || sourceHeight <= 0) return

    val fitScale = min(
        contentWidth.toFloat() / sourceWidth.toFloat(),
        contentHeight.toFloat() / sourceHeight.toFloat()
    )
    if (!fitScale.isFinite() || fitScale <= 0f) return

    minScale = fitScale

    val computedMaxScale = (fitScale * maxScaleFactor).coerceAtLeast(fitScale)
    maxScale = computedMaxScale

    val targetDoubleTapScale = (fitScale * doubleTapFactor)
        .coerceIn(fitScale, computedMaxScale)
    setDoubleTapZoomScale(targetDoubleTapScale)

    val currentScale = scale
    if (!currentScale.isFinite() || currentScale < fitScale) {
        val center = getCenter() ?: PointF(sourceWidth / 2f, sourceHeight / 2f)
        setScaleAndCenter(fitScale, center)
    }
}

private fun SubsamplingScaleImageView.orientedSourceSize(): Pair<Int, Int> {
    val rotation = orientation
    val swap = rotation == SubsamplingScaleImageView.ORIENTATION_90 ||
        rotation == SubsamplingScaleImageView.ORIENTATION_270
    val width = if (swap) sHeight else sWidth
    val height = if (swap) sWidth else sHeight
    return width to height
}
