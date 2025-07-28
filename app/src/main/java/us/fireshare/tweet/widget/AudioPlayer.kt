package us.fireshare.tweet.widget

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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

@OptIn(UnstableApi::class)
@Composable
fun AudioPlayer(
    attachments: List<MimeiFileType>,
    initialIndex: Int = 0,
) {
    val context = LocalContext.current
    val aspectRatio by remember { mutableFloatStateOf(16 / 9f) }
    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    // Create a Player.Listener outside the DisposableEffect
    val playerListener = remember {
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && currentIndex < attachments.size - 1) {
                    currentIndex = (currentIndex + 1) % attachments.size
                    exoPlayer.seekTo(currentIndex, 0)
                    exoPlayer.playWhenReady = true
                }
            }
        }
    }
    // Create a list of MediaItems from the attachments
    val mediaItems = remember {
        attachments.map {
            androidx.media3.common.MediaItem.fromUri(it.url!!)
        }
    }
    // Add the media items to the ExoPlayer
    LaunchedEffect(key1 = mediaItems) {
        exoPlayer.addMediaItems(mediaItems)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }
    LaunchedEffect(currentIndex) {
        println("current index = $currentIndex")
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
        items(attachments, key = { it.mid }) {
            val isSelected = currentIndex == attachments.indexOf(it)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable {
                        currentIndex = attachments.indexOf(it)
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
                    text = it.fileName ?: it.mid,
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
                }
            },
            modifier = Modifier.aspectRatio(aspectRatio)
        )
    }
}
