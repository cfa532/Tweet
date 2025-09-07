package us.fireshare.tweet.service

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.updateRetweetCount
import us.fireshare.tweet.HproseInstance.uploadToIPFS
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter

@HiltWorker
class UploadCommentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            val tweetId = inputData.getString("tweetId") ?: return Result.failure()
            val authorId = inputData.getString("authorId") ?: return Result.failure()
            
            // Fetch the parent tweet using ID and author ID
            val parentTweet = HproseInstance.fetchTweet(tweetId, authorId) ?: return Result.failure()

            // whether the comment is also posted as a tweet.
            val isChecked = inputData.getBoolean("isCheckedToTweet", false)
            val commentContent = inputData.getString("content")     // comment content
            val attachmentUris = inputData.getStringArray("attachmentUris")?.toList() ?: emptyList<Uri>()

            val attachments = withContext(Dispatchers.IO) {
                attachmentUris.mapNotNull { uri ->
                    try {
                        Timber.tag("UploadCommentWorker").d("Starting upload for URI: $uri")
                        Timber.tag("UploadCommentWorker").d("Calling uploadToIPFS for URI: $uri")
                        val result = uploadToIPFS(applicationContext, uri.toString().toUri())
                        if (result != null) {
                            Timber.tag("UploadCommentWorker").d("Successfully uploaded attachment: ${result.mid}")
                        } else {
                            Timber.tag("UploadCommentWorker").e("uploadToIPFS returned null for URI: $uri")
                        }
                        result
                    } catch (e: Exception) {
                        Timber.tag("UploadCommentWorker").e(e, "Error uploading attachment: $uri")
                        null // Return null in case of error
                    }
                }
            }
            if (attachmentUris.size != attachments.size) {
                Timber.tag("UploadCommentWorker").e("Attachments upload failure")
                TweetNotificationCenter.postAsync(TweetEvent.CommentUploadFailed("Attachments upload failure"))
                return Result.failure()
            }
            val comment = Tweet(
                mid = TW_CONST.GUEST_ID,  // placeholder
                authorId = appUser.mid,
                content = commentContent,
                attachments = attachments,
                timestamp = System.currentTimeMillis()
            )

            val updatedTweet = HproseInstance.uploadComment(parentTweet, comment)
            if (updatedTweet != null) {
                // updatedTweet is the original tweet with new comment. After uploading comment,
                // !!!comment.mid is updated by uploadComment() with newly created mid!!!
                // retweet is a new tweet with the comment as its content.
                if (isChecked) {
                    comment.originalTweetId = parentTweet.mid
                    comment.originalAuthorId = parentTweet.authorId
                    HproseInstance.uploadTweet(comment)?.let { retweet ->
                        updateRetweetCount(parentTweet, retweet.mid)?.let {
                            updatedTweet.retweetCount = it.retweetCount
                        }
                    }
                }
                return Result.success()
            } else {
                TweetNotificationCenter.postAsync(TweetEvent.CommentUploadFailed("Comment upload failed"))
                return Result.failure()
            }
        } catch (e: Exception) {
            Timber.tag("UploadCommentWorker").e(e, "Error in doWork")
            TweetNotificationCenter.postAsync(TweetEvent.CommentUploadFailed("Comment upload failed: ${e.message}"))
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
                val attachments = mutableListOf<MimeiFileType>()
                val uriPairs = attachmentUris.chunked(2)
                for (pair in uriPairs) {
                    val deferreds =
                        mutableListOf<Deferred<MimeiFileType?>>()
                    for (uriString in pair) {
                        Timber.tag("UploadTweetWorker").d("Starting upload for URI: $uriString")
                        val deferred = CoroutineScope(Dispatchers.IO).async {
                            try {
                                Timber.tag("UploadTweetWorker").d("Calling uploadToIPFS for URI: $uriString")
                                val result = uploadToIPFS(applicationContext, uriString.toUri())
                                if (result != null) {
                                    Timber.tag("UploadTweetWorker").d("Successfully uploaded attachment: ${result.mid}")
                                } else {
                                    Timber.tag("UploadTweetWorker").e("uploadToIPFS returned null for URI: $uriString")
                                }
                                result
                            } catch (e: Exception) {
                                Timber.tag("UploadTweetWorker")
                                    .e(e, "Error uploading attachment: $uriString")
                                null
                            }
                        }
                        deferreds.add(deferred)
                    }
                    val results = deferreds.awaitAll()
                    Timber.tag("UploadTweetWorker").d("Upload results for pair: ${results.map { it?.mid ?: "null" }}")
                    results.forEach { result ->
                        if (result != null) {
                            attachments.add(result)
                            Timber.tag("UploadTweetWorker").d("Added attachment to list: ${result.mid}")
                        } else {
                            Timber.tag("UploadTweetWorker").e("Attachment upload failure in pair - null result")
                            TweetNotificationCenter.postAsync(TweetEvent.TweetUploadFailed("Attachment upload failed"))
                            return Result.failure()
                        }
                    }
                }

                if (attachmentUris.size != attachments.size) {
                    Timber.tag("UploadTweetWorker").e("Attachments upload failure")
                    TweetNotificationCenter.postAsync(TweetEvent.TweetUploadFailed("Attachments upload failure"))
                    return Result.failure()
                }

                val tweet = Tweet(
                    mid = System.currentTimeMillis().toString(), // placeholder
                    authorId = appUser.mid,
                    content = tweetContent ?: " ",
                    attachments = attachments,
                    isPrivate = isPrivate
                )

                HproseInstance.uploadTweet(tweet)?.let { t: Tweet ->
                    Timber.tag("UploadTweetWorker").d(tweet.toString())
                    
                    // Remove incomplete upload from tracking since it completed successfully
                    val workId = id.toString()
                    HproseInstance.removeIncompleteUpload(applicationContext, workId)
                    
                    return Result.success()
                }
                TweetNotificationCenter.postAsync(TweetEvent.TweetUploadFailed("Tweet upload failed"))
                Result.failure()
            } finally {
                wakeLock.release()
            }
        } catch (e: Exception) {
            Timber.tag("UploadTweetWorker").e(e, "Error in doWork")
            TweetNotificationCenter.postAsync(TweetEvent.TweetUploadFailed("Tweet upload failed: ${e.message}"))
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