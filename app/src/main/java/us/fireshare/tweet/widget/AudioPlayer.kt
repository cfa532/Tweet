package us.fireshare.tweet.widget

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser

@OptIn(UnstableApi::class)
@Composable
fun AudioPlayer(
    attachments: List<MimeiFileType>,
    initialIndex: Int = 0,
) {
    if (attachments.isEmpty()) {
        return
    }
    val context = LocalContext.current
    val aspectRatio by remember { mutableFloatStateOf(16 / 9f) }
    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    val resolvedAttachments = remember(attachments) {
        attachments.mapNotNull { attachment ->
            val resolvedUrl = attachment.url?.takeIf { it.isNotBlank() }
                ?: HproseInstance.getMediaUrl(attachment.mid, appUser.baseUrl)
            resolvedUrl?.let { attachment.copy(url = it) }
        }
    }

    if (resolvedAttachments.isEmpty()) {
        return
    }

    LaunchedEffect(resolvedAttachments.size) {
        if (currentIndex >= resolvedAttachments.size) {
            currentIndex = 0
        }
    }

    // Create a Player.Listener outside the DisposableEffect
    val playerListener = remember {
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && currentIndex < resolvedAttachments.size - 1) {
                    currentIndex = (currentIndex + 1) % resolvedAttachments.size
                    exoPlayer.seekTo(currentIndex, 0)
                    exoPlayer.playWhenReady = true
                }
            }
        }
    }
    // Create a list of MediaItems from the attachments
    val mediaItems = remember(resolvedAttachments) {
        resolvedAttachments.mapNotNull { attachment ->
            attachment.url?.let { url -> androidx.media3.common.MediaItem.fromUri(url) }
        }
    }

    if (mediaItems.isEmpty()) {
        return
    }
    // Add the media items to the ExoPlayer
    LaunchedEffect(mediaItems) {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.addMediaItems(mediaItems)
        val targetIndex = currentIndex.coerceIn(0, mediaItems.lastIndex)
        exoPlayer.prepare()
        exoPlayer.seekTo(targetIndex, 0)
        exoPlayer.playWhenReady = true
        currentIndex = targetIndex
    }
    // Listen for playback completion
    DisposableEffect(exoPlayer) {
        exoPlayer.addListener(playerListener)
        onDispose {
            exoPlayer.removeListener(playerListener)
            exoPlayer.release()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            .heightIn(max = 800.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 8.dp) // Add padding to the top and bottom
    ) {
        items(resolvedAttachments, key = { it.mid }) { attachment ->
            val itemIndex = resolvedAttachments.indexOf(attachment)
            val isSelected = currentIndex == itemIndex
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable {
                        currentIndex = itemIndex
                        exoPlayer.seekTo(currentIndex, 0) // Seek to the selected track
                        exoPlayer.playWhenReady = true
                    }
                    .background(
                        if (isSelected) {
                            Color.LightGray
                        } else {
                            Color.Transparent
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.btn_play),
                    contentDescription = stringResource(R.string.play),
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = attachment.fileName ?: attachment.mid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    controllerShowTimeoutMs = -1    // show controls all the time.
                    controllerAutoShow = true
                    // Force hardware acceleration and proper clipping for Media3 1.7.1
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                }
            },
            modifier = Modifier
                .aspectRatio(aspectRatio)
                .clipToBounds() // Ensure content is clipped to bounds
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun AudioPreview(
    mediaItems: List<MimeiFileType>,
    index: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.btn_play),
                contentDescription = stringResource(R.string.play),
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = mediaItems[index].fileName ?: mediaItems[index].mid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
