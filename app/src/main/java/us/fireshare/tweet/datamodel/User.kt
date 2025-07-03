package us.fireshare.tweet.datamodel

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import us.fireshare.tweet.HproseInstance
import hprose.client.HproseHttpClient
import kotlinx.parcelize.IgnoredOnParcel
import timber.log.Timber

@Parcelize
@Serializable
data class User(
    var baseUrl: String? = null,
    var writableUrl: String? = null,
    var mid: MimeiId,
    var name: String? = null,
    var username: String? = null,
    var password: String? = null,
    var avatar: MimeiId? = null,
    var email: String? = null,
    var profile: String? = null,
    var timestamp: Long = System.currentTimeMillis(),
    var lastLogin: Long? = System.currentTimeMillis(),
    var cloudDrivePort: Int = 8010,

    var tweetCount: Int = 0,
    var followingCount: Int = 0,
    var followersCount: Int = 0,
    var bookmarksCount: Int = 0,
    var favoritesCount: Int = 0,
    var commentsCount: Int = 0,

    var hostIds: List<MimeiId>? = null,
    var publicKey: String? = null,

    var fansList: List<MimeiId>? = null,
    var followingList: List<MimeiId>? = null,
    var bookmarkedTweets: List<MimeiId>? = null,
    var favoriteTweets: List<MimeiId>? = null,
    var repliedTweets: List<MimeiId>? = null,
    var commentsList: List<MimeiId>? = null,
    var topTweets: List<MimeiId>? = null
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
                val jsonString = gson.toJson(dict)
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
     * Check if IP address is IPv6
     */
    private fun isIPv6Address(ip: String): Boolean {
        return ip.contains(":")
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