package us.fireshare.tweet.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import java.util.concurrent.TimeUnit

/**
 * Checks the main feed in background and lets the UI decide where to show the banner.
 *
 * WorkManager periodic work has a 15-minute minimum interval, so this worker
 * reschedules itself with a 5-minute delay after each run.
 */
@HiltWorker
class MainFeedCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Timber.tag(TAG).d("Checking main feed for new tweets")

            if (!HproseInstance.isOnline.value) {
                Timber.tag(TAG).d("Offline: skipping main feed check")
                return@withContext Result.success()
            }

            if (!waitForAppUser()) {
                Timber.tag(TAG).w("AppUser not initialized, skipping main feed check")
                return@withContext Result.success()
            }

            if (HproseInstance.appUser.isGuest()) {
                Timber.tag(TAG).d("User is guest, skipping main feed check")
                return@withContext Result.success()
            }

            val feedTweets = HproseInstance.getTweetFeed(
                pageNumber = 0,
                pageSize = TW_CONST.PAGE_SIZE,
                maxRetries = 1
            )
                .filterNotNull()

            val followingTweets = HproseInstance.getTweetFeed(
                pageNumber = 0,
                pageSize = TW_CONST.PAGE_SIZE,
                entry = "update_following_tweets",
                maxRetries = 1
            )
                .filterNotNull()

            val newTweets = (feedTweets + followingTweets)
                .distinctBy { it.mid }
                .filterNot { it.isPrivate }

            if (newTweets.isNotEmpty()) {
                Timber.tag(TAG).d(
                    "Found ${newTweets.size} new main feed tweets (get_tweet_feed=${feedTweets.size}, update_following_tweets=${followingTweets.size})"
                )
                TweetNotificationCenter.post(
                    TweetEvent.MainFeedNewTweetsFound(newTweets)
                )
            } else {
                Timber.tag(TAG).d("No new main feed tweets found")
            }

            Result.success()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error checking main feed for new tweets")
            Result.success()
        } finally {
            scheduleNext(applicationContext)
        }
    }

    private suspend fun waitForAppUser(timeoutMillis: Long = 10000L): Boolean {
        val startTime = System.currentTimeMillis()
        Timber.tag(TAG).d("Waiting for appUser initialization (timeout: ${timeoutMillis}ms)")
        while (!HproseInstance.isAppUserInitialized.value && System.currentTimeMillis() - startTime < timeoutMillis) {
            delay(200)
        }
        val elapsed = System.currentTimeMillis() - startTime
        return if (HproseInstance.isAppUserInitialized.value) {
            Timber.tag(TAG).d("appUser initialized after ${elapsed}ms")
            true
        } else {
            Timber.tag(TAG).w("Timeout waiting for appUser initialization after ${elapsed}ms")
            false
        }
    }

    companion object {
        private const val TAG = "MainFeedCheckWorker"
        private const val UNIQUE_WORK_NAME = "MainFeedCheck"
        private const val CHECK_INTERVAL_MINUTES = 5L

        fun reschedule(context: Context) {
            enqueue(context, ExistingWorkPolicy.REPLACE)
        }

        private fun scheduleNext(context: Context) {
            enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<MainFeedCheckWorker>()
                .setInitialDelay(CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                policy,
                request
            )
        }
    }
}
