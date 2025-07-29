package us.fireshare.tweet.widget

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import timber.log.Timber
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId

/**
 * FullScreenVideoManager manages a dedicated ExoPlayer instance for full screen videos.
 * This singleton ensures no conflicts with the regular VideoManager and provides
 * consistent full screen video behavior.
 */
@OptIn(UnstableApi::class)
object FullScreenVideoManager {
    
    private var fullScreenPlayer: ExoPlayer? = null
    private var currentVideoMid: MimeiId? = null
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
    fun loadVideo(context: Context, videoMid: MimeiId, videoUrl: String) {
        val player = getFullScreenPlayer(context)
        
        // Only reload if it's a different video
        if (currentVideoMid != videoMid || currentVideoUrl != videoUrl) {
            Timber.d("FullScreenVideoManager - Loading video: $videoMid")
            
            try {
                // Stop current playback if any
                player.stop()
                
                // Create MediaItem directly using the existing createExoPlayer approach
                val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context)
                val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                
                // For data blobs, try HLS first, then fallback to original URL
                val baseUrl = if (videoUrl.endsWith("/")) videoUrl else "$videoUrl/"
                val masterUrl = "${baseUrl}master.m3u8"
                val playlistUrl = "${baseUrl}playlist.m3u8"
                
                // Add comprehensive listener for debugging and fallback
                player.addListener(object : Player.Listener {
                    private var hasTriedPlaylist = false
                    private var hasTriedOriginal = false
                    
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Timber.tag("FullScreenVideoManager").e("Player error: ${error.message}")
                        
                        if (!hasTriedPlaylist) {
                            hasTriedPlaylist = true
                            
                            // If master.m3u8 fails, try playlist.m3u8
                            val fallbackMediaSource = mediaSourceFactory.createMediaSource(
                                androidx.media3.common.MediaItem.fromUri(playlistUrl)
                            )
                            player.setMediaSource(fallbackMediaSource)
                            player.prepare()
                        } else if (!hasTriedOriginal) {
                            hasTriedOriginal = true
                            
                            // If both HLS attempts fail, try the original URL (progressive video)
                            val originalMediaSource = mediaSourceFactory.createMediaSource(
                                androidx.media3.common.MediaItem.fromUri(videoUrl)
                            )
                            player.setMediaSource(originalMediaSource)
                            player.prepare()
                        } else {
                            Timber.e("FullScreenVideoManager - All fallback attempts failed for URL: $videoUrl")
                        }
                    }
                })
                
                // Start with master.m3u8 (try HLS first)
                val mediaSource = mediaSourceFactory.createMediaSource(androidx.media3.common.MediaItem.fromUri(masterUrl))
                player.setMediaSource(mediaSource)
                player.prepare()
                
                currentVideoMid = videoMid
                currentVideoUrl = videoUrl
                
                Timber.d("FullScreenVideoManager - Video loaded successfully")
            } catch (e: Exception) {
                Timber.e("FullScreenVideoManager - Error loading video: $e")
                throw e
            }
        }
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
     * Get current video info
     */
    fun getCurrentVideo(): Pair<MimeiId?, String?> {
        return currentVideoMid to currentVideoUrl
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
        currentVideoMid = null
        currentVideoUrl = null
    }
} 