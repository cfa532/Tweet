package com.fireshare.tweet.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.fireshare.tweet.datamodel.TweetCacheDatabase
import com.fireshare.tweet.widget.CacheManager
import com.fireshare.tweet.widget.VideoCacheManager
import timber.log.Timber
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

            // clear old cached images
            val cacheManager = CacheManager(applicationContext)
            val oneMonthInMillis = 30L * 24L * 60L * 60L * 1000L
            cacheManager.clearOldCachedImages(oneMonthInMillis)
            VideoCacheManager.clearOldCachedVideos(applicationContext, oneMonthInMillis)

            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("CleanUpWorker").e(e.toString())
            return Result.failure()
        }
    }
}