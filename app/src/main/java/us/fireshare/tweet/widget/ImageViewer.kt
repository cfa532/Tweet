package us.fireshare.tweet.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl


/**
 * Image viewer that loads images directly without caching
 * @param imageUrl: Image URL in the format of http://ip/ipfs/mimeiId or http://ip/mm/mimeiId
 * @param isFullSize: If true, shows full-size image
 * @param imageSize: Preview size in KB (default 200KB)
 * @param placeholderUrl: Optional placeholder image URL to show while main image loads
 * @param enableLongPress: Whether to enable long press gesture for context menu
 * */
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
    var menuPosition by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val mid = remember(imageUrl) { imageUrl.getMimeiKeyFromUrl() }
    var cachedBitmap by remember(mid) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // Try to load cached image on first composition
    LaunchedEffect(mid) {
        cachedBitmap = ImageCacheManager.getCachedImage(context, mid)
        // For full-size images, we still want to show cached image as placeholder if available
        // For non-full-size images, we can load and cache if not already cached
        if (cachedBitmap == null && !isFullSize && !isLoading) {
            isLoading = true
            // Download, compress, and cache the image
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            if (result is SuccessResult) {
                val bmp = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                if (bmp != null) {
                    ImageCacheManager.cacheImage(context, mid, bmp)
                    cachedBitmap = bmp
                }
            }
            isLoading = false
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
        if (cachedBitmap != null) {
            // Show cached image as placeholder for both full-size and preview images
            Image(
                bitmap = cachedBitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = adjustedModifier
            )
        }
        // Always try to load the original image (for full size or as replacement)
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = if (enableLongPress) {
                adjustedModifier.pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { offset ->
                            showMenu = true
                            menuPosition = offset
                        }
                    )
                }
            } else {
                adjustedModifier
            }
        )
        if (showMenu) {
            // Calculate DropdownMenu position
            var parentSize by remember { mutableStateOf(IntSize.Zero) }
            val density = LocalDensity.current

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier
                    .onGloballyPositioned { coordinates ->
                        parentSize = coordinates.size
                    }
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer),
                offset = DpOffset(
                    with(density) { menuPosition.x.toDp() },
                    with(density) { menuPosition.y.toDp() }
                )
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Download",
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    onClick = {
                        showMenu = false
                        // Download functionality can be implemented here if needed
                    },
                    modifier = Modifier.heightIn(max = 30.dp)
                )
            }
        }
    }
}

/**
 * Enhanced image viewer for full-screen mode that shows cached preview as placeholder
 * @param imageUrl: Full-size image URL
 * @param previewUrl: Preview image URL (should be cached by Coil)
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
    var menuPosition by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Box(modifier = modifier) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .crossfade(true)
                .placeholderMemoryCacheKey(previewUrl) // Use cached preview as placeholder
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = if (enableLongPress) {
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { offset ->
                                showMenu = true
                                menuPosition = offset
                            }
                        )
                    }
            } else {
                Modifier.fillMaxSize()
            }
        )
        
        if (showMenu) {
            // Calculate DropdownMenu position
            var parentSize by remember { mutableStateOf(IntSize.Zero) }
            val density = LocalDensity.current

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier
                    .onGloballyPositioned { coordinates ->
                        parentSize = coordinates.size
                    }
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer),
                offset = DpOffset(
                    with(density) { menuPosition.x.toDp() },
                    with(density) { menuPosition.y.toDp() }
                )
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Download",
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    onClick = {
                        showMenu = false
                        // Download functionality can be implemented here if needed
                    },
                    modifier = Modifier.heightIn(max = 30.dp)
                )
            }
        }
    }
}
