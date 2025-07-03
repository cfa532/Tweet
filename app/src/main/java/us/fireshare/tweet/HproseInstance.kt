package us.fireshare.tweet

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import hprose.client.HproseClient
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import us.fireshare.tweet.datamodel.CachedTweet
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
import us.fireshare.tweet.widget.Gadget.filterIpAddresses
import us.fireshare.tweet.widget.Gadget.getAccessibleIP
import us.fireshare.tweet.widget.Gadget.getAccessibleIP2
import us.fireshare.tweet.widget.Gadget.getAccessibleUser
import us.fireshare.tweet.widget.VideoCacheManager.getVideoAspectRatio
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.Date
import java.util.regex.Pattern

// Encapsulate Hprose client and related operations in a singleton object.
object HproseInstance {

    private var appId: MimeiId = BuildConfig.APP_ID
    lateinit var preferenceHelper: PreferenceHelper
    var appUser: User = User(mid = TW_CONST.GUEST_ID)
    
    // in-memory cache of users.
    private var cachedUsers: MutableSet<User> = emptySet<User>().toMutableSet()
    private lateinit var chatDatabase: ChatDatabase
    lateinit var dao: CachedTweetDao

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
            requestTimeoutMillis = 60_000 // Total request timeout
            connectTimeoutMillis = 30_000  // Connection timeout
            socketTimeoutMillis = 60_000  // Socket timeout
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
                                cachedUsers.add(appUser)
                                Timber.tag("initAppEntry").d("User initialized. $appId, $appUser")
                            }
                        } else {
                            appUser.followingList = getAlphaIds()
                            cachedUsers.add(appUser)
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

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var retryCount = 0
        while (retryCount < 2) { // Retry up to 2 times
            try {
                return block() // Return the result of the block
            } catch (e: IOException) { // Catch only IOException (network exception)
                Timber.tag("HproseInstance").e("IOException: ${e.message}")
                retryCount++
                initAppEntry()
            } catch (e: ProtocolException) { // Catch only ProtocolException (network exception)
                Timber.tag("HproseInstance").e("ProtocolException: ${e.message}")
                retryCount++
                initAppEntry()
            }
        }
        // If all retries fail, handle the error (e.g., throw an exception or return a default value)
        Timber.tag("HproseInstance").e("Network error: All retries failed.")
        throw Exception("Network error: All retries failed.") // Or return a default value
    }

    private suspend fun <T> (() -> T).withRetry(): T {
        var retryCount = 0
        while (retryCount < 2) {
            try {
                return this.invoke() // Invoke the original function
            } catch (e: IOException) { // Catch only IOException (network exception)
                Timber.tag("HproseInstance").e("Network error: ${e.message}")
                retryCount++
                initAppEntry()
            } catch (e: ProtocolException) { // Catch only ProtocolException (network exception)
                Timber.tag("HproseInstance").e("Network error: ${e.message}")
                retryCount++
                initAppEntry()
            }
        }
        Timber.tag("HproseInstance").e("Network error: All retries failed.")
        throw Exception("Network error: All retries failed.")
    }

    suspend fun sendMessage(receiptId: MimeiId, msg: ChatMessage) { return withRetry {
        var entry = "message_outgoing"
        val encodedMsg = URLEncoder.encode(Json.encodeToString(msg), "utf-8")
        var url =
            "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$entry&userid=${appUser.mid}" +
                    "&receiptid=$receiptId&msg=$encodedMsg&hostid=${appUser.hostIds?.first()}"
        // write outgoing message to user's Mimei db
        try {
            val response: HttpResponse = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                // write message to receipt's Mimei db on the receipt's node
                val receipt = getUser(receiptId)
                entry = "message_incoming"
                url = "${receipt?.baseUrl}/entry?aid=$appId&ver=last&entry=$entry" +
                            "&senderid=${appUser.mid}&receiptid=$receiptId&msg=${
                                URLEncoder.encode(Json.encodeToString(msg), "utf-8")
                            }"
                httpClient.get(url)
            }
        } catch (e: Exception) {
            Timber.tag("sendMessage").e(e.toString())
        }
    } }

    // get the recent unread message from a sender.
    suspend fun fetchMessages(senderId: MimeiId): List<ChatMessage>? { return withRetry {
        try {
            val gson = Gson()
            val entry = "message_fetch"
            val url = "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$entry" +
                    "&userid=${appUser.mid}&senderid=$senderId"
            // write outgoing message to user's Mimei db
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val list = gson.fromJson(
                    response.bodyAsText(),
                    object : TypeToken<List<ChatMessage>>() {}.type
                ) as List<ChatMessage>?
                return@withRetry list
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("fetchMessages").e(e.toString())
        }
        null
    } }

    /**
     * Get a list of unread incoming messages. Only check, do not fetch them.
     * */
    suspend fun checkNewMessages(): List<ChatMessage>? { return withRetry {
        if (appUser.isGuest()) return@withRetry null
        try {
            val entry = "message_check"
            val url =
                "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$entry&userid=${appUser.mid}"
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val list = Gson().fromJson(
                    response.bodyAsText(),
                    object : TypeToken<List<ChatMessage>>() {}.type
                ) as List<ChatMessage>?
                return@withRetry list
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("checkNewMessages").e(appUser.toString())
        }
        null
    } }

    suspend fun checkUpgrade(): Map<String, String>? { return withRetry {
        try {
            val gson = Gson()
            val url = "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=check_upgrade"
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val map = gson.fromJson(
                    response.bodyAsText(),
                    object : TypeToken<Map<String, String>>() {}.type
                ) as Map<String, String>?
                return@withRetry map
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("checkUpgrade").e("$e")
        }
        null
    } }

    suspend fun getUserId(username: String): MimeiId? { return withRetry {
        try {
            val entry = "get_userid"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "username" to username
            )
            val response = appUser.hproseService?.runMApp<String?>(entry, params)
            return@withRetry response
        } catch (e: Exception) {
            Timber.tag("GetUserId").e("$e")
            null
        }
    } }

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
    ): Pair<User?, String?> { return withRetry {
        try {
            val userId = getUserId(username) ?: return@withRetry Pair(
                null,
                context.getString(R.string.login_getuserid_fail)
            )
            val user = getUser(userId) ?: return@withRetry Pair(
                null,
                context.getString(R.string.login_getuser_fail)
            )
            
            val entry = "login"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "username" to username,
                "password" to password
            )
            
            val hproseClient = HproseClient.create("${user.baseUrl}/webapi/").useService(HproseService::class.java)
            val response = hproseClient.runMApp(entry, params) as? Map<String, Any>
            
            if (response != null) {
                val status = response["status"] as? String
                if (status == "success") {
                    return@withRetry Pair(user, null)
                } else if (status == "failure") {
                    val reason = response["reason"] as? String ?: "Unknown error occurred"
                    return@withRetry Pair(null, reason)
                }
            }
        } catch (e: Exception) {
            Timber.tag("Login").e("$e")
            return@withRetry Pair(null, context.getString(R.string.login_failed))
        }
        Pair(null, context.getString(R.string.login_error))
    } }

    /**
     * Given host url, get the node Id
     * */
    suspend fun getHostId(host: String? = appUser.baseUrl): MimeiId? { return withRetry {
        val url = "$host/getvar?name=hostid"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                return@withRetry response.bodyAsText().trim().trim('"').trim(',')
            }
        } catch (e: Exception) {
            Timber.tag("getHostId").e("$e $url")
        }
        null
    } }

    /**
     * @param nodeId
     * Find IP addresses of given node.
     * */
    suspend fun getHostIP(nodeId: MimeiId): String?  { return withRetry {
        val url = "${appUser.baseUrl}/getvar?name=ips&arg0=$nodeId"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                response.bodyAsText().trim().trim('"').trim(',')
                    .split(',').let { ips ->
                    if (ips.isNotEmpty()) {
                        val accessibleIp = getAccessibleIP2(ips)
                        return@withRetry accessibleIp
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("getHostIP").e("$e $url")
        }
        null
    } }

    /**
     * Register a new user or update an existing user account.
     * */
    suspend fun setUserData(userObj: User): Map<*, *>? { return withRetry {
        val url: String
        val user = userObj.copy(fansList = null, followingList = null)  // Do not save them.
        try {
            if (user.isGuest()) {
                /**
                 * Register a new user.
                 * */
                user.followingList = getAlphaIds()
                url = "${user.baseUrl}/entry?aid=$appId&ver=last&entry=register&user=${
                    URLEncoder.encode(Json.encodeToString(user), "utf-8")
                }"
            } else {
                /**
                 * Update existing user account.
                 * If hostId is changed, sync user mimei on new node first.
                 * */
                val entry = "set_author_core_data"
                url = "${user.baseUrl}/entry?aid=$appId&ver=last&entry=$entry&user=${
                    URLEncoder.encode(Json.encodeToString(user), "utf-8")
                }"
            }
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val updatedUser = Gson().fromJson(response.bodyAsText(), Map::class.java)
                return@withRetry updatedUser
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("setUserData").e(e.toString())
        }
        null
    } }

    suspend fun setUserAvatar(user: User, avatar: MimeiId) {
        return withRetry {
            val entry = "set_user_avatar"
            val json = """
            {"aid": $appId, "ver": "last", "userid": ${user.mid}, "avatar": $avatar}
        """.trimIndent()
            val gson = Gson()
            val request = gson.fromJson(json, Map::class.java)
            try {
                val hproseClient =
                    HproseClient.create("${user.writableUrl()}/webapi/").useService(HproseService::class.java)
                hproseClient.runMApp(entry, request) as Unit?
            } catch (e: Exception) {
                Timber.tag("setUserAvatar").e(e.toString())
            }
        }
    }

    /**
     * Given user object get a list of Field-Value, where Field is user Id,
     * Value is timestamp when the following is added.
     * */
    suspend fun getFollowings(user: User): List<MimeiId> { return withRetry {
        try {
            if (!user.isGuest()) {
                val entry = "get_followings_sorted"
                val params = mapOf(
                    "aid" to appId,
                    "ver" to "last",
                    "userid" to user.mid
                )
                
                val hproseClient = HproseClient.create("${user.baseUrl}/webapi/").useService(HproseService::class.java)
                val response = hproseClient.runMApp(entry, params) as? List<Map<String, Any>>
                
                if (response != null) {
                    val sorted = response.sortedByDescending { (it["value"] as? Int) ?: 0 }
                    return@withRetry sorted.mapNotNull { it["field"] as? String }
                }
            }
        } catch (e: Exception) {
            Timber.tag("Hprose.getFollowings").e("$e")
        }
        getAlphaIds()
    } }

    /**
     * Given user object get a list of Field-Value, where Field is user Id,
     * Value is timestamp when the follower is added.
     * */
    suspend fun getFans(user: User): List<MimeiId>? { return withRetry {
        try {
            if (!user.isGuest()) {
                val entry = "get_followers_sorted"
                val params = mapOf(
                    "aid" to appId,
                    "ver" to "last",
                    "userid" to user.mid
                )
                
                val hproseClient = HproseClient.create("${user.baseUrl}/webapi/").useService(HproseService::class.java)
                val response = hproseClient.runMApp(entry, params) as? List<Map<String, Any>>
                
                if (response != null) {
                    val sorted = response.sortedByDescending { (it["value"] as? Int) ?: 0 }
                    return@withRetry sorted.mapNotNull { it["field"] as? String }
                }
            }
        } catch (e: Exception) {
            Timber.tag("Hprose.getFans").e(e.toString())
        }
        null
    } }

    /**
     * Load tweets of appUser and its followings from network.
     * */
    fun getTweetFeed(
        user: User,
        pageNumber: Int = 0,
        pageSize: Int = 20,
        entry: String = "get_tweet_feed"
    ): Flow<List<Tweet>> = channelFlow {
        try {
            val tweetList = withRetry {
                val params = mapOf(
                    "aid" to appId,
                    "ver" to "last",
                    "pn" to pageNumber,
                    "ps" to pageSize,
                    "userid" to if (!user.isGuest()) user.mid else getAlphaIds().first(),
                    "appuserid" to appUser.mid
                )
                val response = user.hproseService?.runMApp(entry, params) as? List<Map<String, Any>?>
                response?.mapNotNull { tweetJson ->
                    tweetJson?.let { Tweet.from(tweetJson) }
                } ?: emptyList()
            }

            tweetList.let {
                val list = it.mapNotNull { tweet ->
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
                send(list)
            }
        } catch (e: Exception) {
            Timber.tag("getTweetFeed").e(e.toString())
            send(emptyList())
        }
    }

    /**
     * Load 10 tweets of an User each time.
     * */
    fun getTweetListByRank(
        user: User,
        pageNumber: Int = 0,
        pageSize: Int = 20,
        entry: String = "get_tweets_by_user"
    ): Flow<List<Tweet>> = channelFlow {
        try {
            // 1. Retrieve cached tweet list for this user and send them to _tweets.
            val cachedTweets = dao.getCachedTweetsByUser(user.mid, pageNumber * pageSize, pageSize)
            send(cachedTweets.map { it.originalTweet })

            // 2. Make network call to get tweets from server, wrapped with retry logic
            val tweetList = withRetry {
                val params = mapOf(
                    "aid" to appId,
                    "ver" to "last",
                    "userid" to user.mid,
                    "pn" to pageNumber,
                    "ps" to pageSize,
                    "appuserid" to appUser.mid
                )
                
                val hproseClient = HproseClient.create("${user.baseUrl}/webapi/").useService(HproseService::class.java)
                val response = hproseClient.runMApp(entry, params) as? List<Map<String, Any>?>
                
                response?.mapNotNull { tweetJson ->
                    tweetJson?.let { Tweet.from(tweetJson as Map<String, Any>) }
                } ?: emptyList()
            }

            // 3. Process the tweetList if it's not null
            tweetList?.let {
                val list = it.mapNotNull { tweet ->
                    tweet.author = user

                    if (tweet.originalTweetId != null) {
                        val originalTweet = getTweet(tweet.originalTweetId!!, tweet.originalAuthorId!!)
                            ?: return@mapNotNull null
                        tweet.originalTweet = originalTweet
                    }

                    updateCachedTweet(tweet)
                    tweet
                }
                send(list)
            } ?: run {
                Timber.w("Tweet list is null after network call.")
                send(emptyList())
            }
        } catch (e: Exception) {
            Timber.tag("getTweetListByRank").e("$e")
            send(emptyList())
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
    ): Tweet? { return withRetry {
        // Check cache first
        loadCachedTweet(tweetId)?.let { cachedTweet ->
            if (cachedTweet.isPrivate && cachedTweet.authorId != appUser.mid)
                return@withRetry null
            else
                return@withRetry cachedTweet
        }
        val author = getUser(authorId)
        val hostIP = (nodeUrl ?: author?.baseUrl) ?: return@withRetry null
        val entry = "get_tweet"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "tweetid" to tweetId,
            "appuserid" to appUser.mid
        )
        
        try {
            val hproseClient = HproseClient.create("$hostIP/webapi/").useService(HproseService::class.java)
            val response = hproseClient.runMApp(entry, params) as? Map<String, Any>
            if (response != null) {
                Tweet.from(response).let { tweet ->
                    tweet.author = author
                    TweetCacheManager.shared.saveTweet(tweet, userId = appUser.mid)
                    return@withRetry tweet
                }
            }
        } catch (e: Exception) {
            Timber.tag("getTweet").e("$tweetId $authorId $hostIP $e")
        }
        null
    }}

    /**
     * Update cached but keep its timestamp when it was cached.
     * */
    fun updateCachedTweet(tweet: Tweet) {
        try {
            dao.insertOrUpdateCachedTweet(
                CachedTweet(
                    tweet.mid, tweet.authorId, tweet,
                    dao.getCachedTweet(tweet.mid)?.timestamp ?: Date()
                )
            )
        } catch (e: Exception) {
            Timber.tag("updateCachedTweet").e("$e")
        }
    }

    /**
     * Get tweet from node Mimei DB to refresh cached tweet.
     * Called when the given tweet is visible.
     * */
    suspend fun refreshTweet(
        tweetId: MimeiId,
        authorId: MimeiId
    ): Tweet? { return withRetry {
        try {
            val author = getUser(authorId) ?: return@withRetry null
            val entry = "refresh_tweet"
            val url = "${author.baseUrl}/entry?aid=$appId&ver=last&entry=$entry" +
                    "&tweetid=$tweetId&appuserid=${appUser.mid}" +
                    "&userid=${author.mid}&hostid=${author.hostIds?.first()}"
            val response = httpClient.get(url) as HttpResponse
            if (response.status == HttpStatusCode.OK) {
                Tweet.from(response).let { tweet ->
                    tweet.author = author
                    /**
                     * update the tweet in the cache database.
                     * */
                    updateCachedTweet(tweet)
                    Timber.tag("refreshTweet").d("$tweet")
                    return@withRetry tweet
                }
            }
        } catch (e: Exception) {
            Timber.tag("refreshTweet").e("$tweetId $authorId $e")
        }
        // if cannot get tweet from node, delete it from cache.
        dao.deleteCachedTweet(tweetId)
        null
    }}

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
    ): Tweet? { return withRetry {
        val entry = if (direction == 1) "retweet_added" else "retweet_removed"
        val url = "${tweet.author?.baseUrl}/entry?aid=$appId&ver=last&entry=$entry" +
                "&tweetid=${tweet.mid}&appuserid=${appUser.mid}&retweetid=$retweetId" +
                "&authorid=${tweet.authorId}"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                return@withRetry Tweet.from(response.bodyAsText())
            }
        } catch (e: Exception) {
            Timber.tag("updateRetweetCount()").e("$e $url")
        }
        null
    } }

    suspend fun uploadTweet(tweet: Tweet): Tweet? { return withRetry {
        val entry = "add_tweet"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "hostid" to (appUser.hostIds?.first() ?: ""),
            "tweet" to Json.encodeToString(tweet)
        )
        
        try {
            val hproseClient = HproseClient.create("${appUser.baseUrl}/webapi/").useService(HproseService::class.java)
            val response = hproseClient.runMApp(entry, params) as? Map<String, Any>
            
            if (response != null) {
                val success = response["success"] as? Boolean
                if (success == true) {
                    val newTweetId = response["mid"] as? String
                    if (newTweetId != null) {
                        tweet.mid = newTweetId
                        tweet.author = appUser
                        return@withRetry tweet
                    }
                } else {
                    val errorMessage = response["message"] as? String ?: "Unknown upload error"
                    Timber.tag("uploadTweet").e("Upload failed: $errorMessage")
                }
            }
        } catch (e: Exception) {
            Timber.tag("uploadTweet").e("$e $tweet $appUser")
        }
        null
    } }

    /**
     * Delete a tweet and returned the deleted tweetId
     * */
    suspend fun delTweet(tweetId: MimeiId): MimeiId? {
        val method = "delete_tweet"
        val url = "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$method" +
                "&tweetid=$tweetId&authorid=${appUser.mid}"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                return Gson().fromJson(response.bodyAsText(), MimeiId::class.java)
            }
        }catch (e: Exception) {
            Timber.tag("delTweet").e("$e $appUser $tweetId $url")
        }
        return null
    }

    suspend fun delComment(parentTweet: Tweet, commentId: MimeiId, callback: (MimeiId) -> Unit) { return withRetry {
        val method = "delete_comment"
        val url = "${parentTweet.author?.baseUrl}/entry?aid=$appId&ver=last&entry=$method" +
                "&tweetid=${parentTweet.mid}&commentid=$commentId&userid=${appUser.mid}" +
                "&hostid=${parentTweet.author?.hostIds?.first()}"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                callback(commentId)
            }
        } catch (e: Exception) {
            Timber.tag("delComment()").e(e.toString())
        }
    } }

    /**
     * Called when appUser clicks the Follow button.
     * @param followedId is the user that appUser is following or unfollowing.
     * */
    suspend fun toggleFollowing(
        followedId: MimeiId,
        followingId: MimeiId = appUser.mid
    ): Boolean? { return withRetry {
        val entry = "toggle_following"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "followingid" to followedId,
            "userid" to followingId
        )
        
        try {
            val hproseClient = HproseClient.create("${appUser.baseUrl}/webapi/").useService(HproseService::class.java)
            val response = hproseClient.runMApp(entry, params) as? Boolean
            return@withRetry response
        } catch (e: Exception) {
            Timber.tag("toggleFollowing()").e("$e")
            null
        }
    } }

    /**
     * @param isFollowing indicates if the appUser is following @param userId. Passing
     * an argument instead of toggling the status of a follower, because toggling
     * following/follower status happens on two different hosts.
     * */
    suspend fun toggleFollower(
        userId: MimeiId,
        isFollowing: Boolean,
        followerId: MimeiId = appUser.mid
    ) { return withRetry {
        val user = getUser(userId)
        val entry = "toggle_follower"
        val url = "${user?.baseUrl}/entry?aid=$appId&ver=last&entry=$entry" +
                    "&otherid=$followerId&userid=${userId}&isfollower=$isFollowing"
        try {
            httpClient.get(url)
        } catch (e: Exception) {
            Timber.tag("toggleFollower()").e("$e $url $user")
        }
    } }

    /**
     * Send a retweet request to backend and get a new tweet object back.
     * */
    suspend fun retweet(
        tweet: Tweet,                       // original tweet to be retweeted
        addTweetToFeed: (Tweet) -> Unit     // add retweet to user's feed
    ) { return withRetry {
        try {
            // upload the retweet, simply a few dozen bytes.
            val retweet = uploadTweet( Tweet(
                mid = TW_CONST.GUEST_ID,    // placeholder will be replaced in backend.
                content = "",
                authorId = appUser.mid,
                originalTweetId = tweet.mid,
                originalAuthorId = tweet.authorId
            )) ?: return@withRetry

            retweet.originalTweet = tweet
            addTweetToFeed(retweet)

            updateRetweetCount(tweet, retweet.mid)?.let { updatedTweet ->
                updateCachedTweet(updatedTweet)
            }
        } catch (e: Exception) {
            Timber.e("toggleRetweet()", e.toString())
        }
    } }

    /**
     * Load favorite status of the tweet by appUser.
     * */
    suspend fun toggleFavorite(tweet: Tweet): Tweet { return withRetry {
        val entry = "toggle_favorite"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "appuserid" to appUser.mid,
            "tweetid" to tweet.mid,
            "authorid" to tweet.authorId,
            "userhostid" to (appUser.hostIds?.first() ?: "")
        )
        
        try {
            val hproseClient = HproseClient.create("${tweet.author?.baseUrl}/webapi/").useService(HproseService::class.java)
            val response = hproseClient.runMApp(entry, params) as? Map<String, Any>
            
            if (response != null) {
                val isFavorite = response["isFavorite"] as? Boolean
                val favoriteCount = response["count"] as? Int
                
                if (isFavorite != null && favoriteCount != null) {
                    val favorites = tweet.favorites?.toMutableList() ?: mutableListOf(false, false, false)
                    favorites[UserActions.FAVORITE] = isFavorite
                    
                    val ret = tweet.copy(
                        favorites = favorites,
                        favoriteCount = favoriteCount
                    )
                    
                    // Update user if provided
                    response["user"]?.let { userData ->
                        val user = Gson().fromJson(Gson().toJson(userData), User::class.java)
                        appUser = appUser.copy(favoritesCount = user.favoritesCount)
                    }
                    
                    updateCachedTweet(tweet)
                    return@withRetry ret
                }
            }
        } catch (e: Exception) {
            Timber.tag("toggleFavorite").e(e, "Error: ${e.message} $tweet")
        }
        tweet
    } }

    /**
     * Load bookmark status of the tweet by appUser.
     * */
    suspend fun toggleBookmark(tweet: Tweet): Tweet { return withRetry {
        val entry = "toggle_bookmark"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "userid" to appUser.mid,
            "tweetid" to tweet.mid,
            "authorid" to tweet.authorId,
            "userhostid" to (appUser.hostIds?.first() ?: "")
        )
        
        try {
            val hproseClient = HproseClient.create("${tweet.author?.baseUrl}/webapi/").useService(HproseService::class.java)
            val response = hproseClient.runMApp(entry, params) as? Map<String, Any>
            
            if (response != null) {
                val hasBookmarked = response["hasBookmarked"] as? Boolean
                val bookmarkCount = response["count"] as? Int
                
                if (hasBookmarked != null && bookmarkCount != null) {
                    val favorites = tweet.favorites?.toMutableList() ?: mutableListOf(false, false, false)
                    favorites[UserActions.BOOKMARK] = hasBookmarked
                    
                    val ret = tweet.copy(
                        favorites = favorites,
                        bookmarkCount = bookmarkCount
                    )
                    
                    // Update user if provided
                    response["user"]?.let { userData ->
                        val user = Gson().fromJson(Gson().toJson(userData), User::class.java)
                        appUser = appUser.copy(bookmarksCount = user.bookmarksCount)
                    }
                    
                    updateCachedTweet(tweet)
                    return@withRetry ret
                }
            }
        } catch (e: Exception) {
            Timber.tag("toggleBookmark").e(e, "Error: ${e.message} $tweet")
        }
        tweet
    } }

    /**
     * Load favorite tweets, bookmarks or comments of an user.
     * */
    suspend fun getUserTweetsByType(
        user: User,
        type: UserContentType
    ):List<Tweet>? { return withRetry {
        val typeString = when (type) {
            UserContentType.FAVORITES -> "favorite_list" // Or whatever your backend expects
            UserContentType.BOOKMARKS -> "bookmark_list"
            UserContentType.COMMENTS -> "comment_list"
        }
        val entry = "get_user_meta"
        val url = "${user.baseUrl}/entry?aid=$appId&ver=last&entry=$entry" +
                "&userid=${user.mid}&type=$typeString&appuserid=${appUser.mid}"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                val jsonArray = gson.fromJson(response.bodyAsText(), Array<Any>::class.java)
                return@withRetry jsonArray.map { tweetJson ->
                    Tweet.from(gson.toJson(tweetJson) as Map<String, Any>)
                }
            } else {
                Timber.tag("getUserTweetsByType").w("$response")
            }
        } catch (e: Exception) {
            Timber.tag("getUserTweetsByType").e("${e.message} $url")
        }
        null
    } }

    /**
     * Delete a tweet and return the deleted tweetId. Only appUser can delete its own tweet.
     */
    suspend fun deleteTweet(tweetId: String): String? { return withRetry {
        val entry = "delete_tweet"
        val params = mapOf(
            "aid" to appId,
            "ver" to "last",
            "authorid" to appUser.mid,
            "tweetid" to tweetId
        )
        
        try {
            val hproseClient = HproseClient.create("${appUser.baseUrl}/webapi/").useService(HproseService::class.java)
            val response = hproseClient.runMApp(entry, params) as? Map<String, Any>
            
            if (response != null) {
                val success = response["success"] as? Boolean
                if (success == true) {
                    return@withRetry response["tweetid"] as? String
                } else {
                    val errorMessage = response["message"] as? String ?: "Unknown tweet deletion error"
                    throw Exception(errorMessage)
                }
            }
        } catch (e: Exception) {
            Timber.tag("deleteTweet").e("$e")
        }
        null
    } }

    /**
     * Load all comments of a tweet.
     * @param pageNumber
     * */
    suspend fun getComments(tweet: Tweet, pageNumber: Int = 0, pageSize: Int = 20): List<Tweet>? { return withRetry {
        try {
            if (tweet.author == null)
                tweet.author = getUser(tweet.authorId)
            
            val entry = "get_comments"
            val params = mapOf(
                "aid" to appId,
                "ver" to "last",
                "tweetid" to tweet.mid,
                "appuserid" to appUser.mid,
                "pn" to pageNumber,
                "ps" to pageSize
            )
            
            val hproseClient = HproseClient.create("${tweet.author?.baseUrl}/webapi/").useService(HproseService::class.java)
            val response = hproseClient.runMApp(entry, params) as? List<Map<String, Any>?>
            
            if (response != null) {
                return@withRetry response.mapNotNull { tweetJson ->
                    tweetJson?.let { Tweet.from(tweetJson as Map<String, Any>) }
                }
            }
        } catch (e: Exception) {
            Timber.tag("getComments()").e(e, "Error: ${e.message}")
        }
        null
    } }

    /**
     * The mid of "comment" is updated here, used to be null.
     * @Return the updated parent tweet.
     * */
    suspend fun uploadComment(tweet: Tweet, comment: Tweet): Tweet { return withRetry {
        val entry = "add_comment"
        val json = URLEncoder.encode(Json.encodeToString(comment), "utf-8")
        val url = "${tweet.author?.baseUrl}/entry?aid=$appId&ver=last&entry=$entry" +
                "&tweetid=${tweet.mid}&comment=$json&userid=${appUser.mid}" +
                "&hostid=${tweet.author?.hostIds?.first()}"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                val res = gson.fromJson<Map<String, Any?>>(
                    response.bodyAsText(),
                    object : TypeToken<Map<String, Any?>>() {}.type
                )
                // update mid of comment, which was null when passed as argument
                comment.mid = res["commentId"] as MimeiId

                val updatedTweet = tweet.copy(
                    commentCount = (res["count"] as Double).toInt()
                )
                updateCachedTweet(updatedTweet)
                updatedTweet
            } else {
                tweet
            }
        } catch (e: Exception) {
            Timber.tag("uploadComment()").e(e, "Error: ${e.message} $url $tweet")
            tweet
        }
    } }

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
     * */
    suspend fun getUser(userId: MimeiId): User? { return withRetry {
        // check if user data has been cached
        cachedUsers.firstOrNull { it.mid == userId }?.let { return@withRetry it }

        try {
            val url = "${appUser.baseUrl}/getvar?name=mmprovsips&arg0=$userId"
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                var string = response.bodyAsText().trim().removeSurrounding("\"")
                    .replace("\\", "")
                val pattern =
                    Pattern.compile("window\\.setParam\\((\\{.*?\\})\\)", Pattern.DOTALL)
                string = """
                    window.setParam({
                        addrs: $string,
                        aid: ""
                    })]
                """.trimIndent()
                val matcher = pattern.matcher(string as CharSequence)
                if (matcher.find()) {
                    matcher.group(1)?.let {
                        val paramMap = Gson().fromJson(it, Map::class.java) as Map<*, *>
                        // Get a list of IP addresses, one IP per host.
                        val hostIPs = filterIpAddresses(paramMap["addrs"] as ArrayList<*>)
                        getAccessibleUser(hostIPs, userId)?.let { user ->
                            cachedUsers.add(user)
                            dao.insertOrUpdateCachedUser(
                                CachedUser(userId, user)
                            )
                            return@withRetry user
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("getUser").e("${appUser.baseUrl} $userId $e")
        }
        null
    } }

    suspend fun getUserCoreData(mid: MimeiId, ip: String): User? { return withRetry {
        try {
            val entry = "get_user_core_data"
            val url = "http://$ip/entry?aid=$appId&ver=last&entry=$entry&userid=$mid"
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                val userData = gson.fromJson(
                    response.bodyAsText(),
                    Map::class.java) as? Map<*, *> ?: return@withRetry null
                
                val user = getUserInstance(mid).apply {
                    baseUrl = "http://$ip"
                    name = userData["name"] as? String
                    username = userData["username"] as? String
                    avatar = userData["avatar"] as? String
                    email = userData["email"] as? String
                    profile = userData["profile"] as? String
                    tweetCount = (userData["tweetCount"] as? Number)?.toInt() ?: 0
                    followingCount = (userData["followingCount"] as? Number)?.toInt() ?: 0
                    followersCount = (userData["followersCount"] as? Number)?.toInt() ?: 0
                    bookmarksCount = (userData["bookmarksCount"] as? Number)?.toInt() ?: 0
                    favoritesCount = (userData["favoritesCount"] as? Number)?.toInt() ?: 0
                    commentsCount = (userData["commentsCount"] as? Number)?.toInt() ?: 0
                    hostIds = (userData["hostIds"] as? List<*>)?.mapNotNull { it as? String }
                    cloudDrivePort = (userData["cloudDrivePort"] as? Number)?.toInt() ?: 8010
                }
                return@withRetry user
            }
        } catch (e: IOException) {
//            Timber.tag("getUserCoreData").e(e, "Network error while retrieving user data")
        } catch (e: JsonSyntaxException) {
            Timber.tag("getUserCoreData").e(e, "Error parsing user data from JSON")
        } catch (e: Exception) {
//            Timber.tag("getUserCoreData").e(e, "Error retrieving user data")
        }
        null
    } }

    /**
     * @param ip
     * Check the versions of AppId on the given IP. It shall return a list of versions.
     * */
    suspend fun isAccessible(ip: String): String? {
        return runCatching {
            val url = "http://$ip/getvar?name=mmversions&arg0=$appId"
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val responseValues = Gson().fromJson(
                    response.bodyAsText(), Array<String>::class.java
                )
                responseValues.firstOrNull()?.let { ip } // Return IP if found
            } else null
        }.onFailure { e ->
            when (e) {
                is ConnectException, is SocketTimeoutException -> {
                    // Ignore these exceptions and continue
                    Timber.tag("isAccessible").w(e, "Timeout for IP: $ip")
                }
//                else -> Timber.tag("isAccessible").e(e, "Error accessing appId for IP: $ip")
            }
        }.getOrNull()
    }

    suspend fun getProviders(mid: MimeiId, baseUrl: String? = appUser.baseUrl): List<String>?
    { return withRetry {
        val entry = "get_providers"
        val url = "$baseUrl/entry?aid=$appId&ver=last&entry=$entry&mid=$mid"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                val ips = gson.fromJson<List<String>>(response.bodyAsText(),
                    object : TypeToken<List<String>>() {}.type)
                return@withRetry ips.toSet().toList()
            }
        } catch (e: Exception) {
            Timber.tag("getProviders").e("$e $url")
        }
        null
    } }

    /**
     * Return the current tweet list that is pinned to top.
     * */
    suspend fun togglePinnedTweet(tweetId: MimeiId): List<Map<*,*>>? { return withRetry {
        val entry = "toggle_top_tweets"
        val url =  "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$entry" +
                "&userid=${appUser.mid}&tweetid=$tweetId"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                return@withRetry gson.fromJson(response.bodyAsText(),
                    object : TypeToken<List<Map<*,*>>>() {}.type) as List<Map<*,*>>?
            }
        } catch (e: Exception) {
            Timber.tag("togglePinnedTweet").e("$e")
        }
        null
    } }

    /**
     * Return a list of {tweetId, timestamp} for each pinned Tweet. The timestamp is when
     * the tweet is pinned.
     * */
    suspend fun getPinnedList(user: User): List<Map<*,*>>? { return withRetry {
        val entry = "get_top_tweets"
        val url =
            "${user.baseUrl}/entry?aid=$appId&ver=last&entry=$entry&userid=${user.mid}" +
                    "&gid=${appUser.mid}"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                return@withRetry gson.fromJson(response.bodyAsText(),
                    object : TypeToken<List<Map<*,*>>>() {}.type) as List<Map<*,*>>?
            }
        } catch (e: Exception) {
            Timber.tag("getPinnedList").e("$e")
        }
        null
    } }

    /**
     * Remove user from cachedUsers list.
     * */
    fun removeCachedUser(userId: MimeiId) {
        cachedUsers.removeIf { it.mid == userId }
    }

    suspend fun logging(msg: String) { return withRetry {
        if (appUser.isGuest()) return@withRetry
        val url =
            "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=logging&msg=${
                URLEncoder.encode(msg, "utf-8")
            }"
        try {
            httpClient.get(url)
        } catch (_: Exception) {
        }
    } }

    /**
     * Upload media file to node and return its IPFS cid with its media type.
     * */
    @OptIn(UnstableApi::class)
    suspend fun uploadToIPFS(
        context: Context,
        uri: Uri,
        referenceId: MimeiId? = null
    ): MimeiFileType? { return withRetry {
        val hproseClient = HproseClient.create("${appUser.writableUrl()}/webapi/")
            .useService(HproseService::class.java)
        var offset = 0L
        var byteRead: Int
        val buffer = ByteArray(TW_CONST.CHUNK_SIZE)
        val json = """{"aid": $appId, "ver": "last", "offset": 0}"""
        val request = Gson().fromJson(json, Map::class.java).toMutableMap()

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

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.use { stream ->
                    while (stream.read(buffer).also { byteRead = it } != -1) {
                        request["fsid"] = hproseClient.runMApp("upload_ipfs",
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
            val cid = hproseClient.runMApp<String?>("upload_ipfs", request.toMap()) ?: return@withRetry null

            // Determine MediaType based on MIME type
            val mimeType = context.contentResolver.getType(uri)
            Timber.tag("uploadToIPFS()").d("cid=$cid $mimeType")
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
            val aspectRatio = if (mediaType == us.fireshare.tweet.datamodel.MediaType.Video) {
                getVideoAspectRatio(context, uri)
            } else null
            return@withRetry MimeiFileType(cid, mediaType, offset, fileName, fileTimestamp, aspectRatio)
        } catch (e: Exception) {
            Timber.tag("uploadToIPFS()").e(e, "Error: ${e.message}")
        }
        null
    } }
}

