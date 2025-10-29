package us.fireshare.tweet.utils

import android.content.Context
import us.fireshare.tweet.R
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException

object ErrorMessageUtils {
    
    /**
     * Converts technical network error messages to user-friendly localized messages
     */
    fun getNetworkErrorMessage(context: Context, error: Throwable?): String {
        return when (error) {
            is SocketTimeoutException -> {
                context.getString(R.string.network_error_timeout)
            }
            is ConnectException -> {
                context.getString(R.string.network_error_unreachable)
            }
            is UnknownHostException -> {
                context.getString(R.string.network_error_connection_lost)
            }
            is IOException -> {
                val message = error.message?.lowercase() ?: ""
                when {
                    message.contains("timeout") -> context.getString(R.string.network_error_timeout)
                    message.contains("connection") -> context.getString(R.string.network_error_connection_lost)
                    message.contains("unreachable") -> context.getString(R.string.network_error_unreachable)
                    message.contains("network") -> context.getString(R.string.network_error_generic)
                    else -> context.getString(R.string.network_error_generic)
                }
            }
            else -> {
                val message = error?.message?.lowercase() ?: ""
                when {
                    message.contains("timeout") -> context.getString(R.string.network_error_timeout)
                    message.contains("connection") -> context.getString(R.string.network_error_connection_lost)
                    message.contains("unreachable") -> context.getString(R.string.network_error_unreachable)
                    message.contains("network") -> context.getString(R.string.network_error_generic)
                    else -> context.getString(R.string.network_error_generic)
                }
            }
        }
    }
    
    /**
     * Converts technical video error messages to user-friendly localized messages
     */
    fun getVideoErrorMessage(context: Context, error: Throwable?): String {
        return when (error) {
            is IOException -> {
                val message = error.message?.lowercase() ?: ""
                when {
                    message.contains("network") || message.contains("connection") -> {
                        context.getString(R.string.video_error_network_issue)
                    }
                    message.contains("format") || message.contains("codec") -> {
                        context.getString(R.string.video_error_format_not_supported)
                    }
                    else -> context.getString(R.string.video_error_playback_failed)
                }
            }
            else -> {
                val message = error?.message?.lowercase() ?: ""
                when {
                    message.contains("network") || message.contains("connection") -> {
                        context.getString(R.string.video_error_network_issue)
                    }
                    message.contains("format") || message.contains("codec") -> {
                        context.getString(R.string.video_error_format_not_supported)
                    }
                    else -> context.getString(R.string.video_error_playback_failed)
                }
            }
        }
    }
    
    /**
     * Checks if an error is network-related and should be retried
     */
    fun isNetworkError(error: Throwable?): Boolean {
        return when (error) {
            is SocketTimeoutException,
            is ConnectException,
            is UnknownHostException,
            is IOException -> true
            else -> {
                val message = error?.message?.lowercase() ?: ""
                message.contains("timeout") || 
                message.contains("connection") || 
                message.contains("unreachable") || 
                message.contains("network")
            }
        }
    }
}
