package com.fireshare.tweet.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.Tweet
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json

@HiltWorker
class UploadCommentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val tweetString = inputData.getString("tweet") ?: return Result.failure()
            val parentTweet = Json.decodeFromString<Tweet>(tweetString)
            val isChecked = inputData.getBoolean("isChecked", false)    // whether the comment is also a tweet.
            val commentContent = inputData.getString("content")
            val attachmentUris = inputData.getStringArray("attachmentUris")?.toList() ?: emptyList<Uri>()

            val attachments = attachmentUris.map { uri ->
                val inputStream = applicationContext.contentResolver.openInputStream(Uri.parse(uri.toString()))
                if (inputStream == null) {
                    Log.e("UploadCommentWorker", "Failed to open input stream for URI: $uri")
                    return@map Result.failure()
                }
                inputStream.use { HproseInstance.uploadToIPFS(it) }
            }
            val comment = Tweet(
                authorId = appUser.mid,
                content = commentContent ?: "",
                attachments = attachments.mapNotNull { it.toString() }
            )
            HproseInstance.uploadComment(parentTweet, comment).let { newTweet: Tweet ->
                // newTweet is the parent Tweet with new comment
                // after uploading, comment.mid is updated.
                val gson = Gson()
                val retweet = if (isChecked) {
                    // retweet is the comment posted as a tweet
                    comment.originalTweetId = parentTweet.mid
                    comment.originalAuthorId = parentTweet.authorId
                    HproseInstance.uploadTweet(comment)
                } else null
                val map = mapOf("retweet" to gson.toJson(retweet), "comment" to gson.toJson(comment),
                    "newTweet" to gson.toJson(newTweet))

                Log.d("UploadCommentWorker", map.toString())
                val outputData = workDataOf("comment" to gson.toJson(map))
                return Result.success(outputData)
            }
        } catch (e: Exception) {
            Log.e("UploadTweetWorker", "Error in doWork", e)
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
            val attachmentUris = inputData.getStringArray("attachmentUris")?.toList() ?: emptyList<Uri>()

            val attachments = attachmentUris.map { uri ->
                val inputStream = applicationContext.contentResolver.openInputStream(Uri.parse(uri.toString()))
                if (inputStream == null) {
                    Log.e("UploadTweetWorker", "Failed to open input stream for URI: $uri")
                    return@map Result.failure()
                }
                inputStream.use { HproseInstance.uploadToIPFS(it) }
            }
            val tweet = Tweet(
                authorId = appUser.mid,
                content = tweetContent ?: " ",
                attachments = attachments.mapNotNull { it.toString() }
            )
            HproseInstance.uploadTweet(tweet)?.let { t: Tweet ->
                Log.d("UploadTweetWorker", tweet.toString())
//                withContext(Dispatchers.Main) {
//                    HproseInstance.tweetFeedViewModel.addTweet(newTweet)
//                }
//                val map = mapOf("mid" to t.mid, "content" to t.content, "authorId" to t.authorId,
//                    "attachments" to t.attachments, "timestamp" to t.timestamp)
                val gson = Gson()
                val outputData = workDataOf("tweet" to gson.toJson(t))
                return Result.success(outputData)
            }
            Result.failure()

        } catch (e: Exception) {
            Log.e("UploadTweetWorker", "Error in doWork", e)
            Result.failure()
        }
    }
}