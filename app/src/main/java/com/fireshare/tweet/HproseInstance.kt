package com.fireshare.tweet

import android.content.Context
import android.net.Uri
import com.fireshare.tweet.datamodel.CachedTweet
import com.fireshare.tweet.datamodel.ChatDatabase
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.MimeiFileType
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.TweetCacheDatabase
import com.fireshare.tweet.datamodel.TweetMidList
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.datamodel.UserFavorites
import com.fireshare.tweet.datamodel.writableUrl
import com.fireshare.tweet.datamodel.writableUrl2
import com.fireshare.tweet.widget.Gadget.getAccessibleIP
import com.fireshare.tweet.widget.Gadget.getAccessibleUser
import com.fireshare.tweet.widget.Gadget.getIpAddresses
import com.fireshare.tweet.widget.Gadget.splitJson
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
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.regex.Pattern

// Encapsulate Hprose client and related operations in a singleton object.
object HproseInstance {

    private lateinit var preferenceHelper: PreferenceHelper
    lateinit var appUser: User    // current user object
    private var appId: MimeiId = BuildConfig.APP_ID     // placeholder, overwritten later

    // get the first user account, or a list of accounts.
    fun getAlphaIds(): List<MimeiId> {
        return BuildConfig.ALPHA_ID.split(",").map { it.trim() }
    }
    // A in-memory cache of users.
    private var cachedUsers: MutableSet<User> = emptySet<User>().toMutableSet()
    private lateinit var chatDatabase: ChatDatabase
    lateinit var tweetCache: TweetCacheDatabase
    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000 // Total request timeout
            connectTimeoutMillis = 30_000  // Connection timeout
            socketTimeoutMillis = 60_000  // Socket timeout
        }
    }

    suspend fun init(context: Context, preferenceHelper: PreferenceHelper) {
        this.preferenceHelper = preferenceHelper
        chatDatabase = ChatDatabase.getInstance(context.applicationContext)
        tweetCache = TweetCacheDatabase.getInstance(context.applicationContext)

        initAppEntry()
    }

    /**
     * App_Url is the network entrance of the App. Use it to initiate appId, and BASE_URL.
     * */
    private suspend fun initAppEntry() {
        // make sure no stale data during retry init.
        cachedUsers.clear()
        val baseUrl = "http://" + preferenceHelper.getAppUrl()!!  // there is a default value
        try {
            val response: HttpResponse = httpClient.get(baseUrl)
            /**
             * retrieve window.Param from page source code of http://base_url
             * window.setParam({
             *         CurNode:0,
             *         log: true,
             *         ver:"last",
             *         addrs: [[["183.159.17.7:8081", 3.080655111],["[240e:391:e00:169:1458:aa58:c381:5c85]:8081", 3.9642842857833],["192.168.0.94:8081", 281478208946270]]],
             *         aid: "",
             *         remote:"::1",
             *         mid:"d4lRyhABgqOnqY4bURSm_T-4FZ4"
             * })]
             * */
            val htmlContent = response.bodyAsText().trimIndent()
            val pattern = Pattern.compile("window\\.setParam\\((\\{.*?\\})\\)", Pattern.DOTALL)
            val matcher = pattern.matcher(htmlContent as CharSequence)
            if (matcher.find()) {
                matcher.group(1)?.let {
                    val paramMap = Gson().fromJson(it, Map::class.java) as Map<*, *>
                    appId = paramMap["mid"].toString()

                    /**
                     * The code above makes a call to base URL of the app, get a html page
                     * and tries to extract appId and host IP addresses from source code.
                     * */
                    Timber.tag("initAppEntry").d("$paramMap")
                    val hostIPs = getIpAddresses(paramMap["addrs"] as ArrayList<*>)

                    /**
                     * addrs is an ArrayList of ArrayList of node's IP address pairs.
                     * Each pair is an ArrayList of two elements. The first is the IP address,
                     * and the second is the time spent to get response from the IP.
                     *
                     * hostIPs is a list of node's IP that is a Mimei provider for this App.
                     */
                    val firstIp = getAccessibleIP(hostIPs)
                    appUser = User(mid = TW_CONST.GUEST_ID, baseUrl = "http://$firstIp")
                    val userId = preferenceHelper.getUserId()
                    if (userId.isNotEmpty() && userId != TW_CONST.GUEST_ID) {
                        /**
                         * If there is a valid userId in preference, this is a login user.
                         * Initiate current account. Get its IP list and choose the best one,
                         * and assign it to appUser.baseUrl.
                         * */
                        getProviders(userId, "http://$firstIp")?.let { ips ->
                            appUser = getAccessibleUser(ips, userId) ?: appUser
                            cachedUsers.add(appUser)
                            Timber.tag("initAppEntry").d("User inited. $appId, $appUser")
                        }
                    } else {
                        appUser.followingList = getAlphaIds()
                        cachedUsers.add(appUser)
                        Timber.tag("initAppEntry").d("Guest user inited. $appId, $appUser")
                    }
                }
            } else {
                Timber.tag("initAppEntry").e("No data found within window.setParam()")
                appUser = User(mid = TW_CONST.GUEST_ID, baseUrl = baseUrl)
            }
        } catch (e: Exception) {
            Timber.tag("initAppEntry").e(e.toString())
        }
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var retryCount = 0
        while (retryCount < 2) { // Retry up to 2 times
            try {
                return block() // Return the result of the block
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
            "${appUser.writableUrl()}/entry?aid=$appId&ver=last&entry=$entry&userid=${appUser.mid}" +
                    "&receiptid=$receiptId&msg=$encodedMsg"
        // write outgoing message to user's Mimei db
        try {
            val response: HttpResponse = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                // write message to receipt's Mimei db on the receipt's node
                val receipt = getUser(receiptId)
                entry = "message_incoming"
                url = "${receipt?.writableUrl()}/entry?aid=$appId&ver=last&entry=$entry" +
                            "&senderid=${appUser.mid}&receiptid=$receiptId&msg=${
                                Json.encodeToString(msg)
                            }"
                httpClient.get(url)
            }
        } catch (e: Exception) {
            Timber.tag("sendMessage").e(e.toString())
        }
    } }

    // get the recent unread message from a sender.
    suspend fun fetchMessages(senderId: MimeiId, numOfMsgs: Int = 50): List<ChatMessage>? { return withRetry {
        try {
            val gson = Gson()
            val entry = "message_fetch"
            val url = "${appUser.writableUrl()}/entry?aid=$appId&ver=last&entry=$entry" +
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
        if (appUser.mid == TW_CONST.GUEST_ID) return@withRetry null
        try {
            val gson = Gson()
            val url =
                "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=message_check&userid=${appUser.mid}"
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
     * There are two steps for a guest user to login.
     * First, find UserID given username.
     * Second, find the node which has this user's data, and logon to that node.
     * Finally update the baseUrl of the current user with the new ip of the user's node.
     * */
    suspend fun login(username: String, password: String, context: Context): Pair<User?, String?> { return withRetry {
        try {
            val reason = Pair(null, context.getString(R.string.login_error))
            val userId = getUserId(username) ?: return@withRetry reason
            val user = getUser(userId) ?: return@withRetry Pair(null, context.getString(R.string.login_failed))
            val url =
                "${user.baseUrl}/entry?aid=$appId&ver=last&entry=login&username=$username&password=$password"
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val ret = Gson().fromJson(response.bodyAsText(), Map::class.java)
                if (ret != null) {
                    if (ret["status"] == "success") {
                        return@withRetry Pair(user, null)
                    } else reason
                } else reason
            } else reason
        } catch (e: Exception) {
            Timber.tag("Hprose.Login").e("${e.message}")
            return@withRetry Pair(null, context.getString(R.string.login_failed))
        }
    } }

    suspend fun getHostId(): MimeiId? { return withRetry {
        val url = "${appUser.baseUrl}/getvar?name=hostid"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                return@withRetry response.bodyAsText().trim().trim('"').trim(',')
            }
        } catch (e: Exception) {
            Timber.tag("getHostId").e(e.toString())
        }
        null
    } }

    suspend fun getHostIP(nodeId: MimeiId): String?  { return withRetry {
        val url = "${appUser.baseUrl}/getvar?name=ips&arg0=$nodeId"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                response.bodyAsText().trim().trim('"').trim(',')
                    .split(',').let { ips ->
                    if (ips.isNotEmpty()) {
                        val accessibleIp = getAccessibleIP(ips)
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
     * Register or update user data.
     * */
    suspend fun setUserData(userObj: User): Map<*, *>? { return withRetry {
        val url: String
        val user = userObj.copy(fansList = null, followingList = null)
        if (user.mid == TW_CONST.GUEST_ID) {
            // register a new User account, with default followings.
            user.followingList = getAlphaIds()
            url =
                "${user.writableUrl()}/entry?aid=$appId&ver=last&entry=register&user=${
                    Json.encodeToString(user)
                }"
        } else {
            // update existing account
            val method = "set_author_core_data"
            url = "${user.writableUrl()}/entry?aid=$appId&ver=last&entry=$method&user=${
                Json.encodeToString(user)
            }"
        }
        try {
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

    suspend fun setUserAvatar(userId: MimeiId, avatar: MimeiId) {
        return withRetry {
            val entry = "set_user_avatar"
            val json = """
            {"aid": $appId, "ver": "last", "userid": $userId, "avatar": $avatar}
        """.trimIndent()
            val gson = Gson()
            val request = gson.fromJson(json, Map::class.java)
            try {
                val hproseClient =
                    HproseClient.create("${appUser.writableUrl()}/webapi/").useService(HproseService::class.java)
                hproseClient.runMApp(entry, request) as Unit?
            } catch (e: Exception) {
                Timber.tag("setUserAvatar").e(e.toString())
            }
        }
    }

    // get Ids of users who the current user is following
    suspend fun getFollowings(user: User): List<MimeiId>? { return withRetry {
        try {
            if (user.mid != TW_CONST.GUEST_ID) {
                val method = "get_followings"
                val url =
                    "${user.baseUrl}/entry?aid=$appId&ver=last&entry=$method&userid=${user.mid}"
                val response = httpClient.get(url)
                if (response.status == HttpStatusCode.OK) {
                    val gson = Gson()
                    user.followingList =
                        gson.fromJson(response.bodyAsText(), object : TypeToken<List<MimeiId>>() {}.type)
                    return@withRetry user.followingList
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("Hprose.getFollowings").e(e.toString())
        }
        null
    } }

    // get fans list of the user
    suspend fun getFans(user: User): List<MimeiId>? { return withRetry {
        try {
            if (user.mid != TW_CONST.GUEST_ID) {
                val method = "get_followers"
                val url =
                    "${user.baseUrl}/entry?aid=$appId&ver=last&entry=$method&userid=${user.mid}"
                val response = httpClient.get(url)
                if (response.status == HttpStatusCode.OK) {
                    val gson = Gson()
                    user.fansList =
                        gson.fromJson(response.bodyAsText(), object : TypeToken<List<MimeiId>>() {}.type)
                    return@withRetry user.fansList
                }
            }
        } catch (e: Exception) {
            Timber.tag("HproseInstance.getFollowings").e(e.toString())
        }
        null
    } }

    /**
     * Get tweets of a given author in a given span of time. if end is null, get all tweets.
     * Update tweets state flow directly.
     * */
    fun getTweetList(
        user: User,
        tweets: List<Tweet>,
        startTimestamp: Long,
        endTimestamp: Long?
    ): Flow<List<Tweet>> = channelFlow {
        try {
            // 1. Retrieve cached tweet mid list for this user.
            tweetCache.tweetDao().getCachedTweetMidList(user.mid)?.let {
                splitJson(it)?.mapNotNull { mid ->
                    retrieveCachedTweet(mid)
                }?.let { it1 -> send(it1) }
            }

            // 3. Make network call to get mid list from server
            val method = "get_tweet_list"
            val url = "${user.baseUrl}/entry?aid=$appId&ver=last&entry=$method" +
                "&userid=${user.mid}&start=$startTimestamp&end=$endTimestamp"
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val midList = Gson().fromJson(
                    response.bodyAsText(),
                    object : TypeToken<List<MimeiId>?>() {}.type
                ) as List<MimeiId>?

                // 4. Overwrite cached mid list of the user with a updated list
                midList?.let {
                    tweetCache.tweetDao().insertOrUpdateTweetMidList(TweetMidList(user.mid, it))
                }

                // 5. Retrieve any tweets not in the cached list and add them to tweets list.
                midList?.filterNot { mid->
                    tweets.any { it.mid == mid }
                }?.mapNotNull { unCachedTweetId ->
                    getTweet(unCachedTweetId, user.mid)?.also { tweet ->
                        if (tweet.originalTweetId != null) {
                            tweet.originalTweet = getTweet(tweet.originalTweetId!!, tweet.originalAuthorId!!)
                                ?: return@mapNotNull null
                        }
                    }
                }?.also { unCachedTweets->
                    send(unCachedTweets)
                }
            }
        } catch (e: Exception) {
            Timber.tag("getTweetList").e(e.toString())
        }
    }

    /**
     * Get only layer one data of the tweet. Do Not fetch its original tweet if there is any.
     * Let the caller to decide if go further on the tweet hierarchy.
     * */
    suspend fun getTweet(
        tweetId: MimeiId,
        authorId: MimeiId
    ): Tweet? { return withRetry {
        try {
            // if there is a cached tweet, return it.
            val cachedTweet = retrieveCachedTweet(tweetId)
            if (cachedTweet != null) {
                if (cachedTweet.isPrivate && cachedTweet.authorId != appUser.mid)
                    return@withRetry null   // private tweet viewable only by author at profile screen.
                else
                    return@withRetry cachedTweet
            }

            val author =
                getUser(authorId) ?: return@withRetry null   // cannot get author data, return null
            val method = "get_tweet"
            val url = StringBuilder("${author.baseUrl}/entry?aid=$appId&ver=last&entry=$method")
                .append("&tweetid=$tweetId")
                // appUser is passed to sever, to check if the current user has liked or bookmarked.
                .append("&userid=${appUser.mid}").toString()
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                gson.fromJson(response.bodyAsText(), Tweet::class.java)?.let { tweet ->
                    tweet.author = author
                    /**
                     * Insert the tweet into the cache database.
                     * */
                    tweetCache.tweetDao().insertOrUpdateCachedTweet(
                        CachedTweet(tweet.mid, gson.toJson(tweet))
                    )
                    Timber.tag("getTweet").d("$tweet")
                    return@withRetry tweet
                }
            }
        } catch (e: Exception) {
            Timber.tag("getTweet").e("$tweetId $authorId $e")
        }
        null
    }}

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
            val url = "${author.baseUrl}/entry?aid=$appId&ver=last&entry=get_tweet" +
                "&tweetid=$tweetId&userid=${appUser.mid}"
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                gson.fromJson(response.bodyAsText(), Tweet::class.java)?.let { tweet ->
                    tweet.author = author
                    /**
                     * update the tweet in the cache database.
                     * */
                    tweetCache.tweetDao().updateCachedTweet(
                        CachedTweet(tweet.mid, gson.toJson(tweet))
                    )
                    Timber.tag("refreshTweet").d("$tweet")
                    return@withRetry tweet
                }
            }
        } catch (e: Exception) {
            Timber.tag("refreshTweet").e("$tweetId $authorId $e")
        }
        // if cannot get tweet from node, delete it from cache.
        tweetCache.tweetDao().deleteCachedTweetAndRemoveFromMidList(tweetId, authorId)
        null
    }}

    /**
     * Retrieve cached tweet from Mimei DB. User info is not cached in Room,
     * which changes frequently, so user data need to be loaded alive every time.
     * */
    private suspend fun retrieveCachedTweet(tweetId: MimeiId): Tweet? {
        val cachedTweet = tweetCache.tweetDao().getCachedTweet(tweetId) ?: return null
        val tweet = Gson().fromJson(cachedTweet.originalTweetJson, Tweet::class.java)
        if (tweet.originalTweetId != null) {
            tweet.originalTweet =
                getTweet(tweet.originalTweetId!!, tweet.originalAuthorId!!) ?: return null
        }
        Timber.tag("retrieveCachedTweet").d("$tweet")
        tweet.author = getUser(tweet.authorId)
        if (tweet.originalTweetId != null) {
            tweet.originalTweet =
                getTweet(tweet.originalTweetId!!, tweet.originalAuthorId!!)
        }
        return tweet
    }

    /**
     * Increase the retweetCount of the original tweet mimei.
     * @return updated original tweet.
     * */
    suspend fun increaseRetweetCount(tweet: Tweet, retweetId: MimeiId): Tweet? { return withRetry {
        val method = "retweet_add"
        val url = "${tweet.author?.writableUrl()}/entry?aid=$appId&ver=last&entry=$method" +
                "&tweetid=${tweet.mid}&userid=${appUser.mid}&retweetid=$retweetId"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                return@withRetry Gson().fromJson(response.bodyAsText(), Tweet::class.java)
            }
        } catch (e: Exception) {
            Timber.tag("increaseRetweetCount()").e("$e $url")
        }
        null
    } }

    // Store an object in a Mimei file and return its MimeiId.
    suspend fun uploadTweet(tweet: Tweet): Tweet? { return withRetry {
        val method = "upload_tweet"
        val json = URLEncoder.encode(Json.encodeToString(tweet), "utf-8")
        val url = "${appUser.writableUrl()}/entry?aid=$appId&ver=last&entry=$method&tweet=$json"
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
     * Delete a tweet. If it has original tweet, decrease its retweet count.
     * Callback() update the in-memory original tweet.
     * */
    suspend fun delTweet(tweet: Tweet, callback: (MimeiId) -> Unit) { return withRetry {
        tweetCache.tweetDao().deleteCachedTweetAndRemoveFromMidList(tweet.mid)

        var method = "delete_tweet"
        var url = "${appUser.writableUrl()}/entry?aid=$appId&ver=last&entry=$method" +
                    "&tweetid=${tweet.mid}&authorid=${appUser.mid}"
        try {
            var response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                // if there is an originalTweet, also decrease its retweet count
                if (tweet.originalTweetId != null) {
                    method = "retweet_remove"
                    url = "${tweet.originalTweet!!.author?.writableUrl()}/entry?aid=$appId&ver=last" +
                            "&entry=$method&tweetid=${tweet.originalTweetId}&userid=${appUser.mid}"
                    response = httpClient.get(url)
                    if (response.status == HttpStatusCode.OK) {
                        callback(tweet.mid)
                    }
                } else
                    callback(tweet.mid)
            }
        } catch (e: Exception) {
            Timber.tag("delTweet()").e("$e $appUser $tweet $url")
        }
    } }

    suspend fun delComment(parentTweet: Tweet, commentId: MimeiId, delComment: (MimeiId) -> Unit) { return withRetry {
        val method = "delete_comment"
        val url =
            "${parentTweet.author?.writableUrl()}/entry?aid=$appId&ver=last&entry=$method" +
                    "&tweetid=${parentTweet.mid}&commentid=$commentId"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                delComment(commentId)
            }
        } catch (e: Exception) {
            Timber.tag("delComment()").e(e.toString())
        }
    } }

    /**
     * @param userId is the user that appUser is following or unfollowing.
     * */
    suspend fun toggleFollowing(userId: MimeiId, appUserId: MimeiId = appUser.mid): Boolean? { return withRetry {
        val method = "toggle_following"
        val url =
            "${appUser.writableUrl()}/entry?aid=$appId&ver=last&entry=$method" +
                    "&userid=$appUserId&otherid=${userId}"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val isFollowing = Gson().fromJson(response.bodyAsText(), Boolean::class.java)
                getUser(userId)?.let { user ->
                    if (isFollowing)
                        provide(user)
                    else
                        unprovide(user)
                }
                return@withRetry isFollowing
            }
        } catch (e: Exception) {
            Timber.tag("toggleFollowing()").e(e.toString())
        }
        null
    } }

    /**
     * @param isFollowing indicates if the appUser is following this userId. Passing an argument
     * instead of toggling the status of a follower because this way will not introduce a
     * persistent inconsistency when something went wrong, which happens easily with the toggle method.
     * */
    suspend fun toggleFollower(
        userId: MimeiId,
        isFollowing: Boolean,
        followerId: MimeiId = appUser.mid )
    { return withRetry {
        val user = getUser(userId)
        val method = "toggle_follower"
        val url = "${user?.writableUrl()}/entry?aid=$appId&ver=last&entry=$method" +
                    "&otherid=$followerId&userid=${userId}&isfollower=$isFollowing"
        try {
            httpClient.get(url)
        } catch (e: Exception) {
            Timber.tag("toggleFollower()").e(e.toString())
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
                mid = System.currentTimeMillis().toString(),    // placeholder
                content = "",
                authorId = appUser.mid,
                originalTweetId = tweet.mid,
                originalAuthorId = tweet.authorId
            )) ?: return@withRetry
            retweet.originalTweet = tweet
            addTweetToFeed(retweet)

            increaseRetweetCount(tweet, retweet.mid)?.let { updatedTweet ->
                // update cached tweet in the database.
                tweetCache.tweetDao().updateCachedTweet(
                    CachedTweet(tweet.mid, Gson().toJson(updatedTweet))
                )
            }
            // become a provider for the original tweet
            provide(tweet.author!!, tweet.mid)
        } catch (e: Exception) {
            Timber.e("toggleRetweet()", e.toString())
        }
    } }

    private suspend fun provide(user: User, tweetId: MimeiId? = null) { return withRetry {
        val url = "${appUser.writableUrl()}/entry?aid=$appId&ver=last&entry=mimei_provide" +
                "&userid=${user.mid}&tweetid=$tweetId&nodeid=${user.hostIds?.get(0)}"
        try {
            httpClient.get(url)
        } catch (e: Exception) {
            Timber.tag("provide()").e("$e $url")
        }
    } }

    private suspend fun unprovide(user: User, tweetId: MimeiId? = null) { return withRetry {
        val url = "${appUser.writableUrl()}/entry?aid=$appId&ver=last&entry=mimei_unprovide" +
                "&userid=${user.mid}&tweetid=$tweetId&nodeid=${user.hostIds?.get(0)}"
        try {
            httpClient.get(url)
        } catch (e: Exception) {
            Timber.tag("provide()").e("$e $url")
        }
    } }

    suspend fun likeTweet(tweet: Tweet): Tweet { return withRetry {
        val method = "toggle_likes"
        val url = "${tweet.author?.writableUrl()}/entry?aid=$appId&ver=last" +
                "&entry=$method&tweetid=${tweet.mid}&userid=${appUser.mid}"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                val res = gson.fromJson(
                    response.bodyAsText(),
                    object : TypeToken<Map<String, Any?>>() {}.type
                ) as Map<String, Any?>
                val hasLiked = res["hasLiked"] as Boolean
                tweet.favorites?.set(UserFavorites.LIKE_TWEET, hasLiked)
                val ret = tweet.copy(
                    likeCount = (res["count"] as Double).toInt()
                )
                // update cached tweet
                tweetCache.tweetDao().updateCachedTweet(
                    CachedTweet(tweet.mid, gson.toJson(ret))
                )
                // become a provider of the tweet if like it.
                if (hasLiked)
                    provide(tweet.author!!, tweet.mid)
                else
                    tweet.author?.let { unprovide(it, tweet.mid) }
                return@withRetry ret
            }
        } catch (e: Exception) {
            Timber.tag("likeTweet()").e(e, "Error: ${e.message} $tweet $url")
        }
        tweet
    } }

    suspend fun bookmarkTweet(tweet: Tweet): Tweet { return withRetry {
        val method = "toggle_bookmark"
        val url = "${tweet.author?.writableUrl()}/entry?aid=$appId&ver=last" +
                "&entry=$method&tweetid=${tweet.mid}&userid=${appUser.mid}"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                val res = gson.fromJson(
                    response.bodyAsText(),
                    object : TypeToken<Map<String, Any?>>() {}.type
                ) as Map<String, Any?>
                val hasBookmarked = res["hasBookmarked"] as Boolean
                tweet.favorites?.set(UserFavorites.BOOKMARK, hasBookmarked)
                val ret = tweet.copy(
                    bookmarkCount = (res["count"] as Double).toInt()
                )
                tweetCache.tweetDao().updateCachedTweet(
                    CachedTweet(tweet.mid, gson.toJson(ret))
                )
                // become a provider of the tweet if like it.
                if (hasBookmarked)
                    provide(tweet.author!!, tweet.mid)
                else
                    tweet.author?.let { unprovide(it, tweet.mid) }
                return@withRetry ret
            }
        } catch (e: Exception) {
            Timber.tag("bookmarkTweet()").e(e, "Error: ${e.message} $tweet $url")
        }
        tweet
    } }

    /**
     * Load all comments on a tweet.
     * @param pageNumber
     * */
    suspend fun getComments(tweet: Tweet, pageNumber: Int = 0): List<Tweet>? { return withRetry {
        try {
            if (tweet.author == null)
                tweet.author = getUser(tweet.authorId)  // deep link
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
     * The mid of "comment" tweet is updated here. Return the updated parent tweet.
     * */
    suspend fun uploadComment(tweet: Tweet, comment: Tweet): Tweet { return withRetry {
        val method = "add_comment"
        val json = URLEncoder.encode(Json.encodeToString(comment), "utf-8")
        val url =
            "${tweet.author?.writableUrl()}/entry?aid=$appId&ver=last&entry=$method" +
                    "&tweetid=${tweet.mid}&comment=$json"
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
                tweetCache.tweetDao().updateCachedTweet(
                    CachedTweet(updatedTweet.mid, gson.toJson(updatedTweet))
                )
                provide(tweet.author!!, tweet.mid)
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
     * Get baseUrl where user data can be accessed. Each user may has a different node.
     * Therefore it is indispensable to acquire base url for each user.
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
                        val hostIPs = getIpAddresses(paramMap["addrs"] as ArrayList<*>)
                        getAccessibleUser(hostIPs, userId)?.let { user ->
                            cachedUsers.add(user)
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

    suspend fun getUserData(mid: MimeiId, ip: String): User? { return withRetry {
        try {
            val entry = "get_user_core_data"
            val url =
                "http://$ip/entry?aid=$appId&ver=last&entry=$entry&userid=$mid"
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
//            Timber.tag("getUserData").e(e, "Network error while retrieving user data")
        } catch (e: JsonSyntaxException) {
            Timber.tag("getUserData").e(e, "Error parsing user data from JSON")
        } catch (e: Exception) {
//            Timber.tag("getUserData").e(e, "Error retrieving user data")
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

    private suspend fun getProviders(mid: MimeiId, baseUrl: String? = appUser.baseUrl): List<String>? { return withRetry {
        val entry = "get_providers"
        val url =  "$baseUrl/entry?aid=$appId&ver=last&entry=$entry&mid=$mid"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                val ips = gson.fromJson<List<String>>(response.bodyAsText(),
                    object : TypeToken<List<String>>() {}.type)
                return@withRetry ips.toSet().toList()
            }
        } catch (e: Exception) {
            Timber.tag("getProviders").e("$e")
        }
        null
    } }

    /**
     * Return the current tweet list that is pinned to top.
     * */
    suspend fun toggleTopList(tweetId: MimeiId): List<Map<*,*>>? { return withRetry {
        val entry = "toggle_top_tweets"
        val url =  "${appUser.writableUrl()}/entry?aid=$appId&ver=last&entry=$entry" +
                "&userid=${appUser.mid}&tweetid=$tweetId"
        try {
            val response = httpClient.get(url)
            if (response.status == HttpStatusCode.OK) {
                val gson = Gson()
                return@withRetry gson.fromJson(response.bodyAsText(),
                    object : TypeToken<List<Map<*,*>>>() {}.type) as List<Map<*,*>>?
            }
        } catch (e: Exception) {
            Timber.tag("toggleTopList").e("$e")
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
        val url =
            "${appUser.writableUrl2()}/entry?aid=$appId&ver=last&entry=logging&msg=${
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
    suspend fun uploadToIPFS(context: Context, uri: Uri,
                             referenceId: MimeiId? = null): MimeiFileType? { return withRetry {
        val hproseClient = HproseClient.create("${appUser.writableUrl()}/webapi/")
            .useService(HproseService::class.java)
        var offset = 0L
        var byteRead: Int
        val buffer = ByteArray(TW_CONST.CHUNK_SIZE)
        val json = """{"aid": $appId, "ver": "last", "offset": 0}"""
        val request = Gson().fromJson(json, Map::class.java).toMutableMap()
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
                mimeType?.startsWith("image/") == true -> com.fireshare.tweet.widget.MediaType.Image
                mimeType?.startsWith("video/") == true -> com.fireshare.tweet.widget.MediaType.Video
                mimeType?.startsWith("audio/") == true -> com.fireshare.tweet.widget.MediaType.Audio
                mimeType == "application/pdf" -> com.fireshare.tweet.widget.MediaType.PDF
                mimeType == "application/zip" || mimeType == "application/x-zip-compressed" -> com.fireshare.tweet.widget.MediaType.Zip
                mimeType == "application/msword" || mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> com.fireshare.tweet.widget.MediaType.Word
                // ... add more mappings for other MediaType values ...
                else -> com.fireshare.tweet.widget.MediaType.Unknown
            }
            // Return MimeiFileType
            return@withRetry MimeiFileType(cid, mediaType, offset)
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