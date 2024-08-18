package com.fireshare.tweet.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.fireshare.tweet.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun UploadFilePreview(uri: Uri) {
    val view = LocalView.current
    val viewWidth = with(LocalDensity.current) { view.width.toDp() }.value.toInt()
    val canvasSize = viewWidth / 2 - 20
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val contentResolver = LocalContext.current.contentResolver

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val bitmap = contentResolver.loadThumbnail(
                    uri, android.util.Size(canvasSize, canvasSize), null
                )
                val resizedBitmap =
                    Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
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
                println("Canvas Size: $canvasSize dp")
                println("Thumbnail Size: ${bitmap.width} x ${bitmap.height}")
                imageBitmap?.let {
                    println("ImageBitmap Size: ${it.width} x ${it.height}")
                }
            } catch (e: Exception) {
                println("Error loading thumbnail: ${e.message}")
            }
        }
    }

    imageBitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Attached File",
            modifier = Modifier.size(canvasSize.dp)
        )
    } ?: run {
        Icon(
            painter = painterResource(id = R.drawable.ic_photo_plus),
            contentDescription = "Attached File",
            modifier = Modifier.size(canvasSize.dp)
        )
    }
}