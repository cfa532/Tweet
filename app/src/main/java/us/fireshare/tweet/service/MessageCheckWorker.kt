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
            Timber.tag("MessageCheckWorker").d("Checking for new messages...")
            
            val newMessages = HproseInstance.checkNewMessages()
            
            if (newMessages != null && newMessages.isNotEmpty()) {
                Timber.tag("MessageCheckWorker").d("Found ${newMessages.size} messages from server")
                
                // Filter out messages that already exist in local database
                val database = ChatDatabase.getInstance(applicationContext)
                val chatMessageDao = database.chatMessageDao()
                
                val trulyNewMessages = newMessages.filter { message ->
                    // Check if message already exists in database
                    val existingMessage = chatMessageDao.getMessageByMessageId(message.id)
                    existingMessage == null
                }
                
                Timber.tag("MessageCheckWorker").d("After filtering: ${trulyNewMessages.size} truly new messages")
                
                if (trulyNewMessages.isNotEmpty()) {
                    BadgeStateManager.updateBadgeCount(trulyNewMessages.size)
                } else {
                    BadgeStateManager.updateBadgeCount(0)
                }
            } else {
                Timber.tag("MessageCheckWorker").d("No new messages found")
                BadgeStateManager.updateBadgeCount(0)
            }
            
            Result.success()
        } catch (e: Exception) {
            Timber.tag("MessageCheckWorker").e(e, "Error checking messages")
            Result.retry()
        }
    }
} 