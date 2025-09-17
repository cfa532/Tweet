package us.fireshare.tweet.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.service.FileTypeDetector

@Composable
fun UploadFilePreview(uri: Uri, onCheckedChange: (Uri, Boolean) -> Unit) {
    val view = LocalView.current
    val viewWidth = with(LocalDensity.current) { view.width.toDp() }.value.toInt()
    val canvasSize = viewWidth / 4 - 20
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val contentResolver = LocalContext.current.contentResolver
    var isChecked by remember { mutableStateOf(true) }
    val context = LocalContext.current
    
    // Detect if the file is a video
    val isVideo = remember(uri) {
        try {
            val mediaType = FileTypeDetector.detectFileType(context, uri)
            mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO
        } catch (e: Exception) {
            false
        }
    }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val bitmap = contentResolver.loadThumbnail(
                    uri, android.util.Size(canvasSize, canvasSize), null
                )
                imageBitmap = bitmap.asImageBitmap()
            } catch (e: Exception) {
                Timber.tag("UploadFilePreview").e(e, "Error loading thumbnail")
            }
        }
    }
    Box(
        modifier = Modifier.size(canvasSize.dp)
    ) {
        // Image container with clipping
        Box(
            modifier = Modifier
                .size(canvasSize.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            imageBitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = stringResource(R.string.attached_file),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } ?: run {
                Icon(
                    painter = painterResource(id = R.drawable.ic_photo_plus),
                    contentDescription = stringResource(R.string.attached_file),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        
        // Play icon for videos
        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
                    .background(
                        color = Color.LightGray.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play video",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(18.dp)
                )
            }
        }
        
        // Checkbox positioned absolutely in lower right corner
        Checkbox(
            checked = isChecked,
            onCheckedChange = { onCheckedChange(uri, it) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(
                    x = (canvasSize - 32).dp,
                    y = (canvasSize - 32).dp
                )
        )
    }
}