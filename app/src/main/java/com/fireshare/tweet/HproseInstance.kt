package com.fireshare.tweet

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.fireshare.tweet.datamodel.ChatDatabase
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.datamodel.UserFavorites
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.widget.Gadget
import com.fireshare.tweet.widget.Gadget.getFirstReachableUri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import hprose.client.HproseClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.io.InputStream
import java.net.ProtocolException
import java.net.URLEncoder
import java.util.regex.Pattern

// Encapsulate Hprose client and related operations in a singleton object.
object HproseInstance {
    private lateinit var appId: MimeiId     // Application Mimei ID, assigned by Leither
    private var BASE_URL: String? = null    // in case no network
    private lateinit var preferenceHelper: PreferenceHelper

    var appUser: User = User(mid = TW_CONST.GUEST_ID)    // current user object

    // all loaded User objects will be inserted in the list, for better performance.
    private var cachedUsers: MutableList<User> = emptyList<User>().toMutableList()
    private lateinit var database: ChatDatabase

    // Keys within the mimei of the user's database
    private const val CHUNK_SIZE = 5 * 1024 * 1024 // 5MB in bytes

    private val client: HproseService by lazy {
        HproseClient.create("$BASE_URL/webapi/").useService(HproseService::class.java)
    }
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    val httpClient = OkHttpClient.Builder()
//        .addInterceptor(loggingInterceptor)
        .build()

