package us.fireshare.tweet.datamodel

import android.os.Parcelable
import com.google.gson.annotations.Expose
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import us.fireshare.tweet.HproseInstance
import hprose.client.HproseHttpClient
import kotlinx.parcelize.IgnoredOnParcel
import timber.log.Timber

@Parcelize
@Serializable
data class User(
    @Expose var baseUrl: String? = null,
    @Expose var writableUrl: String? = null,
    @Expose var mid: MimeiId,
    @Expose var name: String? = null,
    @Expose var username: String? = null,
    @Expose var password: String? = null,
    @Expose var avatar: MimeiId? = null,
    @Expose var email: String? = null,
    @Expose var profile: String? = null,
    @Expose var timestamp: Long = System.currentTimeMillis(),
    @Expose var lastLogin: Long? = System.currentTimeMillis(),
    @Expose var cloudDrivePort: Int = 8010,

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
         * Update user instance with backend data. Keep current baseUrl
         */
        fun from(dict: Map<String, Any>): User {
            try {
                val gson = com.google.gson.Gson()
                
                // Pre-process the dictionary to handle scientific notation in numeric fields
                val processedDict = dict.toMutableMap()
                
                // Handle timestamp and lastLogin fields
                processedDict["timestamp"]?.let { value ->
                    when (value) {
                        is Number -> processedDict["timestamp"] = value.toLong()
                        is String -> {
                            try {
                                processedDict["timestamp"] = value.toDouble().toLong()
                            } catch (e: NumberFormatException) {
                                Timber.w("Failed to parse timestamp: $value")
                            }
                        }
                    }
                }
                
                processedDict["lastLogin"]?.let { value ->
                    when (value) {
                        is Number -> processedDict["lastLogin"] = value.toLong()
                        is String -> {
                            try {
                                processedDict["lastLogin"] = value.toDouble().toLong()
                            } catch (e: NumberFormatException) {
                                Timber.w("Failed to parse lastLogin: $value")
                            }
                        }
                    }
                }
                
                val jsonString = gson.toJson(processedDict)
                val decodedUser = gson.fromJson(jsonString, User::class.java)
                
                // Keep original baseUrl when updated by user dictionary from backend
                val instance = getInstance(mid = decodedUser.mid)
                decodedUser.baseUrl = instance.baseUrl
                decodedUser.writableUrl = instance.writableUrl
                
                updateUserInstance(decodedUser)
                return userInstances[decodedUser.mid]!!
            } catch (e: Exception) {
                Timber.e("Cannot decode dict to user: $e")
                throw RuntimeException("Cannot decode dict to user", e)
            }
        }

        /**
         * Update user instance with new data
         */
        private fun updateUserInstance(user: User) {
            val instance = getInstance(mid = user.mid)
            instance.apply {
                name = user.name
                username = user.username
                password = user.password
                avatar = user.avatar
                email = user.email
                profile = user.profile
                lastLogin = user.lastLogin
                cloudDrivePort = user.cloudDrivePort
                hostIds = user.hostIds
                baseUrl = user.baseUrl
                writableUrl = user.writableUrl
                
                tweetCount = user.tweetCount
                followingCount = user.followingCount
                followersCount = user.followersCount
                bookmarksCount = user.bookmarksCount
                favoritesCount = user.favoritesCount
                commentsCount = user.commentsCount
            }
        }
    }

    // Hprose service management
    @IgnoredOnParcel
    private var _hproseService: HproseService? = null
    val hproseService: HproseService?
        get() {
            val baseUrl = baseUrl ?: return null
            if (_hproseService != null) {
                return _hproseService
            } else {
                val client = HproseHttpClient("$baseUrl/webapi/")
                client.timeout = 300
                val service = client.useService(HproseService::class.java)
                _hproseService = service
                return service
            }
        }

    @IgnoredOnParcel
    private var _uploadService: HproseService? = null
    val uploadService: HproseService?
        get() {
            val writableUrl = writableUrl ?: return null
            if (_uploadService != null) {
                return _uploadService
            } else {
                val client = HproseHttpClient("$writableUrl/webapi/")
                client.timeout = 300
                val service = client.useService(HproseService::class.java)
                _uploadService = service
                return service
            }
        }

    /**
     * Check if user is guest
     */
    fun isGuest(): Boolean {
        return mid == TW_CONST.GUEST_ID
    }

    /**
     * Get avatar URL
     */
    fun getAvatarUrl(): String? {
        return if (avatar != null && baseUrl != null) {
            if (avatar!!.length > 27) {
                "$baseUrl/ipfs/$avatar"
            } else {
                "$baseUrl/mm/$avatar"
            }
        } else null
    }

    /**
     * Resolve writable URL from hostIds
     */
    suspend fun resolveWritableUrl(): String? {
        Timber.d("[resolveWritableUrl] Starting resolution for user: $mid")
        Timber.d("[resolveWritableUrl] Current hostIds: $hostIds")
        Timber.d("[resolveWritableUrl] Current baseUrl: $baseUrl")
        Timber.d("[resolveWritableUrl] Current writableUrl: $writableUrl")

        if (!writableUrl.isNullOrEmpty()) {
            Timber.d("[resolveWritableUrl] Using existing writableUrl: $writableUrl")
            return writableUrl
        }

        val hostIds = hostIds ?: return writableUrl
        if (hostIds.isEmpty()) {
            Timber.d("[resolveWritableUrl] No hostIds available, keeping existing writableUrl")
            return writableUrl
        }

        val firstHostId = hostIds.first()
        Timber.d("[resolveWritableUrl] Attempting to resolve first hostId: $firstHostId")

        if (firstHostId.isEmpty()) {
            Timber.d("[resolveWritableUrl] First hostId is empty, keeping existing writableUrl")
            return writableUrl
        }

        val hostIP = HproseInstance.getHostIP(firstHostId)
        if (hostIP != null) {
            Timber.d("[resolveWritableUrl] Successfully resolved hostIP: $hostIP for hostId: $firstHostId")
            
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
                hostIP.contains(":") && !hostIP.contains("]:") && !hostIP.contains("[") -> {
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
                    // No port specified, use default port 8010
                    val cleanIP = if (hostIP.startsWith("[") && hostIP.endsWith("]")) {
                        hostIP.substring(1, hostIP.length - 1)
                    } else {
                        hostIP
                    }
                    cleanIP to "8010"
                }
            }

            // Validate port is between 8000-9000
            val portNumber = port.toIntOrNull()
            if (portNumber == null || portNumber !in 8000..9000) {
                Timber.w("[resolveWritableUrl] Port $port is not in valid range 8000-9000")
                return writableUrl
            }

            // Construct URL string
            val urlString = if (isIPv6Address(cleanIP)) {
                "http://[$cleanIP]:$port"
            } else {
                "http://$cleanIP:$port"
            }

            Timber.d("[resolveWritableUrl] Final constructed urlString: $urlString")
            writableUrl = urlString
            Timber.d("✅ Resolved writableUrl to: $urlString from first hostId: $firstHostId")
            return urlString
        } else {
            Timber.w("[resolveWritableUrl] Failed to resolve hostIP for hostId: $firstHostId")
        }

        Timber.d("[resolveWritableUrl] Keeping existing writableUrl")
        return writableUrl
    }

    /**
     * Update user data from a map (similar to iOS updateFromMap)
     */
    fun from(userData: Map<String, Any>) {
        try {
            // Pre-process the data to handle scientific notation in numeric fields
            val processedData = userData.toMutableMap()
            
            // Handle timestamp and lastLogin fields
            processedData["timestamp"]?.let { value ->
                when (value) {
                    is Number -> processedData["timestamp"] = value.toLong()
                    is String -> {
                        try {
                            processedData["timestamp"] = value.toDouble().toLong()
                        } catch (e: NumberFormatException) {
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
                        } catch (e: NumberFormatException) {
                            Timber.w("Failed to parse lastLogin: $value")
                        }
                    }
                }
            }
            
            name = processedData["name"] as? String ?: name
            username = processedData["username"] as? String ?: username
            avatar = processedData["avatar"] as? String ?: avatar
            email = processedData["email"] as? String ?: email
            profile = processedData["profile"] as? String ?: profile
            cloudDrivePort = (processedData["cloudDrivePort"] as? Number)?.toInt() ?: cloudDrivePort
            
            tweetCount = (processedData["tweetCount"] as? Number)?.toInt() ?: tweetCount
            followingCount = (processedData["followingCount"] as? Number)?.toInt() ?: followingCount
            followersCount = (processedData["followersCount"] as? Number)?.toInt() ?: followersCount
            bookmarksCount = (processedData["bookmarksCount"] as? Number)?.toInt() ?: bookmarksCount
            favoritesCount = (processedData["favoritesCount"] as? Number)?.toInt() ?: favoritesCount
            commentsCount = (processedData["commentsCount"] as? Number)?.toInt() ?: commentsCount
            
            hostIds = (processedData["hostIds"] as? List<*>)?.mapNotNull { id -> id as? String } ?: hostIds
        } catch (e: Exception) {
            Timber.tag("User.from").e("Error updating user from map: $e")
        }
    }

    /**
     * Check if IP address is IPv6
     */
    private fun isIPv6Address(ip: String): Boolean {
        return ip.contains(":")
    }

    /**
     * Computed property that returns baseUrl with TW_CONST.CLOUD_PORT
     * Used for accessing netdisk and transcode services
     */
    val netdiskUrl: String?
        get() {
            val baseUrl = baseUrl ?: return null
            
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
                
                "$scheme://$hostPart:${TW_CONST.CLOUD_PORT}$path$query$fragment"
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse baseUrl for cloud port replacement: $baseUrl")
                baseUrl
            }
        }

    /**
     * Get writable URL with fallback
     */
    suspend fun writableUrl(): String? {
        return if (!writableUrl.isNullOrEmpty()) {
            writableUrl
        } else {
            hostIds?.firstOrNull()?.let { hostId ->
                HproseInstance.getHostIP(hostId)?.let { hostIP ->
                    "http://$hostIP".also { newUrl ->
                        writableUrl = newUrl
                    }
                } ?: baseUrl
            }
        }
    }

    /**
     * Check if this user instance has expired and needs to be fetched from backend again
     */
    val hasExpired: Boolean
        get() {
            val timestamp = HproseInstance.getUserCacheTimestamp(mid)
            val currentTime = System.currentTimeMillis()
            return (currentTime - timestamp) > HproseInstance.USER_CACHE_EXPIRATION_TIME
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