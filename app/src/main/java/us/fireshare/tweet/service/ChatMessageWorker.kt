package us.fireshare.tweet.service

import android.content.Context
import android.os.PowerManager
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.uploadToIPFS
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.ChatSession
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import us.fireshare.tweet.utils.ErrorMessageUtils

@HiltWorker
class SendChatMessageWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val runAttemptCount = runAttemptCount
        val receiptId = inputData.getString("receiptId") ?: run {
            Timber.tag("SendChatMessageWorker").e("Missing receiptId in input data")
            // Only show toast on final attempt
            if (runAttemptCount >= 3) {
                TweetNotificationCenter.postAsync(TweetEvent.ChatMessageSendFailed("Missing recipient ID"))
            }
            return Result.failure()
        }
        
        val content = inputData.getString("content") ?: ""
        val attachmentUri = inputData.getString("attachmentUri")
        val messageTimestamp = inputData.getLong("messageTimestamp", System.currentTimeMillis())
        val optimisticMessageId = inputData.getString("optimisticMessageId")

        Timber.tag("SendChatMessageWorker").d("Starting message send: receiptId=$receiptId, content=$content, attachmentUri=$attachmentUri, optimisticMessageId=$optimisticMessageId")

        val powerManager =
            applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Chat:SendMessageWakeLockTag"
        )
        wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/)

        return try {
            var attachments: List<us.fireshare.tweet.datamodel.MimeiFileType>? = null

            // Upload attachment if provided
            if (attachmentUri != null) {
                try {
                    Timber.tag("SendChatMessageWorker").d("Starting attachment upload: $attachmentUri")
                    val uri = attachmentUri.toUri()
                    val uploadedFile = withContext(Dispatchers.IO) {
                        uploadToIPFS(uri)
                    }
                    if (uploadedFile != null) {
                        attachments = listOf(uploadedFile)
                        Timber.tag("SendChatMessageWorker")
                            .d("File uploaded successfully: ${uploadedFile.mid}")
                        
                        // Cache the local file immediately with the uploaded mid
                        // This way it's available instantly when the message is displayed
                        withContext(Dispatchers.IO) {
                            try {
                                when (uploadedFile.type) {
                                    us.fireshare.tweet.datamodel.MediaType.Image -> {
                                        // Cache local image file
                                        us.fireshare.tweet.widget.ImageCacheManager.cacheLocalImageFile(
                                            applicationContext,
                                            uploadedFile.mid,
                                            uri
                                        )
                                        Timber.tag("SendChatMessageWorker")
                                            .d("Cached local image for mid: ${uploadedFile.mid}")
                                    }
                                    us.fireshare.tweet.datamodel.MediaType.Video,
                                    us.fireshare.tweet.datamodel.MediaType.HLS_VIDEO -> {
                                        // Cache local video file
                                        us.fireshare.tweet.widget.VideoManager.cacheLocalVideoFile(
                                            applicationContext,
                                            uploadedFile.mid,
                                            uri
                                        )
                                        Timber.tag("SendChatMessageWorker")
                                            .d("Cached local video for mid: ${uploadedFile.mid}")
                                    }
                                    else -> {
                                        // For other file types, we don't need to cache
                                        Timber.tag("SendChatMessageWorker")
                                            .d("Skipping cache for non-media file type: ${uploadedFile.type}")
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.tag("SendChatMessageWorker")
                                    .w(e, "Failed to cache local file, will download from network later")
                                // Don't fail the whole operation if caching fails
                            }
                        }
                    } else {
                        Timber.tag("SendChatMessageWorker").e("Failed to upload file - uploadToIPFS returned null")
                        // Show localized error message to user
                        val errorMessage = applicationContext.getString(R.string.attachment_upload_failed)
                        TweetNotificationCenter.postAsync(TweetEvent.ChatMessageSendFailed(errorMessage))
                        return Result.failure()
                    }
                } catch (e: Exception) {
                    Timber.tag("SendChatMessageWorker").e(e, "Error uploading file: $attachmentUri")
                    // Show localized error message to user
                    val errorMessage = applicationContext.getString(R.string.attachment_upload_failed)
                    TweetNotificationCenter.postAsync(TweetEvent.ChatMessageSendFailed(errorMessage))
                    return Result.failure()
                }
            }

            // Determine content for the message
            val messageContent = when {
                content.trim().isNotBlank() -> content.trim()
                !attachments.isNullOrEmpty() -> null // Don't show any text for attachment-only messages
                else -> null
            }

            // Generate session ID for this conversation
            val conversationSessionId =
                ChatSession.generateDeterministicSessionId(appUser.mid, receiptId)

            val message = ChatMessage(
                receiptId = receiptId,
                authorId = appUser.mid,
                timestamp = messageTimestamp,
                content = messageContent,
                attachments = attachments,
                sessionId = conversationSessionId
            )

            Timber.tag("SendChatMessageWorker").d("Sending message: ${message.id}")

            // Send message via network
            val (success, errorMsg) = withContext(Dispatchers.IO) {
                try {
                    HproseInstance.sendMessage(receiptId, message)
                } catch (e: Exception) {
                    Timber.tag("SendChatMessageWorker").e(e, "Error sending message via network")
                    Pair(false, ErrorMessageUtils.getNetworkErrorMessage(applicationContext, e))
                }
            }

            if (success) {
                // Notify success
                Timber.tag("SendChatMessageWorker").d("Message sent successfully: ${message.id}")
                TweetNotificationCenter.postAsync(TweetEvent.ChatMessageSent(message))
                Result.success(
                    workDataOf(
                        "receiptId" to receiptId,
                        "messageId" to message.timestamp.toString()
                    )
                )
            } else {
                // Create failed message with error info
                Timber.tag("SendChatMessageWorker").e("Message send failed: $errorMsg")
                val failedMessage = message.copy(success = false, errorMsg = errorMsg)
                TweetNotificationCenter.postAsync(TweetEvent.ChatMessageSent(failedMessage))
                Result.failure()
            }
        } catch (e: Exception) {
            Timber.tag("SendChatMessageWorker").e(e, "Unexpected error in doWork")
            // Only show toast on final attempt
            if (runAttemptCount >= 3) {
                TweetNotificationCenter.postAsync(TweetEvent.ChatMessageSendFailed("Message send failed: ${e.message}"))
            }
            Result.failure()
        } finally {
            try {
                wakeLock.release()
                Timber.tag("SendChatMessageWorker").d("Wake lock released")
            } catch (e: Exception) {
                Timber.tag("SendChatMessageWorker").e(e, "Error releasing wake lock")
            }
        }
    }
}