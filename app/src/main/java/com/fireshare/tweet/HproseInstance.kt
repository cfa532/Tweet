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
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.datamodel.UserFavorites
import com.fireshare.tweet.widget.Gadget.findFirstReachableAddress
import com.fireshare.tweet.widget.Gadget.getFirstReachableUser
import com.fireshare.tweet.widget.Gadget.getIpAddresses
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import hprose.client.HproseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber
import java.io.IOException
import java.net.ProtocolException
import java.net.URLEncoder
import java.util.regex.Pattern

// Encapsulate Hprose client and related operations in a singleton object.
object HproseInstance {

    private lateinit var preferenceHelper: PreferenceHelper
    var appUser: User = User(mid = TW_CONST.GUEST_ID)    // current user object
    private var appId: MimeiId =
        "d4lRyhABgqOnqY4bURSm_T-4FZ4"    // Application Mimei ID, assigned by Leither

    // get the first user account, or a list of accounts.
    fun getAlphaIds(): List<MimeiId> {
        return listOf("uTE6yhCWGLlkK6KGI9iMkOFZGGv")
    }

    // A in-memory cache of users.
    private var cachedUsers: MutableSet<User> = emptySet<User>().toMutableSet()

    private lateinit var chatDatabase: ChatDatabase
    lateinit var tweetCache: TweetCacheDatabase
    private var hproseClient: HproseService? = null

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    private val httpClient = OkHttpClient.Builder()
//        .addInterceptor(loggingInterceptor)
        .build()

    suspend fun init(context: Context, preferenceHelper: PreferenceHelper) {
        this.preferenceHelper = preferenceHelper
        initAppEntry()

//        chatDatabase = Room.databaseBuilder(
//            context.applicationContext,
//            ChatDatabase::class.java,
//            "chat_database"
//        ).build()
        chatDatabase = ChatDatabase.getInstance(context.applicationContext)
        tweetCache = TweetCacheDatabase.getInstance(context.applicationContext)
//        tweetCache.tweetDao().clearAllCachedTweets()
    }

