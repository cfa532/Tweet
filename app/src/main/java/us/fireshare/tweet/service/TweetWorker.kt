package us.fireshare.tweet.service

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.updateRetweetCount
import us.fireshare.tweet.HproseInstance.uploadToIPFS
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet

@HiltWorker
class UploadCommentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            val tweetString = inputData.getString("tweet") ?: return Result.failure()
            val originalTweet = Json.decodeFromString<Tweet>(tweetString)

            // whether the comment is also posted as a tweet.
            val isChecked = inputData.getBoolean("isChecked", false)
            val commentContent = inputData.getString("content")
            val attachmentUris = inputData.getStringArray("attachmentUris")?.toList() ?: emptyList<Uri>()

            val attachments = withContext(Dispatchers.IO) {
                attachmentUris.mapNotNull { uri ->
                    try {
                        uploadToIPFS(applicationContext, uri.toString().toUri())
                    } catch (e: Exception) {
                        Timber.tag("UploadCommentWorker").e(e, "Error uploading attachment: $uri")
                        null // Return null in case of error
                    }
                }
            }
            if (attachmentUris.size != attachments.size) {
                Timber.tag("UploadCommentWorker").e("Attachments upload failure")
                return Result.failure()
            }
            val comment = Tweet(
                mid = TW_CONST.GUEST_ID,  // placeholder
                authorId = appUser.mid,
                content = commentContent,
                attachments = attachments,
                timestamp = System.currentTimeMillis()
            )

            HproseInstance.uploadComment(originalTweet, comment).let { updatedTweet: Tweet ->
                // updatedTweet is the original tweet with new comment. After uploading comment,
                // !!!comment.mid is updated by uploadComment() with newly created mid!!!
                // retweet is a new tweet with the comment as its content.
                val retweet = if (isChecked) {
                    comment.originalTweetId = originalTweet.mid
                    comment.originalAuthorId = originalTweet.authorId
                    HproseInstance.uploadTweet(comment)?.let { retweet ->
                        updateRetweetCount(originalTweet, retweet.mid)?.let {
                            updatedTweet.retweetCount = it.retweetCount
                        }
                        retweet
                    }
                } else null

                val gson = Gson()
                val map = mapOf("retweet" to gson.toJson(retweet), "comment" to gson.toJson(comment),
                    "updatedTweet" to gson.toJson(updatedTweet))
                Timber.tag("UploadCommentWorker").d(map.toString())
                val outputData = workDataOf("commentedTweet" to gson.toJson(map))
                return Result.success(outputData)
            }
        } catch (e: Exception) {
            Timber.tag("UploadCommentWorker").e(e, "Error in doWork")
            Result.failure()
        }
    }
}

@HiltWorker
class UploadTweetWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            val tweetContent = inputData.getString("tweetContent")
            val attachmentUris =
                inputData.getStringArray("attachmentUris")?.toList() ?: emptyList()
            val isPrivate = inputData.getBoolean("isPrivate", false)

            val powerManager =
                applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Tweet:UploadWakeLockTag"
            )
            wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/)
            try {
                val attachments = mutableListOf<MimeiFileType>() // Changed to MimeiFileType
                val uriPairs = attachmentUris.chunked(2)
                for (pair in uriPairs) {
                    val deferreds = mutableListOf<Deferred<MimeiFileType?>>() // Changed to MimeiFileType?
                    for (uriString in pair) {
                        val deferred = CoroutineScope(Dispatchers.IO).async {
                            try {
                                uploadToIPFS(applicationContext, uriString.toUri())
                            } catch (e: Exception) {
                                Timber.tag("UploadCommentWorker")
                                    .e(e, "Error uploading attachment: $uriString")
                                null // Return null in case of error
                            }
                        }
                        deferreds.add(deferred)
                    }
                    val results = deferreds.awaitAll()
                    results.forEach { result ->
                        if (result != null) {
                            attachments.add(result)
                        } else {
                            Timber.tag("UploadTweetWorker").e("Attachment upload failure in pair")
                            wakeLock.release()
                            return Result.failure() // Fail if any upload in the pair fails
                        }
                    }
                }

                if (attachmentUris.size != attachments.size) {
                    Timber.tag("UploadTweetWorker").e("Attachments upload failure")
                    wakeLock.release()
                    return Result.failure()
                }

                val tweet = Tweet(
                    mid = System.currentTimeMillis().toString(), // placeholder
                    authorId = appUser.mid,
                    content = tweetContent ?: " ",
                    attachments = attachments, // Now a List<MimeiFileType>
                    isPrivate = isPrivate
                )

                // might make the upload less error prone
                withContext(Dispatchers.IO) {
                    HproseInstance.uploadTweet(tweet)?.let { t: Tweet ->
                        Timber.tag("UploadTweetWorker").d(tweet.toString())
                        val gson = Gson()
                        val outputData = workDataOf("tweet" to gson.toJson(t))
                        return@withContext Result.success(outputData)
                    }
                    return@withContext Result.failure()
                }
            } finally {
                wakeLock.release()
            }
        } catch (e: Exception) {
            Timber.tag("UploadTweetWorker").e(e, "Error in doWork")
            return Result.failure()
        }
    }
}

@HiltWorker
class DeleteTweetWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            val tweetId: MimeiId = inputData.getString("tweetId").toString()
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Tweet:UploadWakeLockTag"
            )
            wakeLock.acquire(10*60*1000L /*10 minutes*/)
            try {
                // might make the upload less error prone
                withContext(Dispatchers.IO) {
                    HproseInstance.delTweet(tweetId)?.let { tweetId: MimeiId ->
                        Timber.tag("DeleteTweetWorker").d("Tweet $tweetId deleted.")
                        val outputData = workDataOf("tweetId" to tweetId)
                        return@withContext Result.success(outputData)
                    }
                    return@withContext Result.failure()
                }
            } finally {
                wakeLock.release()
            }
        } catch (e: Exception) {
            Timber.tag("DeleteTweetWorker").e(e, "Error in doWork")
            Result.failure()
        }
    }
}