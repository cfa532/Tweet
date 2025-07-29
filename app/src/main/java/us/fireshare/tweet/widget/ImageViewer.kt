package us.fireshare.tweet.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import timber.log.Timber
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl

/**
 * State object for image loading to reduce recomposition
 */
data class ImageLoadState(
    val bitmap: android.graphics.Bitmap? = null,
    val isLoading: Boolean = false,
    val hasError: Boolean = false
)

/**
 * ImageViewer displays images with caching support
 * @param imageUrl URL of the image to display
 * @param modifier Modifier for the image container
 * @param isFullSize Whether to display the image in full size
 * @param imageSize Size hint for the image (used for caching)
 * @param placeholderUrl Optional placeholder image URL
 * @param enableLongPress Whether to enable long press menu
 */
@Composable
fun ImageViewer(
    imageUrl: String,
    modifier: Modifier = Modifier,
    isFullSize: Boolean = false,
    imageSize: Int = 200,    // Preview size in KB
    placeholderUrl: String? = null,
    enableLongPress: Boolean = true
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
            
            // If not cached and not full size, download and cache
            if (cachedBitmap == null && !isFullSize) {
                val downloadedBitmap = ImageCacheManager.loadImage(context, imageUrl, mid)
                
                if (downloadedBitmap == null) {
                    loadState = loadState.copy(isLoading = false, hasError = true)
                    Timber.e("ImageViewer - Failed to load image: $imageUrl")
                } else {
                    loadState = loadState.copy(bitmap = downloadedBitmap, isLoading = false)
                }
            } else {
                loadState = loadState.copy(bitmap = cachedBitmap, isLoading = false)
            }
        } catch (e: Exception) {
            loadState = loadState.copy(isLoading = false, hasError = true)
            Timber.e("ImageViewer - Error loading image: $e")
        }
    }

    val adjustedModifier = if (isFullSize) {
        modifier.fillMaxWidth()     // Full-size image takes full width
    } else {
        modifier.fillMaxSize()      // Preview image fits within parent
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (loadState.bitmap != null) {
            // Show cached image
            Image(
                bitmap = loadState.bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = if (enableLongPress) {
                    adjustedModifier
                        .clickable { showMenu = true }
                 } else {
                    adjustedModifier
                }
            )
        } else if (loadState.hasError) {
            // Show error state
            Box(
                modifier = adjustedModifier
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.failed_to_load_image))
            }
        } else if (loadState.isLoading) {
            // Show loading state
            Box(
                modifier = adjustedModifier
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.loading))
            }
        }

        // Long press menu
        if (enableLongPress && showMenu) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.save_image)) },
                    onClick = {
                        // TODO: Implement save functionality
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share_image)) },
                    onClick = {
                        // TODO: Implement share functionality
                        showMenu = false
                    }
                )
            }
        }
    }
}

/**
 * Enhanced image viewer for full-screen mode that shows cached preview as placeholder
 * @param imageUrl: Full-size image URL
 * @param previewUrl: Preview image URL (should be cached by ImageCacheManager)
 * @param modifier: Modifier for the image
 * @param enableLongPress: Whether to enable long press gesture
 */
@Composable
fun FullScreenImageViewer(
    imageUrl: String,
    previewUrl: String,
    modifier: Modifier = Modifier,
    enableLongPress: Boolean = true
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val imageMid = remember(imageUrl) { imageUrl.getMimeiKeyFromUrl() }
    var loadState by remember(imageMid) { mutableStateOf(ImageLoadState()) }

    // Load image using ImageCacheManager
    LaunchedEffect(imageMid, imageUrl) {
        try {
            loadState = loadState.copy(isLoading = true, hasError = false)
            
            // Try to load from cache first
            val cachedBitmap = ImageCacheManager.getCachedImage(context, imageMid)
            
            // If not cached, download and cache
            if (cachedBitmap == null) {
                val downloadedBitmap = ImageCacheManager.loadImage(context, imageUrl, imageMid)
                
                if (downloadedBitmap == null) {
                    loadState = loadState.copy(isLoading = false, hasError = true)
                    Timber.e("FullScreenImageViewer - Failed to load full-size image: $imageUrl")
                } else {
                    loadState = loadState.copy(bitmap = downloadedBitmap, isLoading = false)
                }
            } else {
                loadState = loadState.copy(bitmap = cachedBitmap, isLoading = false)
            }
        } catch (e: Exception) {
            loadState = loadState.copy(isLoading = false, hasError = true)
            Timber.e("FullScreenImageViewer - Error loading full-size image: $e")
        }
    }

    Box(modifier = modifier) {
        if (loadState.bitmap != null) {
            // Show cached image
            Image(
                bitmap = loadState.bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = if (enableLongPress) {
                    Modifier.fillMaxSize()
                        .clickable { showMenu = true }
                } else {
                    Modifier.fillMaxSize()
                }
            )
        } else if (loadState.hasError) {
            // Show error state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.failed_to_load_image))
            }
        } else if (loadState.isLoading) {
            // Show loading state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.loading))
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
                        // TODO: Implement download functionality
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share_image)) },
                    onClick = {
                        // TODO: Implement share functionality
                        showMenu = false
                    }
                )
            }
        }
    }
}
