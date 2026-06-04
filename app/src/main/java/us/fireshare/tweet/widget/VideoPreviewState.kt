package us.fireshare.tweet.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.preferenceHelper
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId

/**
 * State holder for VideoPreview composable.
 * Extracts all mutable state, error handling, and retry logic
 * to keep the composable lightweight.
 */
@Stable
class VideoPreviewState(
    val videoMid: MimeiId?,
    val useIndependentMuteState: Boolean,
    initialMuted: Boolean,
    initialLoading: Boolean
) {
    var isVideoVisible by mutableStateOf(false)
    var lastVisibilityUpdate by mutableLongStateOf(0L)
    var lastVisibilityRatio by mutableStateOf(0f)
    var hasLastGeometry by mutableStateOf(false)
    var lastVideoTop by mutableStateOf(0f)
    var lastVideoBottom by mutableStateOf(0f)
    var lastViewportTop by mutableStateOf(0f)
    var lastViewportBottom by mutableStateOf(0f)
    var isMuted by mutableStateOf(initialMuted)
    var isLoading by mutableStateOf(initialLoading)
    var hasError by mutableStateOf(false)
    var showTimeLabel by mutableStateOf(false)
    var remainingTime by mutableLongStateOf(0L)
    var retryCount by mutableIntStateOf(0)
    var isMediaCodecRecoveryInProgress by mutableStateOf(false)
    var isNetworkRecoveryInProgress by mutableStateOf(false)
    var blockAutoPrepareAfterError by mutableStateOf(false)
    var showControls by mutableStateOf(false)
    var coordinatorWantsToPlay by mutableStateOf(false)
    var isPlaying by mutableStateOf(false)
    private var hasVisiblePlaybackProgress by mutableStateOf(false)
    private var lastObservedPositionMs by mutableLongStateOf(0L)
    private var lastObservedBufferedMs by mutableLongStateOf(0L)
    private var stagnantPlaybackTicks by mutableIntStateOf(0)
    private var stagnantBufferTicks by mutableIntStateOf(0)
    private var stillFrameRecoveryAttempts by mutableIntStateOf(0)

    val maxRetries = 3
    val visibilityUpdateThrottleMs = 100L

    fun toggleMute() {
        isMuted = !isMuted
    }

    /**
     * Handle playback state change from Player.Listener.onPlaybackStateChanged.
     * Called from the listener created in the composable.
     */
    fun onPlaybackStateChanged(
        playbackState: Int,
        player: Player,
        shouldUseCoordinator: Boolean,
        autoPlay: Boolean,
        isVisible: Boolean,
        coordinator: VideoPlaybackCoordinator,
        playbackTweetId: MimeiId?,
        playbackVideoId: String?,
        onLoadComplete: (() -> Unit)?,
        onVideoCompleted: (() -> Unit)?
    ) {
        when (playbackState) {
            Player.STATE_READY -> {
                retryCount = 0
                blockAutoPrepareAfterError = false
                val currentShouldPlay = if (shouldUseCoordinator) coordinatorWantsToPlay else autoPlay
                if (currentShouldPlay && isVisible && !player.isPlaying) {
                    player.playWhenReady = true
                }
                isLoading = currentShouldPlay && isVisible && !hasVisiblePlaybackProgress
                onLoadComplete?.invoke()
            }
            Player.STATE_BUFFERING -> {
                isLoading = true
            }
            Player.STATE_ENDED -> {
                isLoading = false
                player.playWhenReady = false
                videoMid?.let { VideoManager.onVideoCompleted(it) }
                if (videoMid != null && playbackTweetId != null && playbackVideoId != null) {
                    coordinator.handleVideoFinished(videoMid, playbackTweetId, playbackVideoId)
                }
                onVideoCompleted?.invoke()
            }
            Player.STATE_IDLE -> {
                if (hasError || isNetworkRecoveryInProgress || blockAutoPrepareAfterError) {
                    isLoading = false
                    player.playWhenReady = false
                    return
                }

                isLoading = true
                val currentShouldPlay = if (shouldUseCoordinator) coordinatorWantsToPlay else autoPlay
                if (currentShouldPlay && isVisible && player.mediaItemCount > 0) {
                    player.prepare()
                    player.playWhenReady = true
                }
            }
        }
    }

    /**
     * Handle player error with full error classification and recovery.
     */
    fun onPlayerError(
        error: PlaybackException,
        context: Context,
        url: String?,
        videoType: MediaType?,
        scope: CoroutineScope
    ) {
        if (isMediaCodecRecoveryInProgress) {
            Timber.tag("VideoPreview").d("Ignoring transient error during recovery for $videoMid: ${error.message}")
            return
        }
        val errorSummary = "Video loading issue for $videoMid code=${error.errorCodeName}/${error.errorCode} " +
            "cause=${error.shortCauseName()}: ${error.message}"
        if (error.isExpectedNetworkPlaybackIssue()) {
            Timber.tag("VideoPreview").w(errorSummary)
        } else {
            Timber.tag("VideoPreview").e(error, errorSummary)
        }

        val errorMessage = listOfNotNull(error.message, error.cause?.message).joinToString(" ")
        val errorType = classifyError(errorMessage)
        blockAutoPrepareAfterError = true

        when {
            errorType == ErrorType.STREAM_PARSING -> {
                Timber.tag("VideoPreview").d("Ignoring stream parsing error for video: $videoMid")
                blockAutoPrepareAfterError = false
                isLoading = false
                hasError = false
            }
            errorType == ErrorType.MEDIA_CODEC && videoMid != null && retryCount < maxRetries -> {
                attemptMediaCodecRecovery(context, url, videoType, scope)
            }
            errorType == ErrorType.MEDIA_CODEC && videoMid != null -> {
                Timber.tag("VideoPreview").e("MediaCodec failure exceeded retry limit for video: $videoMid")
                isLoading = false
                hasError = true
            }
            errorType == ErrorType.RECOVERABLE && retryCount < 1 && videoMid != null -> {
                attemptNetworkRecovery(context, url, videoType, scope)
            }
            else -> {
                isLoading = false
                hasError = true
                if (error.isExpectedNetworkPlaybackIssue()) {
                    Timber.tag("VideoPreview").w("Network playback issue for video: $videoMid (retries: $retryCount)")
                } else {
                    Timber.tag("VideoPreview").e("Final error for video: $videoMid (retries: $retryCount)")
                }
            }
        }
    }

    /**
     * Handle visibility/shouldPlay state changes for playback control.
     */
    fun handlePlaybackStateChange(
        player: Player,
        shouldPlay: Boolean,
        effectivelyVisible: Boolean,
        context: Context,
        url: String?,
        videoType: MediaType?,
        playerKey: MimeiId? = videoMid
    ) {
        if (effectivelyVisible) {
            player.repeatMode = Player.REPEAT_MODE_OFF
            when (player.playbackState) {
                Player.STATE_READY -> {
                    retryCount = 0
                    blockAutoPrepareAfterError = false
                    player.playWhenReady = shouldPlay
                    isLoading = shouldPlay && !hasVisiblePlaybackProgress
                    hasError = false
                }
                Player.STATE_IDLE -> {
                    if (hasError || isNetworkRecoveryInProgress || blockAutoPrepareAfterError) {
                        isLoading = false
                        player.playWhenReady = false
                        return
                    }

                    isLoading = true
                    hasError = false
                    if (player.mediaItemCount == 0) {
                        if (videoMid != null && url != null) {
                            VideoManager.attemptVideoRecovery(context, videoMid, url, videoType, forceSoftwareDecoder = false)
                        }
                    } else {
                        player.prepare()
                        player.playWhenReady = shouldPlay
                    }
                }
                Player.STATE_BUFFERING -> {
                    player.playWhenReady = shouldPlay
                    isLoading = true
                    hasError = false
                }
                Player.STATE_ENDED -> {
                    hasError = false
                    if (shouldPlay) {
                        player.seekTo(0)
                        player.playWhenReady = true
                    }
                }
                else -> {
                    isLoading = true
                    hasError = false
                    if (videoMid != null && url != null) {
                        VideoManager.attemptVideoRecovery(context, videoMid, url, videoType, forceSoftwareDecoder = false)
                    }
                }
            }
        } else {
            playerKey?.let { key ->
                val activeCount = VideoManager.getVideoActiveCount(key)
                val isInFullScreen = videoMid?.let { VideoManager.isVideoInFullScreen(it) } == true
                if (activeCount <= 1 && !isInFullScreen) {
                    player.playWhenReady = false
                }
            } ?: run {
                player.playWhenReady = false
            }
        }
    }

    /**
     * ExoPlayer can report READY/playing while the rendered frame is still frozen on a slow
     * source. Keep the loading affordance until playback time actually advances, then try a
     * light same-player nudge before escalating to source recovery.
     */
    fun observePlaybackProgress(
        player: Player,
        shouldPlay: Boolean,
        effectivelyVisible: Boolean,
        context: Context,
        url: String?,
        videoType: MediaType?,
        scope: CoroutineScope
    ) {
        if (!effectivelyVisible || !shouldPlay || hasError) {
            resetPlaybackObservation(player.currentPosition, player.bufferedPosition)
            isLoading = false
            return
        }

        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val bufferedMs = player.bufferedPosition.coerceAtLeast(positionMs)
        val positionAdvanced = positionMs > lastObservedPositionMs + 150L
        val bufferAdvanced = bufferedMs > lastObservedBufferedMs + 250L

        if (positionAdvanced) {
            hasVisiblePlaybackProgress = true
            stagnantPlaybackTicks = 0
            stagnantBufferTicks = 0
            stillFrameRecoveryAttempts = 0
            isLoading = player.playbackState == Player.STATE_BUFFERING
        } else if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
            stagnantPlaybackTicks++
            stagnantBufferTicks = if (bufferAdvanced) 0 else stagnantBufferTicks + 1
            isLoading = true

            if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
                when {
                    stagnantPlaybackTicks == 3 -> nudgeStuckPlayer(player, positionMs)
                    stagnantPlaybackTicks >= 10 &&
                        stagnantBufferTicks >= 6 &&
                        stillFrameRecoveryAttempts < 2 &&
                        videoMid != null &&
                        url != null -> {
                        stillFrameRecoveryAttempts++
                        stagnantPlaybackTicks = 0
                        stagnantBufferTicks = 0
                        Timber.tag("VideoPreview").w(
                            "Playback stuck for $videoMid at ${positionMs}ms; recovering player"
                        )
                        scope.launch(Dispatchers.Main) {
                            VideoManager.attemptVideoRecovery(
                                context,
                                videoMid,
                                url,
                                videoType,
                                forceSoftwareDecoder = false
                            )
                        }
                    }
                }
            }
        } else if (player.playbackState == Player.STATE_ENDED) {
            isLoading = false
        }

        lastObservedPositionMs = positionMs
        lastObservedBufferedMs = bufferedMs
    }

    private fun nudgeStuckPlayer(player: Player, positionMs: Long) {
        try {
            Timber.tag("VideoPreview").d("Nudging stuck player for $videoMid at ${positionMs}ms")
            player.playWhenReady = false
            player.seekTo(positionMs)
            player.playWhenReady = true
        } catch (e: RuntimeException) {
            Timber.tag("VideoPreview").w("Stuck-player nudge failed for $videoMid: ${e.message}")
        }
    }

    private fun resetPlaybackObservation(positionMs: Long = 0L, bufferedMs: Long = 0L) {
        hasVisiblePlaybackProgress = false
        lastObservedPositionMs = positionMs.coerceAtLeast(0L)
        lastObservedBufferedMs = bufferedMs.coerceAtLeast(lastObservedPositionMs)
        stagnantPlaybackTicks = 0
        stagnantBufferTicks = 0
        stillFrameRecoveryAttempts = 0
    }

    /**
     * Manual retry from the retry button.
     */
    fun manualRetry(
        context: Context,
        url: String?,
        videoType: MediaType?,
        scope: CoroutineScope
    ) {
        if (videoMid == null) return
        retryCount = 0
        blockAutoPrepareAfterError = false
        isNetworkRecoveryInProgress = false
        retryCount++
        hasError = false
        isLoading = true
        Timber.tag("VideoPreview").d("Manual retry attempt $retryCount for video: $videoMid")

        scope.launch(Dispatchers.Main) {
            try {
                val success = if (url != null) {
                    VideoManager.forceRecreatePlayer(context, videoMid, url, videoType)
                } else false
                if (success) {
                    Timber.tag("VideoPreview").d("Manual retry successful for video: $videoMid")
                } else {
                    hasError = true
                    isLoading = false
                }
            } catch (e: Exception) {
                Timber.d("VideoPreview - Manual retry failed: ${e.message}")
                hasError = true
                isLoading = false
            }
        }
    }

    private fun attemptMediaCodecRecovery(
        context: Context,
        url: String?,
        videoType: MediaType?,
        scope: CoroutineScope
    ) {
        if (videoMid == null) return
        retryCount++
        Timber.tag("VideoPreview").w("MediaCodec failure (attempt $retryCount/$maxRetries) for video: $videoMid")
        isLoading = true
        hasError = false
        isMediaCodecRecoveryInProgress = true

        scope.launch {
            try {
                delay(1000)
                val success = if (url != null) {
                    VideoManager.forceRecreatePlayer(context, videoMid, url, videoType)
                } else false
                if (success) {
                    isLoading = false
                    hasError = false
                } else {
                    isLoading = false
                    hasError = true
                }
            } catch (e: Exception) {
                Timber.tag("VideoPreview").e("Exception during MediaCodec recovery: ${e.message}")
                isLoading = false
                hasError = true
            } finally {
                isMediaCodecRecoveryInProgress = false
            }
        }
    }

    private fun attemptNetworkRecovery(
        context: Context,
        url: String?,
        videoType: MediaType?,
        scope: CoroutineScope
    ) {
        if (videoMid == null || url == null) return
        retryCount++
        Timber.tag("VideoPlaybackDebug").w(
            "Automatic recovery retry $retryCount/1 for videoMid=$videoMid type=$videoType"
        )
        isLoading = true
        hasError = false
        isNetworkRecoveryInProgress = true

        scope.launch {
            delay(1000)
            try {
                if (isLoading && !hasError) {
                    VideoManager.attemptVideoRecovery(context, videoMid, url, videoType, forceSoftwareDecoder = false)
                }
            } finally {
                isNetworkRecoveryInProgress = false
            }
        }
    }

    private enum class ErrorType { STREAM_PARSING, MEDIA_CODEC, RECOVERABLE, FATAL }

    private fun classifyError(errorMessage: String): ErrorType {
        return when {
            errorMessage.contains("Unexpected start code", true) ||
            errorMessage.contains("PesReader", true) ||
            errorMessage.contains("start code prefix", true) -> ErrorType.STREAM_PARSING

            errorMessage.contains("MediaCodec", true) ||
            errorMessage.contains("Decoder init failed", true) ||
            errorMessage.contains("OMX.", true) ||
            errorMessage.contains("Failed to initialize", true) ||
            errorMessage.contains("CodecException", true) ||
            errorMessage.contains("DecoderInitializationException", true) ||
            errorMessage.contains("MediaCodecRenderer", true) ||
            errorMessage.contains("error 0xfffffff4", true) ||
            errorMessage.contains("native_setup", true) -> ErrorType.MEDIA_CODEC

            errorMessage.contains("network", true) ||
            errorMessage.contains("timeout", true) ||
            errorMessage.contains("connection", true) ||
            errorMessage.contains("server", true) ||
            errorMessage.contains("400", true) ||
            errorMessage.contains("401", true) ||
            errorMessage.contains("403", true) ||
            errorMessage.contains("404", true) ||
            errorMessage.contains("408", true) ||
            errorMessage.contains("429", true) ||
            errorMessage.contains("500", true) ||
            errorMessage.contains("502", true) ||
            errorMessage.contains("503", true) ||
            errorMessage.contains("504", true) ||
            errorMessage.contains("InvalidResponseCodeException", true) ||
            errorMessage.contains("HttpDataSource", true) -> ErrorType.RECOVERABLE

            else -> ErrorType.FATAL
        }
    }

    companion object {
        fun formatTime(timeMs: Long): String {
            if (timeMs <= 0) return "0:00"
            val totalSeconds = timeMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return if (minutes > 0) {
                "$minutes:${seconds.toString().padStart(2, '0')}"
            } else {
                "0:${seconds.toString().padStart(2, '0')}"
            }
        }
    }
}

@Composable
fun rememberVideoPreviewState(
    videoMid: MimeiId?,
    useIndependentMuteState: Boolean
): VideoPreviewState {
    return remember(videoMid) {
        val initialMuted = if (useIndependentMuteState) false else preferenceHelper.getSpeakerMute()
        val initialLoading = videoMid?.let { mid ->
            val player = VideoManager.getCachedVideoPlayer(mid)
            if (player != null) player.playbackState != Player.STATE_READY else true
        } ?: true
        VideoPreviewState(videoMid, useIndependentMuteState, initialMuted, initialLoading)
    }
}
