package us.fireshare.tweet

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import hprose.client.HproseClient
import hprose.io.HproseClassManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import us.fireshare.tweet.datamodel.BlackList
import us.fireshare.tweet.datamodel.CachedTweetDao
import us.fireshare.tweet.datamodel.ChatDatabase
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.ChatMessageDeserializer
import us.fireshare.tweet.datamodel.HproseService
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.FeedResetReason
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetCacheDatabase
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.datamodel.UserContentType
import us.fireshare.tweet.service.FileTypeDetector
import us.fireshare.tweet.service.MediaUploadService
import us.fireshare.tweet.utils.ErrorMessageUtils
import us.fireshare.tweet.video.LocalVideoProcessingService
import us.fireshare.tweet.widget.Gadget.filterIpAddresses
import us.fireshare.tweet.widget.VideoManager
import java.io.File
import java.util.UUID
import java.util.regex.Pattern
import us.fireshare.tweet.datamodel.User.Companion.getInstance as getUserInstance

// Encapsulate Hprose client and related operations in a singleton object.
object HproseInstance {
    private var _appId: MimeiId = BuildConfig.APP_ID
    val appId: MimeiId get() = _appId
    // Use Application context to avoid memory leaks - Application lives for the entire app lifecycle
    private lateinit var applicationContext: Application
    lateinit var preferenceHelper: PreferenceHelper
    
    // Private backing field for appUser
    private var _appUser: User = User(mid = TW_CONST.GUEST_ID)
    
    // Lazy initialization of MediaUploadService
    private val mediaUploadService: MediaUploadService by lazy {
        MediaUploadService(applicationContext, httpClient, appUser, appId)
    }
    
    /**
     * Global app user with automatic expiration checking.
     * When accessed, automatically checks if the user has expired (30 minutes)
     * and refreshes from server if needed, similar to other user objects.
     */
    var appUser: User
        get() {
            // Check if appUser has expired and refresh if needed
            if (!_appUser.isGuest() && _appUser.hasExpired) {
                Timber.tag("appUser").d("AppUser expired, refreshing from server...")
                // Use coroutine scope to refresh user data
                TweetApplication.applicationScope.launch {
                    try {
                        val refreshedUser = getUser(_appUser.mid, _appUser.baseUrl)
                        if (refreshedUser != null) {
                            _appUser = refreshedUser
                            Timber.tag("appUser").d("AppUser refreshed successfully")
                        } else {
                            Timber.tag("appUser").w("Failed to refresh appUser, keeping current instance")
                        }
                    } catch (e: Exception) {
                        Timber.tag("appUser").e(e, "Error refreshing appUser")
                    }
                }
            }
            return _appUser
        }
        set(value) {
            val oldBaseUrl = _appUser.baseUrl
            _appUser = value
            // If baseUrl changed from null to a valid value, trigger tweet feed refresh
            if (oldBaseUrl == null && value.baseUrl != null && !value.isGuest()) {
                Timber.tag("appUser").d("BaseUrl became available for logged-in user, triggering tweet feed refresh")
                TweetApplication.applicationScope.launch {
                    try {
                        // Post notification to trigger feed refresh
                        TweetNotificationCenter.post(
                            TweetEvent.FeedResetRequested(FeedResetReason.BASEURL_AVAILABLE)
                        )
                        Timber.tag("appUser").d("Posted FeedResetRequested notification after baseUrl became available: ${value.baseUrl}")
                    } catch (e: Exception) {
                        Timber.tag("appUser").e(e, "Error triggering tweet refresh after baseUrl became available")
                    }
                }
            }
        }

    private lateinit var chatDatabase: ChatDatabase
    lateinit var dao: CachedTweetDao
    
    // Data class for tracking incomplete uploads
    data class IncompleteUpload(
        val workId: String,
        val tweetContent: String,
        val attachmentUris: List<String>,
        val isPrivate: Boolean,
        val timestamp: Long,
        val videoConversionJobId: String? = null,  // For HLS video conversion jobs
        val videoConversionBaseUrl: String? = null,  // Base URL for polling status
        val videoConversionUri: String? = null  // Original video URI for aspect ratio calculation
    )

    suspend fun init(context: Context) {
        try {
            // Store Application context to avoid memory leaks
            this.applicationContext = context.applicationContext as Application
            HproseClassManager.register(Tweet::class.java, "Tweet")
            HproseClassManager.register(User::class.java, "User")

            this.preferenceHelper = PreferenceHelper(context)
            chatDatabase = ChatDatabase.getInstance(context)
            val tweetCache = TweetCacheDatabase.getInstance(context)
            dao = tweetCache.tweetDao()

            // Initialize appUser with userId from preferences, or GUEST_ID if not available
            val storedUserId = preferenceHelper.getUserId()
            val initialUserId = if (storedUserId != TW_CONST.GUEST_ID) storedUserId else TW_CONST.GUEST_ID
            appUser = User(
                mid = initialUserId,
                baseUrl = null,
                followingList = if (initialUserId == TW_CONST.GUEST_ID) getAlphaIds() else emptyList()
            )
            Timber.tag("HproseInstance").d("Initialized appUser with mid: ${appUser.mid}")
            
            // CRITICAL: initAppEntry() must complete first and set IP-based baseUrl
            // This is suspend, so init() will wait for it to complete
            initAppEntry()
        } catch (e: Exception) {
            Timber.tag("HproseInstance").e(e, "Error during HproseInstance initialization")
            // Set up minimal fallback state to prevent app from being completely broken
            if (!::preferenceHelper.isInitialized) {
                this.preferenceHelper = PreferenceHelper(context)
            }
            // If network is unavailable (all URLs failed), try to load cached user
            if (appUser.baseUrl == null) {
                Timber.tag("HproseInstance").w("Network unavailable, attempting to load cached user for offline mode")
                val storedUserId = preferenceHelper.getUserId()
                if (storedUserId != TW_CONST.GUEST_ID) {
                    // Try to load the cached user
                    val cachedUser = TweetCacheManager.getCachedUser(storedUserId)
                    if (cachedUser != null) {
                        // Use the cached user but keep baseUrl as null for offline mode
                        appUser = cachedUser.copy(baseUrl = null)
                        Timber.tag("HproseInstance").d("✅ Loaded cached user for offline mode: userId=${appUser.mid}, username=${appUser.username}")
                    } else {
                        // No cached user found, keep the userId from preferences but set baseUrl to null
                        Timber.tag("HproseInstance").w("No cached user found for userId: $storedUserId, keeping userId but baseUrl is null")
                        appUser = appUser.copy(baseUrl = null)
                    }
                } else {
                    // Guest user
                    Timber.tag("HproseInstance").d("Guest user, using alpha IDs")
                    appUser = appUser.copy(
                        baseUrl = null,
                        followingList = getAlphaIds()
                    )
                }
            }
            // Re-throw the exception so the calling code can handle it
            throw e
        }
    }

    /**
     * App_Url is the network entrance of the App. Use it to initiate appId, and BASE_URL.
     * */
    private suspend fun initAppEntry() {
        val urls = preferenceHelper.getAppUrls()
        Timber.tag("initAppEntry").d("Attempting to initialize app entry with ${urls.size} URL(s): $urls")
        
        // make sure no stale data during retry init.
        for (url in urls) {
            try {
                Timber.tag("initAppEntry").d("Trying URL: $url")
                /**
                 * retrieve window.Param from page source code of http://base_url
                 * window.setParam({
                 *         CurNode:0,
                 *         log: true,
                 *         ver:"last",
                 *         addrs: [[["183.159.17.7:8081", 3.080655111],["[240e:391:e00:169:1458:aa58:c381:5c85]:8081",
                 *                  3.9642842857833],["192.168.0.94:8081", 281478208946270]]],
                 *         aid: "",
                 *         remote:"::1",
                 *         mid:"d4lRyhABgqOnqY4bURSm_T-4FZ4"
                 * })]
                 * */
                val response: HttpResponse = httpClient.get(url)
                val pattern = Pattern.compile("window\\.setParam\\((\\{.*?\\})\\)", Pattern.DOTALL)
                val matcher = pattern.matcher(response.bodyAsText().trimIndent() as CharSequence)
                if (matcher.find()) {
                    matcher.group(1)?.let {
                        val paramMap = Gson().fromJson(it, Map::class.java) as Map<*, *>
                        // For debug builds, always use BuildConfig.APP_ID to ensure correct APP_ID
                        // For release builds, use the server's mid value
                        val serverMid = paramMap["mid"]?.toString()
                        if (BuildConfig.DEBUG) {
                            // Debug builds: Use BuildConfig.APP_ID, don't overwrite with server value
                            Timber.tag("initAppEntry").d("Debug build: Using BuildConfig.APP_ID (${BuildConfig.APP_ID}) instead of server mid ($serverMid)")
                            // _appId is already set from BuildConfig.APP_ID at initialization, keep it
                        } else {
                            // Release builds: Use server's mid value
                            _appId = serverMid ?: BuildConfig.APP_ID
                            Timber.tag("initAppEntry").d("Release build: Using server mid: $_appId")
                        }

                        /**
                         * The code above makes a call to base URL of the app, get a html page
                         * and tries to extract appId and host IP addresses from source code.
                         * For debug builds, APP_ID is always from BuildConfig to ensure debug/release separation.
                         *
                         * addrs is an ArrayList of ArrayList of node's IP address pairs.
                         * Each pair is an ArrayList of two elements. The first is the IP address,
                         * and the second is the time spent to get response from the IP.
                         *
                         * bestIp is the IP with the smallest response time from valid public IPs.
                         * */
                        Timber.tag("initAppEntry").d("Successfully parsed paramMap: $paramMap")
                        val bestIp = filterIpAddresses(paramMap["addrs"] as List<String>)

                        appUser = appUser.copy(baseUrl = "http://$bestIp")
                        Timber.tag("initAppEntry").d("Set baseUrl to IP: http://$bestIp")
                        
                        val userId = preferenceHelper.getUserId()
                        Timber.tag("initAppEntry").d("Retrieved userId from preferences: $userId")
                        if (userId != TW_CONST.GUEST_ID) {
                            /**
                             * If there is a valid userId in preference, this is a login user.
                             * Initiate current account.
                             * 
                             * Always force refresh of appUser's baseUrl on app start to ensure we have the latest IP.
                             * Pass empty string to force IP re-resolution and bypass cache (matching iOS behavior).
                             * This matches iOS initAppEntry() which calls fetchUser(appUser.mid, baseUrl: "")
                             * */
                            Timber.tag("initAppEntry").d("Always refreshing appUser's baseUrl on app start for userId: $userId")
                            // Pass empty string to getUser to force IP re-resolution (like iOS fetchUser with baseUrl: "")
                            // forceRefresh ensures fresh data from server without needing to clear cache
                            val refreshedUser = getUser(userId, baseUrl = "", forceRefresh = true)
                            val refreshedBaseUrl = refreshedUser?.baseUrl
                            if (refreshedUser != null && refreshedBaseUrl != null && refreshedBaseUrl.isNotEmpty()) {
                                // Use the refreshed user's baseUrl
                                appUser = refreshedUser
                                TweetCacheManager.saveUser(refreshedUser) // Ensure the refreshed user is saved to cache
                                Timber.tag("initAppEntry").d("✅ App initialized with refreshed user baseUrl: ${appUser.baseUrl}")
                            } else {
                                // Network fetch failed, try to load cached user
                                Timber.tag("initAppEntry").w("User fetch failed, attempting to load cached user for userId: $userId")
                                val cachedUser = TweetCacheManager.getCachedUser(userId)
                                if (cachedUser != null) {
                                    // Use cached user but update baseUrl to the resolved IP
                                    appUser = cachedUser.copy(baseUrl = "http://$bestIp")
                                    Timber.tag("initAppEntry").d("✅ Loaded cached user with resolved IP baseUrl: ${appUser.baseUrl}, username: ${appUser.username}")
                                } else {
                                    // No cached user found, fall back to bestIp with current userId
                                    Timber.tag("initAppEntry").w("No cached user found, using resolved IP: $bestIp")
                                    appUser = appUser.copy(baseUrl = "http://$bestIp")
                                }
                            }
                            Timber.tag("initAppEntry").d("User initialized. $appId, appUser.baseUrl: ${appUser.baseUrl}")
                        } else {
                            appUser.followingList = getAlphaIds()
                            TweetCacheManager.saveUser(appUser)
                            Timber.tag("initAppEntry").d("Guest user initialized. $appId, $appUser")
                        }
                        // once a workable URL is found, return successfully
                        Timber.tag("initAppEntry").d("✅ Successfully initialized app entry")
                        return
                    }
                } else {
                    Timber.tag("initAppEntry").w("No data found within window.setParam() for URL: $url")
                }
            } catch (e: Exception) {
                val isNetworkError = ErrorMessageUtils.isNetworkError(e)
                if (isNetworkError) {
                    Timber.tag("initAppEntry").w(e, "Network error connecting to URL: $url (will try next URL if available)")
                } else {
                    Timber.tag("initAppEntry").e(e, "Failed to initialize app entry from URL: $url")
                }
            }
        }
        
        // If we reach here, all URLs failed - throw exception with detailed error message
        val errorMsg = "Failed to initialize app entry. Tried ${urls.size} URL(s): ${urls.joinToString(", ")}"
        Timber.tag("initAppEntry").e(errorMsg)
        throw IllegalStateException(errorMsg)
    }

