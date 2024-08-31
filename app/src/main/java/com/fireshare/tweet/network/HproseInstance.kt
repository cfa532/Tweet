package com.fireshare.tweet.network

import android.util.Log
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.datamodel.UserFavorites
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import hprose.client.HproseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.io.InputStream
import java.math.BigInteger
import java.net.URLEncoder

// Encapsulate Hprose client and related operations in a singleton object.
object HproseInstance {
    private const val BASE_URL = "http://10.0.2.2:8081"
    const val TWBE_APP_ID = "d4lRyhABgqOnqY4bURSm_T-4FZ4"
    private const val CHUNK_SIZE = 50 * 1024 * 1024 // 10MB in bytes

    // Keys within the mimei of the user's database
    private const val TWT_LIST_KEY = "list_of_tweets_mid"
    private const val TWT_CONTENT_KEY = "core_data_of_tweet"     // account data of user
    private const val FOLLOWINGS_KEY = "list_of_followings_mid"
    private const val COMMENT_LIST = "comment_list_key"

    private val client: HproseService by lazy {
        HproseClient.create("$BASE_URL/webapi/").useService(HproseService::class.java)
    }
    private var sid = ""
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .build()

    private val users: MutableSet<User> = emptySet<User>().toMutableSet()

    val appUser: User by lazy {
        users.find { it.mid == appMid } ?: runBlocking {
            withContext(IO) {
                val method = "get_author_core_data"
                val url = "$BASE_URL/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method&userid=$appMid"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()
                val gson = Gson()
                val user = gson.fromJson(responseBody, User::class.java)
//                val user = Json.decodeFromString<User>(response.body?.string() ?: "")
                user.baseUrl = BASE_URL
                users.add(user)
                user
            }
        }
    }

