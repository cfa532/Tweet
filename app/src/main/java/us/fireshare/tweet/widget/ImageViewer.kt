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
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.getMimeiKeyFromUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Download and compress image in cache
 * @param imageUrl: Leither image url in the format of
 *                  http://ip/ipfs/mimeiId
 * @param isPreview: download the original image to cache without compression if False
 * @param imageSize: compress download image below imageSize(KB)
 * */
@Composable
fun ImageViewer(
    imageUrl: String,
    modifier: Modifier = Modifier,
    isPreview: Boolean = true,
    imageSize: Int = 200    // Cache size in KB
) {
    val context = LocalContext.current
    val cacheManager = remember { CacheManager(context) }
    val cachedPath = rememberUpdatedState(cacheManager.getCachedImagePath(imageUrl, isPreview))

    var isDownloading by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf(false) }

    var showMenu by remember { mutableStateOf(false) }
    var menuPosition by remember { mutableStateOf(Offset.Zero) }
    val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Check if image is already cached. Use it directly if so.
    val cachedImage = remember(imageUrl.getMimeiKeyFromUrl()) {
        mutableStateOf(cacheManager.loadImageFromCache(cachedPath.value))
    }
    val adjustedModifier = if (isPreview) {
        modifier.fillMaxSize()      // image is viewed within a parent composable
    } else {
        modifier.fillMaxWidth()     // image is viewed full screen
    }

    LaunchedEffect(imageUrl) {
        if (cachedImage.value == null) {
            downloadScope.launch {
                isDownloading = true
                downloadError = false
                val downloadedPath =
                    cacheManager.downloadImageToCache(imageUrl, isPreview, imageSize)

                isDownloading = false
                if (downloadedPath != null) {
                    cachedImage.value = cacheManager.loadImageFromCache(downloadedPath)
                } else {
                    downloadError = true
                }
            }
        }
    }

    if (cachedImage.value != null) {
        Box(modifier = modifier) {
            Image(
                painter = BitmapPainter(cachedImage.value!!),
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
    } else if (isDownloading || downloadError) { // Combined conditions
        Box(
            modifier = adjustedModifier
                .fillMaxWidth()
                .background(Color.LightGray) // Gray background
        ) {
            if (!isPreview) {
                if (cacheManager.isCached(imageUrl, true)) {
                    val placeholderImage = cacheManager.loadImageFromCache(
                        cacheManager.getCachedImagePath(imageUrl, true)
                    )
                    if (placeholderImage != null) {
                        Image(
                            painter = BitmapPainter(placeholderImage),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = adjustedModifier
                        )
                    }
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .heightIn(min = 400.dp)
                    )
                }
            }
        }
    } else {
        // Display placeholder for non-existent resource
        Box(
            modifier = adjustedModifier
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (cacheManager.isCached(imageUrl, true)) {
                val placeholderImage = cacheManager.loadImageFromCache(
                    cacheManager.getCachedImagePath(imageUrl, true)
                )
                if (placeholderImage != null) {
                    Image(
                        painter = BitmapPainter(placeholderImage),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = adjustedModifier
                    )
                }
            } else {
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
}
