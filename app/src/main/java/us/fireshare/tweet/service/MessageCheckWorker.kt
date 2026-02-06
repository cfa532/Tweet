package us.fireshare.tweet.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.datamodel.ChatDatabase
import us.fireshare.tweet.datamodel.ChatMessage

/**
 * Worker for periodic message checking using WorkManager.
 * Runs every 15 minutes to check for new messages and update badge count.
 */
@HiltWorker
class MessageCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Timber.tag("MessageCheckWorker").d("Starting periodic message check...")
            
            // Check if user is logged in
            if (us.fireshare.tweet.HproseInstance.appUser.isGuest()) {
                Timber.tag("MessageCheckWorker").d("User is guest, skipping message check")
                return@withContext Result.success()
            }
            
            val newMessages = try {
                HproseInstance.checkNewMessages()
            } catch (e: Exception) {
                Timber.tag("MessageCheckWorker").e(e, "Error calling checkNewMessages")
                return@withContext Result.retry()
            }
            
            if (newMessages != null && newMessages.isNotEmpty()) {
                Timber.tag("MessageCheckWorker").d("Found ${newMessages.size} messages from server")
                
                // Filter out messages that already exist in local database
                val database = try {
                    ChatDatabase.getInstance(applicationContext)
                } catch (e: Exception) {
                    Timber.tag("MessageCheckWorker").e(e, "Error getting ChatDatabase instance")
                    return@withContext Result.retry()
                }
                
                val chatMessageDao = database.chatMessageDao()
                
                val trulyNewMessages = newMessages.filter { message ->
                    try {
                        // Check if message already exists in database
                        val existingMessage = chatMessageDao.getMessageByMessageId(message.id)
                        existingMessage == null
                    } catch (e: Exception) {
                        Timber.tag("MessageCheckWorker").e(e, "Error checking message existence: ${message.id}")
                        false // Assume message exists to avoid duplicates
                    }
                }
                
                Timber.tag("MessageCheckWorker").d("After filtering: ${trulyNewMessages.size} truly new messages")
                
                if (trulyNewMessages.isNotEmpty()) {
                    BadgeStateManager.updateBadgeCount(trulyNewMessages.size)
                    Timber.tag("MessageCheckWorker").d("Updated badge count to: ${trulyNewMessages.size}")
                    
                    // Show system bar notification for new messages
                    showNewMessageNotifications(trulyNewMessages)
                } else {
                    BadgeStateManager.updateBadgeCount(0)
                    Timber.tag("MessageCheckWorker").d("Cleared badge count - no new messages")
                }
            } else {
                Timber.tag("MessageCheckWorker").d("No new messages found")
                BadgeStateManager.updateBadgeCount(0)
            }
            
            Timber.tag("MessageCheckWorker").d("Message check completed successfully")
            Result.success()
        } catch (e: Exception) {
            Timber.tag("MessageCheckWorker").e(e, "Unexpected error in message check")
            Result.retry()
        }
    }
    
    /**
     * Show system bar notifications for new messages
     */
    private suspend fun showNewMessageNotifications(messages: List<ChatMessage>) {
        try {
            if (messages.isEmpty()) return
            
            // Get the first message for notification
            val firstMessage = messages.first()
            val authorId = firstMessage.authorId
            
            // Create message preview text
            val messagePreview = when {
                !firstMessage.content.isNullOrBlank() -> firstMessage.content
                !firstMessage.attachments.isNullOrEmpty() -> {
                    // Show attachment type in preview
                    val attachmentType = firstMessage.attachments.first().type.name.lowercase()
                    "Sent $attachmentType"
                }
                else -> "New message"
            }
            
            // Count messages from the same sender
            val messagesFromSameSender = messages.count { it.authorId == authorId }
            
            // Try to get sender name from cache, fetch if not available
            val senderName = try {
                var cachedUser = us.fireshare.tweet.datamodel.TweetCacheManager.getCachedUser(authorId)
                
                // If user not in cache or doesn't have name/username, try to fetch with short timeout
                if (cachedUser == null || (cachedUser.name.isNullOrBlank() && cachedUser.username.isNullOrBlank())) {
                    Timber.tag("MessageCheckWorker").d("User $authorId not in cache or missing name, fetching...")
                    
                    // Fetch user with 5 second timeout to avoid blocking notification
                    cachedUser = kotlinx.coroutines.withTimeoutOrNull(5000) {
                        us.fireshare.tweet.HproseInstance.fetchUser(authorId, skipRetryAndBlacklist = true)
                    }
                    
                    if (cachedUser != null) {
                        Timber.tag("MessageCheckWorker").d("Fetched user: ${cachedUser.name ?: cachedUser.username}")
                    }
                }
                
                // Use name (alias) first, fallback to username, then authorId
                cachedUser?.name?.takeIf { it.isNotBlank() } 
                    ?: cachedUser?.username?.takeIf { it.isNotBlank() }
                    ?: authorId
            } catch (e: Exception) {
                Timber.tag("MessageCheckWorker").e(e, "Error getting sender name for $authorId")
                authorId
            }
            
            SystemNotificationManager.showChatMessageNotification(
                applicationContext,
                senderName,
                messagePreview,
                messagesFromSameSender
            )
            
            Timber.tag("MessageCheckWorker").d("System notifications shown for ${messages.size} new messages from $senderName")
        } catch (e: Exception) {
            Timber.tag("MessageCheckWorker").e(e, "Error showing system notifications")
        }
    }
} 