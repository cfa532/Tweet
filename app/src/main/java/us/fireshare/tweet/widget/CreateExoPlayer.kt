package us.fireshare.tweet.widget

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId

private const val FEED_MAX_VIDEO_WIDTH = 1280
private const val FEED_MAX_VIDEO_HEIGHT = 1280
private const val FEED_MAX_VIDEO_BITRATE = 2_500_000

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
    resolvedHlsUrl: String? = null,
    minBufferMs: Int = 3_000,
    maxBufferMs: Int = 12_000,
    bufferForPlaybackMs: Int = 500,
    bufferForPlaybackAfterRebufferMs: Int = 1_000,
    maxVideoWidth: Int? = FEED_MAX_VIDEO_WIDTH,
    maxVideoHeight: Int? = FEED_MAX_VIDEO_HEIGHT,
    maxVideoBitrate: Int? = FEED_MAX_VIDEO_BITRATE
): ExoPlayer {
    val reliabilityMediaId = extractMediaMidFromUrl(url)

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
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                MediaCodecSelector.PREFER_SOFTWARE
                    .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                    .filter { it.softwareOnly }
                    .ifEmpty {
                        MediaCodecSelector.PREFER_SOFTWARE.getDecoderInfos(
                            mimeType,
                            requiresSecureDecoder,
                            requiresTunnelingDecoder
                        )
                    }
            }
            setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            setEnableDecoderFallback(false)
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
            minBufferMs,
            maxBufferMs,
            bufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    val trackSelector = DefaultTrackSelector(context).apply {
        val parameterBuilder = buildUponParameters()
        if (maxVideoWidth != null && maxVideoHeight != null) {
            parameterBuilder.setMaxVideoSize(maxVideoWidth, maxVideoHeight)
        }
        if (maxVideoBitrate != null) {
            parameterBuilder.setMaxVideoBitrate(maxVideoBitrate)
        }
        parameters = parameterBuilder.build()
    }

    val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .setRenderersFactory(renderersFactory)
        .setLoadControl(loadControl)
        .setTrackSelector(trackSelector)
        .build()
        .apply {
            // Add listener for HLS fallback logic.
            // When resolvedHlsUrl is provided we already know the correct URL, so the
            // fallback logic is a no-op (onPlayerError just logs and returns).
            addListener(object : Player.Listener {
                private var hasTriedPlaylist = false
                private var hasRecordedSuccess = false

                override fun onPlayerError(error: PlaybackException) {
                    val errorSummary = "Player error mediaId=$reliabilityMediaId type=$mediaType " +
                        "code=${error.errorCodeName}/${error.errorCode} state=${playerStateName(playbackState)} " +
                        "pos=${currentPosition}ms buffered=${bufferedPosition}ms duration=${duration}ms " +
                        "playWhenReady=$playWhenReady isPlaying=$isPlaying cause=${error.shortCauseName()}"
                    if (error.isExpectedNetworkPlaybackIssue()) {
                        Timber.tag("VideoPlaybackDebug").w(errorSummary)
                    } else {
                        Timber.tag("VideoPlaybackDebug").e(error, errorSummary)
                    }
                    reliabilityMediaId?.let { mediaId ->
                        CoroutineScope(Dispatchers.IO).launch {
                            HproseInstance.recordReliabilityFailureMedia(mediaId)
                        }
                    }
                    // Only handle HLS fallback for HLS_VIDEO type
                    if (mediaType != MediaType.HLS_VIDEO) {
                        Timber.tag("createExoPlayer").d("Progressive video error (no fallback): ${error.message}")
                        return
                    }

                    // If the URL was already resolved (via HlsUrlResolver), skip sequential
                    // fallback — we already probed in parallel and know the correct URL.
                    if (resolvedHlsUrl != null) {
                        if (error.isExpectedNetworkPlaybackIssue()) {
                            Timber.tag("createExoPlayer").w(
                                "HLS network issue on pre-resolved URL $resolvedHlsUrl: ${error.errorCodeName}"
                            )
                        } else {
                            Timber.tag("createExoPlayer").e("HLS error on pre-resolved URL $resolvedHlsUrl: ${error.message}")
                        }
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
                        if (error.isExpectedNetworkPlaybackIssue()) {
                            Timber.tag("createExoPlayer").w("All HLS attempts hit network issues for URL: $url")
                        } else {
                            Timber.tag("createExoPlayer").e("All HLS attempts failed for URL: $url")
                            Timber.tag("createExoPlayer").e("Final error: ${error.message}")
                        }
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            if (!hasRecordedSuccess && reliabilityMediaId != null) {
                                hasRecordedSuccess = true
                                CoroutineScope(Dispatchers.IO).launch {
                                    HproseInstance.recordReliabilitySuccessMedia(reliabilityMediaId)
                                }
                            }
                        }
                        Player.STATE_BUFFERING -> Unit
                        Player.STATE_IDLE -> Unit
                        Player.STATE_ENDED -> {
                            // Auto-rewind when video ends - applies to all videos (fullscreen, MediaItemView, DetailView, etc.)
                            this@apply.seekTo(0)
                            this@apply.playWhenReady = false
                        }
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    val delta = newPosition.positionMs - oldPosition.positionMs
                    val isLargeRollback = delta < -1_000
                    val tag = Timber.tag("VideoPlaybackDebug")
                    val message = "Position discontinuity mediaId=$reliabilityMediaId type=$mediaType " +
                        "reason=${discontinuityReasonName(reason)} old=${oldPosition.positionMs}ms new=${newPosition.positionMs}ms " +
                        "delta=${delta}ms state=${playerStateName(playbackState)} playWhenReady=$playWhenReady " +
                        "isPlaying=$isPlaying buffered=${bufferedPosition}ms duration=${duration}ms"
                    if (isLargeRollback) {
                        tag.w(message)
                    }
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
            mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(hlsUrl))
        }
        MediaType.Video -> {
            // For progressive videos: play URL directly
            mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(url))
        }
        else -> {
            // Default to progressive video for unknown types
            mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(url))
        }
    }
    
    player.setMediaSource(mediaSource)
    player.prepare()
    return player
}

private fun extractMediaMidFromUrl(url: String): MimeiId? {
    if (url.isBlank()) return null
    val normalized = url.substringBefore("?").trimEnd('/')
    val segments = normalized.split('/').filter { it.isNotBlank() }
    if (segments.isEmpty()) return null

    val mmIndex = segments.indexOfLast { it == "mm" }
    if (mmIndex >= 0 && mmIndex + 1 < segments.size) {
        return segments[mmIndex + 1]
    }

    val ipfsIndex = segments.indexOfLast { it == "ipfs" }
    if (ipfsIndex >= 0 && ipfsIndex + 1 < segments.size) {
        return segments[ipfsIndex + 1]
    }

    return null
}

private fun playerStateName(state: Int): String = when (state) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> "UNKNOWN($state)"
}

private fun discontinuityReasonName(reason: Int): String = when (reason) {
    Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
    Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
    Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
    Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
    Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
    Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
    else -> "UNKNOWN($reason)"
}
