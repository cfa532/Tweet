package us.fireshare.tweet

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
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
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetCacheDatabase
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.datamodel.UserContentType
import us.fireshare.tweet.service.FileTypeDetector
import us.fireshare.tweet.video.LocalVideoProcessingService
import us.fireshare.tweet.widget.Gadget.filterIpAddresses
import us.fireshare.tweet.widget.VideoManager
import java.util.UUID
import java.util.regex.Pattern
import us.fireshare.tweet.datamodel.User.Companion.getInstance as getUserInstance

// Encapsulate Hprose client and related operations in a singleton object.
object HproseInstance {
    private var _appId: MimeiId = BuildConfig.APP_ID
    val appId: MimeiId get() = _appId
    lateinit var preferenceHelper: PreferenceHelper
    var appUser: User = User(mid = TW_CONST.GUEST_ID)

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
            HproseClassManager.register(Tweet::class.java, "Tweet")
            HproseClassManager.register(User::class.java, "User")

            this.preferenceHelper = PreferenceHelper(context)
            chatDatabase = ChatDatabase.getInstance(context)
            val tweetCache = TweetCacheDatabase.getInstance(context)
            dao = tweetCache.tweetDao()

            appUser = User(
                mid = TW_CONST.GUEST_ID,
                baseUrl = preferenceHelper.getAppUrls().first(),
                followingList = getAlphaIds()
            )
            initAppEntry()
        } catch (e: Exception) {
            Timber.tag("HproseInstance").e(e, "Error during HproseInstance initialization")
            // Set up minimal fallback state to prevent app from being completely broken
            if (!::preferenceHelper.isInitialized) {
                this.preferenceHelper = PreferenceHelper(context)
            }
            // appUser is already initialized with default value, but ensure it has a valid baseUrl
            if (appUser.baseUrl == null) {
                appUser = User(
                    mid = TW_CONST.GUEST_ID,
                    baseUrl = preferenceHelper.getAppUrls().firstOrNull(),
                    followingList = getAlphaIds()
                )
            }
            // Re-throw the exception so the calling code can handle it
            throw e
        }
    }

    /**
     * App_Url is the network entrance of the App. Use it to initiate appId, and BASE_URL.
     * */
    private suspend fun initAppEntry() {
        // make sure no stale data during retry init.
        for (url in preferenceHelper.getAppUrls()) {
            try {
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
                        _appId = paramMap["mid"].toString()

                        /**
                         * The code above makes a call to base URL of the app, get a html page
                         * and tries to extract appId and host IP addresses from source code.
                         * */
                        Timber.tag("initAppEntry").d("$paramMap")
                        val bestIp = filterIpAddresses(paramMap["addrs"] as List<String>)

                        /**
                         * addrs is an ArrayList of ArrayList of node's IP address pairs.
                         * Each pair is an ArrayList of two elements. The first is the IP address,
                         * and the second is the time spent to get response from the IP.
                         *
                         * bestIp is the IP with the smallest response time from valid public IPs.
                         */
                        appUser = appUser.copy(baseUrl = "http://$bestIp")
                        val userId = preferenceHelper.getUserId()
                        if (userId != null && userId != TW_CONST.GUEST_ID) {
                            /**
                             * If there is a valid userId in preference, this is a login user.
                             * Initiate current account. Get its IP list and choose the best one,
                             * and assign it to appUser.baseUrl.
                             * */
                            getProviderIP(userId)?.let { ip ->
                                TweetCacheManager.removeCachedUser(userId)
                                appUser = getUser(userId, "http://$ip") ?: appUser
                                Timber.tag("initAppEntry").d("User initialized. $appId, $appUser")
                            }
                        } else {
                            appUser.followingList = getAlphaIds()
                            TweetCacheManager.saveUser(appUser)
                            Timber.tag("initAppEntry").d("Guest user initialized. $appId, $appUser")
                        }
                        // once a workable URL is found, break.
                        return
                    }
                } else {
                    Timber.tag("initAppEntry").e("No data found within window.setParam()")
                }
            } catch (e: Exception) {
                Timber.tag("initAppEntry").e(e.toString())
            }
        }
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
                            Pair(false, errorMsg ?: "Unknown error")
                        }
                    } catch (e: Exception) {
                        Timber.tag("sendMessage").e("Error sending to receipt: $e")
                        return Pair(false, e.message ?: "Network error")
                    }
                }
                return Pair(true, null) // No receipt user found, but outgoing was successful
            } else {
                return Pair(false, "Failed to send outgoing message")
            }
        } catch (e: Exception) {
            Timber.tag("sendMessage").e(e)
            return Pair(false, e.message ?: "Network error")
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
                        val errorMsg = response["reason"] as? String ?: "Unknown error occurred"
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
                lastError = context.getString(R.string.login_error)
                Timber.tag("Login").e(e, "Login attempt ${attempt + 1} failed")
                
                // Check if it's a network-related error that should be retried
                val isNetworkError = e.message?.contains("network", ignoreCase = true) == true ||
                        e.message?.contains("timeout", ignoreCase = true) == true ||
                        e.message?.contains("connection", ignoreCase = true) == true ||
                        e.message?.contains("unreachable", ignoreCase = true) == true
                
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
            Timber.tag("getTweetFeed").w("No alpha IDs configured for guest user, returning empty list")
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
                val errorMessage = response?.get("message") as? String ?: "Unknown error occurred"
                Timber.tag("getTweetFeed").e("Tweet feed loading failed: $errorMessage")
                Timber.tag("getTweetFeed").e("Response: $response")

                return emptyList()
            }

            // Extract tweets and originalTweets from the new response format
            val tweetsData = response["tweets"] as? List<Map<String, Any>?>
            val originalTweetsData = response["originalTweets"] as? List<Map<String, Any>?>

            // Cache original tweets first
            originalTweetsData?.forEach { originalTweetJson ->
                if (originalTweetJson != null) {
                    try {
                        val originalTweet = Tweet.from(originalTweetJson)
                        originalTweet.author = getUser(originalTweet.authorId)
                        TweetCacheManager.saveTweet(originalTweet, appUser.mid, shouldCache = true)
                        Timber.tag("getTweetFeed").d("Cached original tweet: ${originalTweet.mid}")
                    } catch (e: Exception) {
                        Timber.tag("getTweetFeed").e("Error caching original tweet: $e")
                    }
                }
            }

            // Process main tweets
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
                            updateCachedTweet(tweet)
                            tweet
                        }
                    } catch (e: Exception) {
                        Timber.tag("getTweetFeed").e("Error decoding tweet: $e")
                        null
                    }
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Timber.tag("getTweetFeed").e("Error fetching tweet feed: $e")
            Timber.tag("getTweetFeed").e("Stack trace: ${e.stackTraceToString()}")

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
                val errorMessage = response?.get("message") as? String ?: "Unknown error occurred"
                Timber.tag("getTweetsByUser")
                    .e("Tweets loading failed for user ${user.mid}: $errorMessage")
                Timber.tag("getTweetsByUser").e("Response: $response")

                return emptyList()
            }

            // Extract tweets and originalTweets from the new response format
            val tweetsData = response["tweets"] as? List<Map<String, Any>?>
            val originalTweetsData = response["originalTweets"] as? List<Map<String, Any>?>

            // Cache original tweets first (same as getTweetFeed)
            originalTweetsData?.forEach { originalTweetJson ->
                if (originalTweetJson != null) {
                    try {
                        val originalTweet = Tweet.from(originalTweetJson)
                        originalTweet.author = getUser(originalTweet.authorId)
                        TweetCacheManager.saveTweet(
                            originalTweet,
                            appUser.mid,
                            shouldCache = false
                        ) // Memory cache only
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
                        // Keep tweets only in memory cache (not database cache)
                        TweetCacheManager.saveTweet(tweet, appUser.mid, shouldCache = false)
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
     * @param shouldCache Whether to cache the tweet (default true for feed, false for profile)
     * */
    suspend fun fetchTweet(
        tweetId: MimeiId,
        authorId: MimeiId,
        shouldCache: Boolean = true
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
                    TweetCacheManager.saveTweet(
                        this,
                        userId = appUser.mid,
                        shouldCache = shouldCache
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
     * @param shouldCache Whether to cache the tweet (default true for feed, false for profile)
     * */
    fun updateCachedTweet(tweet: Tweet, shouldCache: Boolean = true) {
        TweetCacheManager.updateCachedTweet(tweet, appUser.mid, shouldCache = shouldCache)
    }

    /**
     * Get tweet from node Mimei DB to refresh cached tweet.
     * Called when the given tweet is visible.
     * */
    suspend fun refreshTweet(
        tweetId: MimeiId?,
        authorId: MimeiId?
    ): Tweet? {
        // Check for null parameters
        if (tweetId == null || authorId == null) {
            Timber.tag("refreshTweet")
                .w("Null parameters: tweetId=$tweetId, authorId=$authorId")
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

    suspend fun loadCachedTweets(
        startRank: Int,  // earlier in time, therefore smaller timestamp
        count: Int,
    ): List<Tweet> = withContext(Dispatchers.IO) {
        return@withContext try {
            dao.getCachedTweets(startRank, count).map {
                // cached tweet is full object.
                it.originalTweet
            }
        } catch (e: Exception) {
            Timber.tag("loadCachedTweets").e("$e")
            emptyList()
        }
    }

    /**
     * Increase the retweetCount of the original tweet mimei.
     * @param tweet is the original tweet
     * @param retweetId of the retweet.
     * @param direction to indicate increase or decrease retweet count.
     * @return updated original tweet.
     * */
    suspend fun updateRetweetCount(
        tweet: Tweet,
        retweetId: MimeiId,
        direction: Int = 1
    ): Tweet? {
        val entry = if (direction == 1) "retweet_added" else "retweet_removed"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
            "tweetid" to tweet.mid,
            "appuserid" to appUser.mid,
            "retweetid" to retweetId,
            "authorid" to tweet.authorId
        )
        return try {
            tweet.author?.hproseService?.runMApp<Map<String, Any>>(entry, params)?.let {
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
                        Timber.tag("HproseInstance")
                            .d("Posting TweetUploaded notification for original tweet: $newTweetId")
                        TweetNotificationCenter.post(TweetEvent.TweetUploaded(updatedTweet))
                        Timber.tag("HproseInstance")
                            .d("TweetUploaded notification posted successfully")
                    } else {
                        Timber.tag("HproseInstance")
                            .d("Skipping TweetUploaded notification for retweet: $newTweetId (will be handled by TweetRetweeted)")
                    }

                    updatedTweet
                } else null
            } else {
                val errorMessage = response?.get("message") as? String ?: "Unknown upload error"
                Timber.tag("uploadTweet").e("Upload failed: $errorMessage")
                null
            }
        } catch (e: Exception) {
            Timber.tag("uploadTweet").e(e)
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
                updateCachedTweet(updatedTweet)
            }

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
                    updateCachedTweet(updatedTweet)
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
                    updateCachedTweet(updatedTweet)
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

            if (response?.get("success") == true) {
                val deletedTweetId = response["tweetid"] as? MimeiId
                deletedTweetId
            } else {
                val errorMessage =
                    response?.get("message") as? String ?: "Unknown tweet deletion error"
                throw Exception(errorMessage)
            }
        } catch (e: Exception) {
            Timber.tag("deleteTweet").e(e)
            throw Exception(e.message)
        }
    }

    /**
     * Load all comments of a tweet.
     * @param pageNumber
     * */
    suspend fun getComments(tweet: Tweet, pageNumber: Int = 0, pageSize: Int = 20): List<Tweet>? {
        return try {
            if (tweet.author == null) tweet.author = getUser(tweet.authorId)
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
                updateCachedTweet(updatedTweet)

                // Post notification for successful comment upload
                TweetNotificationCenter.post(
                    TweetEvent.CommentUploaded(
                        updatedComment,
                        updatedTweet
                    )
                )
                updatedTweet
            } else {
                val errorMessage = response?.get("message") as? String ?: "Unknown error"
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
    suspend fun getUser(userId: MimeiId, baseUrl: String? = appUser.baseUrl, maxRetries: Int = 3): User? {
        // Check if user is blacklisted
        if (BlackList.isBlacklisted(userId)) {
            Timber.tag("getUser").d("User $userId is blacklisted, returning null")
            return null
        }

        // Step 1: Check user cache first (if baseUrl matches appUser.baseUrl)
        val cachedUser = TweetCacheManager.getCachedUser(userId)
        if (cachedUser != null && cachedUser.baseUrl != null) {
            return cachedUser
        }

        // Step 2: Create user instance, which was either expired or not found in cache.
        val user = getUserInstance(userId)

        // Step 3: Determine the base URL to use with retry logic
        val finalBaseUrl = if (baseUrl.isNullOrEmpty()) {
            // Get provider IP for the user with retry logic
            getProviderIPWithRetry(userId, maxRetries)?.let { "http://$it" } ?: return null
        } else {
            baseUrl
        }

        // Step 4: Set the base URL and fetch user data with retry logic
        user.baseUrl = finalBaseUrl
        val fetchSuccess = updateUserFromServerWithRetry(user, maxRetries)  // user object is updated in this function

        // Step 5: Only cache the user if fetch was successful and user data is valid
        if (fetchSuccess && user.mid.isNotEmpty() && user.username != null) {
            TweetCacheManager.saveUser(user)
            return user
        } else {
            Timber.tag("getUser").w("Failed to fetch valid user data for $userId, not caching")
            return null
        }
    }

    /**
     * Update user data from server using "get_user" entry with retry logic
     * @return true if user data was successfully fetched and updated, false otherwise
     */
    private suspend fun updateUserFromServerWithRetry(user: User, maxRetries: Int = 3): Boolean {
        repeat(maxRetries) { attempt ->
            try {
                val entry = "get_user"
                val params = mapOf(
                    "aid" to appId,
                    "ver" to "last",
                    "userid" to user.mid
                )
                
                val response = user.hproseService?.runMApp<Any>(entry, params)

                when (response) {
                    is String -> {
                        // User data not found on this node, but IP of a valid provider is returned.
                        // Provider IP received, update baseUrl and retry
                        val providerIP = response
                        user.baseUrl = "http://$providerIP"
                        user.hproseService?.runMApp<Map<String, Any>?>(entry, params)?.let { userData ->
                            // Record successful access
                            BlackList.recordSuccess(user.mid)
                            user.from(userData)
                            // Validate that user data is not null or empty
                            if (user.mid.isNotEmpty() && user.username != null) {
                                return true // Success, exit retry loop
                            } else {
                                Timber.tag("updateUserFromServer").w("Invalid user data received for ${user.mid}")
                            }
                        }
                    }

                    is Map<*, *> -> {
                        // Record successful access
                        BlackList.recordSuccess(user.mid)
                        user.from(response as Map<String, Any>)
                        // Validate that user data is not null or empty
                        if (user.mid.isNotEmpty() && user.username != null) {
                            return true // Success, exit retry loop
                        } else {
                            Timber.tag("updateUserFromServer").w("Invalid user data received for ${user.mid}")
                        }
                    }
                    
                    null -> {
                        Timber.tag("updateUserFromServer").w("Null response received for user ${user.mid}")
                    }
                }
            } catch (e: Exception) {
                // Record failed access
                BlackList.recordFailure(user.mid)
                Timber.tag("updateUserFromServer").e("${e.message} ${user.mid} (attempt ${attempt + 1})")
                
                // Check if it's a network-related error that should be retried
                val isNetworkError = e.message?.contains("network", ignoreCase = true) == true ||
                        e.message?.contains("timeout", ignoreCase = true) == true ||
                        e.message?.contains("connection", ignoreCase = true) == true ||
                        e.message?.contains("unreachable", ignoreCase = true) == true
                
                if (!isNetworkError) {
                    // Don't retry for non-network errors
                    return false
                }
            }
            
            // If this isn't the last attempt, wait before retrying
            if (attempt < maxRetries - 1) {
                val delayMs = minOf(3000L, 1000L * (1 shl attempt)) // Exponential backoff: 1s, 2s
                Timber.tag("updateUserFromServer").d("Retrying user fetch in ${delayMs}ms (attempt ${attempt + 2}/$maxRetries)")
                kotlinx.coroutines.delay(delayMs)
            }
        }
        return false // All retries failed
    }

    /**
     * Update user data from server using "get_user" entry (legacy method for backward compatibility)
     */
    private suspend fun updateUserFromServer(user: User) {
        updateUserFromServerWithRetry(user, 1) // Single attempt for legacy calls
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
                val isNetworkError = e.message?.contains("network", ignoreCase = true) == true ||
                        e.message?.contains("timeout", ignoreCase = true) == true ||
                        e.message?.contains("connection", ignoreCase = true) == true ||
                        e.message?.contains("unreachable", ignoreCase = true) == true
                
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
    suspend fun getProviderIP(userId: MimeiId): String? {
        val entry = "get_provider_ip"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "mid" to userId
        )
        return try {
            appUser.hproseService?.runMApp<String>(entry, params)
        } catch (e: Exception) {
            Timber.tag("getProviderIP").e("Error getting provider IP for user: $userId")
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

    suspend fun logging(msg: String) {
        if (appUser.isGuest()) return
        val entry = "logging"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "msg" to msg
        )
        try {
            appUser.hproseService?.runMApp<Any>(entry, params)
        } catch (e: Exception) {
            Timber.tag("logging").e(e)
        }
    }

    /**
     * Upload media file to node and return its IPFS cid with its media type.
     * For videos, first tries to upload to net disk URL, then falls back to IPFS method.
     * */
    @OptIn(UnstableApi::class)
    suspend fun uploadToIPFS(
        context: Context,
        uri: Uri,
        referenceId: MimeiId? = null,
        noResample: Boolean = false
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
            mimeType == "application/zip" || mimeType == "application/x-zip-compressed" -> us.fireshare.tweet.datamodel.MediaType.Zip
            mimeType == "application/msword" || mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> us.fireshare.tweet.datamodel.MediaType.Word
            // ... add more mappings for other MediaType values ...
            else -> {
                // Fallback: try to determine type from file extension
                val extension = fileName?.substringAfterLast('.', "")?.lowercase()
                val extensionType = when (extension) {
                    "jpg", "jpeg", "png", "gif", "webp" -> us.fireshare.tweet.datamodel.MediaType.Image
                    "mp4", "avi", "mov", "mkv" -> us.fireshare.tweet.datamodel.MediaType.Video
                    "mp3", "wav", "aac" -> us.fireshare.tweet.datamodel.MediaType.Audio
                    "pdf" -> us.fireshare.tweet.datamodel.MediaType.PDF
                    "zip" -> us.fireshare.tweet.datamodel.MediaType.Zip
                    "doc", "docx" -> us.fireshare.tweet.datamodel.MediaType.Word
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

        // For video files, use local processing only (no fallback for testing)
        if (mediaType == us.fireshare.tweet.datamodel.MediaType.Video || mediaType == us.fireshare.tweet.datamodel.MediaType.HLS_VIDEO) {
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
     * Process video locally using FFmpeg Kit: convert to HLS, compress, and upload to /process-zip
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
        } catch (e: Exception) {
            Timber.tag("processVideoLocally").e(e, "Error in local video processing")
            null
        }
    }

    /**
     * Upload video file to backend for HLS conversion using multipart form data with progress polling
     */
    private suspend fun uploadHLSVideo(
        context: Context,
        uri: Uri,
        fileName: String?,
        fileTimestamp: Long,
        referenceId: MimeiId?,
        noResample: Boolean = false
    ): MimeiFileType? {
        Timber.tag("uploadHLSVideo").d("Uploading original video to backend for HLS conversion")

        // Get the user's cloudDrivePort with fallback to default
        val cloudDrivePort = appUser.cloudDrivePort.takeIf { it > 0 } ?: 8010

        // Ensure writableUrl is available
        var writableUrl = appUser.writableUrl
        if (writableUrl.isNullOrEmpty()) {
            writableUrl = appUser.resolveWritableUrl()
        }

        if (writableUrl.isNullOrEmpty()) {
            Timber.tag("uploadHLSVideo").e("Writable URL not available")
            return null
        }

        // Construct convert-video endpoint URL
        val scheme = if (writableUrl.startsWith("https")) "https" else "http"
        val host = writableUrl.replace(Regex("^https?://"), "").split("/").firstOrNull()?.split(":")
            ?.firstOrNull()
            ?: writableUrl
        val convertVideoURL = "$scheme://$host:$cloudDrivePort/convert-video"

        Timber.tag("uploadHLSVideo").d("Convert-video URL: $convertVideoURL")

        // Read the video data once
        val videoData = context.contentResolver.openInputStream(uri)?.readBytes() ?: ByteArray(0)
        Timber.tag("uploadHLSVideo").d("Data size: ${videoData.size} bytes")

        // Determine content type based on file extension
        val contentType = determineVideoContentType(fileName)
        Timber.tag("uploadHLSVideo").d("Determined content type: $contentType")
        Timber.tag("uploadHLSVideo").d("Filename: $fileName")
        Timber.tag("uploadHLSVideo").d("ReferenceId: $referenceId")
        Timber.tag("uploadHLSVideo").d("NoResample: $noResample")

        try {
            // Step 1: Upload video and get job ID
            val uploadResponse = httpClient.post(convertVideoURL) {
                setBody(
                    io.ktor.client.request.forms.MultiPartFormDataContent(
                        io.ktor.client.request.forms.formData {
                            // Add filename if provided (server expects this field)
                            fileName?.let {
                                append("filename", it)
                            }

                            // Add reference ID if provided (server expects this field)
                            referenceId?.let {
                                append("referenceId", it)
                            }

                            // Add noResample parameter - use the value passed from compose view
                            append("noResample", noResample.toString())

                            // Add the video file (server expects field name "videoFile")
                            append(
                                "videoFile",
                                videoData,
                                io.ktor.http.Headers.build {
                                    append(
                                        "Content-Disposition",
                                        "filename=\"${fileName ?: "video.mp4"}\""
                                    )
                                    append("Content-Type", contentType)
                                }
                            )
                        }
                    )
                )
            }

            if (uploadResponse.status != HttpStatusCode.OK) {
                throw Exception("Upload failed with status: ${uploadResponse.status}")
            }

            val uploadResponseText = uploadResponse.bodyAsText()
            val uploadResponseData = Gson().fromJson(uploadResponseText, Map::class.java)
            
            val success = uploadResponseData?.get("success") as? Boolean
            if (success != true) {
                val errorMessage = uploadResponseData?.get("message") as? String ?: "Upload failed"
                throw Exception(errorMessage)
            }

            val jobId = uploadResponseData["jobId"] as? String
                ?: throw Exception("No job ID in response")
            
            Timber.tag("uploadHLSVideo").d("Upload started, job ID: $jobId")

            // Update incomplete upload with video conversion job information
            // This allows resuming video conversion polling if the app is backgrounded
            updateIncompleteUploadWithVideoJob(context, jobId, "$scheme://$host:$cloudDrivePort", uri.toString())

            // Step 2: Poll for progress and completion
            return pollVideoConversionStatus(
                context = context,
                uri = uri,
                fileName = fileName,
                fileTimestamp = fileTimestamp,
                jobId = jobId,
                baseUrl = "$scheme://$host:$cloudDrivePort"
            )

        } catch (e: Exception) {
            Timber.tag("uploadHLSVideo").e("Error uploading to netdisk: ${e.message}")
            throw e
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
                throw Exception("Video processing timeout after ${maxPollingTime / 1000 / 60} minutes")
            }

            try {
                val statusResponse = httpClient.get(statusURL)
                
                if (statusResponse.status == HttpStatusCode.NotFound) {
                    // Job ID not found - cancel immediately without retry
                    Timber.tag("pollVideoConversionStatus").e("Job ID not found: $jobId")
                    throw Exception("Job ID not found: $jobId")
                }
                
                if (statusResponse.status != HttpStatusCode.OK) {
                    throw Exception("Status check failed with status: ${statusResponse.status}")
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
                        throw Exception("Job ID not found: $jobId")
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
                            ?: throw Exception("No CID in completion response")
                        
                        @OptIn(UnstableApi::class)
                        val aspectRatio = VideoManager.getVideoAspectRatio(context, uri)

                        Timber.tag("pollVideoConversionStatus").d("Video conversion completed successfully: $cid")
                        return MimeiFileType(
                            cid,
                            us.fireshare.tweet.datamodel.MediaType.HLS_VIDEO,
                            0L, // File size not provided in new response format
                            fileName,
                            fileTimestamp,
                            aspectRatio
                        )
                    }
                    "failed" -> {
                        val errorMessage = statusData["message"] as? String ?: "Video conversion failed"
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
                    throw Exception("Failed to poll status after $maxConsecutiveFailures consecutive failures: ${e.message}")
                }
                
                // Exponential backoff for retries, but cap at reasonable maximum
                val retryDelay = minOf(60000L, 2000L * (1 shl minOf(consecutiveFailures - 1, 5))) // Max 60 seconds
                Timber.tag("pollVideoConversionStatus").d("Retrying in ${retryDelay}ms...")
                kotlinx.coroutines.delay(retryDelay)
            }
        }
    }

    /**
     * Determine video content type based on file extension and magic bytes
     */
    private fun determineVideoContentType(fileName: String?): String {
        // First try extension-based detection
        val extensionType = when (fileName?.lowercase()?.substringAfterLast('.', "")) {
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "m4v" -> "video/x-m4v"
            else -> null
        }
        return extensionType ?: "video/mp4" // Default fallback
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
                    ratio ?: (16f / 9f).also { 
                        Timber.tag("uploadToIPFSOriginal").w("Using fallback aspect ratio 16:9 for video URI: $uri")
                    }
                }
                else -> {
                    Timber.tag("uploadToIPFSOriginal").d("No aspect ratio calculation for media type: $mediaType")
                    null
                }
            }

            Timber.tag("uploadToIPFSOriginal").d("Final MimeiFileType created with aspect ratio: $aspectRatio")
            return MimeiFileType(cid, mediaType, offset, fileName, fileTimestamp, aspectRatio)
        } catch (e: Exception) {
            Timber.tag("uploadToIPFSOriginal()").e(e, "Error: ${e.message}")
        }
        return null
    }

    suspend fun getImageAspectRatio(context: Context, uri: Uri): Float? =
        withContext(Dispatchers.IO) {
            return@withContext try {
                // Try multiple methods to get image dimensions with EXIF orientation consideration
                var width = 0
                var height = 0
                var orientation = ExifInterface.ORIENTATION_NORMAL
                
                // Method 1: BitmapFactory with inJustDecodeBounds
                try {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, options)
                    }
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        width = options.outWidth
                        height = options.outHeight
                    }
                } catch (e: Exception) {
                    Timber.tag("getImageAspectRatio").w("BitmapFactory method failed: ${e.message}")
                }
                
                // Method 2: Get EXIF data including orientation
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val exif = ExifInterface(input)
                        
                        // Get dimensions from EXIF if BitmapFactory failed
                        if (width == 0 || height == 0) {
                            width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                            height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                        }
                        
                        // Get orientation for proper aspect ratio calculation
                        orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    }
                } catch (e: Exception) {
                    Timber.tag("getImageAspectRatio").w("ExifInterface method failed: ${e.message}")
                }
                
                if (width > 0 && height > 0) {
                    // Calculate aspect ratio considering EXIF orientation
                    val aspectRatio = when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90,
                        ExifInterface.ORIENTATION_ROTATE_270,
                        ExifInterface.ORIENTATION_TRANSPOSE,
                        ExifInterface.ORIENTATION_TRANSVERSE -> {
                            // For 90/270 degree rotations, swap width and height for correct aspect ratio
                            height.toFloat() / width.toFloat()
                        }
                        else -> {
                            // For normal orientation, use width/height
                            width.toFloat() / height.toFloat()
                        }
                    }
                    
                    Timber.tag("getImageAspectRatio").d("Image aspect ratio calculated: $aspectRatio (${width}x${height}, orientation: $orientation) for URI: $uri")
                    aspectRatio
                } else {
                    Timber.tag("getImageAspectRatio").w("Could not determine image dimensions for URI: $uri")
                    null
                }
            } catch (e: Exception) {
                Timber.tag("getImageAspectRatio").e(e, "Error calculating image aspect ratio for URI: $uri")
                null
            }
        }


    val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 3_000_000 // Total request timeout (5 minutes)
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
     * Update incomplete upload with video conversion job information
     */
    private fun updateIncompleteUploadWithVideoJob(context: Context, jobId: String, baseUrl: String, videoUri: String) {
        val prefs = context.getSharedPreferences("incomplete_uploads", Context.MODE_PRIVATE)
        val allEntries = prefs.all
        
        // Find the most recent incomplete upload (likely the current one)
        var mostRecentUpload: IncompleteUpload? = null
        var mostRecentKey: String? = null
        var mostRecentTime = 0L
        
        for ((key, value) in allEntries) {
            try {
                val upload = Gson().fromJson(value as String, IncompleteUpload::class.java)
                if (upload.timestamp > mostRecentTime) {
                    mostRecentTime = upload.timestamp
                    mostRecentUpload = upload
                    mostRecentKey = key
                }
            } catch (e: Exception) {
                Timber.tag("HproseInstance").e("Error parsing incomplete upload: $e")
            }
        }
        
        // Update the most recent upload with video job information
        mostRecentUpload?.let { upload ->
            val updatedUpload = upload.copy(
                videoConversionJobId = jobId,
                videoConversionBaseUrl = baseUrl,
                videoConversionUri = videoUri
            )
            val uploadJson = Gson().toJson(updatedUpload)
            prefs.edit { putString(mostRecentKey, uploadJson) }
            Timber.tag("HproseInstance").d("Updated incomplete upload with video job info: $jobId")
        }
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
        
        // TEMPORARILY CLEAR ALL INCOMPLETE UPLOADS FOR TESTING
        // This prevents old failed uploads from interfering with new video processing
        Timber.tag("HproseInstance").d("Clearing all incomplete uploads for clean testing")
        val prefs = context.getSharedPreferences("incomplete_uploads", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        return
        
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
                            val videoUri = upload.videoConversionUri?.let { Uri.parse(it) }
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

