package us.fireshare.tweet.widget

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import timber.log.Timber
import us.fireshare.tweet.datamodel.MimeiId

/**
 * FullScreenVideoManager manages a dedicated ExoPlayer instance for full screen videos.
 * This singleton ensures no conflicts with the regular VideoManager and provides
 * consistent full screen video behavior.
 */
@OptIn(UnstableApi::class)
object FullScreenVideoManager {

    private var fullScreenPlayer: ExoPlayer? = null
    private var currentVideoUrl: String? = null
    private var autoReplayListener: Player.Listener? = null

    /**
     * Get the dedicated full screen video player
     */
    fun getFullScreenPlayer(context: Context): ExoPlayer {
        if (fullScreenPlayer == null) {
            Timber.d("FullScreenVideoManager - Creating dedicated full screen player")
            fullScreenPlayer = ExoPlayer.Builder(context).build()
        }
        return fullScreenPlayer!!
    }

    /**
     * Load a video into the full screen player
     */
    fun loadVideo(context: Context, videoUrl: String) {
        if (currentVideoUrl == videoUrl) {
            return
        }

        currentVideoUrl = videoUrl

        val player = getFullScreenPlayer(context)
        
        // For now, we'll use a simple approach - this can be enhanced later
        // The main goal is to support the existing player transfer
        try {
            player.stop()
            // Create a simple media source - this is a placeholder
            // In practice, you'd want to use the same media source creation as VideoManager
            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context)
            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
            val mediaSource = mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(videoUrl))
            player.setMediaSource(mediaSource)
            player.prepare()
        } catch (e: Exception) {
            Timber.e("FullScreenVideoManager - Error loading video: $e")
        }
    }

    /**
     * Use an existing player instance for full-screen mode
     * This allows seamless transition from preview to full-screen without losing position
     * @param existingPlayer The existing ExoPlayer instance to use
     * @param videoMid Video's unique identifier
     */
    fun useExistingPlayer(existingPlayer: ExoPlayer, videoMid: MimeiId) {
        Timber.d("FullScreenVideoManager - Using existing player for: $videoMid")
        
        // Release current full-screen player if different
        if (fullScreenPlayer != null && fullScreenPlayer != existingPlayer) {
            fullScreenPlayer?.release()
        }
        
        // Use the existing player
        fullScreenPlayer = existingPlayer
        currentVideoUrl = null // Not needed when using existing player
    }

    /**
     * Start playback with auto-replay
     */
    fun startPlayback(autoReplay: Boolean = true) {
        val player = fullScreenPlayer ?: return

        // Remove existing auto-replay listener if any
        autoReplayListener?.let { listener ->
            player.removeListener(listener)
        }

        // Set up auto-replay listener
        autoReplayListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        if (autoReplay) {
                            Timber.d("FullScreenVideoManager - Video ended, auto-replaying")
                            player.seekTo(0)
                            player.playWhenReady = true
                            player.play()
                        }
                    }
                }
            }
        }

        player.addListener(autoReplayListener!!)

        // Start playback
        player.playWhenReady = true
        player.play()

        Timber.d("FullScreenVideoManager - Started playback")
    }

    /**
     * Pause playback
     */
    fun pausePlayback() {
        fullScreenPlayer?.playWhenReady = false
        Timber.d("FullScreenVideoManager - Paused playback")
    }

    /**
     * Resume playback
     */
    fun resumePlayback() {
        fullScreenPlayer?.playWhenReady = true
        Timber.d("FullScreenVideoManager - Resumed playback")
    }

    /**
     * Set volume (0f = muted, 1f = full volume)
     */
    fun setVolume(volume: Float) {
        fullScreenPlayer?.volume = volume
        Timber.d("FullScreenVideoManager - Set volume to: $volume")
    }

    /**
     * Get current playback position
     */
    fun getCurrentPosition(): Long {
        return fullScreenPlayer?.currentPosition ?: 0L
    }

    /**
     * Seek to position
     */
    fun seekTo(position: Long) {
        fullScreenPlayer?.seekTo(position)
    }

    /**
     * Check if player is playing
     */
    fun isPlaying(): Boolean {
        return fullScreenPlayer?.isPlaying ?: false
    }

    /**
     * Release the full screen player
     * This should be called when the app is being destroyed
     */
    fun release() {
        fullScreenPlayer?.let { player ->
            try {
                // Remove auto-replay listener
                autoReplayListener?.let { listener ->
                    player.removeListener(listener)
                }
                autoReplayListener = null

                player.stop()
                player.release()
                Timber.d("FullScreenVideoManager - Released full screen player")
            } catch (e: Exception) {
                Timber.e("FullScreenVideoManager - Error releasing player: $e")
            }
        }
        fullScreenPlayer = null
        currentVideoUrl = null
    }
}