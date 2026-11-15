package us.fireshare.tweet.widget

import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import androidx.core.net.toUri
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.service.FileTypeDetector

@Composable
fun UploadFilePreview(uri: Uri, onCheckedChange: (Uri, Boolean) -> Unit, enabled: Boolean = true) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val fileName = remember(uri) {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    }
    val mediaType = remember(uri, fileName) {
        try {
            FileTypeDetector.detectFileType(context, uri, fileName)
        } catch (e: Exception) {
            MediaType.Unknown
        }
    }

    AttachmentPreviewTile(mediaType, enabled, onRemove = { onCheckedChange(uri, false) }) { canvasSize ->
        if (mediaType == MediaType.Audio) {
            AudioAttachmentPlaybackPreview(uri, Modifier.fillMaxSize())
        } else {
            var imageBitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(uri, canvasSize) {
                imageBitmap = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.loadThumbnail(uri, android.util.Size(canvasSize, canvasSize), null)
                            .asImageBitmap()
                    } catch (e: Exception) {
                        Timber.tag("UploadFilePreview").e(e, "Error loading thumbnail")
                        null
                    }
                }
            }
            val thumbnail = imageBitmap
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = stringResource(R.string.attached_file),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AttachmentPreviewPlaceholder()
            }
        }
    }
}

@Composable
internal fun RetainedFilePreview(
    attachment: MimeiFileType,
    baseUrl: String?,
    onRemove: () -> Unit,
    enabled: Boolean
) {
    val url = attachment.url ?: HproseInstance.getMediaUrl(attachment.mid, baseUrl)
    AttachmentPreviewTile(attachment.type, enabled, onRemove) {
        when {
            attachment.type == MediaType.Image && url != null ->
                ImageViewer(
                    imageUrl = url,
                    imageMid = attachment.mid,
                    modifier = Modifier.fillMaxSize(),
                    enableLongPress = false,
                    useFillMode = true
                )
            attachment.type == MediaType.Audio && url != null ->
                AudioAttachmentPlaybackPreview(url.toUri(), Modifier.fillMaxSize())
            else -> AttachmentPreviewPlaceholder()
        }
    }
}

@Composable
private fun AttachmentPreviewTile(
    mediaType: MediaType,
    enabled: Boolean,
    onRemove: () -> Unit,
    preview: @Composable (Int) -> Unit
) {
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val viewWidth = with(LocalDensity.current) { windowWidth.toDp().value.toInt() }
    val canvasSize = (viewWidth / 4 - 20).coerceAtLeast(1)
    val isAudio = mediaType == MediaType.Audio
    val previewWidth = if (isAudio) (viewWidth - 48).coerceAtLeast(240) else canvasSize
    val previewHeight = if (isAudio) 96 else canvasSize

    Box(modifier = Modifier.size(previewWidth.dp, previewHeight.dp)) {
        Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
            preview(canvasSize)
        }
        if (mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
                    .background(Color.LightGray.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.play_video),
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(18.dp)
                )
            }
        }
        Checkbox(
            checked = true,
            onCheckedChange = { if (!it) onRemove() },
            enabled = enabled,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (previewWidth - 32).dp, y = (previewHeight - 32).dp)
        )
    }
}

@Composable
private fun AttachmentPreviewPlaceholder() {
    Icon(
        painter = painterResource(id = R.drawable.ic_photo_plus),
        contentDescription = stringResource(R.string.attached_file),
        modifier = Modifier.fillMaxSize()
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun AudioAttachmentPlaybackPreview(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                player = exoPlayer
                useController = true
                controllerAutoShow = true
                controllerShowTimeoutMs = -1
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
        },
        update = { playerView ->
            if (playerView.player !== exoPlayer) {
                playerView.player = exoPlayer
            }
        },
        modifier = modifier.background(Color.Black)
    )
}
