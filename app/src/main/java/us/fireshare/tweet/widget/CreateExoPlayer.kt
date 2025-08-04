package us.fireshare.tweet.widget

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import timber.log.Timber
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.Tweet

/**
 * Creates an ExoPlayer instance that handles video data blobs with HLS fallback
 *
 * @param context Android context
 * @param url Video URL (data blob)
 * @param mediaType Optional MediaType (not used in this system)
 * @return Configured ExoPlayer instance
 */
@OptIn(UnstableApi::class)
fun createExoPlayer(context: Context, url: String, mediaType: MediaType? = null): ExoPlayer {
    val dataSourceFactory = DefaultDataSource.Factory(context)

    // For data blobs, try HLS first, then fallback to original URL
    val baseUrl = if (url.endsWith("/")) url else "$url/"
    val masterUrl = "${baseUrl}master.m3u8"
    val playlistUrl = "${baseUrl}playlist.m3u8"

    // Use DefaultMediaSourceFactory which automatically handles HLS and progressive
    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

    val player = ExoPlayer.Builder(context)
        .build()
        .apply {
            // Add comprehensive listener for debugging and fallback
            addListener(object : androidx.media3.common.Player.Listener {
                private var hasTriedPlaylist = false
                private var hasTriedOriginal = false

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Timber.tag("createExoPlayer").e("Player error: ${error.message}")

                    if (!hasTriedPlaylist) {
                        hasTriedPlaylist = true

                        // If master.m3u8 fails, try playlist.m3u8
                        val fallbackMediaSource = mediaSourceFactory.createMediaSource(
                            androidx.media3.common.MediaItem.fromUri(playlistUrl)
                        )
                        setMediaSource(fallbackMediaSource)
                        prepare()
                    } else if (!hasTriedOriginal) {
                        hasTriedOriginal = true

                        // If both HLS attempts fail, try the original URL (progressive video)
                        val originalMediaSource = mediaSourceFactory.createMediaSource(
                            androidx.media3.common.MediaItem.fromUri(url)
                        )
                        setMediaSource(originalMediaSource)
                        prepare()
                    } else {
                        Timber.e("createExoPlayer - All fallback attempts failed for URL: $url")
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                }

                override fun onIsLoadingChanged(isLoading: Boolean) {
                }
            })
        }

    // Start with master.m3u8 (try HLS first)
    val mediaSource = mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(masterUrl))
    player.setMediaSource(mediaSource)

    // Prepare the player immediately after setting up the listener
    player.prepare()
    return player
}