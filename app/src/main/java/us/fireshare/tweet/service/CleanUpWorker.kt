package us.fireshare.tweet.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.work.Worker
import androidx.work.WorkerParameters
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.datamodel.TweetCacheDatabase
import java.util.Calendar

class CleanUpWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    @OptIn(UnstableApi::class)
    override fun doWork(): Result {
        try {
            val oneMonthAgo = Calendar.getInstance().apply {
                add(Calendar.MONTH, -1)
            }.time
            
            val database = TweetCacheDatabase.getInstance(applicationContext)
            val cachedTweetDao = database.tweetDao()
            
            // Get old cached tweets that would be deleted (excludes bookmarks and favorites)
            val oldTweets = cachedTweetDao.getOldCachedTweets(oneMonthAgo)
            
            // Count tweets before cleanup
            val totalOldTweets = oldTweets.size
            var preservedPrivateTweets = 0
            var deletedTweets = 0
            
            // Filter out and preserve appUser's private tweets
            oldTweets.forEach { cachedTweet ->
                val tweet = cachedTweet.originalTweet
                
                // Preserve appUser's private tweets
                if (tweet.authorId == appUser.mid && tweet.isPrivate) {
                    preservedPrivateTweets++
                    Timber.tag("CleanUpWorker").d("Preserving appUser's private tweet: ${tweet.mid}")
                } else {
                    // Delete non-private tweets
                    cachedTweetDao.deleteCachedTweet(cachedTweet.mid)
                    deletedTweets++
                }
            }
            
            Timber.tag("CleanUpWorker").d("""
                Clean up summary:
                - Total old tweets found: $totalOldTweets
                - Preserved private tweets: $preservedPrivateTweets
                - Deleted public tweets: $deletedTweets
                - Bookmarks preserved: ∞ (never expire)
                - Favorites preserved: ∞ (never expire)
            """.trimIndent())

            // Note: VideoManager doesn't have clearOldCachedVideos method
            // The memory management is handled automatically by VideoManager
            Timber.d("CleanUpWorker - Video cache cleanup handled by VideoManager")
            Timber.tag("CleanUpWorker").d("Clean up finished!!!!")

            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("CleanUpWorker").e(e.toString())
            return Result.failure()
        }
    }
}