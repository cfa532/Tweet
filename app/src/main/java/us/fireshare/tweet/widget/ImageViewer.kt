package us.fireshare.tweet.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl


/**
 * Image viewer that caches compressed previews and loads full-size images on demand
 * @param imageUrl: Image URL in the format of http://ip/ipfs/mimeiId or http://ip/mm/mimeiId
 * @param isFullSize: If true, shows full-size image (loads from backend without caching)
 * @param imageSize: Preview cache size in KB (default 200KB)
 * */
@Composable
fun ImageViewer(
    imageUrl: String,
    modifier: Modifier = Modifier,
    isFullSize: Boolean = false,
    imageSize: Int = 200    // Preview cache size in KB
) {
    val context = LocalContext.current
    val cacheManager = remember { CacheManager(context) }
    val imageMid = imageUrl.getMimeiKeyFromUrl()
    val cachedPath = rememberUpdatedState(cacheManager.getCachedImagePath(imageMid))

    var isDownloading by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf(false) }
    var fullSizeImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoadingFullSize by remember { mutableStateOf(false) }

    var showMenu by remember { mutableStateOf(false) }
    var menuPosition by remember { mutableStateOf(Offset.Zero) }
    val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Check if preview is already cached
    val cachedPreview = remember(imageMid) {
        mutableStateOf(cacheManager.loadImageFromCache(cachedPath.value))
    }
    
    val adjustedModifier = if (isFullSize) {
        modifier.fillMaxWidth()     // Full-size image takes full width
    } else {
        modifier.fillMaxSize()      // Preview image fits within parent
    }

    // Load preview if not cached
    LaunchedEffect(imageMid) {
        if (cachedPreview.value == null) {
            downloadScope.launch {
                isDownloading = true
                downloadError = false
                val downloadedPath = cacheManager.downloadImageToCache(imageUrl)

                isDownloading = false
                if (downloadedPath != null) {
                    cachedPreview.value = cacheManager.loadImageFromCache(downloadedPath)
                } else {
                    downloadError = true
                }
            }
        }
    }

    // Load full-size image when needed
    LaunchedEffect(imageMid, isFullSize) {
        if (isFullSize && fullSizeImage == null && !isLoadingFullSize) {
            downloadScope.launch {
                isLoadingFullSize = true
                fullSizeImage = cacheManager.loadFullSizeImage(imageUrl)
                isLoadingFullSize = false
            }
        }
    }

    // Determine which image to show
    val imageToShow = if (isFullSize) {
        fullSizeImage ?: cachedPreview.value // Show full-size if available, otherwise show preview
    } else {
        cachedPreview.value // Always show preview for non-full-size
    }

    if (imageToShow != null) {
        Box(modifier = modifier) {
            Image(
                painter = BitmapPainter(imageToShow),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = adjustedModifier
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            // Wait for the first down event
                            val down = awaitFirstDown()

                            // Variable to track if a long press was detected
                            var longPressDetected = false

                            // Check for a long press
                            val longPress = awaitLongPressOrCancellation(down.id)
                            if (longPress != null) {
                                // Long press detected
                                longPressDetected = true
                                showMenu = true
                                menuPosition = longPress.position
                            }

                            // Wait for the up event or cancellation
                            // If the gesture is released before a long press is detected
                            if (waitForUpOrCancellation() != null && !longPressDetected) {
                                // Allow the event to propagate up for single click handling
                                // Do not consume the event here
                            }
                        }
                    }
            )
            
            // Show loading indicator for full-size images while loading
            if (isFullSize && isLoadingFullSize && fullSizeImage == null) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                )
            }
            
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
                        .wrapContentWidth()
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
                            downloadScope.launch {
                                downloadImage(context, imageUrl)
                            }
                        },
                        modifier = Modifier.heightIn(max = 30.dp)
                    )
                }
            }
        }
    } else if (isDownloading || downloadError) {
        Box(
            modifier = adjustedModifier
                .fillMaxWidth()
                .background(Color.LightGray) // Gray background
        ) {
            if (isFullSize) {
                // For full-size images, show loading indicator
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .heightIn(min = 400.dp)
                )
            } else {
                // For previews, show placeholder
                Image(
                    painter = painterResource(id = R.drawable.ic_user_avatar),
                    contentDescription = "Placeholder Avatar",
                    modifier = modifier
                        .size(imageSize.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
        }
    } else {
        // Display placeholder for non-existent resource
        Box(
            modifier = adjustedModifier
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_user_avatar),
                contentDescription = "Placeholder Avatar",
                modifier = modifier
                    .size(imageSize.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }
    }
}
