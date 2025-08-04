package us.fireshare.tweet.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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

@Composable
fun UploadFilePreview(uri: Uri, onCheckedChange: (Uri, Boolean) -> Unit) {
    val view = LocalView.current
    val viewWidth = with(LocalDensity.current) { view.width.toDp() }.value.toInt()
    val canvasSize = viewWidth / 4 - 20
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val contentResolver = LocalContext.current.contentResolver
    var isChecked by remember { mutableStateOf(true) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val bitmap = contentResolver.loadThumbnail(
                    uri, android.util.Size(canvasSize, canvasSize), null
                )
                val resizedBitmap =
                    createBitmap(canvasSize, canvasSize)
                val canvas = Canvas(resizedBitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()

                val (scaledWidth, scaledHeight) = if (aspectRatio > 1) {
                    // Landscape image
                    canvasSize to (canvasSize / aspectRatio).toInt()
                } else {
                    // Portrait or square image
                    (canvasSize * aspectRatio).toInt() to canvasSize
                }

                val xOffset = (canvasSize - scaledWidth) / 2
                val yOffset = (canvasSize - scaledHeight) / 2
                canvas.drawBitmap(
                    bitmap,
                    null,
                    Rect(xOffset, yOffset, xOffset + scaledWidth, yOffset + scaledHeight),
                    paint
                )
                imageBitmap = resizedBitmap.asImageBitmap()
            } catch (e: Exception) {
                Timber.tag("UploadFilePreview").e(e, "Error loading thumbnail")
            }
        }
    }
    Box(
        modifier = Modifier
            .size(canvasSize.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        imageBitmap?.let {
            Image(
                bitmap = it,
                contentDescription = stringResource(R.string.attached_file),
                modifier = Modifier
                    .size(canvasSize.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } ?: run {
            Icon(
                painter = painterResource(id = R.drawable.ic_photo_plus),
                contentDescription = stringResource(R.string.attached_file),
                modifier = Modifier
                    .size(canvasSize.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
        Checkbox(
            checked = isChecked,
            onCheckedChange = { onCheckedChange(uri, it) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp) // Add some padding for better visibility
        )
    }
}