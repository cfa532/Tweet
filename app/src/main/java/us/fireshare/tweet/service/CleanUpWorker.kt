package us.fireshare.tweet.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.work.Worker
import androidx.work.WorkerParameters
import timber.log.Timber
import us.fireshare.tweet.datamodel.TweetCacheDatabase
import us.fireshare.tweet.widget.CacheManager
import us.fireshare.tweet.widget.VideoCacheManager
import java.util.Calendar

class CleanUpWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    @OptIn(UnstableApi::class)
    override fun doWork(): Result {
        try {
            val oneMonthAgo = Calendar.getInstance().apply {
                add(Calendar.MONTH, -1)
            }.time
            // clear old tweet in database
            val database = TweetCacheDatabase.getInstance(applicationContext)
            val cachedTweetDao = database.tweetDao()
            cachedTweetDao.deleteOldCachedTweets(oneMonthAgo)

            // clear old cached images after 30 days.
            val cacheManager = CacheManager(applicationContext)
            val oneMonthInMillis = 30L * 24L * 60L * 60L * 1000L
            cacheManager.clearOldCachedImages(oneMonthInMillis)
            VideoCacheManager.clearOldCachedVideos(applicationContext, oneMonthInMillis)
            Timber.tag("CleanUpWorker").d("Clean up finished!!!!")

            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("CleanUpWorker").e(e.toString())
            return Result.failure()
        }
    }
}