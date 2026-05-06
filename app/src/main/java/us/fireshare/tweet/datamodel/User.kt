package us.fireshare.tweet.datamodel

import android.os.Parcelable
import com.google.gson.annotations.Expose
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.network.HproseClientPool

@Parcelize
@Serializable
data class User(
    @Expose var baseUrl: String? = null,
    @Expose var writableUrl: String? = null,
    @Expose val mid: MimeiId,
    @Expose var name: String? = null,
    @Expose var username: String? = null,
    @Expose var password: String? = null,
    @Expose var avatar: MimeiId? = null,
    @Expose var email: String? = null,
    @Expose var profile: String? = null,
    @Expose var domainToShare: String? = null,
    @Expose var timestamp: Long = System.currentTimeMillis(),
    @Expose var lastLogin: Long? = System.currentTimeMillis(),
    @Expose var cloudDrivePort: Int = 0,

    @Expose var tweetCount: Int = 0,
    @Expose var followingCount: Int = 0,
    @Expose var followersCount: Int = 0,
    @Expose var bookmarksCount: Int = 0,
    @Expose var favoritesCount: Int = 0,
    @Expose var commentsCount: Int = 0,

    @Expose var hostIds: List<MimeiId>? = null,
    @Expose var publicKey: String? = null,

    @Expose var fansList: List<MimeiId>? = null,
    @Expose var followingList: List<MimeiId>? = null,
    @Expose var bookmarkedTweets: List<MimeiId>? = null,
    @Expose var favoriteTweets: List<MimeiId>? = null,
    @Expose var repliedTweets: List<MimeiId>? = null,
    @Expose var commentsList: List<MimeiId>? = null,
    @Expose var topTweets: List<MimeiId>? = null
) : Parcelable {

    companion object {
        // Singleton dictionary to store user instances
        private val userInstances = mutableMapOf<String, User>()

        /**
         * Get or create a user instance with the given mid
         */
        fun getInstance(mid: String): User {
            return userInstances.getOrPut(mid) { User(mid = mid) }
        }

        /**
         * Update user instance with new data
         */
        fun updateUserInstance(user: User, shouldUpdateBaseUrl: Boolean = false) {
            val instance = getInstance(mid = user.mid)

            // Capture old values before mutation for appUser sync
            val isAppUser = !instance.isGuest() && instance.mid == HproseInstance.appUser.mid
            val oldBaseUrl = if (isAppUser) instance.baseUrl else null
            val oldAvatar = if (isAppUser) instance.avatar else null

            instance.apply {
                name = user.name
                username = user.username
                password = user.password
                avatar = user.avatar
                email = user.email
                profile = user.profile
                domainToShare = user.domainToShare
                lastLogin = user.lastLogin
                cloudDrivePort = user.cloudDrivePort
                hostIds = user.hostIds

                // CRITICAL: Never overwrite baseUrl from user parameter
                if (shouldUpdateBaseUrl) {
                    baseUrl = user.baseUrl
                }

                tweetCount = user.tweetCount
                followingCount = user.followingCount
                followersCount = user.followersCount
                bookmarksCount = user.bookmarksCount
                favoritesCount = user.favoritesCount
                commentsCount = user.commentsCount

                // Update array properties
                fansList = user.fansList
                followingList = user.followingList
                bookmarkedTweets = user.bookmarkedTweets
                favoriteTweets = user.favoriteTweets
                repliedTweets = user.repliedTweets
                commentsList = user.commentsList
                topTweets = user.topTweets
            }

            // If we just mutated the appUser singleton, sync StateFlows
            if (isAppUser) {
                HproseInstance.notifyAppUserChanged(oldBaseUrl, oldAvatar)
            }
        }
    }

    // Hprose service management using shared connection pool for distributed nodes
    // Multiple users on the same node will share the same client instance
    @IgnoredOnParcel
    private var _lastBaseUrl: String? = null
    
    val hproseService: HproseService?
        get() {
            val baseUrl = baseUrl ?: return null
            
            // Use HproseClientPool for shared client management across users on same node
            // This significantly reduces memory footprint and improves connection reuse
            return HproseClientPool.getRegularClient(baseUrl)
        }
    
    /**
     * Clear cached Hprose service from pool
     * This will remove the client from the pool for this node
     */
    fun clearHproseService() {
        val baseUrl = baseUrl ?: return
        if (_lastBaseUrl != null && _lastBaseUrl != baseUrl) {
            // Release old client if URL changed
            HproseClientPool.releaseClient(_lastBaseUrl!!, isWritableClient = false)
        }
        HproseClientPool.clearClient(baseUrl)
        _lastBaseUrl = null
    }

    @IgnoredOnParcel
    private var _lastWritableUrl: String? = null
    
    /**
     * Hprose client targeting the user's writable node. Use for any RPC that
     * mutates server-side data (uploads, toggles, edits, deletes) so the
     * request lands directly on the writable host instead of being delegated.
     * Returns null when writableUrl hasn't been resolved yet — callers should
     * call resolveWritableUrl() (a suspend function) before reading this
     * property and treat null as an error.
     *
     * Mirrors iOS User.writableClient.
     */
    val writableClient: HproseService?
        get() {
            val currentWritableUrl = writableUrl ?: return null

            // Pooled, shared writable client targeting hostIds[0] (extended timeout
            // for long-running mutations like file uploads).
            return HproseClientPool.getWritableClient(currentWritableUrl)
        }

    /**
     * Clear cached writable client from pool. Removes the pooled client for
     * the writable host so the next access will re-fetch / re-create.
     */
    fun clearWritableClient() {
        val currentWritableUrl = writableUrl ?: return
        if (_lastWritableUrl != null && _lastWritableUrl != currentWritableUrl) {
            // Release old client if URL changed
            HproseClientPool.releaseClient(_lastWritableUrl!!, isWritableClient = true)
        }
        HproseClientPool.clearClient(currentWritableUrl)
        _lastWritableUrl = null
    }

    /**
     * Check if user is guest
     */
    fun isGuest(): Boolean {
        return mid == TW_CONST.GUEST_ID
    }

    /**
     * Resolve writable URL from hostIds and reset writableClient if needed
     */
    suspend fun resolveWritableUrl(): String? {
        // If writableUrl is already valid, return it immediately
        if (!writableUrl.isNullOrEmpty()) {
            return writableUrl
        }
        
        // If writableUrl is null or empty, clear writableClient and resolve
        clearWritableClient()

        suspend fun tryResolve(): String? {
            if (hostIds.isNullOrEmpty()) {
                return writableUrl
            }

            val firstHostId = hostIds?.first()

            if (firstHostId.isNullOrEmpty()) {
                return writableUrl
            }

            val hostIP = HproseInstance.getHostIP(firstHostId, v4Only = "true")
            if (hostIP != null) {
                
                // Extract clean IP and port
                val (cleanIP, port) = when {
                    hostIP.startsWith("[") && hostIP.contains("]:") -> {
                        // IPv6 with port
                        val endBracket = hostIP.indexOf("]")
                        val colon = hostIP.indexOf(":", endBracket)
                        if (endBracket != -1 && colon != -1) {
                            val ipv6 = hostIP.substring(1, endBracket)
                            val portStr = hostIP.substring(colon + 1).trim()
                            ipv6 to portStr
                        } else {
                            Timber.w("[resolveWritableUrl] Failed to parse IPv6 with port: $hostIP")
                            return writableUrl
                        }
                    }
                    hostIP.contains(":") && !hostIP.contains("]: ") && !hostIP.contains("[") -> {
                        // IPv4 with port
                        val parts = hostIP.split(":", limit = 2)
                        if (parts.size == 2) {
                            parts[0] to parts[1]
                        } else {
                            Timber.w("[resolveWritableUrl] Failed to parse IPv4 with port: $hostIP")
                            return writableUrl
                        }
                    }
                    else -> {
                        // No port specified - cannot construct URL
                        Timber.w("[resolveWritableUrl] No port specified in hostIP: $hostIP")
                        return writableUrl
                    }
                }

                val portNumber = port.toIntOrNull()
                if (portNumber == null || portNumber !in 1..65535) {
                    Timber.w("[resolveWritableUrl] Port $port is not a valid port (1-65535)")
                    return writableUrl
                }

                // Construct URL string
                val urlString = if (isIPv6Address(cleanIP)) {
                    "http://[$cleanIP]:$port"
                } else {
                    "http://$cleanIP:$port"
                }

                writableUrl = urlString
                return urlString
            } else {
                Timber.w("[resolveWritableUrl] Failed to resolve hostIP for hostId: $firstHostId")
            }

            return writableUrl
        }

        // First attempt
        val firstAttempt = tryResolve()
        if (!firstAttempt.isNullOrEmpty() && firstAttempt != writableUrl) {
            return firstAttempt
        }
        // Retry once if first attempt failed
        val retryAttempt = tryResolve()
        return retryAttempt
    }

    /**
     * Update user data from a map, which should be from server.
     */
    fun from(userData: Map<String, Any>) {
        try {
            // Capture old values before mutation for appUser sync
            val isAppUser = !isGuest() && mid == HproseInstance.appUser.mid
            val prevBaseUrl = if (isAppUser) baseUrl else null
            val prevAvatar = if (isAppUser) avatar else null

            // Pre-process the data to handle scientific notation in numeric fields
            val processedData = userData.toMutableMap()
            
            // Handle timestamp and lastLogin fields
            processedData["timestamp"]?.let { value ->
                when (value) {
                    is Number -> processedData["timestamp"] = value.toLong()
                    is String -> {
                        try {
                            processedData["timestamp"] = value.toDouble().toLong()
                        } catch (_: NumberFormatException) {
                            Timber.w("Failed to parse timestamp: $value")
                        }
                    }
                }
            }
            
            processedData["lastLogin"]?.let { value ->
                when (value) {
                    is Number -> processedData["lastLogin"] = value.toLong()
                    is String -> {
                        try {
                            processedData["lastLogin"] = value.toDouble().toLong()
                        } catch (_: NumberFormatException) {
                            Timber.w("Failed to parse lastLogin: $value")
                        }
                    }
                }
            }
            
            val oldBaseUrl = baseUrl
            val oldWritableUrl = writableUrl
            name = processedData["name"] as? String ?: name
            username = processedData["username"] as? String ?: username
            avatar = processedData["avatar"] as? String ?: avatar
            email = processedData["email"] as? String ?: email
            profile = processedData["profile"] as? String ?: profile
            // Handle domainToShare: update if present in response (including null), otherwise keep existing
            if (processedData.containsKey("domainToShare")) {
                domainToShare = processedData["domainToShare"] as? String
            }
            // Handle cloudDrivePort: update if present in response (including null), handle both Number and String types
            if (processedData.containsKey("cloudDrivePort")) {
                val value = processedData["cloudDrivePort"]
                cloudDrivePort = when (value) {
                    null -> 0
                    is Number -> value.toInt()
                    is String -> {
                        try {
                            value.toIntOrNull() ?: 0
                        } catch (_: NumberFormatException) {
                            Timber.w("Failed to parse cloudDrivePort: $value")
                            cloudDrivePort
                        }
                    }

                    else -> cloudDrivePort
                }
            }
            
            tweetCount = (processedData["tweetCount"] as? Number)?.toInt() ?: tweetCount
            followingCount = (processedData["followingCount"] as? Number)?.toInt() ?: followingCount
            followersCount = (processedData["followersCount"] as? Number)?.toInt() ?: followersCount
            bookmarksCount = (processedData["bookmarksCount"] as? Number)?.toInt() ?: bookmarksCount
            favoritesCount = (processedData["favoritesCount"] as? Number)?.toInt() ?: favoritesCount
            commentsCount = (processedData["commentsCount"] as? Number)?.toInt() ?: commentsCount
            
            hostIds = (processedData["hostIds"] as? List<*>)?.mapNotNull { id -> id as? String } ?: hostIds
            
            // Clear cached services if URLs changed
            if (oldBaseUrl != baseUrl) {
                clearHproseService()
            }
            if (oldWritableUrl != writableUrl) {
                clearWritableClient()
            }

            // Sync appUser StateFlows if this instance is appUser
            if (isAppUser) {
                HproseInstance.notifyAppUserChanged(prevBaseUrl, prevAvatar)
            }
        } catch (e: Exception) {
            Timber.tag("User.from").e("Error updating user from map: $e")
        }
    }

    fun from(userData: User) {
        // Capture old values before mutation for appUser sync
        val isAppUser = !isGuest() && mid == HproseInstance.appUser.mid
        val prevBaseUrl = if (isAppUser) baseUrl else null
        val prevAvatar = if (isAppUser) avatar else null

        name = userData.name
        username = userData.username
        avatar = userData.avatar
        email = userData.email
        profile = userData.profile
        domainToShare = userData.domainToShare
        cloudDrivePort = userData.cloudDrivePort

        tweetCount = userData.tweetCount
        followingCount = userData.followingCount
        followersCount = userData.followersCount
        bookmarksCount = userData.bookmarksCount
        favoritesCount = userData.favoritesCount
        commentsCount = userData.commentsCount
        hostIds = userData.hostIds

        // Sync appUser StateFlows if this instance is appUser
        if (isAppUser) {
            HproseInstance.notifyAppUserChanged(prevBaseUrl, prevAvatar)
        }
    }

    /**
     * Check if IP address is IPv6
     */
    private fun isIPv6Address(ip: String): Boolean {
        return ip.contains(":")
    }

    /**
     * Computed property that returns writableUrl with cloudDrivePort
     * Used for accessing TUS server (resumable upload server) and transcode services
     * Note: Preserves full path, query, and fragment from writableUrl since TUS server may be hosted at a subpath
     * Important: 
     * - Callers must ensure writableUrl is resolved (call resolveWritableUrl() first) before accessing this property
     * - Returns null if cloudDrivePort is not set (0 means not set)
     */
    val tusServerUrl: String?
        get() {
            val baseUrl = writableUrl ?: return null
            val port = cloudDrivePort
            // cloudDrivePort of 0 means not set
            if (port == 0) return null
            
            return try {
                val uri = java.net.URI(baseUrl)
                val scheme = uri.scheme ?: "http"
                val host = uri.host ?: return baseUrl
                val path = uri.path ?: ""
                val query = uri.query?.let { "?$it" } ?: ""
                val fragment = uri.fragment?.let { "#$it" } ?: ""
                
                // Handle IPv6 addresses
                val hostPart = if (host.contains(":")) {
                    "[$host]"
                } else {
                    host
                }
                
                "$scheme://$hostPart:$port$path$query$fragment"
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse baseUrl for cloud port replacement: $baseUrl")
                null
            }
        }

    /**
     * Check if this user instance has expired and needs to be fetched from backend again
     */
    val hasExpired: Boolean
        get() {
            val timestamp = TweetCacheManager.getUserCacheTimestamp(mid)
            val currentTime = System.currentTimeMillis()
            return (currentTime - timestamp) > TweetCacheManager.USER_CACHE_EXPIRATION_TIME
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as User
        return mid == other.mid
    }

    override fun hashCode(): Int {
        return mid.hashCode()
    }
} 