package com.fireshare.tweet.network

import android.util.Log
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import hprose.client.HproseClient
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.io.InputStream
import java.math.BigInteger
import java.net.URLEncoder

object UserFavorites {
    const val TWEET = 0
    const val BOOKMARK = 1
    const val RETWEET = 2
}

// Encapsulate Hprose client and related operations in a singleton object.
object HproseInstance {
    private const val BASE_URL = "http://10.0.2.2:8081"
    const val TWBE_APP_ID = "d4lRyhABgqOnqY4bURSm_T-4FZ4"

    private const val CHUNK_SIZE = 50 * 1024 * 1024 // 10MB in bytes
    private const val APP_ID = "V6MUd0cVeuCFE7YsGLNn5ygyJlm"
    private const val APP_EXT = "com.example.twitterclone"
    private const val APP_MARK = "version 0.0.3"

    // Keys within the mimei of each tweet
    private const val TWT_CONTENT_KEY = "core_data_of_tweet"  // content key within the Mimei
    private const val TWT_LIKED_COUNT = "count_of_likes"

    // Keys within the mimei of the user's database
    private const val TWT_LIST_KEY = "list_of_tweets_mid"
    private const val OWNER_DATA_KEY = "data_of_author"     // account data of user
    private const val FOLLOWINGS_KEY = "list_of_followings_mid"
    private const val FOLLOWERS_KEY = "list_of_followers_mid"

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

    @Serializable
    data class TempJson(
        var sid: String = "",
        var mid: String = ""
    )

    lateinit var viewModel: TweetFeedViewModel

    val appUser: User by lazy {
        viewModel.users.find { it.mid == appMid } ?: runBlocking {
            withContext(IO) {
                val method = "get_author_core_data"
                val url = "$BASE_URL/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method&userid=$appMid"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                val user = Json.decodeFromString<User>(response.body?.string() ?: "")
                user.baseUrl = BASE_URL
                viewModel.users.add(user)
                user
            }
        }
    }

    // Initialize lazily, also used as UserId
    private val appMid: MimeiId by lazy {
        runBlocking {   // necessary for the whole App
            withContext(IO) {
                val method = "get_app_mid"
                val url = "$BASE_URL/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                val json = Json.decodeFromString<TempJson>(response.body?.string() ?: "")
                sid = json.sid
                json.mid
            }
        }
    }

    // Get base url where user data can be accessed, and user data
    private suspend fun getUserBase(userId: MimeiId): User? {
        // check if user data has been read
        viewModel.users.find { it.mid == userId }?.let { user -> return user }

        val providers = client.getVar("", "mmprovsips", userId)
        val providerList = Json.parseToJsonElement(providers).jsonArray
        if (providerList.isNotEmpty()) {
            println(providerList)
            val ipAddresses = providerList[0].jsonArray.map { it.jsonArray }
            Gadget.getFirstReachableUri(ipAddresses, userId)?.let { u ->
                viewModel.users.add(u)
                println("Get userbase=${viewModel.users}")
                return u
            }
        }
        return null
    }

