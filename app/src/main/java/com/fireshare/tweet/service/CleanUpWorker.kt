package com.fireshare.tweet.service

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.fireshare.tweet.datamodel.TweetCacheDatabase
import java.util.Calendar

class CleanUpWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    override fun doWork(): Result {
        val oneMonthAgo = Calendar.getInstance().apply {
            add(Calendar.MONTH, -1)
        }.time

        val database = TweetCacheDatabase.getInstance(applicationContext)
        val cachedTweetDao = database.tweetDao()

        cachedTweetDao.deleteOldCachedTweets(oneMonthAgo)

        return Result.success()
    }
}