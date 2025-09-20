package us.fireshare.tweet.widget

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import timber.log.Timber
import us.fireshare.tweet.datamodel.MediaType

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
    // Create HTTP data source with extended timeouts for network congestion
    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(30000) // 30 seconds connection timeout (increased from default)
        .setReadTimeoutMs(30000)    // 30 seconds read timeout (increased from default)
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent("TweetApp/1.0")

    val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
    val cache = VideoManager.getCache(context)
    val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    // For data blobs, try HLS first, then fallback to original URL
    val baseUrl = if (url.endsWith("/")) url else "$url/"
    val masterUrl = "${baseUrl}master.m3u8"
    val playlistUrl = "${baseUrl}playlist.m3u8"

    // Use DefaultMediaSourceFactory backed by CacheDataSource which handles HLS and progressive
    val mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)

    val player = ExoPlayer.Builder(context)
        .build()
        .apply {
            // Add comprehensive listener for debugging and fallback
            addListener(object : androidx.media3.common.Player.Listener {
                private var hasTriedPlaylist = false
                private var hasTriedOriginal = false
                private var retryCount = 0
                private val maxRetries = 2

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // For progressive videos, don't try HLS fallback
                    if (mediaType != MediaType.HLS_VIDEO) {
                        Timber.tag("createExoPlayer").d("Progressive video error: ${error.message}")
                        return
                    }
                    
                    // For HLS videos, try playlist fallback only
                    if (!hasTriedPlaylist) {
                        hasTriedPlaylist = true
                        Timber.tag("createExoPlayer").d("Trying playlist.m3u8 fallback")

                        // If master.m3u8 fails, try playlist.m3u8
                        val fallbackMediaSource = mediaSourceFactory.createMediaSource(
                            androidx.media3.common.MediaItem.fromUri(playlistUrl)
                        )
                        setMediaSource(fallbackMediaSource)
                        prepare()
                    } else {
                        // Only log the final failure as an error
                        Timber.tag("createExoPlayer")
                            .e("All HLS fallback attempts failed for URL: $url")
                        Timber.tag("createExoPlayer").e("Final error: ${error.message}")
                        Timber.tag("createExoPlayer").e("Final error cause: ${error.cause}")
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        androidx.media3.common.Player.STATE_READY -> {
                            Timber.tag("createExoPlayer").d("Player ready for URL: $url")
                        }

                        androidx.media3.common.Player.STATE_BUFFERING -> {
                            Timber.tag("createExoPlayer").d("Player buffering for URL: $url")
                        }

                        androidx.media3.common.Player.STATE_IDLE -> {
                            Timber.tag("createExoPlayer").d("Player idle for URL: $url")
                        }

                        androidx.media3.common.Player.STATE_ENDED -> {
                            Timber.tag("createExoPlayer").d("Player ended for URL: $url")
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Timber.tag("createExoPlayer")
                        .d("Player playing state changed: $isPlaying for URL: $url")
                }

                override fun onIsLoadingChanged(isLoading: Boolean) {
                    // Reduced logging to avoid spam during video operations
                    // Timber.tag("createExoPlayer")
                    //     .d("Player loading state changed: $isLoading for URL: $url")
                }
            })
        }

    // Create media source based on video type
    val mediaSource = if (mediaType == MediaType.HLS_VIDEO) {
        // For HLS videos, start with master.m3u8
        mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(masterUrl))
    } else {
        // For progressive videos, use original URL directly
        mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(url))
    }
    player.setMediaSource(mediaSource)

    // Prepare the player immediately after setting up the listener
    player.prepare()
    return player
}