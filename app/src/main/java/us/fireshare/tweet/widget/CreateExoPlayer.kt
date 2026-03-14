package us.fireshare.tweet.widget

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import timber.log.Timber
import us.fireshare.tweet.datamodel.MediaType

/**
 * Creates an ExoPlayer instance with type-specific video handling:
 * - MediaType.Video: Plays URL directly as progressive video
 * - MediaType.HLS_VIDEO: Uses [resolvedHlsUrl] when available (no guessing needed),
 *   otherwise tries master.m3u8 first and falls back to playlist.m3u8 on error.
 *
 * Note: "Unexpected start code prefix" warnings from PesReader are common with HLS streams
 * and typically don't affect playback quality. These warnings indicate minor stream formatting
 * issues that ExoPlayer can handle gracefully.
 *
 * @param context Android context
 * @param url Video base URL
 * @param mediaType MediaType to determine playback strategy
 * @param forceSoftwareDecoder If true, forces software decoder usage to avoid MediaCodec failures
 * @param resolvedHlsUrl Pre-resolved HLS playlist URL from [HlsUrlResolver]; when non-null the
 *   master/playlist guessing and fallback listener are skipped entirely.
 * @return Configured ExoPlayer instance
 */
@OptIn(UnstableApi::class)
fun createExoPlayer(
    context: Context,
    url: String,
    mediaType: MediaType? = null,
    forceSoftwareDecoder: Boolean = false,
    resolvedHlsUrl: String? = null
): ExoPlayer {
    // Create HTTP data source with extended timeouts for network congestion
    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(30000) // 30 seconds connection timeout
        .setReadTimeoutMs(30000)    // 30 seconds read timeout
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent("TweetApp/1.0")

    val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
    val cache = VideoManager.getCache(context)
    val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setCacheKeyFactory(MediaIdCacheKeyFactory()) // Use media ID as cache key
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    // Use DefaultMediaSourceFactory backed by CacheDataSource which handles HLS and progressive
    val mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)

    // Create ExoPlayer with enhanced software decoder fallback for MediaCodec failures
    val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context).apply {
        if (forceSoftwareDecoder) {
            // Force software decoder usage to avoid MediaCodec failures
            setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setEnableDecoderFallback(false) // Disable hardware decoder fallback
            Timber.tag("createExoPlayer").w("🔧 FORCING SOFTWARE DECODER for URL: $url")
        } else {
            // Prefer hardware decoders but allow software fallback
            setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true) // Enable hardware decoder fallback
        }
    }

    // Moderate buffering to balance smooth playback with memory usage on low-end devices
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            15_000,   // min buffer (15s) - reduced from 50s to save RAM
            50_000,   // max buffer (50s) - reduced from 120s to save ~100MB RAM
            1_000,    // buffer for playback (1s) - faster initial start
            2_000     // buffer for playback after rebuffer (2s)
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .setRenderersFactory(renderersFactory)
        .setLoadControl(loadControl)
        .build()
        .apply {
            // Add listener for HLS fallback logic.
            // When resolvedHlsUrl is provided we already know the correct URL, so the
            // fallback logic is a no-op (onPlayerError just logs and returns).
            addListener(object : androidx.media3.common.Player.Listener {
                private var hasTriedPlaylist = false

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // Only handle HLS fallback for HLS_VIDEO type
                    if (mediaType != MediaType.HLS_VIDEO) {
                        Timber.tag("createExoPlayer").d("Progressive video error (no fallback): ${error.message}")
                        return
                    }

                    // If the URL was already resolved (via HlsUrlResolver), skip sequential
                    // fallback — we already probed in parallel and know the correct URL.
                    if (resolvedHlsUrl != null) {
                        Timber.tag("createExoPlayer").e("HLS error on pre-resolved URL $resolvedHlsUrl: ${error.message}")
                        return
                    }

                    // For HLS videos: try playlist.m3u8 fallback only once
                    if (!hasTriedPlaylist) {
                        hasTriedPlaylist = true
                        Timber.tag("createExoPlayer").d("HLS master.m3u8 failed, trying playlist.m3u8 fallback")

                        // Construct playlist URL
                        val baseUrl = if (url.endsWith("/")) url else "$url/"
                        val playlistUrl = "${baseUrl}playlist.m3u8"

                        // Try playlist.m3u8 fallback
                        val fallbackMediaSource = mediaSourceFactory.createMediaSource(
                            androidx.media3.common.MediaItem.fromUri(playlistUrl)
                        )
                        setMediaSource(fallbackMediaSource)
                        prepare()
                    } else {
                        // Final failure - no more fallbacks
                        Timber.tag("createExoPlayer").e("All HLS attempts failed for URL: $url")
                        Timber.tag("createExoPlayer").e("Final error: ${error.message}")
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        androidx.media3.common.Player.STATE_READY -> {
                            Timber.tag("createExoPlayer").d("Player ready for URL: $url (type: $mediaType, software: $forceSoftwareDecoder)")
                        }
                        androidx.media3.common.Player.STATE_BUFFERING -> {
                            Timber.tag("createExoPlayer").d("Player buffering for URL: $url")
                        }
                        androidx.media3.common.Player.STATE_IDLE -> {
                            Timber.tag("createExoPlayer").d("Player idle for URL: $url")
                        }
                        androidx.media3.common.Player.STATE_ENDED -> {
                            // Auto-rewind when video ends - applies to all videos (fullscreen, MediaItemView, DetailView, etc.)
                            Timber.tag("createExoPlayer").d("Player ended for URL: $url, rewinding to beginning")
                            this@apply.seekTo(0)
                            this@apply.playWhenReady = false
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Timber.tag("createExoPlayer").d("Player playing state changed: $isPlaying for URL: $url")
                }
            })
        }

    // Create media source based on video type.
    // For HLS: use resolvedHlsUrl when available (no guessing), otherwise guess master.m3u8.
    val mediaSource = when (mediaType) {
        MediaType.HLS_VIDEO -> {
            val hlsUrl = resolvedHlsUrl ?: run {
                val baseUrl = if (url.endsWith("/")) url else "$url/"
                "${baseUrl}master.m3u8"
            }
            Timber.tag("createExoPlayer").d("Creating HLS media source: $hlsUrl (pre-resolved=${resolvedHlsUrl != null})")
            mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(hlsUrl))
        }
        MediaType.Video -> {
            // For progressive videos: play URL directly
            Timber.tag("createExoPlayer").d("Creating progressive media source with URL: $url")
            mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(url))
        }
        else -> {
            // Default to progressive video for unknown types
            Timber.tag("createExoPlayer").d("Unknown media type '$mediaType', defaulting to progressive video: $url")
            mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(url))
        }
    }
    
    player.setMediaSource(mediaSource)
    player.prepare()
    return player
}