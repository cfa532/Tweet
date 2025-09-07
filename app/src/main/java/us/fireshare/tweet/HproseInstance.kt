package us.fireshare.tweet

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequest
import androidx.work.workDataOf
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
import us.fireshare.tweet.widget.Gadget.filterIpAddresses
import us.fireshare.tweet.widget.VideoManager
import java.util.regex.Pattern
import us.fireshare.tweet.datamodel.User.Companion.getInstance as getUserInstance
import androidx.core.content.edit

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
        val timestamp: Long
    )

    suspend fun init(context: Context) {
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
     * */
    suspend fun login(
        username: String,
        password: String,
        context: Context
    ): Pair<User?, String?> {
        return try {
            val userId = getUserId(username) ?: return Pair(
                null,
                context.getString(R.string.login_getuserid_fail)
            )
            val user =
                getUser(userId) ?: return Pair(null, context.getString(R.string.login_getuser_fail))
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
                "success" -> Pair(user, null)
                "failure" -> Pair(null, response["reason"] as? String ?: "Unknown error occurred")
                else -> Pair(null, context.getString(R.string.login_error))
            }
        } catch (e: Exception) {
            Timber.tag("Login").e(e)
            Pair(null, context.getString(R.string.login_error))
        }
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
        val params = mutableMapOf(
            "aid" to appId,
            "ver" to "last",
            "pn" to pageNumber,
            "ps" to pageSize,
            "userid" to if (!user.isGuest()) user.mid else getAlphaIds().first(),
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

    /**
     * Delete a tweet and returned the deleted tweetId
     * */
    suspend fun delTweet(tweetId: MimeiId): MimeiId? {
        val entry = "delete_tweet"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
            "tweetid" to tweetId,
            "authorid" to appUser.mid
        )
        return try {
            val response =
                appUser.hproseService?.runMApp<String>(entry, params)
            if (response != null) {
                // Post notification for successful deletion
                TweetNotificationCenter.post(TweetEvent.TweetDeleted(tweetId, appUser.mid))
            }
            response
        } catch (e: Exception) {
            Timber.tag("delTweet").e(e)
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

                // Post notification for successful deletion
                if (deletedTweetId != null) {
                    TweetNotificationCenter.post(
                        TweetEvent.TweetDeleted(
                            deletedTweetId,
                            appUser.mid
                        )
                    )
                }
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
     */
    suspend fun getUser(userId: MimeiId, baseUrl: String? = appUser.baseUrl): User? {
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

        // Step 3: Determine the base URL to use
        val finalBaseUrl = if (baseUrl.isNullOrEmpty()) {
            // Get provider IP for the user
            val providerIP = getProviderIP(userId) ?: return null
            "http://$providerIP"
        } else {
            baseUrl
        }

        // Step 4: Set the base URL and fetch user data
        user.baseUrl = finalBaseUrl
        updateUserFromServer(user)  // user object is updated in this function

        // Step 5: Cache the user and return
        TweetCacheManager.saveUser(user)
        return user
    }

    /**
     * Update user data from server using "get_user" entry
     */
    private suspend fun updateUserFromServer(user: User) {
        val entry = "get_user"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "userid" to user.mid
        )
        try {
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
                    }
                }

                is Map<*, *> -> {
                    // Record successful access
                    BlackList.recordSuccess(user.mid)
                    user.from(response as Map<String, Any>)
                }
            }
        } catch (e: Exception) {
            // Record failed access
            BlackList.recordFailure(user.mid)
            Timber.tag("updateUserFromServer").e("${e.message} ${user.mid}")
        }
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

        // For video files, try uploading to netdisk first
        if (mediaType == us.fireshare.tweet.datamodel.MediaType.Video || mediaType == us.fireshare.tweet.datamodel.MediaType.HLS_VIDEO) {
            Timber.tag("uploadToIPFS").d("Detected video file, attempting netdisk upload first")
            try {
                val netdiskResult =
                    uploadHLSVideo(context, uri, fileName, fileTimestamp, referenceId, noResample)
                if (netdiskResult != null) {
                    Timber.tag("uploadToIPFS()")
                        .d("Video uploaded to netdisk successfully: ${netdiskResult.mid}")
                    return netdiskResult
                }
            } catch (e: Exception) {
                Timber.tag("uploadToIPFS()")
                    .w("Failed to upload video to netdisk, falling back to IPFS: ${e.message}")
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
     * Poll video conversion status until completion
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
        
        Timber.tag("pollVideoConversionStatus").d("Starting to poll status for job: $jobId")

        while (true) {
            try {
                val statusResponse = httpClient.get(statusURL)
                
                if (statusResponse.status != HttpStatusCode.OK) {
                    throw Exception("Status check failed with status: ${statusResponse.status}")
                }

                val statusResponseText = statusResponse.bodyAsText()
                val statusData = Gson().fromJson(statusResponseText, Map::class.java)
                
                val success = statusData?.get("success") as? Boolean
                if (success != true) {
                    val errorMessage = statusData?.get("message") as? String ?: "Status check failed"
                    throw Exception(errorMessage)
                }

                val status = statusData["status"] as? String
                val progress = (statusData["progress"] as? Number)?.toInt() ?: 0
                val message = statusData["message"] as? String ?: "Processing..."

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
                Timber.tag("pollVideoConversionStatus").e("Error polling status: ${e.message}")
                throw e
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
                us.fireshare.tweet.datamodel.MediaType.Image -> getImageAspectRatio(context, uri)
                us.fireshare.tweet.datamodel.MediaType.Video -> VideoManager.getVideoAspectRatio(
                    context,
                    uri
                )

                else -> null
            }

            return MimeiFileType(cid, mediaType, offset, fileName, fileTimestamp, aspectRatio)
        } catch (e: Exception) {
            Timber.tag("uploadToIPFSOriginal()").e(e, "Error: ${e.message}")
        }
        return null
    }

    suspend fun getImageAspectRatio(context: Context, uri: Uri): Float? =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
                if (options.outWidth > 0 && options.outHeight > 0) {
                    options.outWidth.toFloat() / options.outHeight.toFloat()
                } else null
            } catch (_: Exception) {
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
                // Create new WorkManager request to resume the upload
                val data = workDataOf(
                    "tweetContent" to upload.tweetContent,
                    "attachmentUris" to upload.attachmentUris.toTypedArray(),
                    "isPrivate" to upload.isPrivate,
                    "isResume" to true // Flag to indicate this is a resumed upload
                )
                
                val uploadRequest = androidx.work.OneTimeWorkRequest.Builder(
                    us.fireshare.tweet.service.UploadTweetWorker::class.java
                )
                    .setInputData(data)
                    .build()
                
                val workManager = androidx.work.WorkManager.getInstance(context)
                workManager.enqueue(uploadRequest)
                
                Timber.tag("HproseInstance").d("Resumed upload for workId: ${upload.workId}")
                
            } catch (e: Exception) {
                Timber.tag("HproseInstance").e("Error resuming upload ${upload.workId}: $e")
            }
        }
    }
}

