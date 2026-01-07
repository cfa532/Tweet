package us.fireshare.tweet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import us.fireshare.tweet.R
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.updateRetweetCount
import us.fireshare.tweet.HproseInstance.uploadToIPFS
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter

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
        } catch (e: Exception) {
            Timber.tag("UploadCommentWorker").e(e, "Error in doWork")
            // Only show toast on final attempt
            if (runAttemptCount >= 1) {
                TweetNotificationCenter.postAsync(TweetEvent.CommentUploadFailed("Comment upload failed: ${e.message}"))
            }
            cleanupPermissions()
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
        val runAttemptCount = runAttemptCount
        return try {
            // Limit to 2 total attempts: initial attempt (0) + 1 retry (1)
            // Stop if we've already tried twice (runAttemptCount >= 2)
            if (runAttemptCount >= 2) {
                Timber.tag("UploadTweetWorker").d("Maximum retry attempts reached (attempt $runAttemptCount), giving up")
                TweetNotificationCenter.postAsync(TweetEvent.TweetUploadFailed("Tweet upload failed after 2 attempts"))
                val workId = id.toString()
                HproseInstance.removeIncompleteUpload(applicationContext, workId)
                return Result.failure()
            }
            
            Timber.tag("UploadTweetWorker").d("Tweet upload attempt ${runAttemptCount + 1} of 2")

            // Force baseUrl refresh before retrying (matching iOS behavior)
            if (runAttemptCount >= 1) {
                Timber.tag("UploadTweetWorker").d("Retry detected (attempt $runAttemptCount), forcing baseUrl refresh for appUser: ${appUser.mid}")
                try {
                    // Force IP re-resolution by passing empty baseUrl (matching iOS fetchUser with baseUrl: "")
                    val refreshedUser = HproseInstance.fetchUser(appUser.mid, baseUrl = "", maxRetries = 1, forceRefresh = true)
                    if (refreshedUser != null && !refreshedUser.isGuest() && !refreshedUser.baseUrl.isNullOrBlank()) {
                        // Update singleton and set appUser to it
                        User.updateUserInstance(refreshedUser, true)
                        HproseInstance.appUser = User.getInstance(refreshedUser.mid)
                        Timber.tag("UploadTweetWorker").d("Successfully refreshed baseUrl for appUser during tweet upload retry: ${refreshedUser.baseUrl}")
                    } else {
                        Timber.tag("UploadTweetWorker").w("Failed to refresh baseUrl for appUser during tweet upload retry (invalid baseUrl: ${refreshedUser?.baseUrl})")
                    }
                } catch (e: Exception) {
                    Timber.tag("UploadTweetWorker").e(e, "Error refreshing baseUrl for appUser during tweet upload retry")
                }
            }
            
            val tweetContent = inputData.getString("tweetContent")
            val attachmentUris =
                inputData.getStringArray("attachmentUris")?.toList() ?: emptyList()
            val isPrivate = inputData.getBoolean("isPrivate", false)

            Timber.tag("UploadTweetWorker").d("Starting tweet upload - content: '${tweetContent ?: "empty"}', uris: ${attachmentUris.size}, private: $isPrivate")

            val powerManager =
                applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Tweet:UploadWakeLockTag"
            )
            wakeLock.acquire(3 * 60 * 60 * 1000L /*3 hours*/)

            // Create foreground notification for long-running video processing
            val notification = createForegroundNotification(applicationContext)
            setForeground(ForegroundInfo(1, notification))
            try {
                val attachments = mutableListOf<MimeiFileType>()
                Timber.tag("UploadTweetWorker").d("Processing ${attachmentUris.size} attachments sequentially")

                for ((index, uriString) in attachmentUris.withIndex()) {
                    Timber.tag("UploadTweetWorker").d("Processing attachment ${index + 1}/${attachmentUris.size}: ${uriString.takeLast(20)}")

                    try {
                        Timber.tag("UploadTweetWorker").d("Calling uploadToIPFS for URI: $uriString")
                        val result = uploadToIPFS(uriString.toUri())

                        if (result != null) {
                            attachments.add(result)
                            Timber.tag("UploadTweetWorker").d("Successfully uploaded attachment ${index + 1}: ${result.mid}")
                        } else {
                            Timber.tag("UploadTweetWorker").e("uploadToIPFS returned null for attachment ${index + 1}: $uriString")
                            // Only show toast on final attempt and clean up incomplete upload
                            if (runAttemptCount >= 1) {
                                TweetNotificationCenter.postAsync(TweetEvent.TweetUploadFailed("Attachment upload failed"))
                                // Clean up incomplete upload tracking on final failure
                                val workId = id.toString()
                                HproseInstance.removeIncompleteUpload(applicationContext, workId)
                            }
                            return Result.failure()
                        }
                    } catch (e: Exception) {
                        Timber.tag("UploadTweetWorker").e(e, "Error uploading attachment ${index + 1}: $uriString")
                        // Only show toast on final attempt and clean up incomplete upload
                        if (runAttemptCount >= 1) {
                            TweetNotificationCenter.postAsync(TweetEvent.TweetUploadFailed("Attachment upload failed"))
                            // Clean up incomplete upload tracking on final failure
                            val workId = id.toString()
                            HproseInstance.removeIncompleteUpload(applicationContext, workId)
                        }
                        return Result.failure()
                    }
                }

                Timber.tag("UploadTweetWorker").d("ALL ATTACHMENTS PROCESSED SUCCESSFULLY")
                Timber.tag("UploadTweetWorker").d("REACHED TWEET CREATION SECTION")
                Timber.tag("UploadTweetWorker").d("Attachment upload summary: uris=${attachmentUris.size}, attachments=${attachments.size}")
                Timber.tag("UploadTweetWorker").d("Attachments list: ${attachments.map { it.mid }}")

                if (attachmentUris.size != attachments.size) {
                    Timber.tag("UploadTweetWorker").e("Attachments upload failure - size mismatch")
                    // Only show toast on final attempt and clean up incomplete upload
                    if (runAttemptCount >= 1) {
                        TweetNotificationCenter.postAsync(TweetEvent.TweetUploadFailed("Attachments upload failure"))
                        // Clean up incomplete upload tracking on final failure
                        val workId = id.toString()
                        HproseInstance.removeIncompleteUpload(applicationContext, workId)
                    }
                    return Result.failure()
                }

                Timber.tag("UploadTweetWorker").d("All attachments uploaded successfully, proceeding to create tweet")

                Timber.tag("UploadTweetWorker").d("Creating tweet with content: '${tweetContent ?: " "}', attachments: ${attachments.size}")

                val tweet = Tweet(
                    mid = System.currentTimeMillis().toString(), // placeholder
                    authorId = appUser.mid,
                    content = tweetContent ?: " ",
                    attachments = attachments,
                    isPrivate = isPrivate
                )

                Timber.tag("UploadTweetWorker").d("Calling uploadTweet for tweet: ${tweet.mid}")
                val uploadResult = HproseInstance.uploadTweet(tweet)
                Timber.tag("UploadTweetWorker").d("uploadTweet returned: ${uploadResult?.mid ?: "null"}")

                uploadResult?.let { uploadedTweet: Tweet ->
                    Timber.tag("UploadTweetWorker").d("Tweet uploaded successfully: ${uploadedTweet.mid}")

                    // Remove incomplete upload from tracking since it completed successfully
                    val workId = id.toString()
                    HproseInstance.removeIncompleteUpload(applicationContext, workId)

                    return Result.success()
                }

                // uploadTweet failed and returned null
                Timber.tag("UploadTweetWorker").e("uploadTweet returned null - tweet upload failed")
                // Only show toast on final attempt and clean up incomplete upload
                if (runAttemptCount >= 1) {
                    TweetNotificationCenter.postAsync(TweetEvent.TweetUploadFailed("Tweet upload failed"))
                    // Clean up incomplete upload tracking on final failure
                    val workId = id.toString()
                    HproseInstance.removeIncompleteUpload(applicationContext, workId)
                }
                Result.failure()
            } finally {
                wakeLock.release()
            }
        } catch (e: OutOfMemoryError) {
            Timber.tag("UploadTweetWorker").e(e, "OUT OF MEMORY ERROR during tweet upload (attempt ${runAttemptCount + 1})")
            // Show error and clean up on final attempt (runAttemptCount >= 1 means 2nd+ attempt)
            if (runAttemptCount >= 1) {
                TweetNotificationCenter.postAsync(TweetEvent.TweetUploadFailed("Tweet upload failed: Out of memory"))
                val workId = id.toString()
                HproseInstance.removeIncompleteUpload(applicationContext, workId)
            }
            // WorkManager will retry once more if runAttemptCount < 2
            return Result.failure()
        } catch (e: Exception) {
            Timber.tag("UploadTweetWorker").e(e, "Error in doWork (attempt ${runAttemptCount + 1})")
            // Show error and clean up on final attempt (runAttemptCount >= 1 means 2nd+ attempt)
            if (runAttemptCount >= 1) {
                TweetNotificationCenter.postAsync(TweetEvent.TweetUploadFailed("Tweet upload failed: ${e.message}"))
                val workId = id.toString()
                HproseInstance.removeIncompleteUpload(applicationContext, workId)
            }
            // WorkManager will retry once more if runAttemptCount < 2
            return Result.failure()
        }
    }

    private fun createForegroundNotification(context: Context): Notification {
        val channelId = "video_upload_channel"
        val channelName = "Video Upload"

        // Create notification channel for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows video upload progress"
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(context, channelId)
            .setContentTitle("Uploading Video")
            .setContentText("Processing video for upload...")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
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
                        if (deletedTweetId != null) {
                            Timber.tag("DeleteTweetWorker").d("Tweet $deletedTweetId deleted.")
                            val outputData = workDataOf("tweetId" to deletedTweetId)
                            return@withContext Result.success(outputData)
                        } else {
                            Timber.tag("DeleteTweetWorker").w("Tweet deletion returned null")
                            return@withContext Result.failure()
                        }
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
        val runAttemptCount = runAttemptCount
        return try {
            val followedId = inputData.getString("followedId") ?: return Result.failure()
            val followingId = inputData.getString("followingId") ?: return Result.failure()
            val isFollowing = inputData.getBoolean("isFollowing", true) // true = follow, false = unfollow
            
            Timber.tag("FollowUserWorker").d("Starting follow operation: followedId=$followedId, followingId=$followingId, isFollowing=$isFollowing")
            
            // Force baseUrl refresh on retries (matching iOS behavior)
            if (runAttemptCount > 1) {
                Timber.tag("FollowUserWorker").d("Retry detected (attempt $runAttemptCount), forcing baseUrl refresh for appUser: ${appUser.mid}")
                try {
                    val refreshedUser = HproseInstance.fetchUser(appUser.mid, baseUrl = "", maxRetries = 1, forceRefresh = true)
                    if (refreshedUser != null && !refreshedUser.isGuest() && !refreshedUser.baseUrl.isNullOrBlank()) {
                        User.updateUserInstance(refreshedUser, true)
                        HproseInstance.appUser = User.getInstance(refreshedUser.mid)
                        Timber.tag("FollowUserWorker").d("Successfully refreshed baseUrl for appUser during follow retry: ${refreshedUser.baseUrl}")
                    } else {
                        Timber.tag("FollowUserWorker").w("Failed to refresh baseUrl for appUser during follow retry (invalid baseUrl: ${refreshedUser?.baseUrl})")
                    }
                } catch (e: Exception) {
                    Timber.tag("FollowUserWorker").e(e, "Error refreshing baseUrl for appUser during follow retry")
                }
            }
            
            val result = HproseInstance.toggleFollowing(followedId, followingId)
            
            if (result != null) {
                // Check if the result matches the expected state
                if (result == isFollowing) {
                    Timber.tag("FollowUserWorker").d("Follow operation succeeded: result=$result")
                    return Result.success()
                } else {
                    Timber.tag("FollowUserWorker").w("Follow operation returned unexpected result: expected=$isFollowing, got=$result")
                    // Still consider it success if the operation completed, even if state is different
                    return Result.success()
                }
            } else {
                Timber.tag("FollowUserWorker").e("Follow operation returned null")
                // Only show notification on final attempt
                if (runAttemptCount >= 3) {
                    SystemNotificationManager.showFollowOperationFailedNotification(applicationContext, followedId)
                }
                return Result.failure()
            }
        } catch (e: Exception) {
            Timber.tag("FollowUserWorker").e(e, "Error in doWork: ${e.message}")
            // Only show notification on final attempt
            if (runAttemptCount >= 3) {
                val followedId = inputData.getString("followedId") ?: ""
                SystemNotificationManager.showFollowOperationFailedNotification(applicationContext, followedId)
            }
            return Result.failure()
        }
    }
}