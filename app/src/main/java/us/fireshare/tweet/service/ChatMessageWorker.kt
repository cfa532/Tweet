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
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.datamodel.ChatSession
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter

@HiltWorker
class SendChatMessageWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val receiptId = inputData.getString("receiptId") ?: run {
            Timber.tag("SendChatMessageWorker").e("Missing receiptId in input data")
            TweetNotificationCenter.postAsync(TweetEvent.ChatMessageSendFailed("Missing recipient ID"))
            return Result.failure()
        }
        
        val content = inputData.getString("content") ?: ""
        val attachmentUri = inputData.getString("attachmentUri")
        val messageTimestamp = inputData.getLong("messageTimestamp", System.currentTimeMillis())

        Timber.tag("SendChatMessageWorker").d("Starting message send: receiptId=$receiptId, content=$content, attachmentUri=$attachmentUri")

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
                    val uploadedFile = withContext(Dispatchers.IO) {
                        uploadToIPFS(applicationContext, attachmentUri.toUri())
                    }
                    if (uploadedFile != null) {
                        attachments = listOf(uploadedFile)
                        Timber.tag("SendChatMessageWorker")
                            .d("File uploaded successfully: ${uploadedFile.mid}")
                    } else {
                        Timber.tag("SendChatMessageWorker").e("Failed to upload file - uploadToIPFS returned null")
                        TweetNotificationCenter.postAsync(TweetEvent.ChatMessageSendFailed("Failed to upload attachment"))
                        return Result.failure()
                    }
                } catch (e: Exception) {
                    Timber.tag("SendChatMessageWorker").e(e, "Error uploading file: $attachmentUri")
                    TweetNotificationCenter.postAsync(TweetEvent.ChatMessageSendFailed("Failed to upload attachment: ${e.message}"))
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
                    Pair(false, e.message ?: "Network error")
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
            TweetNotificationCenter.postAsync(TweetEvent.ChatMessageSendFailed("Message send failed: ${e.message}"))
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