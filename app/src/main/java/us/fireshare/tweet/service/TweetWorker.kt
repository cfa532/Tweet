package us.fireshare.tweet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.uploadToIPFS
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import us.fireshare.tweet.datamodel.User

@HiltWorker
class UploadCommentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val runAttemptCount = runAttemptCount

        // Get attachment URIs early so we can clean them up
        val attachmentUriStrings = inputData.getStringArray("attachmentUris")?.toList() ?: emptyList<String>()

        // Clean up persistent URI permissions when done
        val cleanupPermissions = {
            attachmentUriStrings.forEach { uriString ->
                try {
                    val uri = uriString.toUri()
                    applicationContext.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Timber.tag("UploadCommentWorker").w("Failed to release permission for URI: $uriString")
                }
            }
        }

        return try {
            // Limit to 2 total attempts: initial attempt (0) + 1 retry (1)
            // Stop if we've already tried twice (runAttemptCount >= 2)
            if (runAttemptCount >= 2) {
                Timber.tag("UploadCommentWorker").d("Maximum retry attempts reached (attempt $runAttemptCount), giving up")
                TweetNotificationCenter.postAsync(TweetEvent.CommentUploadFailed("Comment upload failed after 2 attempts"))
                cleanupPermissions()
                return Result.failure()
            }
            
            Timber.tag("UploadCommentWorker").d("Comment upload attempt ${runAttemptCount + 1} of 2")

            val tweetId = inputData.getString("tweetId") ?: run {
                cleanupPermissions()
                return Result.failure()
            }
            val authorId = inputData.getString("authorId") ?: run {
                cleanupPermissions()
                return Result.failure()
            }

            // Force baseUrl refresh before retrying (matching iOS behavior)
            if (runAttemptCount >= 1) {
                Timber.tag("UploadCommentWorker").d("Retry detected (attempt $runAttemptCount), forcing baseUrl refresh for target user: $authorId")
                try {
                    // Force IP re-resolution by passing empty baseUrl (matching iOS fetchUser with baseUrl: "")
                    val refreshedUser = HproseInstance.fetchUser(authorId, baseUrl = "", maxRetries = 1, forceRefresh = true)
                    if (refreshedUser != null) {
                        Timber.tag("UploadCommentWorker").d("Successfully refreshed baseUrl for user $authorId during comment upload retry")
                    } else {
                        Timber.tag("UploadCommentWorker").w("Failed to refresh baseUrl for user $authorId during comment upload retry")
                    }
                } catch (e: Exception) {
                    Timber.tag("UploadCommentWorker").e(e, "Error refreshing baseUrl for user $authorId during comment upload retry")
                }
            }
            
            // Fetch the parent tweet using ID and author ID
            val parentTweet = HproseInstance.fetchTweet(tweetId, authorId) ?: run {
                cleanupPermissions()
                return Result.failure()
            }

            // whether the comment is also posted as a tweet.
            val isChecked = inputData.getBoolean("isCheckedToTweet", false)
            val commentContent = inputData.getString("content")     // comment content

            val attachments = withContext(Dispatchers.IO) {
                attachmentUriStrings.mapNotNull { uriString ->
                    try {
                        val uri = uriString.toUri()
                        Timber.tag("UploadCommentWorker").d("Starting upload for URI: $uri")
                        Timber.tag("UploadCommentWorker").d("Calling uploadToIPFS for URI: $uri")
                        val result = uploadToIPFS(uri)
                        if (result != null) {
                            Timber.tag("UploadCommentWorker").d("Successfully uploaded attachment: ${result.mid}")
                        } else {
                            Timber.tag("UploadCommentWorker").e("uploadToIPFS returned null for URI: $uri")
                        }
                        result
                    } catch (e: Exception) {
                        Timber.tag("UploadCommentWorker").e(e, "Error uploading attachment: $uriString")
                        null // Return null in case of error
                    }
                }
            }
            if (attachmentUriStrings.size != attachments.size) {
                Timber.tag("UploadCommentWorker").e("Attachments upload failure")
                // Only show toast on final attempt
                if (runAttemptCount >= 1) {
                    TweetNotificationCenter.postAsync(TweetEvent.CommentUploadFailed("Attachments upload failure"))
                }
                return Result.failure()
            }
            val comment = Tweet(
                mid = TW_CONST.GUEST_ID,  // placeholder
                authorId = appUser.mid,
                parentTweetId = parentTweet.mid,
                content = commentContent,
                attachments = attachments,
                timestamp = System.currentTimeMillis()
            )

            // Build the quote payload from the comment's own data rather than mutating the
            // comment. The two are separate objects server-side — add_comment.js strips
            // originalTweetId before storing the comment — and a comment must never influence
            // the original's retweetCount.
            val quotePayload = if (isChecked) {
                Tweet(
                    mid = TW_CONST.GUEST_ID,  // placeholder, replaced by the backend
                    authorId = appUser.mid,
                    parentTweetId = parentTweet.mid,
                    originalTweetId = parentTweet.mid,
                    originalAuthorId = parentTweet.authorId,
                    content = commentContent,
                    attachments = attachments,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                null
            }

            val updatedTweet = HproseInstance.uploadComment(parentTweet, comment, id.toString())
            if (updatedTweet != null) {
                // updatedTweet is the original tweet with new comment. After uploading comment,
                // !!!comment.mid is updated by uploadComment() with newly created mid!!!
                if (quotePayload != null) {
                    if (HproseInstance.uploadTweet(quotePayload, "$id-quote") == null) {
                        Timber.tag("UploadCommentWorker").e("Quote tweet upload failed")
                    }
                }
                cleanupPermissions()
                return Result.success()
            } else {
                // Only show toast on final attempt
                if (runAttemptCount >= 1) {
                    TweetNotificationCenter.postAsync(TweetEvent.CommentUploadFailed("Comment upload failed"))
                }
                cleanupPermissions()
                return Result.failure()
            }
        } catch (e: CancellationException) {
            // A system-driven cancellation (constraints, app standby) is not a
            // failed upload; rethrow so WorkManager reschedules instead of the
            // UI reporting a permanent failure the user never saw happen.
            throw e
        } catch (e: Exception) {
            Timber.tag("UploadCommentWorker").e(e, "Error in doWork")
            // Only show toast on final attempt
            if (runAttemptCount >= 1) {
                TweetNotificationCenter.postAsync(TweetEvent.CommentUploadFailed("Comment upload failed: ${e.message}"))
            }
            cleanupPermissions()
            // Transient errors use the configured backoff for one retry; the
            // runAttemptCount gates above expect Result.retry to be reachable.
            if (runAttemptCount < 1) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}

@HiltWorker
class UploadTweetWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val author = appUser
        val draftAuthor = inputData.getString("authorId")
        if (author.isGuest() || (draftAuthor != null && draftAuthor != author.mid)) {
            return reportFailure(R.string.tweet_session_required)
        }

        return try {
            val attachmentUris = inputData.getStringArray("attachmentUris").orEmpty()
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Tweet:UploadWakeLockTag"
            )
            wakeLock.acquire(3 * 60 * 60 * 1000L)
            try {
                val steps = attachmentUris.size + 1
                setForeground(uploadForegroundInfo(steps, completed = 0))
                val attachments = mutableListOf<MimeiFileType>()
                for ((index, uri) in attachmentUris.withIndex()) {
                    val attachment = uploadToIPFS(uri.toUri(), uploadUser = author)
                    if (attachment == null) {
                        Timber.tag("UploadTweetWorker").e("Upload failed for attachment ${index + 1}")
                        return reportFailure(R.string.tweet_attachment_failed)
                    }
                    attachments.add(attachment)
                    setForeground(uploadForegroundInfo(steps, completed = index + 1))
                }

                if (appUser.mid != author.mid) {
                    return reportFailure(R.string.tweet_session_required)
                }
                val tweet = Tweet(
                    mid = System.currentTimeMillis().toString(),
                    authorId = author.mid,
                    content = inputData.getString("tweetContent") ?: " ",
                    attachments = attachments,
                    isPrivate = inputData.getBoolean("isPrivate", false)
                )
                HproseInstance.submitTweet(tweet, inputData.getString("requestId") ?: id.toString())
                HproseInstance.removeIncompleteUpload(applicationContext, id.toString())
                showAppToast(applicationContext.getString(R.string.tweet_uploaded))
                Result.success()
            } finally {
                wakeLock.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HproseInstance.TweetSessionRequiredException) {
            Timber.tag("UploadTweetWorker").w(e, "Tweet submission rejected")
            reportFailure(R.string.tweet_session_required)
        } catch (e: OutOfMemoryError) {
            Timber.tag("UploadTweetWorker").e(e, "Out of memory during tweet upload")
            reportFailure()
        } catch (e: Exception) {
            Timber.tag("UploadTweetWorker").e(e, "Tweet upload failed")
            reportFailure()
        }
    }

    private fun reportFailure(messageRes: Int = R.string.tweet_failed): Result =
        Result.failure(workDataOf("error" to applicationContext.getString(messageRes)))

    private suspend fun showAppToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                applicationContext,
                message,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun uploadForegroundInfo(steps: Int, completed: Int): ForegroundInfo {
        val context = applicationContext
        val channelId = "tweet_upload_channel"
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(channelId, context.getString(R.string.uploading), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(context.getString(R.string.uploading))
            .setContentText(context.getString(R.string.tweet_uploading_background))
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setProgress(steps, completed, false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        return ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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
                    try {
                        val deletedTweetId = HproseInstance.deleteTweet(tweetId)
                        Timber.tag("DeleteTweetWorker").d("Tweet $deletedTweetId deleted.")
                        val outputData = workDataOf("tweetId" to deletedTweetId)
                        return@withContext Result.success(outputData)
                    } catch (e: Exception) {
                        Timber.tag("DeleteTweetWorker").e(e, "Error deleting tweet: ${e.message}")
                        return@withContext Result.failure()
                    }
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

@HiltWorker
class FollowUserWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            val followedId = inputData.getString("followedId") ?: return Result.failure()
            val followingId = inputData.getString("followingId") ?: return Result.failure()
            val isFollowing = inputData.getBoolean("isFollowing", true) // true = follow, false = unfollow

            Timber.tag("FollowUserWorker").d("Starting follow operation: followedId=$followedId, followingId=$followingId, isFollowing=$isFollowing")

            val result = HproseInstance.toggleFollowing(followedId, isFollowing, followingId)

            if (result != null) {
                if (result != isFollowing) {
                    Timber.tag("FollowUserWorker").w("Follow op completed but server returned isFollowing=$result, expected=$isFollowing")
                }
                Timber.tag("FollowUserWorker").d("Follow operation succeeded: result=$result")
                // Pipe the server-returned isFollowing back to UserViewModel so it
                // can reconcile UI state to server truth (matches iOS behavior).
                Result.success(workDataOf("serverIsFollowing" to result))
            } else {
                Timber.tag("FollowUserWorker").e("Follow operation returned null")
                SystemNotificationManager.showFollowOperationFailedNotification(applicationContext, followedId)
                Result.failure()
            }
        } catch (e: Exception) {
            Timber.tag("FollowUserWorker").e(e, "Error in doWork: ${e.message}")
            val followedId = inputData.getString("followedId") ?: ""
            SystemNotificationManager.showFollowOperationFailedNotification(applicationContext, followedId)
            Result.failure()
        }
    }
}
