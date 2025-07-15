package us.fireshare.tweet

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import com.google.gson.Gson
import hprose.client.HproseClient
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import timber.log.Timber
import us.fireshare.tweet.datamodel.CachedTweetDao
import us.fireshare.tweet.datamodel.CachedUser
import us.fireshare.tweet.datamodel.ChatDatabase
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetCacheDatabase
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.datamodel.User.Companion.getInstance as getUserInstance
import us.fireshare.tweet.datamodel.UserActions
import us.fireshare.tweet.datamodel.UserContentType
import us.fireshare.tweet.datamodel.HproseService
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import us.fireshare.tweet.widget.Gadget.filterIpAddresses
import us.fireshare.tweet.widget.Gadget.getAccessibleIP
import us.fireshare.tweet.widget.Gadget.getAccessibleIP2
import us.fireshare.tweet.widget.Gadget.getAccessibleUser
import us.fireshare.tweet.widget.SimplifiedVideoCacheManager.getVideoAspectRatio
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Encapsulate Hprose client and related operations in a singleton object.
object HproseInstance {

    private var appId: MimeiId = BuildConfig.APP_ID
    lateinit var preferenceHelper: PreferenceHelper
    var appUser: User = User(mid = TW_CONST.GUEST_ID)
    
    // User cache expiration time (30 minutes in milliseconds)
    const val USER_CACHE_EXPIRATION_TIME = 30 * 60 * 1000L
    
    // in-memory cache of users.
    private var cachedUsers: MutableSet<User> = emptySet<User>().toMutableSet()
    private var userCacheTimestamps: MutableMap<MimeiId, Long> = mutableMapOf()
    private lateinit var chatDatabase: ChatDatabase
    lateinit var dao: CachedTweetDao

    /**
     * Add user to cache with current timestamp
     */
    private fun addUserToCache(user: User) {
        cachedUsers.add(user)
        userCacheTimestamps[user.mid] = System.currentTimeMillis()
        dao.insertOrUpdateCachedUser(CachedUser(user.mid, user))
    }

    /**
     * Remove user from cache
     */
    private fun removeUserFromCache(userId: MimeiId) {
        cachedUsers.removeIf { it.mid == userId }
        userCacheTimestamps.remove(userId)
    }

    /**
     * Clean up expired users from cache
     */
    fun cleanupExpiredUsers() {
        val expiredUserIds = cachedUsers.filter { user ->
            user.hasExpired
        }.map { it.mid }
        
        expiredUserIds.forEach { userId ->
            removeUserFromCache(userId)
            Timber.tag("cleanupExpiredUsers").d("Removed expired user: $userId")
        }
        
        if (expiredUserIds.isNotEmpty()) {
            Timber.tag("cleanupExpiredUsers").d("Cleaned up ${expiredUserIds.size} expired users")
        }
    }

    /**
     * Get user cache statistics
     */
    fun getUserCacheStats(): UserCacheStats {
        val totalUsers = cachedUsers.size
        val expiredUsers = cachedUsers.count { it.hasExpired }
        val validUsers = totalUsers - expiredUsers
        
        return UserCacheStats(
            totalUsers = totalUsers,
            validUsers = validUsers,
            expiredUsers = expiredUsers,
            expirationTimeMs = USER_CACHE_EXPIRATION_TIME
        )
    }

    /**
     * User cache statistics data class
     */
    data class UserCacheStats(
        val totalUsers: Int,
        val validUsers: Int,
        val expiredUsers: Int,
        val expirationTimeMs: Long
    )

