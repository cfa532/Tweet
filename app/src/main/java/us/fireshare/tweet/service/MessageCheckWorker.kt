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
} 