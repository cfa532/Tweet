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
            // Add listener for debugging and HLS fallback only
            addListener(object : androidx.media3.common.Player.Listener {
                private var hasTriedPlaylist = false
                private var hasTriedOriginal = false

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // Only handle HLS fallback, let VideoPreview handle retries
                    if (!hasTriedPlaylist) {
                        hasTriedPlaylist = true
                        Timber.tag("createExoPlayer").d("Trying playlist.m3u8 fallback")

                        // If master.m3u8 fails, try playlist.m3u8
                        val fallbackMediaSource = mediaSourceFactory.createMediaSource(
                            androidx.media3.common.MediaItem.fromUri(playlistUrl)
                        )
                        setMediaSource(fallbackMediaSource)
                        prepare()
                    } else if (!hasTriedOriginal) {
                        hasTriedOriginal = true
                        Timber.tag("createExoPlayer").d("Trying original URL fallback")

                        // If both HLS attempts fail, try the original URL (progressive video)
                        val originalMediaSource = mediaSourceFactory.createMediaSource(
                            androidx.media3.common.MediaItem.fromUri(url)
                        )
                        setMediaSource(originalMediaSource)
                        prepare()
                    } else {
                        // Log the final failure - VideoPreview will handle retries
                        Timber.tag("createExoPlayer")
                            .d("All fallback attempts failed for URL: $url")
                        Timber.tag("createExoPlayer").d("Final error: ${error.message}")
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

    // Start with master.m3u8 (try HLS first)
    val mediaSource =
        mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(masterUrl))
    player.setMediaSource(mediaSource)

    // Prepare the player immediately after setting up the listener
    player.prepare()
    return player
}