    /**
     * List of system users to be followed by default
     * */
    fun getAlphaIds(): List<MimeiId> {
        return BuildConfig.ALPHA_ID.split(",").map { it.trim() }
    }

    suspend fun sendMessage(receiptId: MimeiId, msg: ChatMessage): Pair<Boolean, String?> {
        val entry = "message_outgoing"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
            "userid" to appUser.mid,
            "receiptid" to receiptId,
            "msg" to Json.encodeToString(msg),
            "hostid" to (appUser.hostIds?.first() ?: "")
        )
        try {
            val response = appUser.hproseService?.runMApp<Boolean>(entry, params)

            if (response == true) {
                getUser(receiptId)?.let { receipt ->
                    val receiptEntry = "message_incoming"
                    val receiptParams = mapOf(
                        "aid" to appId,
                        "ver" to "last",
                        "entry" to receiptEntry,
                        "senderid" to appUser.mid,
                        "receiptid" to receiptId,
                        "msg" to Json.encodeToString(msg)
                    )
                    try {
                        val receiptResponse = receipt.hproseService?.runMApp<Map<String, Any>>(receiptEntry, receiptParams)
                        
                        // Check if the response indicates success or failure
                        val success = receiptResponse?.get("success") as? Boolean ?: true
                        val errorMsg = receiptResponse?.get("error") as? String

                        return if (success) {
                            Pair(true, null)
                        } else {
                            Pair(false, errorMsg ?: applicationContext.getString(R.string.error_unknown))
                        }
                    } catch (e: Exception) {
                        Timber.tag("sendMessage").e("Error sending to receipt: $e")
                        return Pair(false, ErrorMessageUtils.getNetworkErrorMessage(applicationContext, e))
                    }
                }
                return Pair(true, null) // No receipt user found, but outgoing was successful
            } else {
                return Pair(false, applicationContext.getString(R.string.error_send_outgoing_message))
            }
        } catch (e: Exception) {
            Timber.tag("sendMessage").e(e)
            return Pair(false, e.message ?: applicationContext.getString(R.string.error_network))
        }
    }

    // get the recent unread message from a sender.
    suspend fun fetchMessages(senderId: MimeiId): List<ChatMessage>? {
        val entry = "message_fetch"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
            "userid" to appUser.mid,
            "senderid" to senderId
        )

        return try {
            val gson = GsonBuilder()
                .registerTypeAdapter(ChatMessage::class.java, ChatMessageDeserializer())
                .create()

            appUser.hproseService?.runMApp<List<Map<String, Any>>>(entry, params)
                ?.mapNotNull { messageData ->
                    gson.fromJson(gson.toJson(messageData), ChatMessage::class.java)
                }
        } catch (e: Exception) {
            Timber.tag("fetchMessages").e(e)
            null
        }
    }

    /**
     * Get a list of unread incoming messages. Only check, do not fetch them.
     * */
    suspend fun checkNewMessages(): List<ChatMessage>? {
        if (appUser.isGuest()) return null
        val entry = "message_check"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
            "userid" to appUser.mid
        )
        return try {
            val gson = GsonBuilder()
                .registerTypeAdapter(ChatMessage::class.java, ChatMessageDeserializer())
                .create()

            appUser.hproseService?.runMApp<List<Map<String, Any>>>(entry, params)
                ?.mapNotNull { messageData ->
                    gson.fromJson(gson.toJson(messageData), ChatMessage::class.java)
                }
        } catch (e: Exception) {
            Timber.tag("checkNewMessages").e(e)
            null
        }
    }

    suspend fun checkUpgrade(): Map<String, String>? {
        val entry = "check_upgrade"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry
        )
        return try {
            val response =
                appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)
            response?.mapValues { it.value.toString() }
        } catch (e: Exception) {
            Timber.tag("checkUpgrade").e(e)
            null
        }
    }

    suspend fun getUserId(username: String): MimeiId? {
        val entry = "get_userid"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "username" to username
        )
        return try {
            appUser.hproseService?.runMApp<String>(entry, params)
        } catch (e: Exception) {
            Timber.tag("GetUserId").e(e)
            null
        }
    }

    /**
     * @return a pair. The first is User object, the second is error message.
     * There are two steps for a guest user to login. First, find UserID given username.
     * Second, find the node which has this user's data, and logon to that node.
     * Finally update the baseUrl of the current user with the new ip of the user's node.
     * Includes retry logic with exponential backoff for network-related failures.
     * */
    suspend fun login(
        username: String,
        password: String,
        context: Context,
        maxRetries: Int = 3
    ): Pair<User?, String?> {
        var lastError: String? = null
        
        repeat(maxRetries) { attempt ->
            try {
                val userId = getUserId(username) ?: return Pair(
                    null,
                    context.getString(R.string.login_getuserid_fail)
                )
                val user =
                    getUser(userId, "") ?: return Pair(null, context.getString(R.string.login_getuser_fail))
                val entry = "login"
                val params = mapOf(
                    "aid" to appId,
                    "ver" to "last",
                    "username" to username,
                    "password" to password
                )
                val response =
                    user.hproseService?.runMApp<Map<String, Any>>(entry, params)

                when (response?.get("status") as? String) {
                    "success" -> return Pair(user, null)
                    "failure" -> {
                        val errorMsg = response["reason"] as? String ?: applicationContext.getString(R.string.error_unknown_occurred)
                        lastError = errorMsg
                        // Don't retry for authentication failures (wrong password, etc.)
                        if (errorMsg.contains("password", ignoreCase = true) || 
                            errorMsg.contains("username", ignoreCase = true) ||
                            errorMsg.contains("invalid", ignoreCase = true)) {
                            return Pair(null, errorMsg)
                        }
                        // For other failures, continue to retry
                    }
                    else -> {
                        lastError = context.getString(R.string.login_error)
                        // Continue to retry for unknown errors
                    }
                }
            } catch (e: Exception) {
                lastError = ErrorMessageUtils.getNetworkErrorMessage(context, e)
                Timber.tag("Login").e(e, "Login attempt ${attempt + 1} failed")
                
                // Check if it's a network-related error that should be retried
                val isNetworkError = ErrorMessageUtils.isNetworkError(e)
                
                if (!isNetworkError) {
                    // Don't retry for non-network errors
                    return Pair(null, lastError)
                }
            }
            
            // If this isn't the last attempt, wait before retrying
            if (attempt < maxRetries - 1) {
                val delayMs = minOf(5000L, 1000L * (1 shl attempt)) // Exponential backoff: 1s, 2s, 4s
                Timber.tag("Login").d("Retrying login in ${delayMs}ms (attempt ${attempt + 2}/$maxRetries)")
                kotlinx.coroutines.delay(delayMs)
            }
        }
        
        // All retries failed
        return Pair(null, lastError ?: context.getString(R.string.login_error))
    }

    /**
     * Given host url, get the node Id
     * */
    suspend fun getHostId(host: String? = appUser.baseUrl): MimeiId? {
        val entry = "getvar"
        val params = mapOf("name" to "hostid")
        return try {
            val hproseClient =
                HproseClient.create("$host/webapi/").useService(HproseService::class.java)
            val response =
                hproseClient.runMApp<String>(entry, params)
            response?.trim()?.trim('"')?.trim(',')
        } catch (e: Exception) {
            Timber.tag("getHostId").e("$e $host")
            null
        }
    }

    /**
     * @param nodeId
     * Find IP addresses of given node.
     * */
    suspend fun getHostIP(nodeId: MimeiId, v4Only: String = "false"): String? {
        val entry = "get_node_ip"
        val params = mapOf("aid" to appId, "ver" to "last", "nodeid" to nodeId, "v4only" to v4Only)
        try {
            return appUser.hproseService?.runMApp<String>(entry, params)
        } catch (e: Exception) {
            Timber.tag("getHostIP").e("$e $nodeId")
        }
        return null
    }

    /**
     * Register a new user or update an existing user account.
     * */
    suspend fun setUserData(userObj: User): Map<*, *>? {
        val user = userObj.copy(fansList = null, followingList = null)  // Do not save them.
        val entry = if (user.isGuest()) {
            /**
             * Register a new user.
             * */
            user.followingList = getAlphaIds()
            "register"
        } else {
            /**
             * Update existing user account.
             * If hostId is changed, sync user mimei on new node first.
             * */
            "set_author_core_data"
        }
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "user" to Json.encodeToString(user)
        )
        return try {
            user.hproseService?.runMApp<Map<String, Any>>(entry, params)
        } catch (e: Exception) {
            Timber.tag("setUserData").e(e)
            null
        }
    }

    suspend fun setUserAvatar(user: User, avatar: MimeiId): MimeiId? {
        val entry = "set_user_avatar"
        val json = """
            {"aid": $appId, "ver": "last", "userid": ${user.mid}, "avatar": $avatar}
        """.trimIndent()
        val gson = Gson()
        val request = gson.fromJson(json, Map::class.java)
        return try {
            val response = appUser.uploadService?.runMApp<MimeiId>(entry, request)
            response
        } catch (e: Exception) {
            Timber.tag("setUserAvatar").e(e)
            null
        }
    }

    /**
     * Given user object get a list of Field-Value, where Field is user Id,
     * Value is timestamp when the following is added.
     * */
    suspend fun getFollowings(user: User): List<MimeiId> {
        if (user.isGuest()) return getAlphaIds()
        val entry = "get_followings_sorted"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "userid" to user.mid
        )
        return try {
            val response = user.hproseService?.runMApp<List<Map<String, Any>>>(entry, params)
            response?.sortedByDescending { (it["value"] as? Int) ?: 0 }
                ?.mapNotNull { it["field"] as? String } ?: getAlphaIds()
        } catch (e: Exception) {
            Timber.tag("Hprose.getFollowings").e(e)
            getAlphaIds()
        }
    }

    /**
     * Given user object get a list of Field-Value, where Field is user Id,
     * Value is timestamp when the follower is added.
     * */
    suspend fun getFans(user: User): List<MimeiId>? {
        if (user.isGuest()) return null
        val entry = "get_followers_sorted"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "userid" to user.mid
        )
        return try {
            val response = user.hproseService?.runMApp<List<Map<String, Any>>>(entry, params)
            response?.sortedByDescending { (it["value"] as? Int) ?: 0 }
                ?.mapNotNull { it["field"] as? String }
        } catch (e: Exception) {
            Timber.tag("Hprose.getFans").e(e)
            null
        }
    }

    /**
     * Load tweets of appUser and its followings from network.
     * Keep null elements in the response list and preserves their positions.
     * */
    suspend fun getTweetFeed(
        user: User = appUser,
        pageNumber: Int = 0,
        pageSize: Int = 20,
        entry: String = "get_tweet_feed"
    ): List<Tweet?> {
        val alphaIds = getAlphaIds()
        val userIdForGuest = if (alphaIds.isNotEmpty()) alphaIds.first() else ""
        
        // For guest users, if no alpha IDs are configured, return empty list
        if (user.isGuest() && alphaIds.isEmpty()) {
            Timber.tag("getTweetFeed").w("No alpha IDs configured for guest user")
            return emptyList()
        }
        
        val params = mutableMapOf(
            "aid" to appId,
            "ver" to "last",
            "pn" to pageNumber,
            "ps" to pageSize,
            "userid" to if (!user.isGuest()) user.mid else userIdForGuest,
            "appuserid" to appUser.mid
        )
        if (entry == "update_following_tweets") {
            appUser.hostIds?.first()?.let { hostId ->
                params["hostid"] = hostId
            }
        }
        return try {
            val response =
                user.hproseService?.runMApp<Map<String, Any>>(entry, params)

            // Check success status first
            val success = response?.get("success") as? Boolean
            if (success != true) {
                val errorMessage = response?.get("message") as? String ?: applicationContext.getString(R.string.error_unknown_occurred)
                Timber.tag("getTweetFeed").e("Feed failed: $errorMessage")
                return emptyList()
            }

            // Extract tweets and originalTweets from the new response format
            val tweetsData = response["tweets"] as? List<Map<String, Any>?>
            val originalTweetsData = response["originalTweets"] as? List<Map<String, Any>?>

            // Cache original tweets by authorId
            originalTweetsData?.forEach { originalTweetJson ->
                if (originalTweetJson != null) {
                    try {
                        val originalTweet = Tweet.from(originalTweetJson)
                        originalTweet.author = getUser(originalTweet.authorId)
                        TweetCacheManager.saveTweet(originalTweet, originalTweet.authorId)
                    } catch (e: Exception) {
                        Timber.tag("getTweetFeed").e("Error caching original tweet: $e")
                    }
                }
            }

            // Process main tweets - cache mainfeed tweets under appUser.mid
            tweetsData?.map { tweetJson ->
                // If the element is null, keep it as null
                if (tweetJson == null) {
                    null
                } else {
                    // Try to decode the tweet
                    try {
                        val tweet = Tweet.from(tweetJson)
                        tweet.author = getUser(tweet.authorId)

                        // Skip private tweets in feed
                        if (tweet.isPrivate) {
                            null
                        } else {
                            // Cache mainfeed tweets under appUser.mid
                            updateCachedTweet(tweet, userId = appUser.mid)
                            tweet
                        }
                    } catch (e: Exception) {
                        Timber.tag("getTweetFeed").e("Error decoding tweet: $e")
                        null
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Timber.tag("getTweetFeed").e("Exception: $e")
            Timber.tag("getTweetFeed").e("❌ STACK TRACE: ${e.stackTraceToString()}")
            emptyList()
        }
    }

    /**
     * Load tweets of a specific user by rank.
     * Handles null elements in the response list and preserves their positions.
     * */
    suspend fun getTweetsByUser(
        user: User,
        pageNumber: Int = 0,
        pageSize: Int = 20,
        entry: String = "get_tweets_by_user"
    ): List<Tweet?> {
        try {
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "userid" to user.mid,
                "pn" to pageNumber,
                "ps" to pageSize,
                "appuserid" to appUser.mid
            )

            Timber.tag("getTweetsByUser")
                .d("Fetching tweets for user: ${user.mid}, page: $pageNumber, size: $pageSize")
            val response = user.hproseService?.runMApp<Map<String, Any>>(entry, params)

            // Check success status first
            val success = response?.get("success") as? Boolean
            if (success != true) {
                val errorMessage = response?.get("message") as? String ?: applicationContext.getString(R.string.error_unknown_occurred)
                Timber.tag("getTweetsByUser")
                    .e("Tweets loading failed for user ${user.mid}: $errorMessage")
                Timber.tag("getTweetsByUser").e("Response: $response")

                return emptyList()
            }

            // Extract tweets and originalTweets from the new response format
            val tweetsData = response["tweets"] as? List<Map<String, Any>?>
            val originalTweetsData = response["originalTweets"] as? List<Map<String, Any>?>

            // Cache original tweets by authorId
            originalTweetsData?.forEach { originalTweetJson ->
                if (originalTweetJson != null) {
                    try {
                        val originalTweet = Tweet.from(originalTweetJson)
                        originalTweet.author = getUser(originalTweet.authorId)
                        TweetCacheManager.saveTweet(originalTweet, originalTweet.authorId)
                        Timber.tag("getTweetsByUser")
                            .d("Cached original tweet: ${originalTweet.mid}")
                    } catch (e: Exception) {
                        Timber.tag("getTweetsByUser").e("Error caching original tweet: $e")
                    }
                }
            }

            val result = tweetsData?.map { tweetJson ->
                // If the element is null, keep it as null
                if (tweetJson == null) {
                    null
                } else {
                    // Try to decode the tweet
                    try {
                        val tweet = Tweet.from(tweetJson)
                        tweet.author = user
                        // Note: originalTweet is no longer loaded here, it will be loaded on-demand in the UI
                        // Cache all tweets by their authorId
                        updateCachedTweet(tweet, userId = tweet.authorId)
                        tweet
                    } catch (e: Exception) {
                        Timber.tag("getTweetsByUser").e("Error decoding tweet: $e")
                        null
                    }
                }
            } ?: emptyList()

            Timber.tag("getTweetsByUser")
                .d("Received ${tweetsData?.size ?: 0} tweets (${result.filterNotNull().size} valid) and ${originalTweetsData?.size ?: 0} original tweets for user: ${user.mid}")

            return result
        } catch (e: Exception) {
            Timber.tag("getTweetsByUser").e("Error fetching tweets for user: ${user.mid}")
            Timber.tag("getTweetsByUser").e("Exception: $e")
            Timber.tag("getTweetsByUser").e("Stack trace: ${e.stackTraceToString()}")

            throw e
        }
    }

    /**
     * Get core data of the tweet. Do Not fetch its original tweet if there is any.
     * Let the caller to decide if go further on the tweet hierarchy.
     * Fetch a tweet by ID
     * */
    suspend fun fetchTweet(
        tweetId: MimeiId,
        authorId: MimeiId
    ): Tweet? {
        // Check if tweet is blacklisted
        if (BlackList.isBlacklisted(tweetId)) {
            Timber.tag("fetchTweet").d("Tweet $tweetId is blacklisted, returning null")
            return null
        }

        return try {
            // Check cache first using TweetCacheManager
            val author = getUser(authorId)
            val cachedTweet = TweetCacheManager.getCachedTweet(tweetId)
            if (cachedTweet != null) {
                cachedTweet.author = author
                return cachedTweet
            }

            val entry = "get_tweet"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "tweetid" to tweetId,
                "appuserid" to appUser.mid
            )

            author?.hproseService?.runMApp<Map<String, Any>>(entry, params)?.let { tweetData ->
                // Record successful access
                BlackList.recordSuccess(tweetId)

                Tweet.from(tweetData).apply {
                    this.author = author
                    // Cache tweet by authorId, not appUser.mid
                    TweetCacheManager.saveTweet(
                        this,
                        userId = authorId
                    )
                }
            }
        } catch (e: Exception) {
            // Record failed access
            BlackList.recordFailure(tweetId)

            Timber.tag("fetchTweet").e("Error fetching tweet: $tweetId, author: $authorId")
            Timber.tag("fetchTweet").e("Exception: $e")

            null
        }
    }

    /**
     * Update cached but keep its timestamp when it was cached.
     * @param userId The user ID to cache under. Defaults to tweet.authorId
     * */
    fun updateCachedTweet(tweet: Tweet, userId: MimeiId? = null) {
        val cacheUserId = userId ?: tweet.authorId
        TweetCacheManager.updateCachedTweet(tweet, cacheUserId)
    }

    /**
     * Get tweet from node Mimei DB to refresh cached tweet.
     * Called when the given tweet is visible.
     * */
    @Suppress("SENSELESS_COMPARISON")
    suspend fun refreshTweet(
        tweetId: MimeiId?,
        authorId: MimeiId?
    ): Tweet? {
        if (tweetId == null || authorId == null) {
            Timber.tag("refreshTweet").w("Null parameters: tweetId=$tweetId, authorId=$authorId")
            return null
        }

        // Check if tweet is blacklisted
        if (BlackList.isBlacklisted(tweetId)) {
            Timber.tag("refreshTweet").d("Tweet $tweetId is blacklisted, returning null")
            return null
        }

        return try {
            val author = getUser(authorId) ?: return null
            val entry = "refresh_tweet"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "tweetid" to tweetId,
                "appuserid" to appUser.mid,
                "userid" to authorId,
                "hostid" to (author.hostIds?.first() ?: "")
            )
            author.hproseService?.runMApp<Map<String, Any>>(entry, params)?.let { tweetData ->
                // Record successful access
                BlackList.recordSuccess(tweetId)

                val tweet = Tweet.from(tweetData)
                tweet.author = author
                tweet
            }
        } catch (e: Exception) {
            // Record failed access
            BlackList.recordFailure(tweetId)

            Timber.tag("refreshTweet").e("Error refreshing tweet: $tweetId, author: $authorId")
            Timber.tag("refreshTweet").e("Exception: $e")

            null
        }
    }

    /**
     * @param userId The user ID to resync with main host node.
     * @return Updated User object from server, or null if resync failed
     */
    @Suppress("UNREACHABLE_CODE")
    suspend fun syncUser(userId: MimeiId): User? {
        // Check if user is blacklisted
        if (BlackList.isBlacklisted(userId)) {
            Timber.tag("syncUser").d("User $userId is blacklisted, returning null")
            return null
        }

        // First attempt: Try with cached user's baseUrl
        val user = getUser(userId) ?: return null

        val result = try {
            val entry = "resync_user"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "userid" to userId,
            )
            
            val response = user.hproseService?.runMApp<Map<String, Any>>(entry, params)
            if (response != null) {
                // Record successful access
                BlackList.recordSuccess(userId)
                
                // Create updated user from server response
                val resyncedUser = getUserInstance(userId)
                resyncedUser.from(response)
                resyncedUser.baseUrl = user.baseUrl
                
                // Update cache with refreshed user data (including baseUrl)
                TweetCacheManager.saveUser(resyncedUser)
                
                Timber.tag("syncUser").d("Successfully synced user: $userId")
                resyncedUser
            } else {
                // If we reach here, the call returned null, try with empty baseUrl
                Timber.tag("syncUser").w("Initial sync attempt returned null for user: $userId, retrying with empty baseUrl")
                syncUserWithEmptyBaseUrl(userId)
            }
        } catch (e: Exception) {
            // Check if it's a network-related error that should trigger retry with empty baseUrl
            val isNetworkError = ErrorMessageUtils.isNetworkError(e)
            
            if (isNetworkError) {
                Timber.tag("syncUser").w("Network error during sync for user: $userId, retrying with empty baseUrl. Error: ${e.message}")
                // Retry with empty baseUrl to force IP resolution
                syncUserWithEmptyBaseUrl(userId)
            } else {
                // Record failed access
                BlackList.recordFailure(userId)
                
                Timber.tag("syncUser").e("Error refreshing user: $userId")
                Timber.tag("syncUser").e("Exception: $e")
                
                null
            }
        }
        return result
    }

    /**
     * Helper function to sync user with empty baseUrl to force IP resolution
     */
    private suspend fun syncUserWithEmptyBaseUrl(userId: MimeiId): User? {
        return try {
            // Force IP resolution by using empty baseUrl
            val user = getUser(userId, baseUrl = "") ?: return null
            
            val entry = "resync_user"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "userid" to userId,
            )
            
            user.hproseService?.runMApp<Map<String, Any>>(entry, params)?.let { userData ->
                // Record successful access
                BlackList.recordSuccess(userId)
                
                // Create updated user from server response
                val resyncedUser = getUserInstance(userId)
                resyncedUser.from(userData)
                resyncedUser.baseUrl = user.baseUrl
                
                // Update cache with refreshed user data (including resolved baseUrl)
                TweetCacheManager.saveUser(resyncedUser)
                
                Timber.tag("syncUser").d("Successfully synced user: $userId with resolved baseUrl: ${user.baseUrl}")
                resyncedUser
            }
        } catch (e: Exception) {
            // Record failed access
            BlackList.recordFailure(userId)
            
            Timber.tag("syncUser").e("Error refreshing user with empty baseUrl: $userId")
            Timber.tag("syncUser").e("Exception: $e")
            
            null
        }
    }

    suspend fun loadCachedTweets(
        startRank: Int,  // earlier in time, therefore smaller timestamp
        count: Int,
    ): List<Tweet> = withContext(Dispatchers.IO) {
        return@withContext try {
            // Load tweets cached for mainfeed (uid = appUser.mid)
            val userId = appUser.mid
            Timber.tag("loadCachedTweets").d("Loading cached tweets for mainfeed user: $userId")
            
            dao.getCachedTweetsByUser(userId, startRank, count).mapNotNull { cachedTweet ->
                val tweet = cachedTweet.originalTweet
                
                // Skip tweets with null authorId (should never happen, but safety check)
                if (tweet.authorId.isNullOrEmpty()) {
                    Timber.tag("loadCachedTweets").w("⚠️ Skipping tweet ${tweet.mid} with null/empty authorId")
                    return@mapNotNull null
                }
                
                // Always populate author from user cache (author field is not serialized with tweet)
                tweet.author = TweetCacheManager.getCachedUser(tweet.authorId)
                
                // If no cached user found, create a skeleton user object as placeholder for offline loading
                if (tweet.author == null) {
                    tweet.author = getUserInstance(tweet.authorId)
                    Timber.tag("loadCachedTweets").d("📦 Created skeleton user placeholder for tweet ${tweet.mid} - authorId ${tweet.authorId}")
                }
                
                Timber.tag("loadCachedTweets").d("✅ Loaded cached tweet ${tweet.mid} with author ${tweet.author?.username ?: tweet.authorId}")
                tweet
            }
        } catch (e: Exception) {
            Timber.tag("loadCachedTweets").e("❌ Error loading cached tweets: $e")
            emptyList()
        }
    }

    /**
     * Load cached tweets for a specific user profile (filtered by authorId)
     * Used for offline loading of user profile tweets
     */
    suspend fun loadCachedTweetsByAuthor(
        authorId: MimeiId,
        startRank: Int,
        count: Int,
    ): List<Tweet> = withContext(Dispatchers.IO) {
        return@withContext try {
            Timber.tag("loadCachedTweetsByAuthor").d("Loading cached tweets for author: $authorId")
            
            val allCachedTweets = mutableListOf<Tweet>()
            
            // For appUser's profile, we need to check BOTH:
            // 1. Tweets cached from mainfeed (uid = appUser.mid, authorId = appUser.mid)
            // 2. Tweets cached from profile (uid = appUser.mid, authorId = appUser.mid)
            // Both are in the same cache bucket, so we load from appUser.mid and filter by authorId
            if (authorId == appUser.mid) {
                // Load from mainfeed cache (appUser.mid) - this includes appUser's tweets from mainfeed
                Timber.tag("loadCachedTweetsByAuthor").d("Loading appUser's tweets from mainfeed cache (uid = appUser.mid)")
                dao.getCachedTweetsByUser(appUser.mid, 0, count * 3).forEach { cachedTweet ->
                    val tweet = cachedTweet.originalTweet
                    // Only include tweets authored by appUser
                    if (!tweet.authorId.isNullOrEmpty() && tweet.authorId == authorId) {
                        allCachedTweets.add(tweet)
                    }
                }
            } else {
                // For other users, load from their authorId cache
                Timber.tag("loadCachedTweetsByAuthor").d("Loading tweets from author cache (uid = $authorId)")
                dao.getCachedTweetsByUser(authorId, 0, count * 3).forEach { cachedTweet ->
                    val tweet = cachedTweet.originalTweet
                    // Only include tweets authored by this author
                    if (!tweet.authorId.isNullOrEmpty() && tweet.authorId == authorId) {
                        allCachedTweets.add(tweet)
                    }
                }
            }
            
            // Populate authors and filter
            allCachedTweets.mapNotNull { tweet ->
                // Always populate author from user cache (author field is not serialized with tweet)
                tweet.author = TweetCacheManager.getCachedUser(tweet.authorId)
                
                // If no cached user found, create a skeleton user object as placeholder for offline loading
                if (tweet.author == null) {
                    tweet.author = getUserInstance(tweet.authorId)
                    Timber.tag("loadCachedTweetsByAuthor").d("📦 Created skeleton user placeholder for tweet ${tweet.mid} - authorId ${tweet.authorId}")
                }
                
                Timber.tag("loadCachedTweetsByAuthor").d("✅ Loaded cached tweet ${tweet.mid} with author ${tweet.author?.username ?: tweet.authorId}")
                tweet
            }
            .distinctBy { it.mid }
            .sortedByDescending { it.timestamp }
            .drop(startRank)
            .take(count)
        } catch (e: Exception) {
            Timber.tag("loadCachedTweetsByAuthor").e("❌ Error loading cached tweets by author: $e")
            emptyList()
        }
    }

    /**
     * Increase/decrease the retweetCount of the original tweet mimei.
     * @param originalTweet is the original tweet
     * @param retweetId of the retweet.
     * @param direction to indicate increase or decrease retweet count.
     * @return the updated original tweet.
     * */
    suspend fun updateRetweetCount(
        originalTweet: Tweet,
        retweetId: MimeiId,
        direction: Int = 1
    ): Tweet? {
        val entry = if (direction == 1) "retweet_added" else "retweet_removed"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
            "tweetid" to originalTweet.mid,
            "appuserid" to appUser.mid,
            "retweetid" to retweetId,
            "authorid" to originalTweet.authorId
        )
        return try {
            originalTweet.author?.hproseService?.runMApp<Map<String, Any>>(entry, params)?.let {
                Tweet.from(it)
            }
        } catch (e: Exception) {
            Timber.tag("updateRetweetCount()").e(e)
            null
        }
    }

    suspend fun uploadTweet(tweet: Tweet): Tweet? {
        val entry = "add_tweet"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "hostid" to (appUser.hostIds?.first() ?: ""),
            "tweet" to Json.encodeToString(tweet)
        )
        return try {
            val response =
                appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)
            if (response?.get("success") == true) {
                val newTweetId = response["mid"] as? String
                if (newTweetId != null) {
                    // Create a new tweet with the updated mid
                    val updatedTweet = tweet.copy(mid = newTweetId, author = appUser)

                    // Post notification for successful upload (only for original tweets, not retweets)
                    if (tweet.originalTweetId == null) {
                        TweetNotificationCenter.post(TweetEvent.TweetUploaded(updatedTweet))
                    }
                    
                    // Refresh appUser from server to get updated tweetCount and other properties
                    try {
                        // Invalidate cache to force fresh fetch from server
                        TweetCacheManager.removeCachedUser(appUser.mid)
                        val refreshedUser = getUser(appUser.mid, appUser.baseUrl, maxRetries = 1)
                        if (refreshedUser != null && !refreshedUser.isGuest()) {
                            // Create a new user object to trigger recompose
                            val updatedUser = refreshedUser.copy()
                            appUser = updatedUser
                            TweetCacheManager.saveUser(appUser)
                            
                            // Notify other ViewModels that user data has been updated
                            TweetNotificationCenter.post(TweetEvent.UserDataUpdated(appUser.copy()))
                        }
                    } catch (e: Exception) {
                        Timber.tag("uploadTweet").w("Failed to refresh appUser after upload: $e")
                    }

                    Timber.tag("uploadTweet").d("Tweet uploaded: $newTweetId")
                    updatedTweet
                } else {
                    Timber.tag("uploadTweet").e("No tweet ID in response")
                    null
                }
            } else {
                val errorMessage = response?.get("message") as? String ?: applicationContext.getString(R.string.error_upload_unknown)
                Timber.tag("uploadTweet").e("Upload failed: $errorMessage")
                null
            }
        } catch (e: Exception) {
            Timber.tag("uploadTweet").e("Upload exception: $e")
            null
        }
    }


    suspend fun delComment(parentTweet: Tweet, commentId: MimeiId, callback: (MimeiId) -> Unit) {
        val entry = "delete_comment"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
            "tweetid" to parentTweet.mid,
            "commentid" to commentId,
            "userid" to appUser.mid,
            "hostid" to (parentTweet.author?.hostIds?.first() ?: "")
        )
        try {
            val response =
                parentTweet.author?.hproseService?.runMApp<Boolean>(entry, params)

            if (response == true) {
                // Post notification for successful comment deletion
                TweetNotificationCenter.post(TweetEvent.CommentDeleted(commentId, parentTweet.mid))
                callback(commentId)
            }
        } catch (e: Exception) {
            Timber.tag("delComment()").e(e)
        }
    }

    /**
     * Called when appUser clicks the Follow button.
     * @param followedId is the user that appUser is following or unfollowing.
     * */
    suspend fun toggleFollowing(
        followedId: MimeiId,
        followingId: MimeiId = appUser.mid
    ): Boolean? {
        val entry = "toggle_following"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "followingid" to followedId,
            "userid" to followingId
        )
        return try {
            appUser.hproseService?.runMApp<Boolean>(entry, params)
        } catch (e: Exception) {
            Timber.tag("toggleFollowing()").e(e)
            null
        }
    }



    /**
     * Send a retweet request to backend and get a new tweet object back.
     * */
    suspend fun retweet(
        tweet: Tweet     // original tweet to be retweeted
    ) {
        try {
            // upload the retweet, simply a few dozen bytes.
            val retweet = uploadTweet(
                Tweet(
                    mid = TW_CONST.GUEST_ID,    // placeholder will be replaced in backend.
                    authorId = appUser.mid,
                    originalTweetId = tweet.mid,
                    originalAuthorId = tweet.authorId
                )
            ) ?: return

            updateRetweetCount(tweet, retweet.mid)?.let { updatedTweet ->
                // Cache updated original tweet by authorId (matches iOS)
                updateCachedTweet(updatedTweet, userId = updatedTweet.authorId)
            }

            // Cache the retweet by its authorId
            updateCachedTweet(retweet, userId = retweet.authorId)

            // Post notification for retweet
            TweetNotificationCenter.post(TweetEvent.TweetRetweeted(tweet, retweet))
        } catch (e: Exception) {
            Timber.tag("toggleRetweet").e(e.toString())
        }
    }

    /**
     * Load favorite status of the tweet by appUser.
     * */
    suspend fun toggleFavorite(tweet: Tweet): Tweet {
        val entry = "toggle_favorite"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "appuserid" to appUser.mid,
            "tweetid" to tweet.mid,
            "authorid" to tweet.authorId,
            "userhostid" to (appUser.hostIds?.first() ?: "")
        )
        return try {
            val response =
                appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)

            val success = response?.get("success") as? Boolean
            if (success == true) {
                // Handle successful response with updated user and tweet data
                val updatedUserData = response["user"] as? Map<String, Any>
                val updatedTweetData = response["tweet"] as? Map<String, Any>
                
                if (updatedUserData != null) {
                    // Update appUser with new data from server
                    appUser.from(updatedUserData)
                }
                
                if (updatedTweetData != null) {
                    // Create updated tweet from server response
                    val updatedTweet = Tweet.from(updatedTweetData)
                    updatedTweet.author = getUser(updatedTweet.authorId)
                    // Cache by authorId
                    updateCachedTweet(updatedTweet, userId = updatedTweet.authorId)
                    return updatedTweet
                }
            } else {
                // Handle error response
                val error = response?.get("error") as? String
                Timber.tag("toggleFavorite").e("Favorite toggle failed: $error")
            }
            
            // Fallback to original tweet if parsing fails
            tweet
        } catch (e: Exception) {
            Timber.tag("toggleFavorite").e(e)
            tweet
        }
    }

    /**
     * Load bookmark status of the tweet by appUser.
     * */
    suspend fun toggleBookmark(tweet: Tweet): Tweet {
        val entry = "toggle_bookmark"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "userid" to appUser.mid,
            "tweetid" to tweet.mid,
            "authorid" to tweet.authorId,
            "userhostid" to (appUser.hostIds?.first() ?: "")
        )
        return try {
            val response =
                tweet.author?.hproseService?.runMApp<Map<String, Any>>(entry, params)

            val success = response?.get("success") as? Boolean
            if (success == true) {
                // Handle successful response with updated user and tweet data
                val updatedUserData = response["user"] as? Map<String, Any>
                val updatedTweetData = response["tweet"] as? Map<String, Any>
                
                if (updatedUserData != null) {
                    // Update appUser with new data from server
                    appUser.from(updatedUserData)
                }
                
                if (updatedTweetData != null) {
                    // Create updated tweet from server response
                    val updatedTweet = Tweet.from(updatedTweetData)
                    updatedTweet.author = getUser(updatedTweet.authorId)
                    // Cache by authorId
                    updateCachedTweet(updatedTweet, userId = updatedTweet.authorId)
                    return updatedTweet
                }
            } else {
                // Handle error response
                val error = response?.get("error") as? String
                Timber.tag("toggleBookmark").e("Bookmark toggle failed: $error")
            }
            
            // Fallback to original tweet if parsing fails
            tweet
        } catch (e: Exception) {
            Timber.tag("toggleBookmark").e(e)
            tweet
        }
    }

    /**
     * Load favorite tweets, bookmarks or comments of an user.
     * Handles null elements in the response list and preserves their positions.
     * */
    suspend fun getUserTweetsByType(
        user: User,
        type: UserContentType,
        pageNumber: Int = 0,
        pageSize: Int = TW_CONST.PAGE_SIZE
    ): List<Tweet?> {
        val entry = "get_user_meta"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
            "userid" to user.mid,
            "type" to type.value,
            "pn" to pageNumber,
            "ps" to pageSize,
            "appuserid" to appUser.mid
        )
        return try {
            val response = user.hproseService?.runMApp<List<Map<String, Any>?>>(entry, params)

            response?.map { tweetJson ->
                // If the element is null, keep it as null
                if (tweetJson == null) {
                    null
                } else {
                    // Try to decode the tweet
                    try {
                        val tweet = Tweet.from(tweetJson)
                        tweet.author = getUser(tweet.authorId)
                        tweet
                    } catch (e: Exception) {
                        Timber.tag("getUserTweetsByType").e("Error decoding tweet: $e")
                        null
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Timber.tag("getUserTweetsByType").e(e)
            emptyList()
        }
    }

    /**
     * Delete a tweet and return the deleted tweetId. Only appUser can delete its own tweet.
     */
    suspend fun deleteTweet(tweetId: MimeiId): MimeiId? {
        // Check if hproseService is available
        if (appUser.hproseService == null) {
            val errorMsg = "Cannot delete tweet: hproseService is null. User may not be properly initialized."
            Timber.tag("deleteTweet").e(errorMsg)
            throw Exception(errorMsg)
        }

        val entry = "delete_tweet"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "userid" to appUser.mid,
            "tweetid" to tweetId
        )
        return try {
            val response =
                appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)

            if (response == null) {
                val errorMsg = "Delete tweet failed: server returned null response"
                Timber.tag("deleteTweet").e(errorMsg)
                throw Exception(errorMsg)
            }

            if (response.get("success") == true) {
                val deletedTweetId = response["tweetid"] as? MimeiId
                
                if (deletedTweetId == null) {
                    val errorMsg = "Delete tweet failed: server returned success but no tweetid"
                    Timber.tag("deleteTweet").e(errorMsg)
                    throw Exception(errorMsg)
                }
                
                // Refresh appUser from server to get updated tweetCount and other properties
                try {
                    // Invalidate cache to force fresh fetch from server
                    TweetCacheManager.removeCachedUser(appUser.mid)
                    val refreshedUser = getUser(appUser.mid, appUser.baseUrl, maxRetries = 1)
                    if (refreshedUser != null && !refreshedUser.isGuest()) {
                        // Create a new user object to trigger recompose
                        val updatedUser = refreshedUser.copy()
                        appUser = updatedUser
                        TweetCacheManager.saveUser(appUser)
                        
                        // Notify other ViewModels that user data has been updated
                        TweetNotificationCenter.post(TweetEvent.UserDataUpdated(appUser.copy()))
                    }
                } catch (e: Exception) {
                    Timber.tag("deleteTweet").w("Failed to refresh appUser after deletion: $e")
                }
                
                deletedTweetId
            } else {
                val errorMessage =
                    response.get("message") as? String ?: applicationContext.getString(R.string.error_tweet_deletion_unknown)
                Timber.tag("deleteTweet").e("Delete tweet failed: $errorMessage")
                throw Exception(errorMessage)
            }
        } catch (e: Exception) {
            Timber.tag("deleteTweet").e(e, "Error deleting tweet: ${e.message}")
            Timber.tag("deleteTweet").e("Stack trace: ${e.stackTraceToString()}")
            // Re-throw with original message or provide default
            throw Exception(e.message ?: "Unknown error occurred while deleting tweet")
        }
    }

    /**
     * Load all comments of a tweet.
     * @param pageNumber
     * */
    suspend fun getComments(tweet: Tweet, pageNumber: Int = 0, pageSize: Int = 20): List<Tweet>? {
        return try {
            if (tweet.author == null) {
                // Check cache first before fetching from server
                tweet.author = TweetCacheManager.getCachedUser(tweet.authorId) ?: getUser(tweet.authorId)
            }
            val entry = "get_comments"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "tweetid" to tweet.mid,
                "appuserid" to appUser.mid,
                "pn" to pageNumber,
                "ps" to pageSize
            )
            val response =
                tweet.author?.hproseService?.runMApp<List<Map<String, Any>?>>(entry, params)

            response?.mapNotNull { tweetJson -> tweetJson?.let { Tweet.from(it) } }
        } catch (e: Exception) {
            Timber.tag("getComments()").e(e)
            null
        }
    }

    /**
     * The mid of "comment" is updated here, used to be null.
     * @Return the updated parent tweet.
     * */
    suspend fun uploadComment(tweet: Tweet, comment: Tweet): Tweet? {
        val entry = "add_comment"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
            "tweetid" to tweet.mid,
            "comment" to Json.encodeToString(comment),
            "userid" to appUser.mid,
            "hostid" to (tweet.author?.hostIds?.first() ?: "")
        )
        return try {
            val response =
                appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)

            if (response != null && response["success"] == true) {
                // update mid of comment, which was null when passed as argument
                val newCommentId = response["mid"] as? MimeiId ?: comment.mid
                val updatedComment = comment.copy(mid = newCommentId, author = appUser)

                val updatedTweet = tweet.copy(
                    commentCount = (response["count"] as? Number)?.toInt() ?: tweet.commentCount
                )
                // Cache by authorId
                updateCachedTweet(updatedTweet, userId = updatedTweet.authorId)

                // Post notification for successful comment upload
                TweetNotificationCenter.post(
                    TweetEvent.CommentUploaded(
                        updatedComment,
                        updatedTweet
                    )
                )
                updatedTweet
            } else {
                val errorMessage = response?.get("message") as? String ?: applicationContext.getString(R.string.error_unknown)
                Timber.tag("uploadComment").e("Failed to upload comment: $errorMessage")
                null
            }
        } catch (e: Exception) {
            Timber.tag("uploadComment()").e(e)
            null
        }
    }

    fun getMediaUrl(mid: MimeiId?, baseUrl: String?): String? {
        if (mid != null && baseUrl != null) {
            return if (mid.length > 27) {
                "$baseUrl/ipfs/$mid"
            } else {
                "$baseUrl/mm/$mid"
            }
        }
        return null
    }

    // Track ongoing user updates to prevent concurrent calls for the same user
    private val ongoingUserUpdates = mutableSetOf<String>()
    private val userUpdateMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Given userId, get baseUrl where user data can be accessed.
     * An user mimei may be stored on many nodes.
     *
     * Algorithm based on iOS implementation:
     * 1. Check user cache first (if baseUrl matches appUser.baseUrl)
     * 2. If no baseUrl provided, get provider IP for the user
     * 3. Fetch user data from server using "get_user" entry
     * 4. Handle both user data and provider IP responses
     * 5. Update cache with fetched user data
     *
     * Cache expiration: Users are cached for 30 minutes. Expired users are refreshed from backend.
     * Includes retry logic with exponential backoff for network-related failures.
     */
    @Suppress("SENSELESS_COMPARISON")
    suspend fun getUser(userId: MimeiId?, baseUrl: String? = appUser.baseUrl, maxRetries: Int = 3, forceRefresh: Boolean = false): User? {
        if (userId == null) {
            Timber.tag("getUser").w("Null userId, returning null")
            return null
        }

        // Check if user is blacklisted
        if (BlackList.isBlacklisted(userId)) {
            Timber.tag("getUser").d("User $userId is blacklisted, returning null")
            return null
        }

        // Step 1: Check user cache first (if baseUrl matches appUser.baseUrl and not forcing refresh)
        Timber.tag("getUser").d("=== USER FETCH START === userId: $userId, baseUrl: $baseUrl, maxRetries: $maxRetries, forceRefresh: $forceRefresh")
        if (!forceRefresh) {
            val cachedUser = TweetCacheManager.getCachedUser(userId)
            if (cachedUser != null && cachedUser.baseUrl != null) {
                Timber.tag("getUser").d("✅ CACHE HIT: Using cached user for userId: $userId, username: ${cachedUser.username}, hasExpired: ${cachedUser.hasExpired}, baseUrl: ${cachedUser.baseUrl}")
                return cachedUser
            }
        } else {
            Timber.tag("getUser").d("🔄 FORCE REFRESH: Skipping cache for userId: $userId")
        }

        Timber.tag("getUser").d("❌ CACHE MISS OR FORCE REFRESH: userId: $userId")

        // Check if we're already updating this user
        val shouldProceed = userUpdateMutex.withLock {
            if (ongoingUserUpdates.contains(userId)) {
                Timber.tag("getUser").d("🔄 CONCURRENT UPDATE DETECTED: userId: $userId already being updated by another thread")
                false
            } else {
                ongoingUserUpdates.add(userId)
                Timber.tag("getUser").d("🚀 STARTING NEW UPDATE: userId: $userId added to ongoing updates")
                true
            }
        }

        // If another update is in progress, wait for it to complete and return the result
        if (!shouldProceed) {
            Timber.tag("getUser").d("⏳ WAITING FOR CONCURRENT UPDATE: userId: $userId, waiting for completion...")
            
            // Wait for the other thread to complete by polling the ongoingUserUpdates set
            // Add timeout to prevent infinite waiting (max 10 seconds)
            val maxWaitTime = 10000L // 10 seconds
            val startTime = System.currentTimeMillis()
            
            while (true) {
                kotlinx.coroutines.delay(50) // Wait 50ms between checks
                
                // Check if we've been waiting too long
                if (System.currentTimeMillis() - startTime > maxWaitTime) {
                    Timber.tag("updateUserFromServer").w("Timeout waiting for concurrent update to complete for userId: $userId")
                    // Fall back to direct cache lookup
                    return TweetCacheManager.getCachedUser(userId)
                }
                
                // Check if the update is still in progress
                val isStillUpdating = userUpdateMutex.withLock {
                    ongoingUserUpdates.contains(userId)
                }
                
                if (!isStillUpdating) {
                    // The other thread has finished, try to get the cached result
                    Timber.tag("getUser").d("✅ CONCURRENT UPDATE COMPLETED: userId: $userId, checking for cached result")
                    val result = TweetCacheManager.getCachedUser(userId)
                    if (result != null) {
                        Timber.tag("getUser").d("✅ SUCCESS: Retrieved user after waiting for concurrent update - userId: $userId, username: ${result.username}")
                        return result
                    } else {
                        Timber.tag("getUser").w("⚠️ CONCURRENT UPDATE FAILED: userId: $userId completed but no cached user found, retrying entire process")
                        // If no cached result, we need to retry the entire process
                        return getUser(userId, baseUrl, maxRetries, forceRefresh)
                    }
                }
            }
        }

        try {
            // Step 2: Create user instance only when we're actually going to fetch from server
            Timber.tag("getUser").d("🔨 CREATING USER INSTANCE: userId: $userId")
            val user = getUserInstance(userId)

            // Step 3: Determine the base URL to use with retry logic
            val finalBaseUrl = if (baseUrl.isNullOrEmpty()) {
                Timber.tag("getUser").d("🌐 GETTING PROVIDER IP: userId: $userId (no baseUrl provided)")
                // Get provider IP for the user with retry logic
                getProviderIPWithRetry(userId, maxRetries)?.let { "http://$it" } ?: run {
                    Timber.tag("getUser").e("❌ FAILED TO GET PROVIDER IP: userId: $userId")
                    return null
                }
            } else {
                Timber.tag("getUser").d("🌐 USING PROVIDED BASE URL: userId: $userId, baseUrl: $baseUrl")
                baseUrl
            }

            // Step 4: Set the base URL and fetch user data with retry logic
            user.baseUrl = finalBaseUrl
            Timber.tag("getUser").d("🚀 FETCHING USER FROM SERVER: userId: $userId, finalBaseUrl: $finalBaseUrl")
            val fetchSuccess = updateUserFromServerWithRetry(user, maxRetries)  // user object is updated in this function

            // Step 5: Only cache the user if fetch was successful and user data is valid
            if (fetchSuccess && user.mid.isNotEmpty() && user.username != null) {
                Timber.tag("getUser").d("💾 SAVING USER TO CACHE: userId: $userId, username: ${user.username}")
                TweetCacheManager.saveUser(user)
                Timber.tag("getUser").d("✅ USER FETCH COMPLETE: userId: $userId, username: ${user.username}")
                return user
            } else {
                Timber.tag("getUser").w("❌ FAILED TO FETCH VALID USER DATA: userId: $userId, fetchSuccess: $fetchSuccess, mid: ${user.mid}, username: ${user.username}")
                return null
            }
        } finally {
            // Always remove from ongoing updates
            Timber.tag("getUser").d("🧹 CLEANING UP ONGOING UPDATE: userId: $userId")
            userUpdateMutex.withLock {
                ongoingUserUpdates.remove(userId)
            }
        }
    }

    /**
     * Update user data from server using "get_user" entry with retry logic
     * @return true if user data was successfully fetched and updated, false otherwise
     */
    private suspend fun updateUserFromServerWithRetry(user: User, maxRetries: Int = 3): Boolean {
        Timber.tag("updateUserFromServer").d("🔄 STARTING USER UPDATE WITH RETRY: userId: ${user.mid}, maxRetries: $maxRetries")
        val originalBaseUrl = user.baseUrl
        var hasTriedEmptyBaseUrl = false
        
        var attempt = 0
        while (attempt < maxRetries) {
            var shouldRetryImmediately = false
            try {
                val entry = "get_user"
                val params = mapOf(
                    "aid" to appId,
                    "ver" to "last",
                    "userid" to user.mid
                )
                
                Timber.tag("updateUserFromServer").d("📡 ATTEMPT ${attempt + 1}/$maxRetries: Calling get_user for userId: ${user.mid}, baseUrl: ${user.baseUrl}")
                val response = user.hproseService?.runMApp<Any>(entry, params)

                when (response) {
                    is String -> {
                        // User data not found on this node, but IP of a valid provider is returned.
                        Timber.tag("updateUserFromServer").d("🔄 PROVIDER IP RECEIVED: userId: ${user.mid}, providerIP: $response")
                        // Provider IP received, update baseUrl and retry
                        val providerIP = response
                        user.baseUrl = "http://$providerIP"
                        Timber.tag("updateUserFromServer").d("🌐 RETRYING WITH NEW BASE URL: userId: ${user.mid}, newBaseUrl: ${user.baseUrl}")
                        user.hproseService?.runMApp<Map<String, Any>?>(entry, params)?.let { userData ->
                            // Record successful access
                            BlackList.recordSuccess(user.mid)
                            Timber.tag("updateUserFromServer").d("📊 USER DATA RECEIVED: userId: ${user.mid}, dataKeys: ${userData.keys}")
                            user.from(userData)
                            // Validate that user data is not null or empty
                            if (user.mid.isNotEmpty() && user.username != null) {
                                Timber.tag("updateUserFromServer").d("✅ USER UPDATE SUCCESS: userId: ${user.mid}, username: ${user.username}")
                                return true // Success, exit retry loop
                            } else {
                                Timber.tag("updateUserFromServer").w("❌ INVALID USER DATA: userId: ${user.mid}, mid: ${user.mid}, username: ${user.username}")
                            }
                        }
                    }

                    is Map<*, *> -> {
                        // Record successful access
                        BlackList.recordSuccess(user.mid)
                        Timber.tag("updateUserFromServer").d("📊 USER DATA RECEIVED: userId: ${user.mid}, dataKeys: ${response.keys}")
                        user.from(response as Map<String, Any>)
                        // Validate that user data is not null or empty
                        if (user.mid.isNotEmpty() && user.username != null) {
                            Timber.tag("updateUserFromServer").d("✅ USER UPDATE SUCCESS: userId: ${user.mid}, username: ${user.username}")
                            return true // Success, exit retry loop
                        } else {
                            Timber.tag("updateUserFromServer").w("❌ INVALID USER DATA: userId: ${user.mid}, mid: ${user.mid}, username: ${user.username}")
                        }
                    }
                    
                    null -> {
                        Timber.tag("updateUserFromServer").w("❌ NULL RESPONSE: userId: ${user.mid}, attempt: ${attempt + 1}")
                        // On first failure with null response, try with empty baseUrl to refresh IP
                        if (!hasTriedEmptyBaseUrl && !originalBaseUrl.isNullOrEmpty() && attempt < maxRetries - 1) {
                            Timber.tag("updateUserFromServer").d("🔄 NULL RESPONSE - RETRYING WITH EMPTY BASE URL: userId: ${user.mid} to force IP resolution")
                            user.clearHproseService()
                            user.baseUrl = ""
                            val newProviderIP = getProviderIPWithRetry(user.mid, maxRetries = 2)
                            if (newProviderIP != null) {
                                user.baseUrl = "http://$newProviderIP"
                                Timber.tag("updateUserFromServer").d("🌐 RESOLVED NEW BASE URL: userId: ${user.mid}, newBaseUrl: ${user.baseUrl}")
                                hasTriedEmptyBaseUrl = true
                                shouldRetryImmediately = true // Retry immediately with new baseUrl
                            } else {
                                Timber.tag("updateUserFromServer").w("⚠️ FAILED TO RESOLVE PROVIDER IP: userId: ${user.mid}, will continue with original baseUrl")
                                user.baseUrl = originalBaseUrl
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Record failed access
                BlackList.recordFailure(user.mid)
                Timber.tag("updateUserFromServer").e("❌ USER UPDATE FAILED: userId: ${user.mid}, attempt: ${attempt + 1}, error: ${e.message}")
                
                // Check if it's a network-related error that should be retried
                val isNetworkError = ErrorMessageUtils.isNetworkError(e)
                
                if (!isNetworkError) {
                    // Don't retry for non-network errors
                    Timber.tag("updateUserFromServer").e("🚫 NON-NETWORK ERROR: userId: ${user.mid}, not retrying, error: ${e.message}")
                    return false
                } else {
                    Timber.tag("updateUserFromServer").d("🌐 NETWORK ERROR: userId: ${user.mid}, will retry, error: ${e.message}")
                }
                
                // On first failure (network error or null response), try with empty baseUrl to refresh IP
                if (!hasTriedEmptyBaseUrl && !originalBaseUrl.isNullOrEmpty() && attempt < maxRetries - 1) {
                    Timber.tag("updateUserFromServer").d("🔄 FIRST FAILURE - RETRYING WITH EMPTY BASE URL: userId: ${user.mid} to force IP resolution")
                    // Clear the cached service first
                    user.clearHproseService()
                    // Set baseUrl to empty to force IP resolution
                    user.baseUrl = ""
                    // Get a new provider IP to resolve the server address
                    val newProviderIP = getProviderIPWithRetry(user.mid, maxRetries = 2)
                    if (newProviderIP != null) {
                        user.baseUrl = "http://$newProviderIP"
                        Timber.tag("updateUserFromServer").d("🌐 RESOLVED NEW BASE URL: userId: ${user.mid}, newBaseUrl: ${user.baseUrl}")
                        hasTriedEmptyBaseUrl = true
                        shouldRetryImmediately = true // Retry immediately with new baseUrl (don't wait)
                    } else {
                        Timber.tag("updateUserFromServer").w("⚠️ FAILED TO RESOLVE PROVIDER IP: userId: ${user.mid}, will continue with original baseUrl")
                        // Restore original baseUrl if we can't resolve
                        user.baseUrl = originalBaseUrl
                    }
                }
            }
            
            // If we should retry immediately, don't increment attempt and don't wait
            if (shouldRetryImmediately) {
                Timber.tag("updateUserFromServer").d("🔄 RETRYING IMMEDIATELY: userId: ${user.mid} with new baseUrl")
                // Don't increment attempt, just continue the loop
            } else {
                attempt++
                // If this isn't the last attempt, wait before retrying
                if (attempt < maxRetries) {
                    val delayMs = minOf(3000L, 1000L * (1 shl (attempt - 1))) // Exponential backoff: 1s, 2s
                    Timber.tag("updateUserFromServer").d("⏳ RETRYING IN ${delayMs}ms: userId: ${user.mid}, attempt ${attempt + 1}/$maxRetries")
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }
        Timber.tag("updateUserFromServer").e("❌ ALL RETRIES FAILED: userId: ${user.mid}, maxRetries: $maxRetries")
        return false // All retries failed
    }

    /**
     * Get provider IP for a user with retry logic
     */
    private suspend fun getProviderIPWithRetry(userId: MimeiId, maxRetries: Int = 3): String? {
        repeat(maxRetries) { attempt ->
            try {
                val entry = "get_provider_ip"
                val params = mapOf(
                    "aid" to appId,
                    "ver" to "last",
                    "mid" to userId
                )
                val response = appUser.hproseService?.runMApp<String>(entry, params)
                if (response != null) {
                    return response
                }
            } catch (e: Exception) {
                Timber.tag("getProviderIP").e("Error getting provider IP for user: $userId (attempt ${attempt + 1})")
                
                // Check if it's a network-related error that should be retried
                val isNetworkError = ErrorMessageUtils.isNetworkError(e)
                
                if (!isNetworkError) {
                    // Don't retry for non-network errors
                    return null
                }
            }
            
            // If this isn't the last attempt, wait before retrying
            if (attempt < maxRetries - 1) {
                val delayMs = minOf(3000L, 1000L * (1 shl attempt)) // Exponential backoff: 1s, 2s
                Timber.tag("getProviderIP").d("Retrying provider IP fetch in ${delayMs}ms (attempt ${attempt + 2}/$maxRetries)")
                kotlinx.coroutines.delay(delayMs)
            }
        }
        return null
    }

    /**
     * Get provider IP for a user using "get_provider" entry
     */
    suspend fun getProviderIP(mid: MimeiId): String? {
        val entry = "get_provider_ip"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "mid" to mid
        )
        return try {
            appUser.hproseService?.runMApp<String>(entry, params)
        } catch (e: Exception) {
            Timber.tag("getProviderIP").e("Error getting provider IP for user: $mid")
            Timber.tag("getProviderIP").e("Exception: $e")
            null
        }
    }

    /**
     * Return the current tweet list that is pinned to top.
     * */
    suspend fun togglePinnedTweet(tweetId: MimeiId): Boolean? {
        val entry = "toggle_pinned_tweet"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "appuserid" to appUser.mid,
            "tweetid" to tweetId
        )
        return try {
            appUser.hproseService?.runMApp<Boolean>(entry, params)
        } catch (e: Exception) {
            Timber.tag("togglePinnedTweet").e("Error toggling pinned tweet: $tweetId $e")
            null
        }
    }

    /**
     * Return a list of {tweet: Tweet, timestamp: Long} for each pinned Tweet. The timestamp is when
     * the tweet is pinned.
     * */
    suspend fun getPinnedTweetsWithTimestamp(user: User): List<Map<String, Any>>? {
        val entry = "get_pinned_tweets"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "userid" to user.mid,
            "appuserid" to appUser.mid
        )
        return try {
            val response = user.hproseService?.runMApp<List<Map<String, Any>>>(entry, params)
            response
        } catch (e: Exception) {
            Timber.tag("getPinnedList").e("Error getting pinned tweets for user: ${user.mid}")
            Timber.tag("getPinnedList").e("Exception: $e")
            null
        }
    }

    /**
     * Upload media file to node and return its IPFS cid with its media type.
     * Delegates to MediaUploadService for all upload operations.
     * */
    @OptIn(UnstableApi::class)
    suspend fun uploadToIPFS(
        context: Context,
        uri: Uri,
        referenceId: MimeiId? = null
    ): MimeiFileType? {
        return mediaUploadService.uploadToIPFS(uri, referenceId)
    }

    /**
     * Legacy upload method - keeping for backwards compatibility
     * For videos, first tries to upload to net disk URL, then falls back to IPFS method.
     * */
    @OptIn(UnstableApi::class)
    private suspend fun uploadToIPFS_Legacy(
        context: Context,
        uri: Uri,
        referenceId: MimeiId? = null
    ): MimeiFileType? {
        // Get file name
        var fileName: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && cursor.columnCount > 0) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("uploadToIPFS")
                .w("Failed to get file name from content resolver: ${e.message}")
            // Fallback: try to get filename from URI
            fileName = uri.lastPathSegment
        }

        // Get file timestamp
        val fileTimestamp: Long = try {
            val documentFile = DocumentFile.fromSingleUri(context, uri)
            documentFile?.lastModified()?.let {
                if (it == 0L) System.currentTimeMillis() else it
            } ?: System.currentTimeMillis()
        } catch (e: Exception) {
            Timber.tag("uploadToIPFS").w("Failed to get file timestamp: ${e.message}")
            System.currentTimeMillis()
        }

        // Determine MediaType based on MIME type
        val mimeType = try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            Timber.tag("uploadToIPFS").w("Failed to get MIME type: ${e.message}")
            null
        }

        val mediaType = when {
            mimeType?.startsWith("image/") == true -> us.fireshare.tweet.datamodel.MediaType.Image
            mimeType?.startsWith("video/") == true -> us.fireshare.tweet.datamodel.MediaType.Video
            mimeType?.startsWith("audio/") == true -> us.fireshare.tweet.datamodel.MediaType.Audio
            mimeType == "application/pdf" -> us.fireshare.tweet.datamodel.MediaType.PDF
            mimeType == "application/zip" || mimeType == "application/x-zip-compressed" || 
            mimeType == "application/x-rar-compressed" || mimeType == "application/x-7z-compressed" -> us.fireshare.tweet.datamodel.MediaType.Zip
            mimeType == "application/msword" || mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> us.fireshare.tweet.datamodel.MediaType.Word
            mimeType == "application/vnd.ms-excel" || mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> us.fireshare.tweet.datamodel.MediaType.Excel
            mimeType == "application/vnd.ms-powerpoint" || mimeType == "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> us.fireshare.tweet.datamodel.MediaType.PPT
            mimeType?.startsWith("text/plain") == true -> us.fireshare.tweet.datamodel.MediaType.Txt
            mimeType?.startsWith("text/html") == true -> us.fireshare.tweet.datamodel.MediaType.Html
            else -> {
                // Fallback: try to determine type from file extension
                val extension = fileName?.substringAfterLast('.', "")?.lowercase()
                val extensionType = when (extension) {
                    "jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff", "tif" -> us.fireshare.tweet.datamodel.MediaType.Image
                    "mp4", "avi", "mov", "mkv", "webm", "m4v", "3gp" -> us.fireshare.tweet.datamodel.MediaType.Video
                    "mp3", "wav", "aac", "ogg", "flac", "m4a" -> us.fireshare.tweet.datamodel.MediaType.Audio
                    "pdf" -> us.fireshare.tweet.datamodel.MediaType.PDF
                    "zip", "rar", "7z" -> us.fireshare.tweet.datamodel.MediaType.Zip
                    "doc", "docx" -> us.fireshare.tweet.datamodel.MediaType.Word
                    "xls", "xlsx" -> us.fireshare.tweet.datamodel.MediaType.Excel
                    "ppt", "pptx" -> us.fireshare.tweet.datamodel.MediaType.PPT
                    "txt", "rtf", "csv" -> us.fireshare.tweet.datamodel.MediaType.Txt
                    "html", "htm" -> us.fireshare.tweet.datamodel.MediaType.Html
                    else -> us.fireshare.tweet.datamodel.MediaType.Unknown
                }
                
                // If extension detection failed, use FileTypeDetector for magic bytes detection
                if (extensionType == us.fireshare.tweet.datamodel.MediaType.Unknown) {
                    Timber.tag("uploadToIPFS").d("Extension detection failed, using FileTypeDetector for magic bytes detection")
                    FileTypeDetector.detectFileType(context, uri, fileName)
                } else {
                    extensionType
                }
            }
        }

        // For video files, optionally bypass local processing for small files
        if (mediaType == us.fireshare.tweet.datamodel.MediaType.Video || mediaType == us.fireshare.tweet.datamodel.MediaType.HLS_VIDEO) {
            if (mediaType == us.fireshare.tweet.datamodel.MediaType.Video) {
                val fileSize = getFileSize(context, uri)
                if (fileSize != null && fileSize < MediaUploadService.VIDEO_DIRECT_UPLOAD_THRESHOLD_BYTES) {
                    Timber.tag("uploadToIPFS").d(
                        "Video size (%d bytes) below %d threshold, converting to MP4 first",
                        fileSize,
                        MediaUploadService.VIDEO_DIRECT_UPLOAD_THRESHOLD_BYTES
                    )
                    // For videos < 50MB, convert to MP4 first with resolution reduction if needed
                    return normalizeAndUploadVideo(context, uri, fileName, fileTimestamp, referenceId)
                }
            }
            Timber.tag("uploadToIPFS").d("Detected video file, attempting local processing only")
            try {
                val localResult = processVideoLocally(context, uri, fileName, fileTimestamp, referenceId)
                if (localResult != null) {
                    Timber.tag("uploadToIPFS()")
                        .d("Video processed locally successfully: ${localResult.mid}")
                    return localResult
                } else {
                    Timber.tag("uploadToIPFS()")
                        .e("Local video processing failed - no fallback available")
                    return null
                }
            } catch (e: Exception) {
                Timber.tag("uploadToIPFS()")
                    .e("Local video processing failed with exception: ${e.message}")
                return null
            }
        } else {
            Timber.tag("uploadToIPFS").d("Non-video file, proceeding with IPFS upload")
        }

        // Fall back to original IPFS method for non-video files or if netdisk upload fails
        Timber.tag("uploadToIPFS").d("Calling uploadToIPFSOriginal with mediaType: $mediaType")
        val result =
            uploadToIPFSOriginal(context, uri, fileName, fileTimestamp, referenceId, mediaType)
        if (result != null) {
            Timber.tag("uploadToIPFS").d("uploadToIPFSOriginal succeeded: ${result.mid}")
        } else {
            Timber.tag("uploadToIPFS").e("uploadToIPFSOriginal returned null")
        }
        Timber.tag("uploadToIPFS").d("Returning result: ${result?.mid ?: "null"}")
        return result
    }

    /**
     * Check if TUS server is available at tusServerUrl
     */
    private suspend fun isConversionServerAvailable(): Boolean {
        return try {
            Timber.tag("isConversionServerAvailable").d("Checking TUS server availability - cloudDrivePort: ${appUser.cloudDrivePort}, writableUrl: ${appUser.writableUrl}")
            
            // First check if cloudDrivePort is valid (0 means not set)
            if (appUser.cloudDrivePort == 0) {
                Timber.tag("isConversionServerAvailable").d("cloudDrivePort is not set (value: ${appUser.cloudDrivePort})")
                return false
            }
            
            // Ensure writableUrl is resolved
            if (appUser.writableUrl.isNullOrEmpty()) {
                val resolved = appUser.resolveWritableUrl()
                Timber.tag("isConversionServerAvailable").d("Resolved writableUrl: $resolved")
                if (resolved.isNullOrEmpty()) {
                    Timber.tag("isConversionServerAvailable").d("Could not resolve writableUrl")
                    return false
                }
            }
            
            val tusServerUrl = appUser.tusServerUrl
            if (tusServerUrl.isNullOrEmpty()) {
                Timber.tag("isConversionServerAvailable").d("tusServerUrl is not available (cloudDrivePort=${appUser.cloudDrivePort}, writableUrl=${appUser.writableUrl})")
                return false
            }
            
            // Try to ping the /health endpoint
            val healthCheckUrl = "$tusServerUrl/health"
            Timber.tag("isConversionServerAvailable").d("Checking server availability at: $healthCheckUrl")
            
            val response = withContext(Dispatchers.IO) {
                try {
                    httpClient.get(healthCheckUrl)
                } catch (e: Exception) {
                    Timber.tag("isConversionServerAvailable").w("Health check request failed: ${e.message}")
                    return@withContext null
                }
            }
            
            val isAvailable = response?.status == HttpStatusCode.OK
            Timber.tag("isConversionServerAvailable").d("Server available: $isAvailable")
            isAvailable
        } catch (e: Exception) {
            Timber.tag("isConversionServerAvailable").w("Error checking conversion server: ${e.message}")
            false
        }
    }

    /**
     * Process video locally using FFmpeg Kit
     * Strategy:
     * 1. Check if cloudDrivePort is valid and conversion server is available
     * 2. If available: convert to HLS, compress, and upload to /process-zip
     * 3. If not available: normalize to mp4 (resample to 720p if > 720p) and upload to IPFS
     */
    private suspend fun processVideoLocally(
        context: Context,
        uri: Uri,
        fileName: String?,
        fileTimestamp: Long,
        referenceId: MimeiId?
    ): MimeiFileType? {
        return try {
            Timber.tag("processVideoLocally").d("Starting local video processing for: $fileName")
            
            // Check if conversion server is available
            val serverAvailable = isConversionServerAvailable()
            Timber.tag("processVideoLocally").d("Conversion server available: $serverAvailable")
            
            if (serverAvailable) {
                // Use HLS conversion and upload to process-zip endpoint
                Timber.tag("processVideoLocally").d("Using HLS conversion with process-zip endpoint")
                val localProcessingService = LocalVideoProcessingService(context, httpClient, appUser)
                val result = localProcessingService.processVideo(
                    uri = uri,
                    fileName = fileName ?: "video",
                    fileTimestamp = fileTimestamp,
                    referenceId = referenceId
                )
                
                when (result) {
                    is LocalVideoProcessingService.VideoProcessingResult.Success -> {
                        Timber.tag("processVideoLocally").d("Local processing successful: ${result.mimeiFile.mid}")
                        result.mimeiFile
                    }
                    is LocalVideoProcessingService.VideoProcessingResult.Error -> {
                        Timber.tag("processVideoLocally").e("Local processing failed: ${result.message}")
                        null
                    }
                }
            } else {
                // Normalize to mp4 and upload via IPFS
                Timber.tag("processVideoLocally").d("Conversion server not available, normalizing to mp4 and uploading via IPFS")
                return normalizeAndUploadVideo(context, uri, fileName, fileTimestamp, referenceId)
            }
        } catch (e: Exception) {
            Timber.tag("processVideoLocally").e(e, "Error in local video processing")
            null
        }
    }

    /**
     * Normalize video to MP4 format and upload via IPFS
     * - Converts video to MP4 format
     * - If resolution > 720p, reduces to 720p
     * - Otherwise keeps original resolution
     * - Uploads the normalized video
     */
    private suspend fun normalizeAndUploadVideo(
        context: Context,
        uri: Uri,
        fileName: String?,
        fileTimestamp: Long,
        referenceId: MimeiId?
    ): MimeiFileType? {
        return try {
            Timber.tag("normalizeAndUploadVideo").d("Normalizing video to MP4 for IPFS upload")
            
            // Check if video resolution is > 720p
            val videoResolution = VideoManager.getVideoResolution(context, uri)
            val needsResampling = if (videoResolution != null) {
                val (width, height) = videoResolution
                width > 1280 || height > 720
            } else {
                false
            }
            
            if (needsResampling) {
                Timber.tag("normalizeAndUploadVideo").d("Video ${videoResolution?.first}x${videoResolution?.second} will be resampled to 720p")
            } else {
                Timber.tag("normalizeAndUploadVideo").d("Video ${videoResolution?.first}x${videoResolution?.second} will keep original resolution")
            }
            
            // Normalize video to mp4
            val normalizer = us.fireshare.tweet.video.VideoNormalizer(context)
            val normalizedFile = File(context.cacheDir, "normalized_${System.currentTimeMillis()}.mp4")
            
            try {
                val normalizationResult = normalizer.normalizeVideo(uri, normalizedFile, needsResampling)
                
                when (normalizationResult) {
                    is us.fireshare.tweet.video.VideoNormalizer.NormalizationResult.Success -> {
                        Timber.tag("normalizeAndUploadVideo").d("Video normalization successful")
                        
                        // Upload normalized video via IPFS
                        val normalizedUri = android.net.Uri.fromFile(normalizedFile)
                        val result = uploadToIPFSOriginal(
                            context,
                            normalizedUri,
                            fileName,
                            fileTimestamp,
                            referenceId,
                            us.fireshare.tweet.datamodel.MediaType.Video
                        )
                        
                        Timber.tag("normalizeAndUploadVideo").d("IPFS upload result: ${result?.mid}")
                        result
                    }
                    is us.fireshare.tweet.video.VideoNormalizer.NormalizationResult.Error -> {
                        Timber.tag("normalizeAndUploadVideo").e("Video normalization failed: ${normalizationResult.message}")
                        null
                    }
                }
            } finally {
                // Clean up normalized file
                if (normalizedFile.exists()) {
                    normalizedFile.delete()
                }
            }
        } catch (e: Exception) {
            Timber.tag("normalizeAndUploadVideo").e(e, "Error in video normalization and upload")
            null
        }
    }

    /**
     * Poll video conversion status until completion with retry logic for connection issues
     */
    private suspend fun pollVideoConversionStatus(
        context: Context,
        uri: Uri,
        fileName: String?,
        fileTimestamp: Long,
        jobId: String,
        baseUrl: String
    ): MimeiFileType? {
        val statusURL = "$baseUrl/convert-video/status/$jobId"
        var lastProgress = 0
        var lastMessage = "Starting video processing..."
        var consecutiveFailures = 0
        val maxConsecutiveFailures = 10 // Allow more failures for long processing
        val maxPollingTime = 2 * 60 * 60 * 1000L // 2 hours max polling time for very long videos
        val startTime = System.currentTimeMillis()
        
        Timber.tag("pollVideoConversionStatus").d("Starting to poll status for job: $jobId")

        while (true) {
            // Check if we've been polling too long
            if (System.currentTimeMillis() - startTime > maxPollingTime) {
                throw Exception(applicationContext.getString(R.string.error_video_processing_timeout, maxPollingTime / 1000 / 60))
            }

            try {
                val statusResponse = httpClient.get(statusURL)
                
                if (statusResponse.status == HttpStatusCode.NotFound) {
                    // Job ID not found - cancel immediately without retry
                    Timber.tag("pollVideoConversionStatus").e("Job ID not found: $jobId")
                    throw Exception(applicationContext.getString(R.string.error_job_id_not_found, jobId))
                }
                
                if (statusResponse.status != HttpStatusCode.OK) {
                    throw Exception(applicationContext.getString(R.string.error_status_check_failed, statusResponse.status.toString()))
                }

                val statusResponseText = statusResponse.bodyAsText()
                val statusData = Gson().fromJson(statusResponseText, Map::class.java)
                
                val success = statusData?.get("success") as? Boolean
                if (success != true) {
                    val errorMessage = statusData?.get("message") as? String ?: "Status check failed"
                    // Check if the error message indicates job not found
                    if (errorMessage.contains("not found", ignoreCase = true) || 
                        errorMessage.contains("job not found", ignoreCase = true)) {
                        Timber.tag("pollVideoConversionStatus").e("Job ID not found in response: $jobId")
                        throw Exception(applicationContext.getString(R.string.error_job_id_not_found, jobId))
                    }
                    throw Exception(errorMessage)
                }

                val status = statusData["status"] as? String
                val progress = (statusData["progress"] as? Number)?.toInt() ?: 0
                val message = statusData["message"] as? String ?: "Processing..."

                // Reset failure counter on successful request
                consecutiveFailures = 0

                // Log progress updates
                if (progress != lastProgress || message != lastMessage) {
                    Timber.tag("pollVideoConversionStatus").d("Progress: $progress% - $message")
                    lastProgress = progress
                    lastMessage = message
                }

                when (status) {
                    "completed" -> {
                        val cid = statusData["cid"] as? String
                            ?: throw Exception(applicationContext.getString(R.string.error_no_cid_in_response))
                        
                        @OptIn(UnstableApi::class)
                        val aspectRatio = VideoManager.getVideoAspectRatio(context, uri)
                        
                        // Calculate file size from the original URI
                        val fileSize = getFileSize(context, uri) ?: 0L
                        Timber.tag("pollVideoConversionStatus").d("Video file size calculated: $fileSize bytes for URI: $uri")

                        Timber.tag("pollVideoConversionStatus").d("Video conversion completed successfully: $cid")
                        return MimeiFileType(
                            cid,
                            us.fireshare.tweet.datamodel.MediaType.HLS_VIDEO,
                            fileSize,
                            fileName,
                            fileTimestamp,
                            aspectRatio
                        )
                    }
                    "failed" -> {
                        val errorMessage = statusData["message"] as? String ?: applicationContext.getString(R.string.error_video_conversion_failed)
                        throw Exception(errorMessage)
                    }
                    "uploading", "processing" -> {
                        // Continue polling
                        kotlinx.coroutines.delay(3000) // Poll every 3 seconds
                    }
                    else -> {
                        Timber.tag("pollVideoConversionStatus").w("Unknown status: $status")
                        kotlinx.coroutines.delay(3000)
                    }
                }
            } catch (e: Exception) {
                consecutiveFailures++
                Timber.tag("pollVideoConversionStatus").e("Error polling status (attempt $consecutiveFailures/$maxConsecutiveFailures): ${e.message}")
                
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    throw Exception(applicationContext.getString(R.string.error_poll_status_failures, maxConsecutiveFailures, e.message ?: ""))
                }
                
                // Exponential backoff for retries, but cap at reasonable maximum
                val retryDelay = minOf(60000L, 2000L * (1 shl minOf(consecutiveFailures - 1, 5))) // Max 60 seconds
                Timber.tag("pollVideoConversionStatus").d("Retrying in ${retryDelay}ms...")
                kotlinx.coroutines.delay(retryDelay)
            }
        }
    }

    /**
     * Original IPFS upload method as fallback
     */
    @OptIn(UnstableApi::class)
    private suspend fun uploadToIPFSOriginal(
        context: Context,
        uri: Uri,
        fileName: String?,
        fileTimestamp: Long,
        referenceId: MimeiId?,
        mediaType: us.fireshare.tweet.datamodel.MediaType
    ): MimeiFileType? {
        Timber.tag("uploadToIPFSOriginal").d("Starting upload for URI: $uri, mediaType: $mediaType")

        // Resolve writableUrl before using uploadService
        val resolvedUrl = appUser.resolveWritableUrl()
        if (resolvedUrl.isNullOrEmpty()) {
            Timber.tag("uploadToIPFSOriginal").e("Failed to resolve writableUrl")
            return null
        }
        Timber.tag("uploadToIPFSOriginal").d("Successfully resolved writableUrl: $resolvedUrl")

        var offset = 0L
        var byteRead: Int
        val buffer = ByteArray(TW_CONST.CHUNK_SIZE)
        val json = """{"aid": $appId, "ver": "last", "offset": 0}"""
        val request = Gson().fromJson(json, Map::class.java).toMutableMap()

        try {
            Timber.tag("uploadToIPFSOriginal").d("Opening input stream for URI: $uri")
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.use { stream ->
                    Timber.tag("uploadToIPFSOriginal").d("Starting chunked upload")
                    while (stream.read(buffer).also { byteRead = it } != -1) {
                        Timber.tag("uploadToIPFSOriginal")
                            .d("Uploading chunk: offset=$offset, bytes=$byteRead")
                        request["fsid"] = appUser.uploadService?.runMApp(
                            "upload_ipfs",
                            request.toMap(), listOf(buffer)
                        )

                        offset += byteRead
                        request["offset"] = offset
                    }
                }
            }
            // Do not know the tweet mid yet, cannot add reference as 2nd argument.
            // Do it later when uploading tweet.
            request["finished"] = "true"
            referenceId?.let { request["referenceid"] = it }
            Timber.tag("uploadToIPFSOriginal").d("Finalizing upload with offset: $offset")
            Timber.tag("uploadToIPFSOriginal").d("Final request: $request")

            val cid = appUser.uploadService?.runMApp<String?>("upload_ipfs", request.toMap())
                ?: return null

            Timber.tag("uploadToIPFSOriginal").d("Upload successful, CID: $cid")

            // Calculate file size - use the offset which represents total bytes uploaded
            val fileSize = offset
            Timber.tag("uploadToIPFSOriginal").d("File size: $fileSize bytes for URI: $uri")

            // Calculate aspect ratio for image or video
            val aspectRatio = when (mediaType) {
                us.fireshare.tweet.datamodel.MediaType.Image -> {
                    val ratio = getImageAspectRatio(context, uri)
                    Timber.tag("uploadToIPFSOriginal").d("Image aspect ratio: $ratio for URI: $uri")
                    // Fallback to 4:3 if aspect ratio calculation fails
                    ratio ?: (4f / 3f).also { 
                        Timber.tag("uploadToIPFSOriginal").w("Using fallback aspect ratio 4:3 for image URI: $uri")
                    }
                }
                us.fireshare.tweet.datamodel.MediaType.Video -> {
                    val ratio = VideoManager.getVideoAspectRatio(context, uri)
                    Timber.tag("uploadToIPFSOriginal").d("Video aspect ratio: $ratio for URI: $uri")
                    // Fallback to 16:9 if aspect ratio calculation fails
                    ratio
                }
                else -> {
                    Timber.tag("uploadToIPFSOriginal").d("No aspect ratio calculation for media type: $mediaType")
                    null
                }
            }

            Timber.tag("uploadToIPFSOriginal").d("Final MimeiFileType created with file size: $fileSize, aspect ratio: $aspectRatio")
            return MimeiFileType(cid, mediaType, fileSize, fileName, fileTimestamp, aspectRatio)
        } catch (e: Exception) {
            Timber.tag("uploadToIPFSOriginal()").e(e, "Error: ${e.message}")
        }
        return null
    }

    /**
     * Calculate file size from URI - delegates to MediaUploadService
     */
    suspend fun getFileSize(context: Context, uri: Uri): Long? =
        mediaUploadService.getFileSize(uri)

    /**
     * Get image aspect ratio - delegates to MediaUploadService
     */
    suspend fun getImageAspectRatio(context: Context, uri: Uri): Float? =
        mediaUploadService.getImageAspectRatio(uri)

    /**
     * Ktor HTTP client with optimized connection pooling for distributed nodes
     * - Supports high concurrency for multiple node connections
     * - Reuses connections efficiently across requests to same nodes
     * - Proper timeout configuration for upload/download operations
     */
    val httpClient = HttpClient(CIO) {
        engine {
            maxConnectionsCount = 1000 // Total connections across all nodes
            // CIO engine handles connection pooling automatically
            // Connections are reused efficiently per host
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 3_000_000 // Total request timeout (50 minutes for large uploads)
            connectTimeoutMillis = 60_000  // Connection timeout (1 minute)
            socketTimeoutMillis = 300_000  // Socket timeout (5 minutes)
        }
    }
    
    /**
     * Save incomplete upload to SharedPreferences
     */
    fun saveIncompleteUpload(context: Context, upload: IncompleteUpload) {
        val prefs = context.getSharedPreferences("incomplete_uploads", Context.MODE_PRIVATE)
        val uploadJson = Gson().toJson(upload)
        prefs.edit { putString(upload.workId, uploadJson) }
        Timber.tag("HproseInstance").d("Saved incomplete upload: ${upload.workId}")
    }
    
    /**
     * Remove incomplete upload from SharedPreferences
     */
    fun removeIncompleteUpload(context: Context, workId: String) {
        val prefs = context.getSharedPreferences("incomplete_uploads", Context.MODE_PRIVATE)
        prefs.edit { remove(workId) }
        Timber.tag("HproseInstance").d("Removed incomplete upload: $workId")
    }

    /**
     * Get all incomplete uploads from SharedPreferences
     */
    fun getIncompleteUploads(context: Context): List<IncompleteUpload> {
        val prefs = context.getSharedPreferences("incomplete_uploads", Context.MODE_PRIVATE)
        val allEntries = prefs.all
        val uploads = mutableListOf<IncompleteUpload>()
        
        for ((key, value) in allEntries) {
            try {
                val upload = Gson().fromJson(value as String, IncompleteUpload::class.java)
                // Only include uploads from the last 24 hours
                if (System.currentTimeMillis() - upload.timestamp < 24 * 60 * 60 * 1000) {
                    uploads.add(upload)
                } else {
                    // Remove old uploads
                    prefs.edit { remove(key) }
                }
            } catch (e: Exception) {
                Timber.tag("HproseInstance").e("Error parsing incomplete upload: $e")
                // Remove corrupted entries
                prefs.edit {remove(key) }
            }
        }
        
        return uploads
    }
    
    /**
     * Resume incomplete uploads when app comes to foreground
     */
    fun resumeIncompleteUploads(context: Context) {
        val incompleteUploads = getIncompleteUploads(context)
        if (incompleteUploads.isEmpty()) {
            Timber.tag("HproseInstance").d("No incomplete uploads to resume")
            return
        }
        
        Timber.tag("HproseInstance").d("Found ${incompleteUploads.size} incomplete uploads to resume")
        
        for (upload in incompleteUploads) {
            try {
                // Check original WorkManager state for this upload
                try {
                    val uuid = UUID.fromString(upload.workId)
                    val info = WorkManager.getInstance(context).getWorkInfoById(uuid).get()
                    if (info != null) {
                        when (info.state) {
                            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                                Timber.tag("HproseInstance").d("Skipping resume: work ${upload.workId} is ${info.state}")
                                continue
                            }
                            WorkInfo.State.SUCCEEDED -> {
                                Timber.tag("HproseInstance").d("Cleaning up: work ${upload.workId} already SUCCEEDED")
                                removeIncompleteUpload(context, upload.workId)
                                continue
                            }
                            else -> { /* proceed */ }
                        }
                    }
                } catch (_: Exception) { /* ignore invalid UUID or fetch errors */ }
                // Check if this is a video conversion job that needs resumption
                if (upload.videoConversionJobId != null && upload.videoConversionBaseUrl != null) {
                    Timber.tag("HproseInstance").d("Resuming video conversion for job: ${upload.videoConversionJobId}")
                    
                    // Create a coroutine to check job status and resume if needed
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            val videoUri = upload.videoConversionUri?.toUri()
                            val fileName = videoUri?.lastPathSegment
                            val fileTimestamp = System.currentTimeMillis()
                            
                            // Check the backend with jobID to see how video conversion is going
                            val result = pollVideoConversionStatus(
                                context = context,
                                uri = videoUri ?: Uri.EMPTY,
                                fileName = fileName,
                                fileTimestamp = fileTimestamp,
                                jobId = upload.videoConversionJobId,
                                baseUrl = upload.videoConversionBaseUrl
                            )
                            
                            if (result != null) {
                                // Video conversion is finished, upload the tweet and get it done
                                val tweet = Tweet(
                                    mid = System.currentTimeMillis().toString(),
                                    authorId = appUser.mid,
                                    content = upload.tweetContent,
                                    attachments = listOf(result),
                                    isPrivate = upload.isPrivate
                                )
                                
                                HproseInstance.uploadTweet(tweet)?.let { uploadedTweet ->
                                    Timber.tag("HproseInstance").d("Successfully completed resumed video upload: ${uploadedTweet.mid}")
                                    removeIncompleteUpload(context, upload.workId)
                                } ?: run {
                                    Timber.tag("HproseInstance").e("Failed to upload tweet after video conversion completion")
                                }
                            } else {
                                Timber.tag("HproseInstance").e("Video conversion polling failed for job: ${upload.videoConversionJobId}")
                            }
                        } catch (e: Exception) {
                            Timber.tag("HproseInstance").e("Error resuming video conversion: $e")
                            // Remove the problematic incomplete upload to prevent future retries
                            removeIncompleteUpload(context, upload.workId)
                        }
                    }
                    continue
                }
                
                // Handle regular file uploads (non-video uploads)
                // For non-video uploads, we still need to validate URIs since they might be expired
                val validUris = mutableListOf<String>()
                
                for (uriString in upload.attachmentUris) {
                    try {
                        val uri = uriString.toUri()
                        // Test if we can access the URI
                        context.contentResolver.openInputStream(uri)?.use {
                            validUris.add(uriString)
                        }
                    } catch (e: Exception) {
                        Timber.tag("HproseInstance").w("URI no longer accessible: $uriString - ${e.message}")
                    }
                }
                
                // If all URIs are invalid, skip this upload
                if (validUris.isEmpty() && upload.attachmentUris.isNotEmpty()) {
                    Timber.tag("HproseInstance").w("Skipping upload ${upload.workId} - all attachment URIs are no longer accessible")
                    removeIncompleteUpload(context, upload.workId)
                    continue
                }
                
                // Create new WorkManager request to resume the upload
                val data = workDataOf(
                    "tweetContent" to upload.tweetContent,
                    "attachmentUris" to validUris.toTypedArray(),
                    "isPrivate" to upload.isPrivate,
                    "isResume" to true
                )
                
                val uploadRequest = androidx.work.OneTimeWorkRequest.Builder(
                    us.fireshare.tweet.service.UploadTweetWorker::class.java
                )
                    .setInputData(data)
                    .build()
                
                val workManager = androidx.work.WorkManager.getInstance(context)
                workManager.enqueue(uploadRequest)
                
                Timber.tag("HproseInstance").d("Resumed non-video upload for workId: ${upload.workId} with ${validUris.size} valid URIs")
                
            } catch (e: Exception) {
                Timber.tag("HproseInstance").e("Error resuming upload ${upload.workId}: $e")
                // Remove the problematic incomplete upload to prevent future retries
                removeIncompleteUpload(context, upload.workId)
            }
        }
    }
}

