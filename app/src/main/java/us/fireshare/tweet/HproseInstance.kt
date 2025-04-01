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
import us.fireshare.tweet.datamodel.UserFavorites
import us.fireshare.tweet.datamodel.isGuest
import us.fireshare.tweet.datamodel.writableUrl
import us.fireshare.tweet.widget.Gadget.filterIpAddresses
import us.fireshare.tweet.widget.Gadget.getAccessibleIP
import us.fireshare.tweet.widget.Gadget.getAccessibleIP2
import us.fireshare.tweet.widget.Gadget.getAccessibleTweet
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
    suspend fun fetchMessages(senderId: MimeiId, MessageCount: Int = 50): List<ChatMessage>? { return withRetry {
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

    suspend fun getUserId(username: String): MimeiId?
    { return withRetry {
        try {
            val entry = "get_userid"
            val url = "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$entry&username=$username"
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val id = Gson().fromJson(response.bodyAsText(), String::class.java)
                return@withRetry id
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("GetUserId").e("$e")
        }
        null
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
//            val user = appUser.copy(baseUrl = "http://125.229.161.122:8080", username="test",
//                mid = "G3OacoFCzrtuOHwIbHUsApZC6VZ", hostIds = listOf("5TVMyFk-DsUH8n_FeF927OEoZSZ"))
//            return@withRetry Pair(user, null)
            val url = "${user.baseUrl}/entry?aid=$appId&ver=last&entry=login" +
                    "&username=$username&password=$password"
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                Gson().fromJson(response.bodyAsText(), Map::class.java)?.let { ret ->
                    if (ret["status"] == "success") {
                        return@withRetry Pair(user, null)
                    }
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

    // get Ids of users who the current user is following
    suspend fun getFollowings(user: User): List<MimeiId> { return withRetry {
        try {
            if (! user.isGuest()) {
                val method = "get_followings_sorted"
                val url =
                    "${user.baseUrl}/entry?aid=$appId&ver=last&entry=$method&userid=${user.mid}"
                val response = httpClient.get(url)
                if (response.status == HttpStatusCode.OK) {
                    val followings = Gson().fromJson(response.bodyAsText(),
                        object : TypeToken<List<Map<*,*>>>() {}.type) as List<Map<*,*>>
                    return@withRetry followings.sortedByDescending { it["Value"] as Double }
                        .map { it["Field"] as MimeiId }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("Hprose.getFollowings").e(e.toString())
        }
        getAlphaIds()
    } }

    // get fans list of an user
    suspend fun getFans(user: User): List<MimeiId>? { return withRetry {
        try {
            if (! user.isGuest()) {
                val entry = "get_followers_sorted"
                val url =
                    "${user.baseUrl}/entry?aid=$appId&ver=last&entry=$entry&userid=${user.mid}"
                val response = httpClient.get(url)
                if (response.status == HttpStatusCode.OK) {
                    val fans = Gson().fromJson(response.bodyAsText(),
                        object : TypeToken<List<Map<*,*>>>() {}.type) as List<Map<*,*>>
                    /**
                     * Map is Redis HSet<Field, Value>, where Field is an user Id
                     * Value is the timestamp of the follower been added.
                     * */
                    return@withRetry fans.sortedByDescending { it["Value"] as Double }
                        .map { it["Field"] as MimeiId }
                }
            }
        } catch (e: Exception) {
            Timber.tag("Hprose.getFans").e(e.toString())
        }
        null
    } }

    /**
     * Get tweets of a given author in a given span of time.
     * Update tweets state flow directly.
     * */
    fun getTweetList(
        user: User,
        startTimestamp: Long,
        endTimestamp: Long?,
    ): Flow<List<Tweet>> = channelFlow {
        try {
            // Wrap the network call with withRetry
            val tweetList = withRetry {
                // 1. Make network call to get tweet list from server
                val method = "get_tweets"
                val url = "${user.baseUrl}/entry?aid=$appId&ver=last&entry=$method" +
                        "&userid=${user.mid}&start=$startTimestamp&end=$endTimestamp&gid=${appUser.mid}"
                val response = httpClient.get(url)
                if (response.status == HttpStatusCode.OK) {
                    val gson = Gson()
                    gson.fromJson<List<Tweet>?>(
                        response.bodyAsText(),
                        object : TypeToken<List<Tweet>?>() {}.type
                    )
                } else {
                    // Handle non-OK status codes appropriately.  Crucially important.
                    Timber.e("HTTP request failed with status: ${response.status}")
                    null // Or throw an exception, depending on your error handling strategy
                }
            }

            // Process the tweetList if it's not null
            tweetList?.let {
                // 2. Overwrite cached tweets of the user with a updated one, but keep its
                // timestamp, which is when the tweet is cached, not when it's created.
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
                // Handle the case where tweetList is null (e.g., due to a failed HTTP request)
                Timber.w("Tweet list is null after network call.")
                send(emptyList()) // Or send a default value, or throw an exception
            }
        } catch (e: Exception) {
            Timber.tag("getTweetList").e(e.toString())
            // Consider sending an empty list or re-throwing the exception, depending on your needs.
            send(emptyList()) // Or re-throw: throw e
        }
    }

    /**
     * Load 10 tweets of an User each time.
     * */
    fun getTweetListByRank(
        user: User,
        startRank: Int = 0,
        count: Int = 10
    ): Flow<List<Tweet>> = channelFlow {
        try {
            // 1. Retrieve cached tweet list for this user and send them to _tweets.
            val cachedTweets = dao.getCachedTweetsByUser(user.mid, count, startRank)
            send(cachedTweets.map { it.originalTweet })

            // 2. Make network call to get tweets from server, wrapped with retry logic
            val tweetList = withRetry {
                val method = "get_tweets_by_rank"
                val url = "${user.baseUrl}/entry?aid=$appId&ver=last&entry=$method&gid=${appUser.mid}" +
                        "&userid=${user.mid}&start=$startRank&end=${startRank + count}"
                val response = httpClient.get(url)
                if (response.status == HttpStatusCode.OK) {
                    Gson().fromJson<List<Tweet>?>(
                        response.bodyAsText(),
                        object : TypeToken<List<Tweet>?>() {}.type
                    )
                } else {
                    Timber.e("HTTP request failed with status: ${response.status}")
                    null // Or throw an exception, depending on your error handling strategy
                }
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
                send(emptyList()) // Or send a default value, or throw an exception
            }
        } catch (e: Exception) {
            Timber.tag("getTweetListByRank").e("$e")
            send(emptyList()) // Or re-throw: throw e
        }
    }

    /**
     * Get core data of the tweet. Do Not fetch its original tweet if there is any.
     * Let the caller to decide if go further on the tweet hierarchy.
     * */
    suspend fun getTweet(
        tweetId: MimeiId,
        authorId: MimeiId,
        nodeUrl: String? = null      // ip address where tweet can be found.
    ): Tweet? { return withRetry {
        // if there is a cached tweet, return it.
        loadCachedTweet(tweetId)?.let { cachedTweet ->
            if (cachedTweet.isPrivate && cachedTweet.authorId != appUser.mid)
                return@withRetry null   // private tweet viewable only by author at profile screen.
            else
                return@withRetry cachedTweet
        }

        // author data could be null, for tweet could be provided by the others,
        // and original author has gone.
        val author = getUser(authorId)
        val hostIP = (nodeUrl ?: author?.baseUrl)?: return@withRetry null

        // appUser is passed to sever, to check if the current user has liked or bookmarked.
        val url = "$hostIP/entry?aid=$appId&ver=last&entry=get_tweet" +
            "&tweetid=$tweetId&userid=${appUser.mid}"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                val tweet = gson.fromJson(response.bodyAsText(), Tweet::class.java)
                if (tweet != null) {
                    tweet.author = author
                    return@withRetry tweet
                } else {
                    if (nodeUrl == null) {
                        // most likely the author cannot provide tweet data.
                        // Try to load the tweet some somewhere else, by tweetId alone.
                        getProviders(tweetId)?.let { ipList ->
                            getAccessibleTweet(ipList, tweetId, authorId)?.let { tweet ->
                                tweet.author = author
                                Timber.tag("getTweet").d("By tweetId alone. $tweet")
                                return@withRetry tweet
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("getTweet").e("$tweetId $authorId $url $nodeUrl $e")
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
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                Gson().fromJson(response.bodyAsText(), Tweet::class.java)?.let { tweet ->
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
        startTimestamp: Long,
        sinceTimestamp: Long, // earlier in time, therefore smaller timestamp
    ): List<Tweet> {
        try {
            return dao.getCachedTweets(startTimestamp, sinceTimestamp).map {
                // cached tweet is full object with everything.
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
     * @param flag to indicate increase or decrease retweet count.
     * @return updated original tweet.
     * */
    suspend fun updateRetweetCount(
        tweet: Tweet,
        retweetId: MimeiId,
        flag: Int = 1
    ): Tweet? { return withRetry {
        val entry = if (flag == 1) "retweet_added" else "retweet_removed"
        val url = "${tweet.author?.baseUrl}/entry?aid=$appId&ver=last&entry=$entry" +
                "&tweetid=${tweet.mid}&userid=${appUser.mid}&retweetid=$retweetId" +
                "&authorid=${tweet.authorId}"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                return@withRetry Gson().fromJson(response.bodyAsText(), Tweet::class.java)
            }
        } catch (e: Exception) {
            Timber.tag("updateRetweetCount()").e("$e $url")
        }
        null
    } }

    // Store an object in a Mimei file and return its MimeiId.
    suspend fun uploadTweet(tweet: Tweet): Tweet? { return withRetry {
        val method = "add_tweet"
        val json = URLEncoder.encode(Json.encodeToString(tweet), "utf-8")
        val url = "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$method" +
                "&tweet=$json&hostid=${appUser.hostIds?.first()}"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                tweet.mid = response.bodyAsText()
                tweet.author = appUser
                return@withRetry tweet
            }
        } catch (e: Exception) {
            Timber.tag("uploadTweet").e("$e $url $tweet $appUser")
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
    suspend fun toggleFollowing(followedId: MimeiId, followingId: MimeiId = appUser.mid): Boolean? { return withRetry {
        val followedUser = getUser(followedId)
        val entry = "toggle_following"
        val url = "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$entry" +
                "&userid=$followingId&otherid=$followedId" +
                "&otherhostid=${followedUser?.hostIds?.first()}"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val isFollowing = Gson().fromJson(response.bodyAsText(), Boolean::class.java)
                return@withRetry isFollowing    // following status after toggle
            }
        } catch (e: Exception) {
            Timber.tag("toggleFollowing()").e("$url $e")
        }
        null
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

    suspend fun updateFavoriteOfUser(
        tweetId: MimeiId,
        isFavorite: Boolean = false
    ): User { return withRetry {
        val entry = "toggle_favorite_by_user"
        val url = """
            ${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$entry
            &tweetid=$tweetId&userid=${appUser.mid}&isfavorite=$isFavorite
        """.trimIndent()
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                return@withRetry Gson().fromJson(response.bodyAsText(), User::class.java)
            }
        } catch (e: Exception) {
            Timber.tag("updateFavoriteOfUser()").e(e, "${e.message} $url")
        }
        appUser
    } }

    suspend fun updateBookmarkOfUser(
        tweetId: MimeiId,
        isBookmarked: Boolean = false
    ): User { return withRetry {
        val entry = "toggle_bookmark_by_user"
        val url = """
            ${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$entry
            &tweetid=$tweetId&userid=${appUser.mid}&isbookmarked=$isBookmarked
        """.trimIndent()
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                return@withRetry Gson().fromJson(response.bodyAsText(), User::class.java)
            }
        } catch (e: Exception) {
            Timber.tag("updateBookmarkOfUser()").e(e, "${e.message} $url")
        }
        appUser
    } }

    suspend fun toggleFavorite(tweet: Tweet): Tweet { return withRetry {
        val entry = "toggle_favorite"
        val url = """
            ${tweet.author?.baseUrl}/entry?aid=$appId&ver=last&entry=$entry
            &tweetid=${tweet.mid}&authorid=${tweet.authorId}&userid=${appUser.mid}
            &userhostid=${appUser.hostIds?.first()}
        """.trimIndent()
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                val res = gson.fromJson(
                    response.bodyAsText(),
                    object : TypeToken<Map<String, Any?>>() {}.type
                ) as Map<String, Any>
                val isFavorite = res["isFavorite"] as Boolean
                tweet.favorites?.set(UserFavorites.LIKE_TWEET, isFavorite)
                val ret = tweet.copy(
                    favoriteCount = (res["count"] as Double).toInt()
                )
                val user = gson.fromJson(gson.toJsonTree(res["user"]), User::class.java)
                appUser = appUser.copy(favoritesCount = user.favoritesCount)
                updateCachedTweet(tweet)
                return@withRetry ret
            }
        } catch (e: Exception) {
            Timber.tag("toggleFavorite").e(e, "Error: ${e.message} $tweet $url")
        }
        tweet
    } }

    suspend fun toggleBookmark(tweet: Tweet): Tweet { return withRetry {
        val entry = "toggle_bookmark"
        val url = """
            ${tweet.author?.baseUrl}/entry?aid=$appId&ver=last&entry=$entry
            &tweetid=${tweet.mid}&authorid=${tweet.authorId}&userid=${appUser.mid}
            &userhostid=${appUser.hostIds?.first()}
        """.trimIndent()
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                val res = gson.fromJson( response.bodyAsText(),
                    object : TypeToken<Map<String, Any?>>() {}.type
                ) as Map<String, Any>

                val hasBookmarked = res["hasBookmarked"] as Boolean
                tweet.favorites?.set(UserFavorites.BOOKMARK, hasBookmarked)
                val ret = tweet.copy(
                    bookmarkCount = (res["count"] as Double).toInt()
                )
                val user = gson.fromJson(gson.toJsonTree(res["user"]), User::class.java)
                appUser = appUser.copy(bookmarksCount = user.bookmarksCount)
                updateCachedTweet(tweet)
                return@withRetry ret
            }
        } catch (e: Exception) {
            Timber.tag("toggleBookmark()").e(e, "Error: ${e.message} $tweet $url")
        }
        tweet
    } }

    suspend fun getSortedMetaByUser(
        user: User,
        type: String
    ):List<MimeiId>? { return withRetry {
        val entry = "get_user_meta"
        val url = "${user.baseUrl}/entry?aid=$appId&ver=last&entry=$entry" +
                "&userid=${user.mid}&type=$type"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val res = Gson().fromJson(
                    response.bodyAsText(),
                    object : TypeToken<List<Map<*,*>>>() {}.type) as List<Map<*,*>>
                return@withRetry res.sortedByDescending { it["Value"] as Double }
                    .map { it["Field"] as MimeiId }
            }
        } catch (e: Exception) {
            Timber.tag("updateUserMeta").e("${e.message} $url")
        }
        null
    } }

    /**
     * Load all comments of a tweet.
     * @param pageNumber
     * */
    suspend fun getComments(tweet: Tweet, pageNumber: Int = 0): List<Tweet>? { return withRetry {
        try {
            if (tweet.author == null)
                tweet.author = getUser(tweet.authorId)  // for the case of deep link
            val pageSize = 50
            val method = "get_comments"
            val url = StringBuilder("${tweet.author?.baseUrl}/entry?aid=$appId&ver=last")
                .append("&entry=$method&tweetid=${tweet.mid}&userid=${appUser.mid}")
                .append("&pn=$pageNumber&ps=$pageSize").toString()
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                return@withRetry gson.fromJson(
                    response.bodyAsText(),
                    object : TypeToken<List<Tweet>>() {}.type) as List<Tweet>?
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
                val user = gson.fromJson(
                    response.bodyAsText(),
                    User::class.java)?: return@withRetry null
                user.baseUrl = "http://$ip"
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
    suspend fun getTopList(user: User): List<Map<*,*>>? { return withRetry {
        val entry = "get_top_tweets"
        val url =
            "${user.baseUrl}/entry?aid=$appId&ver=last&entry=$entry&userid=${user.mid}"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                return@withRetry gson.fromJson(response.bodyAsText(),
                    object : TypeToken<List<Map<*,*>>>() {}.type) as List<Map<*,*>>?
            }
        } catch (e: Exception) {
            Timber.tag("getTopList").e("$e")
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
                        request["fsid"] = hproseClient.runMApp(
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
    }
} }

interface ScorePair {
    val score: Long
    val member: String
}

interface HproseService {
    fun<T> runMApp(entry: String, request: Map<*, *>, args: List<ByteArray?> = emptyList()): T?
    fun getVarByContext(sid: String, context: String, mapOpt: Map<String, String>? = null): String
    fun login(ppt: String): Map<String, String>
    fun getVar(sid: String, name: String, arg1: String? = null, arg2: String? = null): String
    fun mmCreate(
        sid: String,
        appId: String,
        ext: String,
        mark: String,
        tp: Byte,
        right: Long
    ): MimeiId

    fun mmOpen(sid: String, mid: MimeiId, version: String): String
    fun mmBackup(
        sid: String,
        mid: MimeiId,
        memo: String = "",
        ref: String = ""
    ) // Add default value for 'ref'

    fun mmAddRef(sid: String, mid: MimeiId, mimeiId: MimeiId)
    fun mmSetObject(fsid: String, obj: Any)
    fun mimeiPublish(sid: String, memo: String, mid: MimeiId)
    fun mfOpenTempFile(sid: String): String
    fun mfTemp2Ipfs(fsid: String, ref: MimeiId? = null): MimeiId
    fun mfSetCid(sid: String, mid: MimeiId, cid: MimeiId)
    fun mfSetData(fsid: String, data: ByteArray, offset: Long)
    fun set(sid: String, key: String, value: Any)
    fun get(sid: String, key: String): Any?
    fun hGet(sid: String, key: String, field: String): Any?
    fun hSet(sid: String, key: String, field: String, value: Any)
    fun hDel(sid: String, key: String, field: String)
    fun zAdd(sid: String, key: String, sp: ScorePair)
    fun zRevRange(sid: String, key: String, start: Long, end: Long): List<*>
}