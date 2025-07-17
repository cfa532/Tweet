package us.fireshare.tweet.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import us.fireshare.tweet.R


/**
 * Image viewer that loads images directly without caching
 * @param imageUrl: Image URL in the format of http://ip/ipfs/mimeiId or http://ip/mm/mimeiId
 * @param isFullSize: If true, shows full-size image
 * @param imageSize: Preview size in KB (default 200KB)
 * */
@Composable
fun ImageViewer(
    imageUrl: String,
    modifier: Modifier = Modifier,
    isFullSize: Boolean = false,
    imageSize: Int = 200,    // Preview size in KB
    enableLongPress: Boolean = true
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var menuPosition by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    val adjustedModifier = if (isFullSize) {
        modifier.fillMaxWidth()     // Full-size image takes full width
    } else {
        modifier.fillMaxSize()      // Preview image fits within parent
    }

    Box(modifier = modifier) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = if (enableLongPress) {
                adjustedModifier
                    .pointerInput(Unit) {
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