    fun init(context: Context, preferenceHelper: PreferenceHelper) {
        this.preferenceHelper = preferenceHelper
        try {
            // Use default AppUrl to enter App network, update with IP of the fastest node.
            val pair = initAppEntry(preferenceHelper)       // load default url: twbe.fireshare.us
            appId = pair.first
            BASE_URL = pair.second
            Log.d("HproseInstance.init()", "AppID=$appId, BaseURL=$BASE_URL")
            preferenceHelper.setAppId(appId)
        } catch (e: Exception) {
            appId = preferenceHelper.getAppId().toString()
            BASE_URL = preferenceHelper.getAppUrl().toString()
            Log.e("HproseInstance.init()", e.toString())
        } finally {
            val userId = preferenceHelper.getUserId()

            if (userId != TW_CONST.GUEST_ID) {
                // There is a registered user. Initiate account data.
                initCurrentUser(userId)?.let {
                    appUser = it
                    cachedUsers.add(it) // the list shall be empty now.
                }
            } else {
                appUser.baseUrl = BASE_URL
            }
            database = Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                "chat_database"
            ).build()
        }
    }

    // Find network entrance of the App
    // Given entry URL, initiate appId, and BASE_URL.
    private fun initAppEntry(preferenceHelper: PreferenceHelper): Pair<MimeiId, String?> {
        val baseUrl = preferenceHelper.getAppUrl().toString()
        val request = Request.Builder().url("https://$baseUrl").build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            // retrieve window.Param from page source code of http://base_url
            val htmlContent = response.body?.string()?.trimIndent()
            val pattern = Pattern.compile("window\\.setParam\\((\\{.*?\\})\\)", Pattern.DOTALL)
            val matcher = pattern.matcher(htmlContent as CharSequence)
            var jsonString: String? = null

            if (matcher.find()) {
                jsonString = matcher.group(1)
            }

            if (jsonString != null) {
                // Step 2: Parse the extracted string into a Kotlin map
                val gson = Gson()
                val paramMap = gson.fromJson(jsonString, Map::class.java) as Map<*, *>
                val ips = ((paramMap["addrs"] as ArrayList<*>)[0] as ArrayList<*>).map { (it as ArrayList<*>)[0] as String }

                return Pair(paramMap["mid"] as MimeiId, "http://${ips[1]}")     // should have been 0, but 1 is safe.
            } else {
                Log.e("initAppEntry", "No data found within window.setParam()")
            }
        }
        Log.e("initAppEntry", "Failed to get AppId, using default ones.")
        return Pair(preferenceHelper.getAppId().toString(), null)
    }

    // only used for registered user, that has userId in preference.
    // if preferenceHelper has current User'd ID, the user is in logon status.
    private fun initCurrentUser(userId: MimeiId? = null, keyPhrase: String = ""): User? {
        if (userId == null && keyPhrase.isEmpty()) return null
        val method = "init_user_mid"
        val url = "$BASE_URL/entry?&aid=$appId&ver=last&entry=$method&userid=$userId&phrase=$keyPhrase"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val json = response.body?.string()
            val gson = Gson()
            val user = gson.fromJson(json, User::class.java)
            user.baseUrl = BASE_URL
            return user
        }
        return null
    }

    suspend fun sendMessage(receiptId: MimeiId, msg: ChatMessage) {
        var entry = "message_outgoing"
        var url =
            "${appUser.baseUrl}/entry?aid=$appId&ver=last&entry=$entry&userid=${appUser.mid}" +
                    "&receiptid=$receiptId&msg=${Json.encodeToString(msg)}"
        // write outgoing message to user's Mimei db
        var request = Request.Builder().url(url).build()
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
    }

    // get the recent unread message from a sender.
    fun fetchMessages(senderId: MimeiId, numOfMsgs: Int = 50): List<ChatMessage>? {
        val gson = Gson()
        val entry = "message_fetch"
        val json = """
            {"aid": $appId, "ver":"last", "userid":${appUser.mid}, "senderid":${senderId}}
        """.trimIndent()
        val request = gson.fromJson(json, Map::class.java) as Map<*, *>
        // write outgoing message to user's Mimei db
        return client.runMApp(entry, request)  as List<ChatMessage>?
    }

    // get a list of unread incoming messages from other users
    fun checkNewMessages(): List<ChatMessage>? {
        val gson = Gson()
        val entry = "message_check"
        val json = """
            {"aid": $appId, "ver":"last", "userid":${appUser.mid}}
        """.trimIndent()
        val request = gson.fromJson(json, Map::class.java) as Map<*, *>
        // write outgoing message to user's Mimei db
        return client.runMApp(entry, request) as List<ChatMessage>?
    }

    fun checkUpdates(): Map<String, String>? {
        val gson = Gson()
        val entry = "upgrade"
        val json = """
             {"aid": $appId, "ver":"last"}
        """.trimIndent()
        val request = gson.fromJson(json, Map::class.java) as Map<*, *>
        return client.runMApp(entry,request)
    }

    fun login(username: String, password: String, keyPhrase: String): User? {
        val gson = Gson()
        val entry = "login"
        val json = """
            {"phrase": "$keyPhrase", "username": "$username", "password": "$password", 
            "aid": "$appId", "ver": "last"}
        """.trimIndent()
        val request = gson.fromJson(json, Map::class.java) as Map<*, *>
        val ret = client.runMApp(entry, request) as String?
        ret?.let {
            val user = Json.decodeFromString<User>(ret)
            user.baseUrl = BASE_URL
            return user
        }
        return null
    }

    // get the first user account, or a list of accounts.
    fun getAlphaIds(): List<MimeiId> {
        return listOf("yifT_a-gWN9-JXsJ6P7gqizKMDM")
    }

    /**
     * Get base url where user data can be accessed. Each user may has a different node.
     * Therefore it is indispensable to acquire base url for each user.
     * */
    suspend fun getUserBase( userId: MimeiId ): User? {
        // check if user data has been read
        cachedUsers.firstOrNull { it.mid == userId }?.let { return it }
        try {
            val method = "get_providers"
            val url = "$BASE_URL/entry?&aid=$appId&ver=last&entry=$method&userid=$userId"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val providers = response.body?.string() ?: return null
                val providerList = Json.parseToJsonElement(providers).jsonArray
                if (providerList.isNotEmpty()) {
                    Log.d("getUserBase()", providerList.toString())
                    val ipAddresses = providerList[0] as JsonArray
                    getFirstReachableUri(
                        ipAddresses.map {
                            Gadget.removeParentheses((it as JsonArray)[0])
                        }, userId
                    )?.let { u ->
                        cachedUsers.add(u)
                        return u
                    }
                }
            }
            return null
        } catch (e: Exception) {
            Log.e("getUserBase()", e.toString())
            return null
        }
    }

    fun setUserData(user: User, phrase: String): User? {
        // use Json here, so that null attributes in User are ignored. On the server-side, only set attributes
        // that have value in incoming data.
        val url: String
        if (user.mid == TW_CONST.GUEST_ID) {
            // register a new User account
            val method = "register"
            url = "${appUser.baseUrl}/entry?&aid=$appId&ver=last&entry=$method&phrase=$phrase&user=${
                Json.encodeToString(user)
            }"
        } else {
            // update existing account
            val method = "set_author_core_data"
            val tmp = User(mid = appUser.mid, name = appUser.name,
                username = appUser.username, avatar = appUser.avatar, profile = appUser.profile,
                timestamp = appUser.timestamp
            )
            url = "${appUser.baseUrl}/entry?&aid=$appId&ver=last&entry=$method&user=${
                Json.encodeToString(tmp)
            }"
        }
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val json = response.body?.string()
            val gson = Gson()
            val ret = gson.fromJson(json, object : TypeToken<User>() {}.type) as User
            ret.baseUrl = BASE_URL

            ret.name?.let { preferenceHelper.saveName(it) }
            ret.profile?.let { preferenceHelper.saveProfile(it) }
            return ret
        }
        Log.d("HproseInstance.setUserData", "Set user data error")
        return null
    }

    fun setUserAvatar(userId: MimeiId, avatar: MimeiId) {
        val entry = "set_user_avatar"
        val json = """
            {"aid": $appId, "ver": "last", "userid": $userId, "avatar": $avatar}
        """.trimIndent()
        val gson = Gson()
        val request = gson.fromJson(json, Map::class.java)
        client.runMApp(entry, request) as Unit?
    }

    // get Ids of users who the current user is following
    fun getFollowings( user: User ) =
        try {
            if (user.mid != TW_CONST.GUEST_ID) {
                val method = "get_followings"
                val url =
                    "${user.baseUrl}/entry?&aid=$appId&ver=last&entry=$method&userid=${user.mid}"
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
            Log.e("HproseInstance.getFollowings", e.toString())
            null   // get default following for testing
        }

    // get fans list of the user
    fun getFans( user: User ) =
        try {
            if (user.mid != TW_CONST.GUEST_ID) {
                val method = "get_followers"
                val url =
                    "${user.baseUrl}/entry?&aid=$appId&ver=last&entry=$method&userid=${user.mid}"
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
            Log.e("HproseInstance.getFollowings", e.toString())
            null
        }

    // get tweets of a given author in a given span of time
    // if end is null, get all tweets
    suspend fun getTweetList(user: User,
                             startTimestamp: Long,
                             endTimestamp: Long?
    ) = try {
        val method = "get_tweets"
        val url = StringBuilder("${user.baseUrl}/entry?&aid=$appId&ver=last&entry=$method")
            .append("&userid=${user.mid}&start=$startTimestamp&end=$endTimestamp").toString()
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string()
            val gson = Gson()
//            val tweets = (gson.fromJson(responseBody, object : TypeToken<List<Tweet>>() {}.type) as List<Tweet>).map {
            val tweets = (gson.fromJson(responseBody, object : TypeToken<List<Tweet>>() {}.type) as List<Tweet>).map {
                Log.d("getTweetList","fetchTweet=$it")
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
        Log.e("getTweetList()", e.toString())
        emptyList()
    }

    suspend fun getTweet(
        tweetId: MimeiId,
        authorId: MimeiId
    ): Tweet? {
        val author = getUserBase(authorId) ?: return null   // cannot get author data, return null
        val method = "get_tweet"
        val url = StringBuilder("${author.baseUrl}/entry?&aid=$appId&ver=last&entry=$method")
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
            Log.e("getTweet()", e.toString())
            return null
        }
    }

    // Store an object in a Mimei file and return its MimeiId.
    fun uploadTweet(tweet: Tweet): Tweet? {
        val method = "upload_tweet"
        val json = URLEncoder.encode(Json.encodeToString(tweet), "utf-8")
        val url =
            "${appUser.baseUrl}/entry?&aid=$appId&ver=last&entry=$method&tweet=$json"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            tweet.mid = response.body?.string() ?: return null
            tweet.author = appUser
            return tweet
        }
        return null
    }

    fun delTweet(tweetId: MimeiId, delTweet: (MimeiId) -> Unit) {
        val method = "delete_tweet"
        val url =
            "${appUser.baseUrl}/entry?&aid=$appId&ver=last&entry=$method&tweetid=$tweetId&authorid=${appUser.mid}"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            delTweet(tweetId)
        }
    }

    fun delComment(parentTweet: Tweet, commentId: MimeiId, delComment: (MimeiId) -> Unit) {
        val method = "delete_comment"
        val url =
            "${parentTweet.author?.baseUrl}/entry?&aid=$appId&ver=last&entry=$method&tweetid=${parentTweet.mid}&commentid=$commentId"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            delComment(commentId)
        }
    }

    fun toggleFollowing(userId: MimeiId): Boolean? {
        val method = "toggle_following"
        val url =
            "${appUser.baseUrl}/entry?&aid=$appId&ver=last&entry=$method&userid=${appUser.mid}&otherid=${userId}"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val json = response.body?.string()
            val gson = Gson()
            return gson.fromJson(json, Boolean::class.java)
        }
        return null
    }

    suspend fun toggleFollower(userId: MimeiId): Boolean? {
        val user = getUserBase(userId)
        val method = "toggle_follower"
        val url =
            "${user?.baseUrl}/entry?&aid=$appId&ver=last&entry=$method&otherid=${appUser.mid}&userid=${userId}"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val json = response.body?.string()
            val gson = Gson()
            return gson.fromJson(json, Boolean::class.java)
        }
        return null
    }

    // retweet or cancel retweet
    fun toggleRetweet(tweet: Tweet, tweetFeedViewModel: TweetFeedViewModel, updateTweetViewModel: (Tweet) -> Unit) {
        val method = "toggle_retweet"
        val hasRetweeted = tweet.favorites?.get(UserFavorites.RETWEET) ?: return
        val url = StringBuilder("${tweet.author?.baseUrl}/entry?&aid=$appId&ver=last&entry=$method")
            .append("&tweetid=${tweet.mid}")
            .append("&userid=${appUser.mid}")

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
                val retweetId = res["retweetId"] as? MimeiId

                updateTweetViewModel(tweet.copy(retweetCount = count))

                if (retweetId != null) {
                    delTweet(retweetId) {
                        tweetFeedViewModel.delTweet(retweetId)
                    }
                }
            }
        } else {
            var retweet = Tweet(
                content = "",
                authorId = appUser.mid,
                originalTweetId = tweet.mid,
                originalAuthorId = tweet.authorId
            )
            retweet = uploadTweet(retweet) ?: return
            url.append("&retweetid=${retweet.mid}")

            val request = Request.Builder().url(url.toString()).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return
                val gson = Gson()
                val res = gson.fromJson(responseBody, object : TypeToken<Map<String, Any?>>() {}.type) as Map<String, Any?>
                tweet.favorites!![UserFavorites.RETWEET] = true
                tweet.retweetCount = (res["count"] as Double).toInt()
                updateTweetViewModel( tweet.copy() )

                retweet.author = appUser
                retweet.originalTweet = tweet
                tweetFeedViewModel.addTweet(retweet)
            }
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
            val url = StringBuilder("${tweet.author?.baseUrl}/entry?&aid=$appId&ver=last")
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
            Log.e("OkHttp", "Network failure: Unexpected status line", e)
        }
        return null
    }

    // update input parameter "comment" with new mid, and return update parent Tweet
    fun uploadComment(tweet: Tweet, comment: Tweet): Tweet {
        // add the comment to tweetId
        val method = "add_comment"
        val json = URLEncoder.encode(Json.encodeToString(comment), "utf-8")
        val url = "${tweet.author?.baseUrl}/entry?&aid=$appId&ver=last&entry=$method&tweetid=${tweet.mid}&comment=$json"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string() ?: return tweet
            val gson = Gson()
            val res = gson.fromJson(responseBody, object : TypeToken<Map<String, Any?>>() {}.type) as Map<String, Any?>
            comment.mid = res["commentId"] as MimeiId
            return tweet.copy(
                commentCount = (res["count"] as Double).toInt()
            )
        }
        return tweet
    }

    fun likeTweet(tweet: Tweet): Tweet {
        val author = tweet.author ?: return tweet
        val method = "liked_count"
        val url =
            "${author.baseUrl}/entry?&aid=$appId&ver=last&entry=$method&tweetid=${tweet.mid}&userid=${appUser.mid}"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string() ?: return tweet
            val gson = Gson()
            val res = gson.fromJson(responseBody, object : TypeToken<Map<String, Any?>>() {}.type) as Map<String, Any?>

            tweet.favorites?.set(UserFavorites.LIKE_TWEET, res["hasLiked"] as Boolean)
            return tweet.copy(
                // return a new object for recomposition to work.
                likeCount = (res["count"] as Double).toInt()
            )
        }
        return tweet
    }

    fun bookmarkTweet(tweet: Tweet): Tweet {
        val author = tweet.author ?: return tweet
        val method = "bookmark"
        val url =
            "${author.baseUrl}/entry?&aid=$appId&ver=last&entry=$method&tweetid=${tweet.mid}&userid=${appUser.mid}"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string() ?: return tweet
            val gson = Gson()
            val res = gson.fromJson(responseBody, object : TypeToken<Map<String, Any?>>() {}.type) as Map<String, Any?>
            tweet.favorites?.set(UserFavorites.BOOKMARK, res["hasBookmarked"] as Boolean)
            return tweet.copy(
                bookmarkCount = (res["count"] as Double).toInt()
            )
        }
        return tweet
    }

    // Upload data from an InputStream to IPFS and return the resulting MimeiId.
    fun uploadToIPFS(inputStream: InputStream): MimeiId? {
        val method = "open_temp_file"
        val url =
            "${appUser.baseUrl}/entry?&aid=$appId&ver=last&entry=$method"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val fsid = response.body?.string()
            var offset = 0
            inputStream.use { stream ->
                val buffer = ByteArray(CHUNK_SIZE)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    if (fsid != null) {
                        client.mfSetData(fsid, buffer, offset)
                    }
                    offset += bytesRead
                }
            }
            val cid = fsid?.let {
                client.mfTemp2Ipfs(
                    it,
                    appUser.mid
                )
            }    // Associate the uploaded data with the app's main Mimei
            inputStream.close()
            println("cid=$cid")
            return cid
        }
        return null
    }

