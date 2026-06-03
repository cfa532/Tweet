package us.fireshare.tweet.widget

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

private const val AUDIO_NETWORK_TIMEOUT_MS = 5_000

@OptIn(UnstableApi::class)
private val audioLoadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy(0) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        return C.TIME_UNSET
    }
}

@OptIn(UnstableApi::class)
fun createAudioExoPlayer(context: Context): ExoPlayer {
    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(AUDIO_NETWORK_TIMEOUT_MS)
        .setReadTimeoutMs(AUDIO_NETWORK_TIMEOUT_MS)
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent("TweetApp/1.0")

    val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

    val mediaSourceFactory = DefaultMediaSourceFactory(upstreamFactory)
        .setLoadErrorHandlingPolicy(audioLoadErrorHandlingPolicy)

    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
}
