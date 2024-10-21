package com.fireshare.tweet

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.Room
import com.fireshare.tweet.datamodel.ChatDatabase
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.MimeiFileType
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.datamodel.UserFavorites
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.widget.Gadget.findFirstReachableAddress
import com.fireshare.tweet.widget.Gadget.getFirstReachableUser
import com.fireshare.tweet.widget.Gadget.getIpAddresses
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import hprose.client.HproseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber
import java.net.ProtocolException
import java.net.URLEncoder
import java.util.regex.Pattern

// Encapsulate Hprose client and related operations in a singleton object.
object HproseInstance {
    private lateinit var appId: MimeiId     // Application Mimei ID, assigned by Leither
//    var BASE_URL: String? = null    // in case no network
    private lateinit var preferenceHelper: PreferenceHelper
    var appUser: User = User(mid = TW_CONST.GUEST_ID)    // current user object

    // get the first user account, or a list of accounts.
    fun getAlphaIds(): List<MimeiId> {
        return listOf("uTE6yhCWGLlkK6KGI9iMkOFZGGv")
    }

    // all loaded User objects will be inserted in the list, for better performance.
    private var cachedUsers: MutableSet<User> = emptySet<User>().toMutableSet()
    private lateinit var database: ChatDatabase

    // Keys within the mimei of the user's database
    private const val CHUNK_SIZE = 5 * 1024 * 1024 // 5MB in bytes
    private var hproseClient: HproseService? = null

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    private val httpClient = OkHttpClient.Builder()
//        .addInterceptor(loggingInterceptor)
        .build()

    suspend fun init(context: Context, preferenceHelper: PreferenceHelper) {
        this.preferenceHelper = preferenceHelper
        initAppEntry(preferenceHelper)

        database = Room.databaseBuilder(
            context.applicationContext,
            ChatDatabase::class.java,
            "chat_database"
        ).build()
    }