    suspend fun init(context: Context) {
        this.preferenceHelper = PreferenceHelper(context)
        chatDatabase = ChatDatabase.getInstance(context)
        val tweetCache = TweetCacheDatabase.getInstance(context)
        dao = tweetCache.tweetDao()
        
        appUser = User(mid = TW_CONST.GUEST_ID,
            baseUrl = preferenceHelper.getAppUrls().first(),
            followingList = getAlphaIds()
        )
        initAppEntry()
    }
    val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000 // Total request timeout (5 minutes)
            connectTimeoutMillis = 60_000  // Connection timeout (1 minute)
            socketTimeoutMillis = 300_000  // Socket timeout (5 minutes)
        }
    }



    /**
     * App_Url is the network entrance of the App. Use it to initiate appId, and BASE_URL.
     * */
    private suspend fun initAppEntry() {
        // make sure no stale data during retry init.
        cachedUsers.clear()
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
                        appId = paramMap["mid"].toString()

                        /**
                         * The code above makes a call to base URL of the app, get a html page
                         * and tries to extract appId and host IP addresses from source code.
                         * */
                        Timber.tag("initAppEntry").d("$paramMap")
                        val hostIPs = filterIpAddresses(paramMap["addrs"] as ArrayList<*>)

                        /**
                         * addrs is an ArrayList of ArrayList of node's IP address pairs.
                         * Each pair is an ArrayList of two elements. The first is the IP address,
                         * and the second is the time spent to get response from the IP.
                         *
                         * hostIPs is a list of node's IP that is a Mimei provider for this App.
                         */
                        val firstIp = getAccessibleIP(hostIPs) ?: getAccessibleIP2(hostIPs)
                        appUser = appUser.copy(baseUrl = "http://$firstIp")
                        val userId = preferenceHelper.getUserId()
                        if (userId != null && userId != TW_CONST.GUEST_ID) {
                            /**
                             * If there is a valid userId in preference, this is a login user.
                             * Initiate current account. Get its IP list and choose the best one,
                             * and assign it to appUser.baseUrl.
                             * */
                            getProviders(userId, "http://$firstIp")?.let { ips ->
                                appUser = getAccessibleUser(ips, userId) ?: appUser
                                addUserToCache(appUser)
                                Timber.tag("initAppEntry").d("User initialized. $appId, $appUser")
                            }
                        } else {
                            appUser.followingList = getAlphaIds()
                            addUserToCache(appUser)
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



    suspend fun sendMessage(receiptId: MimeiId, msg: ChatMessage) {
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
            val response =
                appUser.hproseService?.runMApp<Boolean>(entry, params)

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
                        receipt.hproseService?.runMApp<Any>(receiptEntry, receiptParams)
                    } catch (e: Exception) {
                        Timber.tag("sendMessage").e("Error sending to receipt: $e")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("sendMessage").e(e)
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
            appUser.hproseService?.runMApp<List<Map<String, Any>>>(entry, params)?.mapNotNull { messageData ->
                Gson().fromJson(Gson().toJson(messageData), ChatMessage::class.java)
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
            appUser.hproseService?.runMApp<List<Map<String, Any>>>(entry, params)?.mapNotNull { messageData ->
                Gson().fromJson(Gson().toJson(messageData), ChatMessage::class.java)
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
            val userId = getUserId(username) ?: return Pair(null, context.getString(R.string.login_getuserid_fail))
            val user = getUser(userId) ?: return Pair(null, context.getString(R.string.login_getuser_fail))
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
            val hproseClient = HproseClient.create("$host/webapi/").useService(HproseService::class.java)
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
    suspend fun getHostIP(nodeId: MimeiId): String? {
        val entry = "getvar"
        val params = mapOf(
            "name" to "ips",
            "arg0" to nodeId
        )
        return try {
            val response =
                appUser.hproseService?.runMApp<String>(entry, params)

            response?.trim()?.trim('"')?.trim(',')?.split(',')?.let { ips ->
                if (ips.isNotEmpty()) getAccessibleIP2(ips) else null
            }
        } catch (e: Exception) {
            Timber.tag("getHostIP").e("$e $nodeId")
            null
        }
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
            "entry" to entry,
            "user" to Json.encodeToString(user)
        )
        return try {
            user.hproseService?.runMApp<Map<String, Any>>(entry, params)
        } catch (e: Exception) {
            Timber.tag("setUserData").e(e)
            null
        }
    }

    suspend fun setUserAvatar(user: User, avatar: MimeiId) {
        val entry = "set_user_avatar"
        val json = """
            {"aid": $appId, "ver": "last", "userid": ${user.mid}, "avatar": $avatar}
        """.trimIndent()
        val gson = Gson()
        val request = gson.fromJson(json, Map::class.java)
        try {
            appUser.uploadService?.runMApp<Any>(entry, request)
        } catch (e: Exception) {
            Timber.tag("setUserAvatar").e(e)
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
            val response =
                user.hproseService?.runMApp<List<Map<String, Any>>>(entry, params)

            response?.sortedByDescending { (it["value"] as? Int) ?: 0 }
                ?.mapNotNull { it["field"] as? String }
        } catch (e: Exception) {
            Timber.tag("Hprose.getFans").e(e)
            null
        }
    }

    /**
     * Load tweets of appUser and its followings from network.
     * Simplified version that returns List<Tweet> directly instead of using channelFlow.
     * */
    suspend fun getTweetFeed(
        user: User,
        pageNumber: Int = 0,
        pageSize: Int = 20,
        entry: String = "get_tweet_feed"
    ): List<Tweet> {
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "pn" to pageNumber,
            "ps" to pageSize,
            "userid" to if (!user.isGuest()) user.mid else getAlphaIds().first(),
            "appuserid" to appUser.mid
        )
        
        val response =
            user.hproseService?.runMApp<List<Map<String, Any>?>>(entry, params)

        val tweetList = response?.mapNotNull { tweetJson ->
            tweetJson?.let { Tweet.from(tweetJson) }
        } ?: emptyList()

        return tweetList.mapNotNull { tweet ->
            tweet.author = getUser(tweet.authorId)

            if (tweet.originalTweetId != null) {
                val originalTweet = getTweet(tweet.originalTweetId!!, tweet.originalAuthorId!!)
                    ?: return@mapNotNull null
                tweet.originalTweet = originalTweet
            }

            // Skip private tweets in feed
            if (tweet.isPrivate) {
                return@mapNotNull null
            }

            updateCachedTweet(tweet)
            tweet
        }
    }

    /**
     * Load tweets of a specific user by rank.
     * Simplified version that returns List<Tweet> directly instead of using channelFlow.
     * */
    suspend fun getTweetListByRank(
        user: User,
        pageNumber: Int = 0,
        pageSize: Int = 20,
        entry: String = "get_tweets_by_user"
    ): List<Tweet> {
        try {
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "userid" to user.mid,
                "pn" to pageNumber,
                "ps" to pageSize,
                "appuserid" to appUser.mid
            )
            
            Timber.tag("getTweetListByRank").d("Fetching tweets for user: ${user.mid}, page: $pageNumber, size: $pageSize")
            val response = user.hproseService?.runMApp<List<Map<String, Any>?>>(entry, params)

            val tweetList = response?.mapNotNull { tweetJson ->
                tweetJson?.let { Tweet.from(tweetJson) }
            } ?: emptyList()

            Timber.tag("getTweetListByRank").d("Received ${tweetList.size} tweets for user: ${user.mid}")

            return tweetList.mapNotNull { tweet ->
                tweet.author = user

                if (tweet.originalTweetId != null) {
                    val originalTweet = getTweet(tweet.originalTweetId!!, tweet.originalAuthorId!!)
                        ?: return@mapNotNull null
                    tweet.originalTweet = originalTweet
                }

                updateCachedTweet(tweet)
                tweet
            }
        } catch (e: Exception) {
            Timber.tag("getTweetListByRank").e(e, "Error fetching tweets for user: ${user.mid}")
            throw e
        }
    }

    /**
     * Get cached tweets for a user (for immediate display while loading from network).
     * This can be called before the network request to show cached data immediately.
     * */
    fun getCachedTweetsByUser(
        user: User,
        pageNumber: Int = 0,
        pageSize: Int = 20
    ): List<Tweet> {
        return try {
            dao.getCachedTweetsByUser(user.mid, pageNumber * pageSize, pageSize)
                .map { it.originalTweet }
        } catch (e: Exception) {
            Timber.tag("getCachedTweetsByUser").e("$e")
            emptyList()
        }
    }

    /**
     * Get core data of the tweet. Do Not fetch its original tweet if there is any.
     * Let the caller to decide if go further on the tweet hierarchy.
     * */
    suspend fun getTweet(
        tweetId: MimeiId,
        authorId: MimeiId,
        nodeUrl: String? = null
    ): Tweet? {
        return try {
            // Check cache first using TweetCacheManager
            TweetCacheManager.getCachedTweet(tweetId)?.let { cachedTweet ->
                return if (cachedTweet.isPrivate && cachedTweet.authorId != appUser.mid)
                    null
                else
                    cachedTweet
            }

            val author = getUser(authorId)
            val entry = "get_tweet"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "tweetid" to tweetId,
                "appuserid" to appUser.mid
            )

            author?.hproseService?.runMApp<Map<String, Any>>(entry, params)?.let { tweetData ->
                Tweet.from(tweetData).copy(author = author).apply {
                    TweetCacheManager.saveTweet(this, userId = appUser.mid)
                }
            }
        } catch (e: Exception) {
            Timber.tag("getTweet").e("$tweetId $authorId $e")
            null
        }
    }

    /**
     * Update cached but keep its timestamp when it was cached.
     * */
    fun updateCachedTweet(tweet: Tweet) {
        TweetCacheManager.updateCachedTweet(tweet, appUser.mid)
    }

    /**
     * Get tweet from node Mimei DB to refresh cached tweet.
     * Called when the given tweet is visible.
     * */
    suspend fun refreshTweet(
        tweetId: MimeiId,
        authorId: MimeiId
    ): Tweet? {
        return try {
            val author = getUser(authorId) ?: return null
            val entry = "refresh_tweet"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "entry" to entry,
                "tweetid" to tweetId,
                "appuserid" to appUser.mid,
                "userid" to author.mid,
                "hostid" to (author.hostIds?.first() ?: "")
            )

            author.hproseService?.runMApp<Map<String, Any>>(entry, params)?.let { tweetData ->
                Tweet.from(tweetData).apply {
                    TweetCacheManager.updateCachedTweet(this, appUser.mid)
                }
            }
        } catch (e: Exception) {
            Timber.tag("refreshTweet").e("$tweetId $authorId $e")
            null
        }
    }

    fun loadCachedTweets(
        startRank: Int,  // earlier in time, therefore smaller timestamp
        count: Int,
    ): List<Tweet> {
        try {
            return dao.getCachedTweets(startRank, count).map {
                // cached tweet is full object with original tweet.
                it.originalTweet
            }
        } catch (e: Exception) {
            Timber.tag("loadCachedTweets").e("$e")
        }
        return emptyList()
    }

    /**
     * Retrieve cached tweet from Mimei DB. User info is not cached in Room,
     * which changes frequently, so user data need to be loaded alive every time.
     * */
    private fun loadCachedTweet(tweetId: MimeiId): Tweet? {
        try {
            dao.getCachedTweet(tweetId)?.let {
                val tweet = it.originalTweet
                Timber.tag("loadCachedTweet").d("$tweet")
                return tweet
            }
        } catch (e: Exception) {
            Timber.tag("loadCachedTweet").e("$e")
        }
        return null
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
                    
                    // Post notification for successful upload
                    TweetNotificationCenter.post(TweetEvent.TweetUploaded(updatedTweet))
                    
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
     * @param isFollowing indicates if the appUser is following @param userId. Passing
     * an argument instead of toggling the status of a follower, because toggling
     * following/follower status happens on two different hosts.
     * */
    suspend fun toggleFollower(
        userId: MimeiId,
        isFollowing: Boolean,
        followerId: MimeiId = appUser.mid
    ) {
        try {
            val user = getUser(userId)
            if (user != null) {
                val entry = "toggle_follower"
                val params = mapOf(
                    "aid" to appId,
                    "ver" to "last",
                    "entry" to entry,
                    "otherid" to followerId,
                    "userid" to userId,
                    "isfollower" to isFollowing
                )
                user.hproseService?.runMApp<Any>(entry, params)
            }
        } catch (e: Exception) {
            Timber.tag("toggleFollower()").e(e)
        }
    }

    /**
     * Send a retweet request to backend and get a new tweet object back.
     * */
    suspend fun retweet(
        tweet: Tweet,                       // original tweet to be retweeted
        addTweetToFeed: (Tweet) -> Unit     // add retweet to user's feed
    ) {
        try {
            // upload the retweet, simply a few dozen bytes.
            val retweet = uploadTweet( Tweet(
                mid = TW_CONST.GUEST_ID,    // placeholder will be replaced in backend.
                content = "",
                authorId = appUser.mid,
                originalTweetId = tweet.mid,
                originalAuthorId = tweet.authorId
            )) ?: return

            retweet.originalTweet = tweet
            addTweetToFeed(retweet)

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

            val isFavorite = response?.get("isFavorite") as? Boolean
            val favoriteCount = response?.get("count") as? Int
            if (isFavorite != null && favoriteCount != null) {
                val favorites = tweet.favorites?.toMutableList() ?: mutableListOf(false, false, false)
                favorites[UserActions.FAVORITE] = isFavorite
                val ret = tweet.copy(
                    favorites = favorites,
                    favoriteCount = favoriteCount
                )
                response["user"]?.let { userData ->
                    // Note: updateUser method needs to be implemented in User class
                    // appUser.updateUser(userData)
                }
                updateCachedTweet(tweet)
                
                // Post notification for like toggle
                TweetNotificationCenter.post(TweetEvent.TweetLiked(ret, isFavorite))
                
                ret
            } else tweet
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

            val hasBookmarked = response?.get("hasBookmarked") as? Boolean
            val bookmarkCount = response?.get("count") as? Int
            if (hasBookmarked != null && bookmarkCount != null) {
                val favorites = tweet.favorites?.toMutableList() ?: mutableListOf(false, false, false)
                favorites[UserActions.BOOKMARK] = hasBookmarked
                val ret = tweet.copy(
                    favorites = favorites,
                    bookmarkCount = bookmarkCount
                )
                response["user"]?.let { userData ->
                    // Note: updateUser method needs to be implemented in User class
                    // appUser.updateUser(userData)
                }
                updateCachedTweet(tweet)
                
                // Post notification for bookmark toggle
                TweetNotificationCenter.post(TweetEvent.TweetBookmarked(ret, hasBookmarked))
                
                ret
            } else tweet
        } catch (e: Exception) {
            Timber.tag("toggleBookmark").e(e)
            tweet
        }
    }

    /**
     * Load favorite tweets, bookmarks or comments of an user.
     * */
    suspend fun getUserTweetsByType(
        user: User,
        type: UserContentType
    ): List<Tweet>? {
        val typeString = when (type) {
            UserContentType.FAVORITES -> "favorite_list" // Or whatever your backend expects
            UserContentType.BOOKMARKS -> "bookmark_list"
            UserContentType.COMMENTS -> "comment_list"
        }
        val entry = "get_user_meta"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
            "userid" to user.mid,
            "type" to typeString,
            "appuserid" to appUser.mid
        )
        return try {
            user.hproseService?.runMApp<List<Map<String, Any>>>(entry, params)?.map {
                tweetData -> Tweet.from(tweetData)
            }
        } catch (e: Exception) {
            Timber.tag("getUserTweetsByType").e(e)
            null
        }
    }

    /**
     * Delete a tweet and return the deleted tweetId. Only appUser can delete its own tweet.
     */
    suspend fun deleteTweet(tweetId: String): String? {
        val entry = "delete_tweet"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "authorid" to appUser.mid,
            "tweetid" to tweetId
        )
        return try {
            val response =
                appUser.hproseService?.runMApp<Map<String, Any>>(entry, params)

            if (response?.get("success") == true) {
                val deletedTweetId = response["tweetid"] as? String
                
                // Post notification for successful deletion
                if (deletedTweetId != null) {
                    TweetNotificationCenter.post(TweetEvent.TweetDeleted(deletedTweetId, appUser.mid))
                }
                
                deletedTweetId
            } else {
                val errorMessage = response?.get("message") as? String ?: "Unknown tweet deletion error"
                throw Exception(errorMessage)
            }
        } catch (e: Exception) {
            Timber.tag("deleteTweet").e(e)
            null
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
    suspend fun uploadComment(tweet: Tweet, comment: Tweet): Tweet {
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

            if (response != null) {
                // update mid of comment, which was null when passed as argument
                val newCommentId = response["commentId"] as? MimeiId ?: comment.mid
                val updatedComment = comment.copy(mid = newCommentId)

                val updatedTweet = tweet.copy(
                    commentCount = (response["count"] as? Number)?.toInt() ?: tweet.commentCount
                )
                updateCachedTweet(updatedTweet)
                
                // Post notification for successful comment upload
                TweetNotificationCenter.post(TweetEvent.CommentUploaded(updatedComment, updatedTweet))
                
                updatedTweet
            } else tweet
        } catch (e: Exception) {
            Timber.tag("uploadComment()").e(e)
            tweet
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
    suspend fun getUser(userId: MimeiId, baseUrl: String? = null): User? {
        // Step 1: Check user cache first (if baseUrl matches appUser.baseUrl)
        cachedUsers.firstOrNull { it.mid == userId }?.let { cachedUser ->
            // Check if user is expired
            if (cachedUser.hasExpired) {
                Timber.tag("getUser").d("User $userId is expired, refreshing from backend")
                removeUserFromCache(userId)
            } else {
                Timber.tag("getUser").d("User $userId found in cache (not expired)")
                return cachedUser
            }
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
        updateUserFromServer(user)
        
        // Step 5: Cache the user and return
        addUserToCache(user)
                    return user
    }

    /**
     * Get user cache timestamp
     */
    fun getUserCacheTimestamp(userId: MimeiId): Long {
        return userCacheTimestamps[userId] ?: 0L
    }

    /**
     * Get provider IP for a user using "get_provider" entry
     */
    private suspend fun getProviderIP(userId: MimeiId): String? {
        val entry = "get_provider"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "mid" to userId
        )
        return try {
            appUser.hproseService?.runMApp<String>(entry, params)
        } catch (e: Exception) {
            Timber.tag("getProviderIP").e("$e $userId")
            null
        }
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
                is Map<*, *> -> {
                    // User data received directly
                    val userData = response as Map<String, Any>
                    user.from(userData)
                    TweetCacheManager.saveUser(user)
                }

                is String -> {
                    // User data not found on this node, but IP of a valid provider is returned.
                    // Provider IP received, update baseUrl and retry
                    val providerIP = response
                    user.baseUrl = "http://$providerIP"
                    user.hproseService?.runMApp<Map<String, Any>>(entry, params)?.let { userData ->
                        user.from(userData)
                        TweetCacheManager.saveUser(user)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("updateUserFromServer").e("$e ${user.mid}")
        }
    }

    suspend fun getUserCoreData(mid: MimeiId, ip: String): User? {
        val entry = "get_user_core_data"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
            "userid" to mid
        )
        return try {
            val hproseClient = HproseClient.create("http://$ip/webapi/").useService(HproseService::class.java)
            val response =
                hproseClient.runMApp<Map<String, Any>>(entry, params)

            response?.let {
                getUserInstance(mid).apply {
                    baseUrl = "http://$ip"
                    name = it["name"] as? String
                    username = it["username"] as? String
                    avatar = it["avatar"] as? String
                    email = it["email"] as? String
                    profile = it["profile"] as? String
                    tweetCount = (it["tweetCount"] as? Number)?.toInt() ?: 0
                    followingCount = (it["followingCount"] as? Number)?.toInt() ?: 0
                    followersCount = (it["followersCount"] as? Number)?.toInt() ?: 0
                    bookmarksCount = (it["bookmarksCount"] as? Number)?.toInt() ?: 0
                    favoritesCount = (it["favoritesCount"] as? Number)?.toInt() ?: 0
                    commentsCount = (it["commentsCount"] as? Number)?.toInt() ?: 0
                    hostIds = (it["hostIds"] as? List<*>)?.mapNotNull { id -> id as? String }
                    cloudDrivePort = (it["cloudDrivePort"] as? Number)?.toInt() ?: 8010
                }
            }
        } catch (e: Exception) {
            Timber.tag("getUserCoreData").e(e)
            null
        }
    }

    /**
     * @param ip
     * Check the versions of AppId on the given IP. It shall return a list of versions.
     * */
    suspend fun isAccessible(ip: String): String? {
        return try {
            val entry = "getvar"
            val params = mapOf(
                "name" to "mmversions",
                "arg0" to appId
            )
            val hproseClient = HproseClient.create("http://$ip/webapi/").useService(HproseService::class.java)
            val response =
                hproseClient.runMApp<Array<String>>(entry, params)

            response?.firstOrNull()?.let { ip } // Return IP if found
        } catch (e: Exception) {
            when (e) {
                is ConnectException, is SocketTimeoutException -> {
                    // Ignore these exceptions and continue
                    Timber.tag("isAccessible").w(e, "Timeout for IP: $ip")
                }
//                else -> Timber.tag("isAccessible").e(e, "Error accessing appId for IP: $ip")
            }
            null
        }
    }

    suspend fun getProviders(mid: MimeiId, baseUrl: String? = appUser.baseUrl): List<String>? {
        val entry = "get_providers"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
            "mid" to mid
        )
        return try {
            val hproseClient = HproseClient.create("$baseUrl/webapi/").useService(HproseService::class.java)
            val response =
                hproseClient.runMApp<List<String>>(entry, params)

            response?.toSet()?.toList()
        } catch (e: Exception) {
            Timber.tag("getProviders").e(e)
            null
        }
    }

    /**
     * Return the current tweet list that is pinned to top.
     * */
    suspend fun togglePinnedTweet(tweetId: MimeiId): List<Map<*,*>>? {
        val entry = "toggle_top_tweets"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
            "userid" to appUser.mid,
            "tweetid" to tweetId
        )
        return try {
            appUser.hproseService?.runMApp<List<Map<String, Any>>>(entry, params)
        } catch (e: Exception) {
            Timber.tag("togglePinnedTweet").e(e)
            null
        }
    }

    /**
     * Return a list of {tweetId, timestamp} for each pinned Tweet. The timestamp is when
     * the tweet is pinned.
     * */
    suspend fun getPinnedList(user: User): List<Map<*,*>>? {
        val entry = "get_top_tweets"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
            "userid" to user.mid,
            "gid" to appUser.mid
        )
        return try {
            user.hproseService?.runMApp<List<Map<String, Any>>>(entry, params)
        } catch (e: Exception) {
            Timber.tag("getPinnedList").e(e)
            null
        }
    }

    /**
     * Remove user from cachedUsers list.
     * */
    fun removeCachedUser(userId: MimeiId) {
        removeUserFromCache(userId)
    }

    suspend fun logging(msg: String) {
        if (appUser.isGuest()) return
        val entry = "logging"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
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
     * For videos, first tries to upload to netdisk URL, then falls back to IPFS method.
     * */
    @OptIn(UnstableApi::class)
    suspend fun uploadToIPFS(
        context: Context,
        uri: Uri,
        referenceId: MimeiId? = null
    ): MimeiFileType? {
        // Get file name
        var fileName: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        
        // Get file timestamp
        val documentFile = DocumentFile.fromSingleUri(context, uri)
        val fileTimestamp: Long = documentFile?.lastModified()?.let {
            if (it == 0L) System.currentTimeMillis() else it
        }?: System.currentTimeMillis()

        // Determine MediaType based on MIME type
        val mimeType = context.contentResolver.getType(uri)
        val mediaType = when {
            mimeType?.startsWith("image/") == true -> us.fireshare.tweet.datamodel.MediaType.Image
            mimeType?.startsWith("video/") == true -> us.fireshare.tweet.datamodel.MediaType.Video
            mimeType?.startsWith("audio/") == true -> us.fireshare.tweet.datamodel.MediaType.Audio
            mimeType == "application/pdf" -> us.fireshare.tweet.datamodel.MediaType.PDF
            mimeType == "application/zip" || mimeType == "application/x-zip-compressed" -> us.fireshare.tweet.datamodel.MediaType.Zip
            mimeType == "application/msword" || mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> us.fireshare.tweet.datamodel.MediaType.Word
            // ... add more mappings for other MediaType values ...
            else -> us.fireshare.tweet.datamodel.MediaType.Unknown
        }

        // For video files, try uploading to netdisk first
        if (mediaType == us.fireshare.tweet.datamodel.MediaType.Video) {
            try {
                val netdiskResult = uploadVideoToNetdisk(context, uri, fileName, fileTimestamp, referenceId)
                if (netdiskResult != null) {
                    Timber.tag("uploadToIPFS()").d("Video uploaded to netdisk successfully: ${netdiskResult.mid}")
                    return netdiskResult
                }
            } catch (e: Exception) {
                Timber.tag("uploadToIPFS()").w("Failed to upload video to netdisk, falling back to IPFS: ${e.message}")
            }
        }

        // Fall back to original IPFS method for non-video files or if netdisk upload fails
                    return uploadToIPFSOriginal(context, uri, fileName, fileTimestamp, referenceId, mediaType)
    }

    /**
     * Upload video file to netdisk URL using multipart form data
     */
    private suspend fun uploadVideoToNetdisk(
        context: Context,
        uri: Uri,
        fileName: String?,
        fileTimestamp: Long,
        referenceId: MimeiId?
    ): MimeiFileType? {
        val netdiskUrl = appUser.netdiskUrl ?: throw Exception("Netdisk URL not available")
        val uploadUrl = "$netdiskUrl/upload"
        
        Timber.tag("uploadVideoToNetdisk()").d("Uploading video to: $uploadUrl")
        
        return try {
            val response = httpClient.post(uploadUrl) {
                setBody(
                    io.ktor.client.request.forms.MultiPartFormDataContent(
                        io.ktor.client.request.forms.formData {
                            append("file", context.contentResolver.openInputStream(uri)?.readBytes() ?: ByteArray(0), io.ktor.http.Headers.build {
                                append("Content-Disposition", "filename=\"${fileName ?: "video.mp4"}\"")
                                append("Content-Type", context.contentResolver.getType(uri) ?: "video/mp4")
                            })
                            append("aid", appId)
                            append("ver", "last")
                            referenceId?.let { append("referenceId", it) }
                        }
                    )
                )
            }
            
            if (response.status == HttpStatusCode.OK) {
                val responseText = response.bodyAsText()
                val responseData = Gson().fromJson(responseText, Map::class.java) as? Map<*, *>
                
                val cid = responseData?.get("cid") as? String ?: throw Exception("No CID in response")
                val fileSize = (responseData?.get("size") as? Number)?.toLong() ?: 0L
                
                @OptIn(UnstableApi::class)
                val aspectRatio = getVideoAspectRatio(context, uri)
                
                Timber.tag("uploadVideoToNetdisk()").d("Video uploaded successfully: $cid")
                MimeiFileType(cid, us.fireshare.tweet.datamodel.MediaType.Video, fileSize, fileName, fileTimestamp, aspectRatio)
            } else {
                throw Exception("Upload failed with status: ${response.status}")
            }
        } catch (e: Exception) {
            Timber.tag("uploadVideoToNetdisk()").e("Error uploading to netdisk: ${e.message}")
            throw e
        }
    }

    /**
     * Original IPFS upload method as fallback
     */
    private suspend fun uploadToIPFSOriginal(
        context: Context,
        uri: Uri,
        fileName: String?,
        fileTimestamp: Long,
        referenceId: MimeiId?,
        mediaType: us.fireshare.tweet.datamodel.MediaType
    ): MimeiFileType? {
        var offset = 0L
        var byteRead: Int
        val buffer = ByteArray(TW_CONST.CHUNK_SIZE)
        val json = """{"aid": $appId, "ver": "last", "offset": 0}"""
        val request = Gson().fromJson(json, Map::class.java).toMutableMap()

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.use { stream ->
                    while (stream.read(buffer).also { byteRead = it } != -1) {
                        request["fsid"] = appUser.uploadService?.runMApp<String>(
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
            val cid = appUser.uploadService?.runMApp<String>("upload_ipfs", request.toMap())

            @OptIn(UnstableApi::class)
            val aspectRatio = if (mediaType == us.fireshare.tweet.datamodel.MediaType.Video) {
                getVideoAspectRatio(context, uri)
            } else null
            appUser.uploadService?.runMApp<String>("upload_ipfs", request.toMap())?.let { cid ->
                Timber.tag("uploadToIPFSOriginal()").d("cid=$cid")
                return MimeiFileType(cid, mediaType, offset, fileName, fileTimestamp, aspectRatio)
            }
        } catch (e: Exception) {
            Timber.tag("uploadToIPFSOriginal()").e(e, "Error: ${e.message}")
        }
        return null
    }
}

