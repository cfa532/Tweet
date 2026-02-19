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
    var isMuted by mutableStateOf(initialMuted)
    var isLoading by mutableStateOf(initialLoading)
    var hasError by mutableStateOf(false)
    var showTimeLabel by mutableStateOf(false)
    var remainingTime by mutableLongStateOf(0L)
    var retryCount by mutableIntStateOf(0)
    var isMediaCodecRecoveryInProgress by mutableStateOf(false)
    var showControls by mutableStateOf(false)
    var coordinatorWantsToPlay by mutableStateOf(false)

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
        onLoadComplete: (() -> Unit)?,
        onVideoCompleted: (() -> Unit)?
    ) {
        when (playbackState) {
            Player.STATE_READY -> {
                isLoading = false
                val currentShouldPlay = if (shouldUseCoordinator) coordinatorWantsToPlay else autoPlay
                if (currentShouldPlay && isVisible && !player.isPlaying) {
                    player.playWhenReady = true
                }
                onLoadComplete?.invoke()
            }
            Player.STATE_BUFFERING -> {
                isLoading = true
            }
            Player.STATE_ENDED -> {
                isLoading = false
                player.playWhenReady = false
                videoMid?.let { VideoManager.onVideoCompleted(it) }
                if (videoMid != null && playbackTweetId != null) {
                    coordinator.handleVideoFinished(videoMid, playbackTweetId)
                }
                onVideoCompleted?.invoke()
            }
            Player.STATE_IDLE -> {
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
        Timber.tag("VideoPreview").e("Video loading error for $videoMid: ${error.message}")

        val errorMessage = error.cause?.message ?: ""
        val errorType = classifyError(errorMessage)

        when {
            errorType == ErrorType.STREAM_PARSING -> {
                Timber.tag("VideoPreview").d("Ignoring stream parsing error for video: $videoMid")
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
            errorType == ErrorType.RECOVERABLE && retryCount < maxRetries && videoMid != null -> {
                attemptNetworkRecovery(context, url, videoType, scope)
            }
            else -> {
                isLoading = false
                hasError = true
                Timber.tag("VideoPreview").e("Final error for video: $videoMid (retries: $retryCount)")
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
        videoType: MediaType?
    ) {
        if (effectivelyVisible) {
            player.repeatMode = Player.REPEAT_MODE_OFF
            when (player.playbackState) {
                Player.STATE_READY -> {
                    player.playWhenReady = shouldPlay
                    isLoading = false
                    hasError = false
                }
                Player.STATE_IDLE -> {
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
            videoMid?.let { mid ->
                val activeCount = VideoManager.getVideoActiveCount(mid)
                val isInFullScreen = VideoManager.isVideoInFullScreen(mid)
                if (activeCount <= 1 && !isInFullScreen) {
                    player.playWhenReady = false
                }
            } ?: run {
                player.playWhenReady = false
            }
        }
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
        Timber.tag("VideoPreview").d("Automatic retry $retryCount/$maxRetries for video: $videoMid")
        isLoading = true
        hasError = false

        scope.launch {
            delay(1000)
            if (isLoading && !hasError) {
                VideoManager.attemptVideoRecovery(context, videoMid, url, videoType, forceSoftwareDecoder = false)
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