    // Find network entrance of the App
    // Given entry URL, initiate appId, and BASE_URL.
    suspend fun initAppEntry(preferenceHelper: PreferenceHelper) {
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
                            getFirstReachableUser(hostIPs, userId) ?:
                                User(mid = TW_CONST.GUEST_ID, baseUrl = "http://${hostIPs[0]}")
                        } else {
                            val firstIp = findFirstReachableAddress(hostIPs)
                            User(mid = TW_CONST.GUEST_ID, baseUrl = "http://$firstIp")
                        }
                        Timber.tag("initAppEntry").d("Succeed. $appId, $appUser")
                    }
                } else {
                    Timber.tag("initAppEntry").e("No data found within window.setParam()")
                }
                hproseClient = HproseClient.create("${appUser.baseUrl}/webapi/").useService(HproseService::class.java)
            }
        } catch (e: Exception) {
            Timber.tag("initAppEntry").e(e.toString())
        }
    }

    suspend fun sendMessage(receiptId: MimeiId, msg: ChatMessage) {
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
                                Json.encodeToString(msg)}"
                request = Request.Builder().url(url).build()
                response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    return
                }
            }
        } catch (e: Exception) {
            Timber.tag("sendMessage").e(e.toString())
            return
        }
    }

    // get the recent unread message from a sender.
    fun fetchMessages(senderId: MimeiId, numOfMsgs: Int = 50): List<ChatMessage>? {
        val gson = Gson()
        val entry = "message_fetch"
        val json = """
            {"aid": $appId, "ver":"last", "userid":${appUser.mid}, "senderid":${senderId}}
        """.trimIndent()
        val request = gson.fromJson(json, Map::class.java) as Map<*, *>
        return try {
            // write outgoing message to user's Mimei db
            hproseClient?.runMApp(entry, request)  as List<ChatMessage>?
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("fetchMessages").e(e.toString())
            null
        }
    }

    // get a list of unread incoming messages from other users
    fun checkNewMessages(): List<ChatMessage>? {
        if (appUser.mid == TW_CONST.GUEST_ID) return null
        return try {
            val gson = Gson()
            val url =
                "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=message_check&userid=${appUser.mid}"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string()
                val list = gson.fromJson(json, object : TypeToken<List<ChatMessage>>() {}.type) as List<ChatMessage>
                return list
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("checkNewMessages").e(appUser.toString())
            null
        }
    }

    fun checkUpgrade(): Map<String, String>? {
        val gson = Gson()
        val entry = "check_upgrade"
        val json = """
             {"aid": $appId, "ver":"last"}
        """.trimIndent()
        val request = gson.fromJson(json, Map::class.java) as Map<*, *>
        return try {
            hproseClient?.runMApp(entry, request)
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("checkUpgrade").e("$hproseClient $e")
            null
        }
    }

    /**
     * There are two steps for a guest user to login.
     * First, find the true UserID given its key phrase, using the IP address of the serving node.
     * Second, find the node which has this user's data, and use it to login.
     * Finally update the baseUrl of the current user with the new ip of the user's node.
     * */
    suspend fun login(username: String, password: String, keyPhrase: String): User? {
        return try {
            val userId = try {
                val entry = "get_userid"
                val url = "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$entry&phrase=$keyPhrase"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.string() ?: return null
                } else {
                    return null
                }
            } catch (e: Exception) {
                Timber.tag("GetUserId").e("Login failed. ${e.message}")
                return null
            }
            val user = getUserBase(userId) ?: return null
            val url = "${user.baseUrl}/entry?aid=$appId&ver=last&entry=login&username=$username&password=$password&phrase=$keyPhrase"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return null
                // only to verify the login succeed.
                val gson = Gson()
                gson.fromJson(json, User::class.java) ?: return null
                /**
                 * Now user object has a new baseUrl of the node which hold user data.
                 * If login succeed, httpClient need to use the new IP from now on.
                 * */
                hproseClient = HproseClient.create(user.baseUrl).useService(HproseService::class.java)
                return user
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("Hprose.Login").e("${e.message}")
            null
        }
    }

    /**
     * Get baseUrl where user data can be accessed. Each user may has a different node.
     * Therefore it is indispensable to acquire base url for each user.
     * */
    suspend fun getUserBase( userId: MimeiId, baseUrl: String? = appUser.baseUrl ): User? {
        // check if user data has been read
        cachedUsers.firstOrNull { it.mid == userId }?.let { return it }
        try {
            val url = "$baseUrl/getvar?name=mmprovsips&arg0=$userId"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                var string = response.body?.string()?.trim()?.removeSurrounding("\"")
                    ?.replace("\\", "") ?: return null
                val pattern = Pattern.compile("window\\.setParam\\((\\{.*?\\})\\)", Pattern.DOTALL)
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
                                return user
                        }
                    }
                }
            }
            return null
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("getUserBase()").e("${appUser.baseUrl} $userId $e")
            return null
        }
    }

    fun setUserData(user: User, phrase: String): User? {
        val url: String
        if (user.mid == TW_CONST.GUEST_ID) {
            // register a new User account
            val method = "register"
            url = "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$method&phrase=$phrase&user=${
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
        return try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string()
                val gson = Gson()
                val updatedUser = gson.fromJson(json, object : TypeToken<User>() {}.type) as User

                updatedUser.name?.let { preferenceHelper.saveName(it) }
                updatedUser.profile?.let { preferenceHelper.saveProfile(it) }
                return updatedUser
            }
            Timber.tag("HproseInstance.setUserData").e("Set user data error. $user")
            null
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("setUserData").e(e.toString())
            null
        }
    }

    fun setUserAvatar(userId: MimeiId, avatar: MimeiId) {
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

    // get Ids of users who the current user is following
    fun getFollowings( user: User ) =
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
                    user.followingList = gson.fromJson(json, object : TypeToken<List<MimeiId>>() {}.type)
                }
            }
            user.followingList
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("Hprose.getFollowings").e(e.toString())
            emptyList<MimeiId>()   // get default following for testing
        }

    // get fans list of the user
    fun getFans( user: User ) =
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
                    user.fansList = gson.fromJson(jsonStr, object : TypeToken<List<MimeiId>>() {}.type)
                }
            }
            user.fansList
        } catch (e: Exception) {
            Timber.tag("HproseInstance.getFollowings").e(e.toString())
            null
        }

    // get tweets of a given author in a given span of time
    // if end is null, get all tweets
    suspend fun getTweetList(user: User,
                             startTimestamp: Long,
                             endTimestamp: Long?
    ): List<Tweet> = try {
        val method = "get_tweets"
        val url = StringBuilder("${user.baseUrl}/entry?aid=$appId&ver=last&entry=$method")
            .append("&userid=${user.mid}&start=$startTimestamp&end=$endTimestamp").toString()
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string()
            val gson = Gson()
            val tweets = (gson.fromJson(responseBody, object : TypeToken<List<Tweet>>() {}.type) as List<Tweet>).map {
                Timber.tag("getTweetList").d("fetchTweet=$it")
                // assign every tweet its author object.
                it.author = user
                it
            }
            val cachedTweets = tweets.toMutableList()
            tweets.forEach {
                it.originalTweetId?.let {ori ->
                    // this is a retweet or quoted tweet, now get the original tweet
                    val cachedTweet = cachedTweets.find { twt -> twt.mid == ori }
                    if (cachedTweet != null) {
                        // the original tweet has been fetched.
                        it.originalTweet = cachedTweet
                    } else {
                        // the tweet might belong to other users.
                        it.originalTweet =
                            it.originalAuthorId?.let {oa -> getTweet(ori, oa) }
                        it.originalTweet?.let {t -> cachedTweets.add(t) }
                    }
                }
            }
            tweets
        } else
            emptyList()
    } catch (e: Exception) {
        e.printStackTrace()
        Timber.tag("getTweetList()").e(e.toString())
        emptyList()
    }

    suspend fun getTweet(
        tweetId: MimeiId,
        authorId: MimeiId
    ): Tweet? {
        val author = getUserBase(authorId) ?: return null   // cannot get author data, return null
        val method = "get_tweet"
        val url = StringBuilder("${author.baseUrl}/entry?aid=$appId&ver=last&entry=$method")
            .append("&tweetid=$tweetId")
            // appUser is passed to sever, to check if the current user has liked or bookmarked.
            .append("&userid=${appUser.mid}").toString()
        try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let { json ->
                    val gson = Gson()
                    val tweet = gson.fromJson(json, Tweet::class.java)
                    tweet.author = author
                    return tweet
                }
            }
            return null
        } catch (e: Exception) {
            Timber.tag("getTweet()").e("$tweetId $authorId $e")
            return null
        }
    }

    // Store an object in a Mimei file and return its MimeiId.
    fun uploadTweet(tweet: Tweet): Tweet? {
        val method = "upload_tweet"
        val json = URLEncoder.encode(Json.encodeToString(tweet), "utf-8")
        val url = "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$method&tweet=$json"
        val request = Request.Builder().url(url).build()
        return try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                tweet.mid = response.body?.string() ?: return null
                tweet.author = appUser
                return tweet
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun delTweet(tweetId: MimeiId, delTweet: (MimeiId) -> Unit) {
        val method = "delete_tweet"
        val url = "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$method&tweetid=$tweetId&authorid=${appUser.mid}"
        val request = Request.Builder().url(url).build()
        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                delTweet(tweetId)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun delComment(parentTweet: Tweet, commentId: MimeiId, delComment: (MimeiId) -> Unit) {
        val method = "delete_comment"
        val url = "${parentTweet.author?.baseUrl}/entry?aid=$appId&ver=last&entry=$method&tweetid=${parentTweet.mid}&commentid=$commentId"
        val request = Request.Builder().url(url).build()
        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                delComment(commentId)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleFollowing(userId: MimeiId): Boolean? {
        val method = "toggle_following"
        val url = "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$method&userid=${appUser.mid}&otherid=${userId}"
        val request = Request.Builder().url(url).build()
        return try {
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
    }

    suspend fun toggleFollower(userId: MimeiId): Boolean? {
        val user = getUserBase(userId)
        val method = "toggle_follower"
        val url = "${user?.baseUrl}/entry?aid=$appId&ver=last&entry=$method&otherid=${appUser.mid}&userid=${userId}"
        val request = Request.Builder().url(url).build()
        return try {
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
    }

    /**
     * Send a retweet request to backend and get a new tweet object back.
     * */
    fun toggleRetweet(tweet: Tweet, tweetFeedViewModel: TweetFeedViewModel, updateTweet: (Tweet) -> Unit) {
        val method = "toggle_retweet"
        val hasRetweeted = tweet.favorites?.get(UserFavorites.RETWEET) ?: return
        val url = StringBuilder("${tweet.author?.baseUrl}/entry?aid=$appId&ver=last&entry=$method")
            .append("&tweetid=${tweet.mid}")
            .append("&userid=${appUser.mid}")

        try {
            if (hasRetweeted) {
                // remove the retweet. Get retweetId first
                val request = Request.Builder().url(url.toString()).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: return
                    val gson = Gson()
                    val res = gson.fromJson(responseBody, object : TypeToken<Map<String, Any?>>() {}.type) as Map<String, Any?>

                    tweet.favorites!![UserFavorites.RETWEET] = false
                    val count = (res["count"] as? Double)?.toInt() ?: 0
                    updateTweet(tweet.copy(retweetCount = count))

                    val retweetId = res["retweetId"] as? MimeiId
                    if (retweetId != null) {
                        delTweet(retweetId) {
                            tweetFeedViewModel.delTweet(retweetId)
                        }
                    }
                }
            } else {
                var retweet = Tweet(content = "",
                    authorId = appUser.mid,
                    originalTweetId = tweet.mid,
                    originalAuthorId = tweet.authorId
                )
                // upload the retweet first
                retweet = uploadTweet(retweet) ?: return

                url.append("&retweetid=${retweet.mid}")
                val request = Request.Builder().url(url.toString()).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: return
                    val gson = Gson()
                    val res = gson.fromJson(responseBody, object : TypeToken<Map<String, Any?>>() {}.type) as Map<String, Any?>
                    tweet.favorites!![UserFavorites.RETWEET] = true
//                    tweet.retweetCount = (res["count"] as Double).toInt()
                    updateTweet(tweet.copy(retweetCount = (res["count"] as Double).toInt()))

                    retweet.author = appUser
                    retweet.originalTweet = tweet
                    tweetFeedViewModel.addTweet(retweet)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.e("toggleRetweet()", e.toString())
        }
    }

    /**
     * Load all comments on a tweet.
     * @param pageNumber
     * @param pageSize
     * */
    suspend fun getComments(tweet: Tweet, pageNumber: Int = 0): List<Tweet>? {
        try {
            if (tweet.author == null)
                tweet.author = getUserBase(tweet.authorId) ?: return null

            val pageSize = 50
            val method = "get_comments"
            val url = StringBuilder("${tweet.author?.baseUrl}/entry?aid=$appId&ver=last")
                .append("&entry=$method&tweetid=${tweet.mid}&userid=${appUser.mid}")
                .append("&pn=$pageNumber&ps=$pageSize").toString()
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return listOf()
                val gson = Gson()
                return gson.fromJson(responseBody, object : TypeToken<List<Tweet>>() {}.type)
            }
        } catch (e: ProtocolException) {
            // handle network failure (e.g., show an error message)
            Timber.tag("getComments()").e(e, "Network failure: Unexpected status line")
            return null
        } catch (e: Exception) {
            Timber.tag("getComments()").e(e, "Error: ${e.message}")
            return null
        }
        return null
    }

    // update input parameter "comment" with new mid, and return update parent Tweet
    fun uploadComment(tweet: Tweet, comment: Tweet): Tweet {
        return try {
            // add the comment to tweetId
            val method = "add_comment"
            val json = URLEncoder.encode(Json.encodeToString(comment), "utf-8")
            val url = "${tweet.author?.baseUrl}/entry?aid=$appId&ver=last&entry=$method&tweetid=${tweet.mid}&comment=$json"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return tweet
                val gson = Gson()
                val res = gson.fromJson(responseBody, object : TypeToken<Map<String, Any?>>() {}.type) as Map<String, Any?>
                comment.mid = res["commentId"] as MimeiId
                tweet.copy(
                    commentCount = (res["count"] as Double).toInt()
                )
            } else {
                tweet
            }
        } catch (e: Exception) {
            Timber.tag("uploadComment()").e(e, "Error: ${e.message}")
            tweet
        }
    }

    fun likeTweet(tweet: Tweet): Tweet {
        return try {
            val author = tweet.author ?: return tweet
            val method = "liked_count"
            val url = "${author.baseUrl}/entry?aid=$appId&ver=last&entry=$method&tweetid=${tweet.mid}&userid=${appUser.mid}"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return tweet
                val gson = Gson()
                val res = gson.fromJson(responseBody, object : TypeToken<Map<String, Any?>>() {}.type) as Map<String, Any?>
                tweet.favorites?.set(UserFavorites.LIKE_TWEET, res["hasLiked"] as Boolean)
                tweet.copy(
                    likeCount = (res["count"] as Double).toInt()
                )
            } else {
                tweet
            }
        } catch (e: Exception) {
            Timber.tag("likeTweet()").e(e, "Error: ${e.message}")
            tweet
        }
    }

    fun bookmarkTweet(tweet: Tweet): Tweet {
        return try {
            val author = tweet.author ?: return tweet
            val method = "bookmark"
            val url = "${author.baseUrl}/entry?aid=$appId&ver=last&entry=$method&tweetid=${tweet.mid}&userid=${appUser.mid}"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return tweet
                val gson = Gson()
                val res = gson.fromJson(responseBody, object : TypeToken<Map<String, Any?>>() {}.type) as Map<String, Any?>
                tweet.favorites?.set(UserFavorites.BOOKMARK, res["hasBookmarked"] as Boolean)
                tweet.copy(
                    bookmarkCount = (res["count"] as Double).toInt()
                )
            } else {
                tweet
            }
        } catch (e: Exception) {
            Timber.tag("bookmarkTweet()").e(e, "Error: ${e.message}")
            tweet
        }
    }

     suspend fun uploadToIPFS(context: Context, uri: Uri): MimeiFileType? {
        return withContext(Dispatchers.IO) { // Execute in IO dispatcher
            try {
                val method = "open_temp_file"
                val url = "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$method"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val fsid = response.body?.string()
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        var offset = 0
                        inputStream.use { stream ->
                            val buffer = ByteArray(CHUNK_SIZE)
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
                        hproseClient?.mfTemp2Ipfs(it, appUser.mid)
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
                        MimeiFileType(cid, mediaType)
                    } else {
                        null
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun getMediaUrl(mid: MimeiId?, baseUrl: String?): String? {
        if (mid != null && baseUrl!= null) {
            return if (mid.length > 27) {
                "$baseUrl/ipfs/$mid"
            } else {
                "$baseUrl/mm/$mid"
            }
        }
        return null
    }

    fun getUserData(mid: MimeiId, ip: String, timeout: Int = 1000): User? {
        try {
            val entry = "get_user_core_data"
            val url =
                "http://$ip/entry?aid=$appId&ver=last&entry=$entry&userid=$mid"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return null
                val gson = Gson()
                val user = gson.fromJson(responseBody, User::class.java)
                user.baseUrl = "http://$ip"
                Timber.tag("getUserData").d("TRUE: user=$user")
                return user
            }
        } catch (e: Exception) {
            Timber.tag("getUserData").e("No found. $ip $mid $e")
            return null
        }
        return null
    }

    fun isReachable(ip: String): String? {
        return try {
            val method = "get_userid"
            val url = "http://$ip/entry?aid=$appId&ver=last&entry=$method&phrase=hello"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return null
                val gson = Gson()
                gson.fromJson(responseBody, String::class.java)
            } else null
        } catch (e: Exception) {
            Timber.tag("isReachable").e("No reachable. $ip $e")
            null
        }
    }

    fun addToTopList(tweetId: MimeiId): List<MimeiId>? {
        val entry = "toggle_top_tweets"
        val json = """
            {"aid": $appId, "ver": "last", "userid": ${appUser.mid}, "tweetid": $tweetId}
        """.trimIndent()
        val gson = Gson()
        val request = gson.fromJson(json, Map::class.java)
        try {
            val list  = hproseClient?.runMApp(entry, request) as List<MimeiId>?
            return list
        } catch (e: Exception) {
            Timber.tag("addToTopList").e("$e")
        }
        return null
    }

    fun getTopList(user: User): List<MimeiId>? {
        val entry = "get_top_tweets"
        val url =
            "${user.baseUrl}/entry?aid=$appId&ver=last&entry=$entry&userid=${user.mid}"
        val request = Request.Builder().url(url).build()
        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return null
                val gson = Gson()
                return gson.fromJson(responseBody, object : TypeToken<List<MimeiId>>() {}.type)
            }
        } catch (e: Exception) {
            Timber.tag("getTopList").e("$e")
        }
        return null
    }

    fun removeUser(userId: MimeiId) {
        cachedUsers.removeIf { it.mid == userId }
    }
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
    fun mfTemp2Ipfs(fsid: String, ref: MimeiId): MimeiId
    fun mfSetCid(sid: String, mid: MimeiId, cid: MimeiId)
    fun mfSetData(fsid: String, data: ByteArray, offset: Int)
    fun set(sid: String, key: String, value: Any)
    fun get(sid: String, key: String): Any?
    fun hGet(sid: String, key: String, field: String): Any?
    fun hSet(sid: String, key: String, field: String, value: Any)
    fun hDel(sid: String, key: String, field: String)
    fun zAdd(sid: String, key: String, sp: ScorePair)
    fun zRevRange(sid: String, key: String, start: Long, end: Long): List<*>
}