package us.fireshare.tweet.widget

import androidx.media3.common.PlaybackException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal fun PlaybackException.isExpectedNetworkPlaybackIssue(): Boolean {
    if (
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    ) {
        return true
    }

    return causeChain().any { cause ->
        cause is SocketTimeoutException ||
            cause is SocketException ||
            cause is UnknownHostException ||
            cause.message?.contains("timeout", ignoreCase = true) == true ||
            cause.message?.contains("socket closed", ignoreCase = true) == true
    }
}

internal fun PlaybackException.shortCauseName(): String {
    val cause = causeChain().firstOrNull { it !is PlaybackException } ?: cause
    return cause?.javaClass?.simpleName ?: javaClass.simpleName
}

private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    var current: Throwable? = this@causeChain
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        yield(current)
        current = current.cause
    }
}