    // Initialize lazily, also used as UserId
    private val appMid: String by lazy {
        runBlocking {   // necessary for the whole App
            withContext(Dispatchers.IO) {
                val method = "get_app_mid"
                val url = "$BASE_URL/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val res = responseBody?.let { Json.decodeFromString<Map<String, String>>(it) }
                    if (res != null) {
                        println(res)
                        sid = res["sid"] ?: ""
                        res["mid"] ?: ""
                    } else {
                        ""
                    }
                } else {
                    ""
                }
            }
        }
    }

    fun getUser(userId: MimeiId): User? {
        return users.find { it.mid == userId }
    }

    // Get base url where user data can be accessed, and user data
    suspend fun getUserBase(userId: MimeiId): User? {
        // check if user data has been read
        getUser(userId)?.let { return it}

        val providers = client.getVar("", "mmprovsips", userId)
        val providerList = Json.parseToJsonElement(providers).jsonArray
        if (providerList.isNotEmpty()) {
            println(providerList)
            val ipAddresses = providerList[0].jsonArray.map { it.jsonArray }
            Gadget.getFirstReachableUri(ipAddresses, userId)?.let { u ->
                users.add(u)
                println("Get userbase=${users}")
                return u
            }
        }
        return null
    }

    fun setUserData(user: User) {
        // use Json here, so that null attributes in User are ignored. On the server-side, only set attributes
        // that have value in incoming data.
        val method = "set_author_core_data"
        val url = "${user.baseUrl}/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method&user=${
            Json.encodeToString(user)
        }"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.d("HproseInstance.setUserData", "Set user data error")
        }
    }

    // get Ids of users who the current user is following
    fun getFollowings(userId: MimeiId = appMid): List<MimeiId> =
        try {
            client.mmOpen("", userId, "last").run {
                client.get(this, FOLLOWINGS_KEY)?.let { keys ->
                    (keys as? List<*>)?.mapNotNull { it as? MimeiId }
                } ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e("HproseInstance.getFollowings", e.toString())
            emptyList()
        }

    // get tweets of a given author in a given span of time
    // if end is null, get all tweets
    suspend fun getTweetList(
        authorId: MimeiId,      // author ID of the tweets
        tweets: MutableList<Tweet>,
        startTimestamp: Long,
        endTimestamp: Long?
    ) = try {
        client.mmOpen("", authorId, "last").also {
            client.zRevRange(it, TWT_LIST_KEY, 0, -1).forEach { e ->
                val sp = e as Map<*, *>
                val score = (sp["score"] as BigInteger).toLong()
                val tweetId = sp["member"] as MimeiId
                if (score <= startTimestamp && (endTimestamp == null || score > endTimestamp)) {
                    // check if the tweet is in the tweets already.
                    getTweet(tweetId, authorId, tweets)?.let { t -> tweets += t }
                }
            }
        }
    } catch (e: Exception) {
        Log.e("HproseInstance.getTweets", e.toString())
    }

    private suspend fun getTweet(
        tweetId: MimeiId,
        authorId: MimeiId,
        tweets: MutableList<Tweet>
    ): Tweet? {
        // if the tweet is fetched already, return null
        tweets.find { it.mid == tweetId }?.let { return null }

        val author = getUserBase(authorId) ?: return null   // cannot get author data, return null
        val method = "get_tweet"
        val url =
            "${author.baseUrl}/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method&tweetid=$tweetId&userid=${appUser.mid}"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            response.body?.string()?.let { json ->
                println("fetchTweet=$json")
                val gson = Gson()
                val tweet = gson.fromJson(json, Tweet::class.java)
                tweet.author = author

                if (tweet.originalTweetId != null) {
                    val cachedTweet = tweets.find { it.mid == tweet.originalTweetId }
                    if (cachedTweet != null) {
                        // isPrivate could be null
                        tweet.originalAuthor = cachedTweet.author
                        tweet.originalTweet = cachedTweet;
                    } else {
                        tweet.originalTweet =
                            tweet.originalAuthorId?.let { getTweet(tweet.originalTweetId, it, tweets) }
                                ?: return null
                        tweet.originalAuthor = tweet.originalTweet!!.author
                    }
                }
                return tweet
            }
        }
        return null
    }

    // Store an object in a Mimei file and return its MimeiId.
    fun uploadTweet(tweet: Tweet): Tweet? {
        val method = "upload_tweet"
        val json = URLEncoder.encode(Json.encodeToString(tweet), "utf-8")
        val url =
            "${appUser.baseUrl}/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method&tweet=$json"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            tweet.mid = response.body?.string() ?: return null
            tweet.author = appUser
            return tweet
        }
        return null
    }

    private fun deleteTweet(tweetId: MimeiId, delTweet: (MimeiId) -> Unit) {
        val method = "delete_tweet"
        val url =
            "${appUser.baseUrl}/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method&tweetid=$tweetId&authorid=${appUser.mid}"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            delTweet(tweetId)
        }
    }

    // retweet or cancel retweet
    fun toggleRetweet(tweet: Tweet, tweetFeedViewModel: TweetFeedViewModel, updateTweetViewModel: (Tweet) -> Unit) {
        val method = "toggle_retweet"
        val hasRetweeted = tweet.favorites?.get(UserFavorites.RETWEET) ?: return
        val url = StringBuilder("${tweet.author?.baseUrl}/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method")
            .append("&tweetid=${tweet.mid}")
            .append("&userid=${appUser.mid}")

        if (hasRetweeted) {
            // remove the retweet. Get retweetId first
            val request = Request.Builder().url(url.toString()).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return
                val gson = Gson()
                val res = gson.fromJson(responseBody, Map::class.java) as Map<*, *>
                tweet.favorites!![UserFavorites.RETWEET] = false
                updateTweetViewModel( tweet.copy(retweetCount = (res["count"] as Double).toInt()) )

                deleteTweet(res["retweetId"] as MimeiId) {
                    tweetFeedViewModel.delTweet(res["retweetId"] as MimeiId)
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
                val res = gson.fromJson(responseBody, Map::class.java) as Map<*, *>
                tweet.favorites!![UserFavorites.RETWEET] = true
                tweet.retweetCount = (res["count"] as Double).toInt()
                updateTweetViewModel( tweet.copy() )

                retweet.author = appUser
                retweet.originalTweet = tweet
                tweetFeedViewModel.addTweet(retweet)
            }
        }
    }

    // get the comment Id list of this tweet
    suspend fun getCommentList(tweetId: MimeiId ): List<Tweet> {
        val list = mutableListOf<Tweet>()
        client.mmOpen("", tweetId, "last").also {
            client.zRevRange(it, COMMENT_LIST, 0, -1).forEach { e ->
                val sp = e as Map<*, *>
                val score = (sp["score"] as BigInteger).toLong()
                val commentId = sp["member"] as MimeiId
                client.mmOpen("", commentId, "last").also {mmsid ->
                    val c = client.get(mmsid, TWT_CONTENT_KEY) as Map<*, *>
                    val comment = Tweet(mid = c["mid"] as MimeiId,
                        authorId = c["authorId"] as MimeiId,
                        content = c["content"] as String,
                        timestamp = (c["timestamp"] as Double).toLong())
                    comment.author = getUserBase(comment.authorId)
                    list.add(comment)
                }
            }
        }
        return list
    }

    // given a tweet, load its comments. If commentId is not Null, retrieve that one alone.
    fun getComments(tweet: Tweet, commentId: MimeiId? = null, pageNumber: Int = 0): List<Tweet> {
        val method = "get_tweets"
        val url = StringBuilder("${tweet.author?.baseUrl}/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method")
            .append("&tweetid=${tweet.mid}&userid=${appUser.mid}")
            .append("&pn=$pageNumber&commentid=$commentId").toString()
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string() ?: return listOf()
            val gson = Gson()
            return gson.fromJson(responseBody, object : TypeToken<List<Tweet>>() {}.type)
        }
        return listOf<Tweet>()
    }

    fun delComment(tweetId: MimeiId, commentId: MimeiId) {
        // remove a comment from parent tweet in Mimei DB
    }

    // update input parameter "comment" with new mid, and return count
    fun uploadComment(tweet: Tweet, comment: Tweet): Tweet {
        // add the comment to tweetId
        val method = "add_comment"
        val json = URLEncoder.encode(Json.encodeToString(comment), "utf-8")
        val url = "${tweet.author?.baseUrl}/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method&tweetid=${tweet.mid}&comment=$json"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string() ?: return tweet
            val gson = Gson()
            val res = gson.fromJson(responseBody, Map::class.java) as Map<*, *>
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
            "${author.baseUrl}/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method&tweetid=${tweet.mid}&userid=${appUser.mid}"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string() ?: return tweet
            val gson = Gson()
            val res = gson.fromJson(responseBody, Map::class.java) as Map<*, *>

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
            "${author.baseUrl}/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method&tweetid=${tweet.mid}&userid=${appUser.mid}"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string() ?: return tweet
            val gson = Gson()
            val res = gson.fromJson(responseBody, Map::class.java) as Map<*, *>
            tweet.favorites?.set(UserFavorites.BOOKMARK, res["hasBookmarked"] as Boolean)
            return tweet.copy(
                bookmarkCount = (res["count"] as Double).toInt()
            )
        }
        return tweet
    }

    // Upload data from an InputStream to IPFS and return the resulting MimeiId.
    fun uploadToIPFS(inputStream: InputStream): MimeiId {
        val fsid = client.mfOpenTempFile(sid)
        var offset = 0
        inputStream.use { stream ->
            val buffer = ByteArray(CHUNK_SIZE)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                client.mfSetData(fsid, buffer, offset)
                offset += bytesRead
            }
        }
        val cid = client.mfTemp2Ipfs(
            fsid,
            appMid
        )    // Associate the uploaded data with the app's main Mimei
        println("cid=$cid")
        return cid
    }

    fun getMediaUrl(mid: MimeiId?, baseUrl: String?): Any {
        if (mid?.isNotEmpty() == true && baseUrl?.isNotEmpty() == true) {
            return if (mid.length > 27) {
                "$baseUrl/ipfs/$mid"
            } else {
                "$baseUrl/mm/$mid"
            }
        }
        return R.drawable.ic_user_avatar
    }

    fun isReachable(mid: MimeiId, ip: String, timeout: Int = 1000): User? {
        try {
            val method = "get_author_core_data"
            val url =
                "http://$ip/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method&userid=$mid"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return null
                val gson = Gson()
                val user = gson.fromJson(responseBody, User::class.java)
                user.baseUrl = "http://$ip"
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