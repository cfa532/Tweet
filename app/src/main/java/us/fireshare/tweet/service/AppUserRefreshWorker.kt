package us.fireshare.tweet.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import us.fireshare.tweet.HproseInstance

/**
 * Background worker that refreshes appUser data every 30 minutes.
 * This ensures user data stays up-to-date even when the app is in the background.
 */
@HiltWorker
class AppUserRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Timber.tag("AppUserRefreshWorker").d("Starting appUser refresh...")
            
            // Only refresh if appUser is not a guest
            if (!HproseInstance.appUser.isGuest()) {
                Timber.tag("AppUserRefreshWorker").d("Refreshing appUser data for user: ${HproseInstance.appUser.mid}")
                
                // Use the existing getUser function to refresh appUser
                // Force refresh to always fetch fresh data from server (bypass cache)
                // Use current baseUrl (don't force IP refresh during periodic refresh)
                val refreshedUser = HproseInstance.fetchUser(
                    HproseInstance.appUser.mid, 
                    HproseInstance.appUser.baseUrl,
                    maxRetries = 3,
                    forceRefresh = true  // Always fetch fresh data, don't use cache
                )
                
                if (refreshedUser != null) {
                    HproseInstance.appUser = refreshedUser
                    Timber.tag("AppUserRefreshWorker").d("AppUser refreshed successfully")
                    Result.success()
                } else {
                    Timber.tag("AppUserRefreshWorker").w("Failed to refresh appUser, keeping current instance")
                    Result.retry()
                }
            } else {
                Timber.tag("AppUserRefreshWorker").d("Skipping refresh for guest user")
                Result.success()
            }
        } catch (e: Exception) {
            Timber.tag("AppUserRefreshWorker").e(e, "Error during appUser refresh")
            Result.failure()
        }
    }
}