//    fun uploadToIPFS(inputStream: InputStream): MimeiId {
//        var offset = 0
//        var cid: String = ""
//        val request = """
//            {"userid": "${appUser.mid}", "aid": "$appId", "ver": "last"}
//        """.trimIndent()
//        val gson = Gson()
//
//        inputStream.use { stream ->
//            val buffer = ByteArray(CHUNK_SIZE)
//            var bytesRead: Int
//            while (stream.read(buffer).also { bytesRead = it } != -1) {
//                cid = client.runMApp("upload_ipfs",
//                    gson.fromJson(request, object : TypeToken<Map<String, String>>() {}.type),
//                    listOf(buffer)) as String
//                offset += bytesRead
//            }
//        }
//        Log.d("uploadToIPFS()", "cid=$cid")
//        return cid
//    }

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

    fun isReachable(mid: MimeiId, ip: String, timeout: Int = 1000): User? {
        try {
            val method = "get_user_core_data"
            val url =
                "http://$ip/entry?&aid=$appId&ver=last&entry=$method&userid=$mid"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return null
                val gson = Gson()
                val user = gson.fromJson(responseBody, User::class.java)
                user.baseUrl = "http://$ip"
                Log.d("isReachable()", "user=$user")
                return user
            }
        } catch (e: Exception) {
            Log.e("Gadget.isReachable", e.toString())
        }
        return null
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