    // Find network entrance of the App
    // Given entry URL, initiate appId, and BASE_URL.
    private suspend fun initAppEntry() {
        val baseUrl = "http://" + preferenceHelper.getAppUrl().toString()
        appUser = User(mid = TW_CONST.GUEST_ID, baseUrl = baseUrl)
        val request = Request.Builder().url(baseUrl).build()
        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
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
                val htmlContent = response.body?.string()?.trimIndent()
                val pattern = Pattern.compile("window\\.setParam\\((\\{.*?\\})\\)", Pattern.DOTALL)
                val matcher = pattern.matcher(htmlContent as CharSequence)
                if (matcher.find()) {
                    matcher.group(1)?.let {
                        val paramMap = Gson().fromJson(it, Map::class.java) as Map<*, *>
                        appId = paramMap["mid"].toString()
                        val hostIPs = getIpAddresses(paramMap["addrs"] as ArrayList<*>)
                        Timber.tag("initAppEntry").d("$paramMap $hostIPs")

                        /**
                         * addrs is an ArrayList of ArrayList of node's IP address pairs.
                         * Each pair is an ArrayList of two elements. The first is the IP address,
                         * and the second is the time spent to get response from the IP.
                         *
                         * hostIPs is a list of node's IP that is a Mimei provider for this App.
                         */
                        val userId = preferenceHelper.getUserId()
                        appUser = if (userId.isNotEmpty() && userId != TW_CONST.GUEST_ID) {
                            /**
                             * This is a login user if preference has valid userId. Initiate current account.
                             * Get its IP list and choose the best one, and assign it to appUser.baseUrl.
                             * */
                            getFirstReachableUser(hostIPs, userId) ?: User(
                                mid = TW_CONST.GUEST_ID,
                                baseUrl = "http://${hostIPs[0]}"
                            )
                        } else {
                            val firstIp = findFirstReachableAddress(hostIPs)
                            User(mid = TW_CONST.GUEST_ID, baseUrl = "http://$firstIp")
                        }
                        Timber.tag("initAppEntry").d("Init succeed. $appId, $appUser")
                    }
                } else {
                    Timber.tag("initAppEntry").e("No data found within window.setParam()")
                }
                hproseClient = HproseClient.create("${appUser.baseUrl}/webapi/")
                    .useService(HproseService::class.java)
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
        var url =
            "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$entry&userid=${appUser.mid}" +
                    "&receiptid=$receiptId&msg=${Json.encodeToString(msg)}"
        // write outgoing message to user's Mimei db
        var request = Request.Builder().url(url).build()
        try {
            var response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                // write message to receipt's Mimei db on the receipt's node
                val receipt = getUserBase(receiptId)
                entry = "message_incoming"
                url =
                    "${receipt?.baseUrl}/entry?aid=$appId&ver=last&entry=$entry" +
                            "&senderid=${appUser.mid}&receiptid=$receiptId&msg=${
                                Json.encodeToString(msg)
                            }"
                request = Request.Builder().url(url).build()
                response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    return@withRetry
                }
            }
        } catch (e: Exception) {
            Timber.tag("sendMessage").e(e.toString())
            return@withRetry
        }
    }}

    // get the recent unread message from a sender.
    suspend fun fetchMessages(senderId: MimeiId, numOfMsgs: Int = 50): List<ChatMessage>? { return withRetry {
        return@withRetry {
            val gson = Gson()
            val entry = "message_fetch"
            val json = """
            {"aid": $appId, "ver":"last", "userid":${appUser.mid}, "senderid":${senderId}}
        """.trimIndent()
            val request = gson.fromJson(json, Map::class.java) as Map<*, *>
            try {
                // write outgoing message to user's Mimei db
                hproseClient?.runMApp(entry, request) as List<ChatMessage>?
            } catch (e: Exception) {
                e.printStackTrace()
                Timber.tag("fetchMessages").e(e.toString())
                null
            }
        }.withRetry()
    }}

    // get a list of unread incoming messages from other users
    suspend fun checkNewMessages(): List<ChatMessage>? {
        return withRetry {
            if (appUser.mid == TW_CONST.GUEST_ID) return@withRetry null
            return@withRetry try {
                val gson = Gson()
                val url =
                    "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=message_check&userid=${appUser.mid}"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = response.body?.string()
                    val list = gson.fromJson(
                        json,
                        object : TypeToken<List<ChatMessage>>() {}.type
                    ) as List<ChatMessage>
                    return@withRetry list
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                Timber.tag("checkNewMessages").e(appUser.toString())
                null
            }
        }
    }

    suspend fun checkUpgrade(): Map<String, String>? {
        return withRetry {
            val gson = Gson()
            val entry = "check_upgrade"
            val json = """
             {"aid": $appId, "ver":"last"}
        """.trimIndent()
            val request = gson.fromJson(json, Map::class.java) as Map<*, *>
            return@withRetry try {
                hproseClient?.runMApp(entry, request) as Map<String, String>?
            } catch (e: Exception) {
                e.printStackTrace()
                Timber.tag("checkUpgrade").e("$e")
                null
            }
        }
    }

    /**
     * There are two steps for a guest user to login.
     * First, find the true UserID given its key phrase, using the IP address of the serving node.
     * Second, find the node which has this user's data, and use it to login.
     * Finally update the baseUrl of the current user with the new ip of the user's node.
     * */
    suspend fun login(username: String, password: String, keyPhrase: String): User? {
        return HproseInstance.withRetry {
            return@withRetry try {
                val userId = try {
                    val entry = "get_userid"
                    val url =
                        "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$entry&phrase=$keyPhrase"
                    val request = Request.Builder().url(url).build()
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        response.body?.string() ?: return@withRetry null
                    } else {
                        return@withRetry null
                    }
                } catch (e: Exception) {
                    Timber.tag("GetUserId").e("Login failed. ${e.message}")
                    return@withRetry null
                }
                val user = getUserBase(userId) ?: return@withRetry null
                val url =
                    "${user.baseUrl}/entry?aid=$appId&ver=last&entry=login&username=$username&password=$password&phrase=$keyPhrase"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = response.body?.string() ?: return@withRetry null
                    // only to verify the login succeed.
                    val gson = Gson()
                    gson.fromJson(json, User::class.java) ?: return@withRetry null
                    /**
                     * Now user object has a new baseUrl of the node which hold user data.
                     * If login succeed, httpClient need to use the new IP from now on.
                     * */
                    hproseClient =
                        HproseClient.create(user.baseUrl).useService(HproseService::class.java)
                    return@withRetry user
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                Timber.tag("Hprose.Login").e("${e.message}")
                null
            }
        }
    }

    /**
     * Get baseUrl where user data can be accessed. Each user may has a different node.
     * Therefore it is indispensable to acquire base url for each user.
     * */
    suspend fun getUserBase(userId: MimeiId, baseUrl: String? = appUser.baseUrl): User? {
        return withRetry {
            // check if user data has been cached
            cachedUsers.firstOrNull { it.mid == userId }?.let { return@withRetry it }

            try {
                val url = "$baseUrl/getvar?name=mmprovsips&arg0=$userId"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    var string = response.body?.string()?.trim()?.removeSurrounding("\"")
                        ?.replace("\\", "")
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
                            getFirstReachableUser(hostIPs, userId)?.let { user: User ->
                                cachedUsers.add(user)
                                return@withRetry user
                            }
                        }
                    }
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                Timber.tag("getUserBase()").e("${appUser.baseUrl} $userId $e")
                null
            }
        }
    }

    suspend fun setUserData(user: User, phrase: String): User? {
        return withRetry {
            val url: String
            if (user.mid == TW_CONST.GUEST_ID) {
                // register a new User account
                val method = "register"
                url =
                    "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$method&phrase=$phrase&user=${
                        Json.encodeToString(user)
                    }"
            } else {
                // update existing account
                val method = "set_author_core_data"
                url = "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$method&user=${
                    Json.encodeToString(user)
                }"
            }
            val request = Request.Builder().url(url).build()
            return@withRetry try {
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = response.body?.string()
                    val gson = Gson()
                    val updatedUser =
                        gson.fromJson(json, object : TypeToken<User>() {}.type) as User?

                    updatedUser?.name?.let { preferenceHelper.saveName(it) }
                    updatedUser?.profile?.let { preferenceHelper.saveProfile(it) }
                    return@withRetry updatedUser
                }
                Timber.tag("HproseInstance.setUserData").e("Set user data error. $user")
                null
            } catch (e: Exception) {
                e.printStackTrace()
                Timber.tag("setUserData").e(e.toString())
                null
            }
        }
    }

    suspend fun setUserAvatar(userId: MimeiId, avatar: MimeiId) {
        return withRetry {
            val entry = "set_user_avatar"
            val json = """
            {"aid": $appId, "ver": "last", "userid": $userId, "avatar": $avatar}
        """.trimIndent()
            val gson = Gson()
            val request = gson.fromJson(json, Map::class.java)
            try {
                hproseClient?.runMApp(entry, request) as Unit?
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
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = response.body?.string()
                    val gson = Gson()
                    user.followingList =
                        gson.fromJson(json, object : TypeToken<List<MimeiId>>() {}.type)
                    return@withRetry user.followingList
                }
            }
            return@withRetry null
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("Hprose.getFollowings").e(e.toString())
            return@withRetry null
        }
    } }

    // get fans list of the user
    suspend fun getFans(user: User): List<MimeiId>? { return withRetry {
        try {
            if (user.mid != TW_CONST.GUEST_ID) {
                val method = "get_followers"
                val url =
                    "${user.baseUrl}/entry?aid=$appId&ver=last&entry=$method&userid=${user.mid}"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val gson = Gson()
                    val jsonStr = response.body?.string()
                    user.fansList =
                        gson.fromJson(jsonStr, object : TypeToken<List<MimeiId>>() {}.type)
                    return@withRetry user.fansList
                }
            }
            return@withRetry null
        } catch (e: Exception) {
            Timber.tag("HproseInstance.getFollowings").e(e.toString())
            return@withRetry null
        }
    }}

    /**
     * Get tweets of a given author in a given span of time. if end is null, get all tweets.
     * */
    suspend fun getTweetList(
        user: User,
        startTimestamp: Long,
        endTimestamp: Long?
    ): List<Tweet> { return withRetry {
        try {
            val tweets = mutableListOf<Tweet>()
            val method = "get_tweet_list"
            val url = StringBuilder("${user.baseUrl}/entry?aid=$appId&ver=last&entry=$method")
                .append("&userid=${user.mid}&start=$startTimestamp&end=$endTimestamp").toString()
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val gson = Gson()
                val midList = gson.fromJson(
                    responseBody,
                    object : TypeToken<List<MimeiId>?>() {}.type
                ) as List<MimeiId>?
                val uncachedMidList = midList?.map {
                    val cachedTweet = restoreCachedTweet(it)
                    if (cachedTweet != null) {
                        tweets.add(cachedTweet)
                        null
                    } else it
                }
                uncachedMidList?.filterNotNull()?.map {
                    getTweet(it, user.mid)?.let { t ->
                        if (t.originalTweetId != null) {
                            t.originalTweet = getTweet(t.originalTweetId!!, t.originalAuthorId!!)
                        }
                        tweets.add(t)
                    }
                }
                tweets
            } else
                emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("getTweetList").e(e.toString())
            emptyList()
        }
    }}

    /**
     * Get only layer one data of the tweet. Do Not fetch its original tweet if there is any.
     * Let the caller to decide if go further on the tweet hierarchy.
     * */
    suspend fun getTweet(
        tweetId: MimeiId,
        authorId: MimeiId
    ): Tweet? { return withRetry {
        try {
            val cachedTweet = restoreCachedTweet(tweetId)
            if (cachedTweet != null) {
                return@withRetry cachedTweet
            }
            val author =
                getUserBase(authorId) ?: return@withRetry null   // cannot get author data, return null
            val method = "get_tweet"
            val url = StringBuilder("${author.baseUrl}/entry?aid=$appId&ver=last&entry=$method")
                .append("&tweetid=$tweetId")
                // appUser is passed to sever, to check if the current user has liked or bookmarked.
                .append("&userid=${appUser.mid}").toString()
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let { json ->
                    val gson = Gson()
                    gson.fromJson(json, Tweet::class.java)?.let { tweet ->
                        tweet.author = author
                        /**
                         * Insert the tweet into the cache database.
                         * */
                        tweetCache.tweetDao().insertCachedTweet(
                            CachedTweet(tweet.mid, gson.toJson(tweet))
                        )
                        Timber.tag("getTweet").d("$tweet")
                        return@withRetry tweet
                    }
                }
            }
            return@withRetry null
        } catch (e: Exception) {
            Timber.tag("getTweet").e("$tweetId $authorId $e")
            return@withRetry null
        }
    }}

    /**
     * Get tweet from Mimei DB to refresh cached tweet.
     * Called when the given tweet is visible.
     * */
    suspend fun refreshTweet(
        tweetId: MimeiId,
        authorId: MimeiId
    ): Tweet? { return withRetry {
        try {
            val author =
                getUserBase(authorId) ?: return@withRetry null   // cannot get author data, return null
            val method = "get_tweet"
            val url = StringBuilder("${author.baseUrl}/entry?aid=$appId&ver=last&entry=$method")
                .append("&tweetid=$tweetId")
                // appUser is passed to sever, to check if the current user has liked or bookmarked.
                .append("&userid=${appUser.mid}").toString()
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let { json ->
                    val gson = Gson()
                    val tweet = gson.fromJson(json, Tweet::class.java)
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
            return@withRetry null
        } catch (e: Exception) {
            Timber.tag("refreshTweet").e("$tweetId $authorId $e")
            return@withRetry null
        }
    }}

    /**
     * Retrieve cached tweet from Mimei DB. User info is not cached,
     * which changes frequently.
     * */
    private suspend fun restoreCachedTweet(tweetId: MimeiId): Tweet? { return withRetry {
        val cachedTweet = tweetCache.tweetDao().getCachedTweet(tweetId) ?: return@withRetry null
        val gson = Gson()
        val tweet = gson.fromJson(cachedTweet.originalTweetJson, Tweet::class.java)
        Timber.tag("restoreTweet").d("$tweet")
        tweet.author = getUserBase(tweet.authorId)
        if (tweet.originalTweetId != null) {
            tweet.originalTweet =
                getTweet(tweet.originalTweetId!!, tweet.originalAuthorId!!)
        }
        return@withRetry tweet
    }}

    // Store an object in a Mimei file and return its MimeiId.
    suspend fun uploadTweet(tweet: Tweet): Tweet? { return withRetry {
        val method = "upload_tweet"
        val json = URLEncoder.encode(Json.encodeToString(tweet), "utf-8")
        val url = "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$method&tweet=$json"
        val request = Request.Builder().url(url).build()
        return@withRetry try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                tweet.mid = response.body?.string() ?: return@withRetry null
                tweet.author = appUser
                tweet
            } else null
        } catch (e: Exception) {
            Timber.tag("uploadTweet").e(e.toString())
            e.printStackTrace()
            null
        }
    }}

    suspend fun delTweet(tweet: Tweet, callback: (MimeiId) -> Unit) { return withRetry {
        var method = "delete_tweet"
        var url =
            "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$method&tweetid=${tweet.mid}&authorid=${appUser.mid}"
        var request = Request.Builder().url(url).build()
        try {
            var response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {

                // if the originalTweet is not null, also decrease its quote count
                if (tweet.originalTweetId != null) {
                    method = "retweet_remove"
                    url = "${tweet.originalTweet!!.author?.baseUrl}/entry?aid=$appId&ver=last" +
                            "&entry=$method&tweetid=${tweet.originalTweetId}&userid=${appUser.mid}"
                    request = Request.Builder().url(url).build()
                    response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val updateOriginTweet =
                            Gson().fromJson(response.body?.string(), Tweet::class.java)
                        tweetCache.tweetDao().updateCachedTweet(
                            CachedTweet(updateOriginTweet.mid, Gson().toJson(updateOriginTweet))
                        )
                        callback(tweet.mid)
                    }
                } else
                    callback(tweet.mid)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }}

    suspend fun delComment(parentTweet: Tweet, commentId: MimeiId, delComment: (MimeiId) -> Unit) { return withRetry {
        val method = "delete_comment"
        val url =
            "${parentTweet.author?.baseUrl}/entry?aid=$appId&ver=last&entry=$method&tweetid=${parentTweet.mid}&commentid=$commentId"
        val request = Request.Builder().url(url).build()
        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                delComment(commentId)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }}

    /**
     * @param userId is the user that appUser is following or unfollowing.
     * */
    suspend fun toggleFollowing(userId: MimeiId): Boolean? { return withRetry {
        val method = "toggle_following"
        val url =
            "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$method&userid=${appUser.mid}&otherid=${userId}"
        val request = Request.Builder().url(url).build()
        return@withRetry try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string()
                val gson = Gson()
                gson.fromJson(json, Boolean::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }}

    /**
     * @param isFollowing indicates if the appUser is following this userId. Passing an argument
     * instead of toggling the status of a follower because this way will not introduce a
     * persistent inconsistency, which happens easily with the toggle method.
     * */
    suspend fun toggleFollower(userId: MimeiId, isFollowing: Boolean) { return withRetry {
        val user = getUserBase(userId)
        val method = "toggle_follower"
        val url =
            "${user?.baseUrl}/entry?aid=$appId&ver=last&entry=$method&otherid=${appUser.mid}" +
                    "&userid=${userId}&isfollower=$isFollowing"
        val request = Request.Builder().url(url).build()
        try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            Timber.tag("toggleFollower()").e(e.toString())
        }
    }}

    /**
     * Send a retweet request to backend and get a new tweet object back.
     * */
    suspend fun retweet(
        tweet: Tweet,                       // original tweet to be retweeted
        addTweetToFeed: (Tweet) -> Unit,    // add tweet to user's feed
        updateTweet: (Tweet) -> Unit        // update viewModel of original tweet
    ) { return withRetry {
        try {
            // upload the retweet
            val retweet = uploadTweet(
                Tweet(
                    mid = System.currentTimeMillis().toString(),    // placeholder
                    content = "",
                    authorId = appUser.mid,
                    originalTweetId = tweet.mid,
                    originalAuthorId = tweet.authorId
                )
            ) ?: return@withRetry

            retweet.originalTweet = tweet
            addTweetToFeed(retweet)

            increaseRetweetCount(tweet, retweet.mid)?.let { t ->
                updateTweet(t.copy(author = tweet.author))

                // update cached tweet in the database.
                tweetCache.tweetDao().updateCachedTweet(
                    CachedTweet(tweet.mid, Gson().toJson(t))
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.e("toggleRetweet()", e.toString())
        }
    }}

    /**
     * Increase the retweet count of a tweet.
     * @return updated tweet object.
     * */
    suspend fun increaseRetweetCount(tweet: Tweet, retweetId: MimeiId): Tweet? { return withRetry {
        val method = "retweet_add"
        val url =
            StringBuilder("${tweet.author?.baseUrl}/entry?aid=$appId&ver=last&entry=$method")
                .append("&tweetid=${tweet.mid}")
                .append("&userid=${appUser.mid}")
                .append("&retweetid=$retweetId")
        val request = Request.Builder().url(url.toString()).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string() ?: return@withRetry null
            val gson = Gson()
            return@withRetry gson.fromJson(responseBody, Tweet::class.java)
        }
        return@withRetry null
    }}

    suspend fun likeTweet(tweet: Tweet): Tweet { return withRetry {
        return@withRetry try {
            val author = tweet.author ?: return@withRetry tweet
            val method = "toggle_likes"
            val url =
                "${author.baseUrl}/entry?aid=$appId&ver=last&entry=$method&tweetid=${tweet.mid}&userid=${appUser.mid}"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return@withRetry tweet
                val gson = Gson()
                val res = gson.fromJson(
                    responseBody,
                    object : TypeToken<Map<String, Any?>>() {}.type
                ) as Map<String, Any?>
                tweet.favorites?.set(UserFavorites.LIKE_TWEET, res["hasLiked"] as Boolean)
                val ret = tweet.copy(
                    likeCount = (res["count"] as Double).toInt()
                )
                tweetCache.tweetDao().updateCachedTweet(
                    CachedTweet(tweet.mid, gson.toJson(ret))
                )
                ret
            } else {
                tweet
            }
        } catch (e: Exception) {
            Timber.tag("likeTweet()").e(e, "Error: ${e.message}")
            tweet
        }
    }}

    suspend fun bookmarkTweet(tweet: Tweet): Tweet { return withRetry {
        return@withRetry try {
            val author = tweet.author ?: return@withRetry tweet
            val method = "toggle_bookmark"
            val url =
                "${author.baseUrl}/entry?aid=$appId&ver=last&entry=$method&tweetid=${tweet.mid}&userid=${appUser.mid}"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return@withRetry tweet
                val gson = Gson()
                val res = gson.fromJson(
                    responseBody,
                    object : TypeToken<Map<String, Any?>>() {}.type
                ) as Map<String, Any?>
                tweet.favorites?.set(UserFavorites.BOOKMARK, res["hasBookmarked"] as Boolean)
                val ret = tweet.copy(
                    bookmarkCount = (res["count"] as Double).toInt()
                )
                tweetCache.tweetDao().updateCachedTweet(
                    CachedTweet(tweet.mid, gson.toJson(ret))
                )
                ret
            } else {
                tweet
            }
        } catch (e: Exception) {
            Timber.tag("bookmarkTweet()").e(e, "Error: ${e.message}")
            tweet
        }
    }}

    /**
     * Load all comments on a tweet.
     * @param pageNumber
     * */
    suspend fun getComments(tweet: Tweet, pageNumber: Int = 0): List<Tweet>? { return withRetry {
        try {
            if (tweet.author == null)
                tweet.author = getUserBase(tweet.authorId) ?: return@withRetry null

            val pageSize = 50
            val method = "get_comments"
            val url = StringBuilder("${tweet.author?.baseUrl}/entry?aid=$appId&ver=last")
                .append("&entry=$method&tweetid=${tweet.mid}&userid=${appUser.mid}")
                .append("&pn=$pageNumber&ps=$pageSize").toString()
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return@withRetry null
                val gson = Gson()
                return@withRetry gson.fromJson(responseBody, object : TypeToken<List<Tweet>>() {}.type) as List<Tweet>?
            }
        } catch (e: ProtocolException) {
            // handle network failure (e.g., show an error message)
            Timber.tag("getComments()").e(e, "Network failure: Unexpected status line")
            return@withRetry null
        } catch (e: Exception) {
            Timber.tag("getComments()").e(e, "Error: ${e.message}")
            return@withRetry null
        }
        return@withRetry null
    }}

    // update input parameter "comment" with new mid, and return update parent Tweet
    suspend fun uploadComment(tweet: Tweet, comment: Tweet): Tweet { return withRetry {
        return@withRetry try {
            // add the comment to tweetId
            val method = "add_comment"
            val json = URLEncoder.encode(Json.encodeToString(comment), "utf-8")
            val url =
                "${tweet.author?.baseUrl}/entry?aid=$appId&ver=last&entry=$method&tweetid=${tweet.mid}&comment=$json"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return@withRetry tweet
                val gson = Gson()
                val res = gson.fromJson(
                    responseBody,
                    object : TypeToken<Map<String, Any?>>() {}.type
                ) as Map<String, Any?>
                comment.mid = res["commentId"] as MimeiId
                val ret = tweet.copy(
                    commentCount = (res["count"] as Double).toInt()
                )
                tweetCache.tweetDao().updateCachedTweet(
                    CachedTweet(ret.mid, gson.toJson(ret))
                )
                ret
            } else {
                tweet
            }
        } catch (e: Exception) {
            Timber.tag("uploadComment()").e(e, "Error: ${e.message}")
            tweet
        }
    }}

    /**
     * Upload media file to node and return its IPFS cid with its media type.
     * */
    suspend fun uploadToIPFS(context: Context, uri: Uri): MimeiFileType? { return withRetry {
        return@withRetry withContext(Dispatchers.IO) { // Execute in IO dispatcher
            try {
                val method = "open_temp_file"
                val url = "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$method"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val fsid = response.body?.string()
                    var offset = 0L
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.use { stream ->
                            val buffer = ByteArray(TW_CONST.CHUNK_SIZE)
                            var bytesRead: Int
                            while (stream.read(buffer).also { bytesRead = it } != -1) {
                                if (fsid != null) {
                                    hproseClient?.mfSetData(fsid, buffer, offset)
                                }
                                offset += bytesRead
                            }
                        }
                    }
                    val cid = fsid?.let {
                        // Do not know the tweet mid yet, cannot add reference.
                        // Do it later when uploading tweet.
                        hproseClient?.mfTemp2Ipfs(it)
                    }

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
                    if (cid != null) {
                        MimeiFileType(cid, mediaType, offset)
                    } else null
                } else null
            } catch (e: Exception) {
                Timber.tag("uploadToIPFS()").e(e, "Error: $e $appUser")
                e.printStackTrace()
                null
            }
        }
    }}

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

    suspend fun getUserData(mid: MimeiId, ip: String, timeout: Int = 1000): User? { return withRetry {
        try {
            val entry = "get_user_core_data"
            val url =
                "http://$ip/entry?aid=$appId&ver=last&entry=$entry&userid=$mid"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return@withRetry null
                val gson = Gson()
                val user = gson.fromJson(responseBody, User::class.java)?: return@withRetry null
                user.baseUrl = "http://$ip"
                Timber.tag("getUserData").d("TRUE: user=$user")
                return@withRetry user
            }
        } catch (e: Exception) {
            Timber.tag("getUserData").e("No found. $ip $mid $e")
            return@withRetry null
        }
        return@withRetry null
    }}

    suspend fun isReachable(ip: String): String? { return withRetry {
        return@withRetry try {
            val method = "get_userid"
            val url = "http://$ip/entry?aid=$appId&ver=last&entry=$method&phrase=hello"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return@withRetry null
                val gson = Gson()
                gson.fromJson(responseBody, String::class.java)
            } else null
        } catch (e: Exception) {
            Timber.tag("isReachable").e("No reachable. $ip $e")
            this.preferenceHelper.getAppUrl()
        }
    }}

    suspend fun toggleTopList(tweetId: MimeiId): List<MimeiId>? { return withRetry {
        val entry = "toggle_top_tweets"
        val json = """
            {"aid": $appId, "ver": "last", "userid": ${appUser.mid}, "tweetid": $tweetId}
        """.trimIndent()
        val gson = Gson()
        val request = gson.fromJson(json, Map::class.java)
        try {
            val list = hproseClient?.runMApp(entry, request) as List<MimeiId>?
            return@withRetry list
        } catch (e: Exception) {
            Timber.tag("toggleTopList").e("$e")
        }
        return@withRetry null
    }}

    suspend fun getTopList(user: User): List<MimeiId>? { return withRetry {
        val entry = "get_top_tweets"
        val url =
            "${user.baseUrl}/entry?aid=$appId&ver=last&entry=$entry&userid=${user.mid}"
        val request = Request.Builder().url(url).build()
        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return@withRetry null
                val gson = Gson()
                return@withRetry gson.fromJson(responseBody, object : TypeToken<List<MimeiId>>() {}.type) as List<MimeiId>?
            }
        } catch (e: Exception) {
            Timber.tag("getTopList").e("$e")
        }
        return@withRetry null
    }}

    /**
     * Remove user from cachedUsers list.
     * */
    fun removeUser(userId: MimeiId) {
        cachedUsers.removeIf { it.mid == userId }
    }

    suspend fun logging(msg: String) { return withRetry {
        val str = URLEncoder.encode(msg, "utf-8")
//          = msg.toRequestBody("application/json; charset=utf-8".toMediaType())
        val entry = "logging"
        val url =
            "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$entry&msg=$str"
        val request = Request.Builder()
            .url(url)
//            .post(requestBody)
            .build()
        try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }}
}

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