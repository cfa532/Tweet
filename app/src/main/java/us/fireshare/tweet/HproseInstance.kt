package us.fireshare.tweet

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import com.google.gson.Gson
import hprose.client.HproseClient
import hprose.io.HproseClassManager
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import timber.log.Timber
import us.fireshare.tweet.datamodel.CachedTweetDao
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
import us.fireshare.tweet.widget.Gadget.getAccessibleIP2
import us.fireshare.tweet.widget.SimplifiedVideoCacheManager.getVideoAspectRatio
import java.util.regex.Pattern
import android.graphics.BitmapFactory

// Encapsulate Hprose client and related operations in a singleton object.
object HproseInstance {

    private var _appId: MimeiId = BuildConfig.APP_ID
    val appId: MimeiId get() = _appId
    lateinit var preferenceHelper: PreferenceHelper
    var appUser: User = User(mid = TW_CONST.GUEST_ID)

    private lateinit var chatDatabase: ChatDatabase
    lateinit var dao: CachedTweetDao

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
    fun fetchMessages(senderId: MimeiId): List<ChatMessage>? {
        val entry = "message_fetch"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
            "userid" to appUser.mid,
            "senderid" to senderId
        )

        return try {
            appUser.hproseService?.runMApp<List<Map<String, Any>>>(entry, params)
                ?.mapNotNull { messageData ->
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
    fun checkNewMessages(): List<ChatMessage>? {
        if (appUser.isGuest()) return null
        val entry = "message_check"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "entry" to entry,
            "userid" to appUser.mid
        )
        return try {
            appUser.hproseService?.runMApp<List<Map<String, Any>>>(entry, params)
                ?.mapNotNull { messageData ->
                    Gson().fromJson(Gson().toJson(messageData), ChatMessage::class.java)
                }
        } catch (e: Exception) {
            Timber.tag("checkNewMessages").e(e)
            null
        }
    }

    fun checkUpgrade(): Map<String, String>? {
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

    fun getUserId(username: String): MimeiId? {
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
    fun getHostId(host: String? = appUser.baseUrl): MimeiId? {
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
    suspend fun getHostIP(nodeId: MimeiId): String? {
        val url = "${appUser.baseUrl}/getvar?name=ips&arg0=$nodeId"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                response.bodyAsText().trim().trim('"').trim(',')
                    .split(',').let { ips ->
                        if (ips.isNotEmpty()) {
                            val accessibleIp = getAccessibleIP2(ips)
                            return accessibleIp
                        }
                    }
            }
        } catch (e: Exception) {
            Timber.tag("getHostIP").e("$e $url")
        }
        return null
    }

    /**
     * Register a new user or update an existing user account.
     * */
    fun setUserData(userObj: User): Map<*, *>? {
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

    fun setUserAvatar(user: User, avatar: MimeiId) {
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
    fun getFollowings(user: User): List<MimeiId> {
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
    fun getFans(user: User): List<MimeiId>? {
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
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "pn" to pageNumber,
            "ps" to pageSize,
            "userid" to if (!user.isGuest()) user.mid else getAlphaIds().first(),
            "appuserid" to appUser.mid
        )

        return try {
            val response =
                user.hproseService?.runMApp<List<Map<String, Any>?>>(entry, params)

            response?.map { tweetJson ->
                // If the element is null, keep it as null
                if (tweetJson == null) {
                    null
                } else {
                    // Try to decode the tweet
                    try {
                        val tweet = Tweet.from(tweetJson)
                        tweet.author = getUser(tweet.authorId)

                        // Note: originalTweet is no longer loaded here, it will be loaded on-demand in the UI

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
            val response = user.hproseService?.runMApp<List<Map<String, Any>?>>(entry, params)

            val result = response?.map { tweetJson ->
                // If the element is null, keep it as null
                if (tweetJson == null) {
                    null
                } else {
                    // Try to decode the tweet
                    try {
                        val tweet = Tweet.from(tweetJson)
                        tweet.author = user
                        // Note: originalTweet is no longer loaded here, it will be loaded on-demand in the UI
                        // Do NOT cache tweets from profile screens
                        tweet
                    } catch (e: Exception) {
                        Timber.tag("getTweetsByUser").e("Error decoding tweet: $e")
                        null
                    }
                }
            } ?: emptyList()

            Timber.tag("getTweetsByUser")
                .d("Received ${response?.size ?: 0} tweets (${result.filterNotNull().size} valid) for user: ${user.mid}")

            return result
        } catch (e: Exception) {
            Timber.tag("getTweetsByUser").e(e, "Error fetching tweets for user: ${user.mid}")
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
        return try {
            // Check cache first using TweetCacheManager
            val author = getUser(authorId)
            val cachedTweet = TweetCacheManager.getCachedTweet(tweetId)
            if (cachedTweet != null) {
                return if (cachedTweet.isPrivate && cachedTweet.authorId != appUser.mid)
                    null
                else {
                    cachedTweet.author = author
                    cachedTweet
                }
            }

            val entry = "get_tweet"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "tweetid" to tweetId,
                "appuserid" to appUser.mid
            )

            author?.hproseService?.runMApp<Map<String, Any>>(entry, params)?.let { tweetData ->
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
            Timber.tag("fetchTweet").e("$tweetId $authorId $e")
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
        return try {
            // Check for null parameters
            if (tweetId == null || authorId == null) {
                Timber.tag("refreshTweet")
                    .w("Null parameters: tweetId=$tweetId, authorId=$authorId")
                return null
            }

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
                val tweet = Tweet.from(tweetData)
                tweet.author = author
                tweet
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
                // cached tweet is full object.
                it.originalTweet
            }
        } catch (e: Exception) {
            Timber.tag("loadCachedTweets").e("$e")
        }
        return emptyList()
    }

    /**
     * Increase the retweetCount of the original tweet mimei.
     * @param tweet is the original tweet
     * @param retweetId of the retweet.
     * @param direction to indicate increase or decrease retweet count.
     * @return updated original tweet.
     * */
    fun updateRetweetCount(
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
                        Timber.tag("HproseInstance").d("Posting TweetUploaded notification for original tweet: $newTweetId")
                        TweetNotificationCenter.post(TweetEvent.TweetUploaded(updatedTweet))
                        Timber.tag("HproseInstance").d("TweetUploaded notification posted successfully")
                    } else {
                        Timber.tag("HproseInstance").d("Skipping TweetUploaded notification for retweet: $newTweetId (will be handled by TweetRetweeted)")
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
    fun toggleFollowing(
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
        tweet: Tweet     // original tweet to be retweeted
    ) {
        try {
            // upload the retweet, simply a few dozen bytes.
            val retweet = uploadTweet(
                Tweet(
                    mid = TW_CONST.GUEST_ID,    // placeholder will be replaced in backend.
                    content = "",
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

            val isFavorite = response?.get("isFavorite") as? Boolean
            val favoriteCount = response?.get("count") as? Int
            if (isFavorite != null && favoriteCount != null) {
                val favorites =
                    tweet.favorites?.toMutableList() ?: mutableListOf(false, false, false)
                favorites[UserActions.FAVORITE] = isFavorite
                val ret = tweet.copy(
                    favorites = favorites,
                    favoriteCount = favoriteCount
                )
                updateCachedTweet(tweet)
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
                val favorites =
                    tweet.favorites?.toMutableList() ?: mutableListOf(false, false, false)
                favorites[UserActions.BOOKMARK] = hasBookmarked
                val ret = tweet.copy(
                    favorites = favorites,
                    bookmarkCount = bookmarkCount
                )
                updateCachedTweet(tweet)
                ret
            } else tweet
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
                tweet
            }
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
    suspend fun getUser(userId: MimeiId, baseUrl: String? = appUser.baseUrl): User? {
        // Step 1: Check user cache first (if baseUrl matches appUser.baseUrl)
        val cachedUser = TweetCacheManager.getCachedUser(userId)
        if (cachedUser != null) {
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
                        user.from(userData)
                    }
                }

                is Map<*, *> -> {
                    // User data received directly
                    user.from(response as Map<String, Any>)
                }
            }
        } catch (e: Exception) {
            Timber.tag("updateUserFromServer").e("$e ${user.mid}")
            // it is possible user's node has changed its IP. Try to fetch user data from another node.
            initAppEntry()
        }
    }

    /**
     * Get provider IP for a user using "get_provider" entry
     */
    fun getProviderIP(userId: MimeiId): String? {
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
     * Return the current tweet list that is pinned to top.
     * */
    fun togglePinnedTweet(tweetId: MimeiId): Boolean? {
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
            Timber.tag("togglePinnedTweet").e(e)
            null
        }
    }

    /**
     * Return a list of {tweet: Tweet, timestamp: Long} for each pinned Tweet. The timestamp is when
     * the tweet is pinned.
     * */
    fun getPinnedList(user: User): List<Map<String, Any>>? {
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
            Timber.tag("getPinnedList").e(e)
            null
        }
    }

    fun logging(msg: String) {
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
     * For videos, first tries to upload to netdisk URL, then falls back to IPFS method.
     * */
    @OptIn(UnstableApi::class)
    suspend fun uploadToIPFS(
        context: Context,
        uri: Uri,
        referenceId: MimeiId? = null
    ): MimeiFileType? {
        return uploadToIPFS(context, uri, referenceId, false)
    }

    suspend fun uploadToIPFS(
        context: Context,
        uri: Uri,
        referenceId: MimeiId? = null,
        noResample: Boolean = false
    ): MimeiFileType? {
        Timber.tag("uploadToIPFS").d("Starting upload for URI: $uri")
        Timber.tag("uploadToIPFS").d("Reference ID: $referenceId")
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
            Timber.tag("uploadToIPFS").w("Failed to get file name from content resolver: ${e.message}")
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
                when (extension) {
                    "jpg", "jpeg", "png", "gif", "webp" -> us.fireshare.tweet.datamodel.MediaType.Image
                    "mp4", "avi", "mov", "mkv" -> us.fireshare.tweet.datamodel.MediaType.Video
                    "mp3", "wav", "aac" -> us.fireshare.tweet.datamodel.MediaType.Audio
                    "pdf" -> us.fireshare.tweet.datamodel.MediaType.PDF
                    "zip" -> us.fireshare.tweet.datamodel.MediaType.Zip
                    "doc", "docx" -> us.fireshare.tweet.datamodel.MediaType.Word
                    else -> us.fireshare.tweet.datamodel.MediaType.Unknown
                }
            }
        }

        // For video files, try uploading to netdisk first
        if (mediaType == us.fireshare.tweet.datamodel.MediaType.Video) {
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
        val result = uploadToIPFSOriginal(context, uri, fileName, fileTimestamp, referenceId, mediaType)
        if (result != null) {
            Timber.tag("uploadToIPFS").d("uploadToIPFSOriginal succeeded: ${result.mid}")
        } else {
            Timber.tag("uploadToIPFS").e("uploadToIPFSOriginal returned null")
        }
        Timber.tag("uploadToIPFS").d("Returning result: ${result?.mid ?: "null"}")
        return result
    }

    /**
     * Upload video file to backend for HLS conversion using multipart form data
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
        
        return try {
            val response = httpClient.post(convertVideoURL) {
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

            if (response.status == HttpStatusCode.OK) {
                val responseText = response.bodyAsText()
                val responseData = Gson().fromJson(responseText, Map::class.java)

                val cid = responseData?.get("cid") as? String ?: throw Exception("No CID in response")
                val fileSize = (responseData["size"] as? Number)?.toLong() ?: 0L

                @OptIn(UnstableApi::class)
                val aspectRatio = getVideoAspectRatio(context, uri)

                Timber.tag("uploadVideoToNetDisk").d("Video uploaded successfully: $cid")
                MimeiFileType(
                    cid,
                    us.fireshare.tweet.datamodel.MediaType.Video,
                    fileSize,
                    fileName,
                    fileTimestamp,
                    aspectRatio
                )
            } else {
                throw Exception("Upload failed with status: ${response.status}")
            }
        } catch (e: Exception) {
            Timber.tag("uploadVideoToNetDisk").e("Error uploading to netdisk: ${e.message}")
            throw e
        }
    }
    
    /**
     * Determine video content type based on file extension
     */
    private fun determineVideoContentType(fileName: String?): String {
        return when (fileName?.lowercase()?.substringAfterLast('.', "")) {
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "m4v" -> "video/x-m4v"
            else -> "video/mp4" // Default fallback
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
                        Timber.tag("uploadToIPFSOriginal").d("Uploading chunk: offset=$offset, bytes=$byteRead")
                        request["fsid"] = appUser.uploadService?.runMApp("upload_ipfs",
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
            
            val cid = appUser.uploadService?.runMApp<String?>("upload_ipfs", request.toMap()) ?: return null
            
            Timber.tag("uploadToIPFSOriginal").d("Upload successful, CID: $cid")

            // Calculate aspect ratio for image or video
            val aspectRatio = when (mediaType) {
                us.fireshare.tweet.datamodel.MediaType.Image -> getImageAspectRatio(context, uri)
                us.fireshare.tweet.datamodel.MediaType.Video -> getVideoAspectRatio(context, uri)
                else -> null
            }
            
            return MimeiFileType(cid, mediaType, offset, fileName, fileTimestamp, aspectRatio)
        } catch (e: Exception) {
            Timber.tag("uploadToIPFSOriginal()").e(e, "Error: ${e.message}")
        }
        return null
    }

    fun getImageAspectRatio(context: Context, uri: Uri): Float? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
            if (options.outWidth > 0 && options.outHeight > 0) {
                options.outWidth.toFloat() / options.outHeight.toFloat()
            } else null
        } catch (e: Exception) {
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
}