    suspend fun getUserData(userId: MimeiId = appMid): User? {
        return runCatching {
            // get each user data based on its node ip
            val user = getUserBase(userId) ?: return null
            client.mmOpen("", userId, "last").let {
                client.get(it, OWNER_DATA_KEY)?.let { userData ->
                    userData as User
                }
            }
        }.onFailure { e ->
            Log.e("HproseInstance.getUserData", "Failed to get user data for userId: $userId", e)
        }.getOrNull()
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
                    getTweet(tweetId, authorId)?.let { t -> tweets += t }
                }
            }
        }
    } catch (e: Exception) {
        Log.e("HproseInstance.getTweets", e.toString())
    }

    private suspend fun getTweet(tweetId: MimeiId, authorId: MimeiId): Tweet? {
        // if the tweet is fetched already, return null
//        InMemoryData._tweets.value.find { it.mid == tweetId }?.let { return null }

        val author = getUserBase(authorId) ?: return null   // cannot get author data, return null
        val method = "get_tweet"
        val url =
            "${author.baseUrl}/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method&tweetid=$tweetId&userid=${appUser.mid}"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            response.body?.string()?.let { json ->
                println("getTweet=$json")
                val tweet = Json.decodeFromString<Tweet>(json)
                tweet.author = author

                if (tweet.originalTweetId != null) {
                    return null
//                    val cachedTweet =
//                        InMemoryData._tweets.value.find { it.mid == tweet.originalTweetId }
//                    if (cachedTweet != null) {
//                        // isPrivate could be null
//                        tweet.originalAuthor = cachedTweet.author
//                        tweet.originalTweet = cachedTweet;
//                    } else {
//                        tweet.originalTweet =
//                            tweet.originalAuthorId?.let { getTweet(tweet.originalTweetId, it) }
//                                ?: return null
//                        tweet.originalAuthor = tweet.originalTweet!!.author
//                    }
                }
                return tweet
            }
        }
        return null
    }

    // Store an object in a Mimei file and return its MimeiId.
    fun uploadTweet(tweet: Tweet, commentOnly: Boolean = false): Tweet? {
        val method = "upload_tweet"

        // make a copy of input tweet and remove attributes that is for display only.
//        val t = Tweet(mid = tweet.mid, authorId = tweet.authorId, content = tweet.content,
//            timestamp = tweet.timestamp, attachments = tweet.attachments, originalTweetId = tweet.originalTweetId,
//            originalAuthorId = tweet.originalAuthorId)

        val json = URLEncoder.encode(Json.encodeToString(tweet), "utf-8")
        val url =
            "${appUser.baseUrl}/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method&tweet=$json&commentonly=$commentOnly"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            tweet.mid = response.body?.string() ?: return null
            tweet.author = appUser
            return tweet
        }
        return null
    }

    private fun deleteTweet(tweetId: MimeiId) {
        val method = "delete_tweet"
        val url =
            "${appUser.baseUrl}/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method&tweetid=$tweetId&authorid=${appUser.mid}"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
//            InMemoryData._tweets.update { currentTweets ->
//                currentTweets.filterNot { it.mid == tweetId }
//            }
        }
    }

    // retweet or cancel retweet
    fun toggleRetweet(tweet: Tweet /* original tweet */): Tweet? {
        val method = "toggle_retweet"
        val hasRetweeted = tweet.favorites?.get(UserFavorites.RETWEET) ?: return null
        val url = StringBuilder("${tweet.author?.baseUrl}/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method")
            .append("&tweetid=${tweet.mid}")
            .append("&userid=${appUser.mid}")

        if (hasRetweeted) {
            // remove the retweet. Get retweetId first
            val request = Request.Builder().url(url.toString()).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return null
                val res = Json.decodeFromString<Map<*, *>>(responseBody)
                deleteTweet(res["retweetId"] as MimeiId)
                tweet.favorites!![UserFavorites.RETWEET] = false
                return tweet.copy(retweetCount = (res["count"] as Double).toInt())
            }
        } else {
            var retweet = Tweet(
                content = "",
                authorId = appUser.mid,
                originalTweetId = tweet.mid,
                originalAuthorId = tweet.authorId
            )
            retweet = uploadTweet(retweet) ?: return null
            url.append("&retweetid=${retweet.mid}")

            val request = Request.Builder().url(url.toString()).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return null
                val res = Json.decodeFromString<Map<*, *>>(responseBody)
                tweet.favorites!![UserFavorites.RETWEET] = true

                retweet.author = appUser
                retweet.originalTweet = tweet
                return tweet.copy(retweetCount = (res["count"] as Double).toInt())
            }
        }
        return null
    }

    fun likeTweet(tweet: Tweet): Tweet? {
        val author = tweet.author ?: return null
        val method = "liked_count"
        val url =
            "${author.baseUrl}/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method&tweetid=${tweet.mid}&userid=${appUser.mid}"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string() ?: return tweet
            val res = Json.decodeFromString<Map<*, *>>(responseBody)

            // return a new object for recomposition to work.
            tweet.favorites?.set(UserFavorites.TWEET, res["hasLiked"] as Boolean)
            return tweet.copy(
                likeCount = (res["count"] as Double).toInt()
            )
        }
        return tweet
    }

    fun bookmarkTweet(tweet: Tweet): Tweet? {
        val author = tweet.author ?: return null
        val method = "bookmark"
        val url =
            "${author.baseUrl}/entry?&aid=$TWBE_APP_ID&ver=last&entry=$method&tweetid=${tweet.mid}&userid=${appUser.mid}"
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string() ?: return tweet
            val res = Json.decodeFromString<Map<*, *>>(responseBody)
            tweet.favorites?.set(UserFavorites.BOOKMARK, res["hasBookmarked"] as Boolean)
            return tweet.copy(bookmarkCount = (res["count"] as Double).toInt())
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

    fun getMediaUrl(mid: MimeiId?, baseUrl: String): Any {
        if (mid?.isNotEmpty() == true) {
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
                val user = Json.decodeFromString<User>(responseBody)
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