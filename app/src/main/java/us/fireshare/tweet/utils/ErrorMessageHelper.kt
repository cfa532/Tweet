package us.fireshare.tweet.utils

import us.fireshare.tweet.R

/**
 * Helper class to convert technical server errors to user-friendly messages
 */
object ErrorMessageHelper {

    /**
     * Convert a server error message to a user-friendly localized message
     */
    fun getUserFriendlyMessage(errorMessage: String?, context: android.content.Context): String {
        if (errorMessage.isNullOrBlank()) {
            return context.getString(R.string.unknown_error)
        }

        val lowerError = errorMessage.lowercase().trim()

        // Server-provided user-friendly error messages mapping to localized versions
        // These match the iOS ErrorMessageHelper mappings
        return when {
            // Registration errors
            lowerError == "username is taken" -> context.getString(R.string.username_taken)
            lowerError == "username is required" -> context.getString(R.string.username_required)
            lowerError == "password is required" -> context.getString(R.string.password_required)
            lowerError == "invalid username format" -> context.getString(R.string.invalid_username_format)

            // Login errors
            lowerError == "user not found" -> context.getString(R.string.login_error)
            lowerError == "wrong password" -> context.getString(R.string.wrong_password)

            // User/host errors
            lowerError == "user not found or missing host" -> context.getString(R.string.user_not_found_or_missing_host)
            lowerError == "user host not found" -> context.getString(R.string.user_host_not_found)
            lowerError == "author host not found" -> context.getString(R.string.author_host_not_found)
            lowerError == "user not found in database" -> context.getString(R.string.user_not_found_in_database)
            lowerError == "user missing host" -> context.getString(R.string.user_missing_host)

            // Following/follower errors
            lowerError == "cannot follow yourself" -> context.getString(R.string.cannot_follow_yourself)
            lowerError == "cannot get followed user" -> context.getString(R.string.cannot_get_followed_user)
            lowerError == "missing host for followed user" -> context.getString(R.string.missing_host_for_followed_user)

            // Tweet errors
            lowerError == "tweet not found" -> context.getString(R.string.tweet_not_found)
            lowerError == "only the tweet author can update privacy settings" -> context.getString(R.string.only_author_can_update_privacy)

            // Authentication/authorization errors
            lowerError == "not a friend of the host" -> context.getString(R.string.not_a_friend_of_the_host)
            lowerError.contains("no provider ip found") -> context.getString(R.string.no_provider_ip_found)

            // Upload errors
            lowerError == "failed to extract zip file" -> context.getString(R.string.failed_to_extract_zip_file)
            lowerError == "invalid hls structure" -> context.getString(R.string.invalid_hls_structure)

            // App errors
            lowerError == "app id mismatch" -> context.getString(R.string.app_id_mismatch)
            lowerError == "invalid host id: must be at least 27 characters" -> context.getString(R.string.invalid_host_id_length)
            lowerError.contains("failed to parse peer id") ||
            lowerError.contains("invalid cid") ||
            lowerError.contains("selected encoding not supported") ->
                context.getString(R.string.invalid_host_id_format)

            // Network/server errors
            lowerError.contains("chunk size") && lowerError.contains("exceeds") && lowerError.contains("1mb limit") ->
                context.getString(R.string.chunk_size_exceeds_limit)

            lowerError.contains("network connection was lost") ||
            lowerError.contains("connection was lost") ||
            lowerError.contains("network is down") ||
            lowerError.contains("not connected to the internet") ->
                context.getString(R.string.network_connection_lost)

            lowerError.contains("timed out") ||
            lowerError.contains("timeout") ||
            lowerError.contains("request timed out") ->
                context.getString(R.string.request_timeout)

            lowerError.contains("could not connect to the server") ||
            lowerError.contains("server is not responding") ||
            lowerError.contains("cannot find the server") ||
            lowerError.contains("dns") ->
                context.getString(R.string.server_unreachable)

            lowerError.contains("connection reset") ||
            lowerError.contains("connection refused") ->
                context.getString(R.string.connection_error)

            // If the message is already user-friendly (short, clear, no technical jargon)
            isUserFriendly(errorMessage) -> errorMessage

            // Default fallback
            else -> context.getString(R.string.unknown_error)
        }
    }

    /**
     * Check if an error message is already user-friendly
     */
    private fun isUserFriendly(message: String): Boolean {
        val lowerMessage = message.lowercase()

        // Check for technical terms that indicate non-user-friendly messages
        val technicalTerms = listOf(
            "nsurlsession", "nsurlerror", "domain error", "error code", "error -",
            "failed with", "underlying error", "userinfo", "nserror", "exception",
            "stack trace", "debug", "parse", "decode", "json", "http", "https",
            "ssl", "certificate", "tcp", "socket", "connection", "timeout"
        )

        val hasTechnicalTerms = technicalTerms.any { lowerMessage.contains(it) }
        val isShortAndClear = message.length < 100 && !message.contains("Error Domain")

        return !hasTechnicalTerms && isShortAndClear
    }
}
