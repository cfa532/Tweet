package us.fireshare.tweet.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.work.Worker
import androidx.work.WorkerParameters
import timber.log.Timber
import us.fireshare.tweet.datamodel.TweetCacheDatabase
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.widget.ImageCacheManager
import us.fireshare.tweet.widget.VideoManager
import java.util.Date

class CleanUpWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    companion object {
        @OptIn(UnstableApi::class)
        internal fun cleanupMediaCaches(context: Context) {
            val permanent = TweetCacheManager.permanentMediaIds(context)
            val avatars = TweetCacheDatabase.getInstance(context).tweetDao()
                .getAllCachedUsers().mapNotNull { it.user.avatar }
            ImageCacheManager.cleanupDiskCache(context, permanent + avatars)
            VideoManager.cleanupDiskCache(context, permanent)
        }
    }
    @OptIn(UnstableApi::class)
    override fun doWork(): Result {
        try {
            // Single source of truth for the retention window; the cutoff used to be
            // computed here as a calendar month, which drifted from the documented
            // constant by a day or three depending on the month.
            val cutoff = Date(System.currentTimeMillis() - TweetCacheManager.CACHE_EXPIRATION_TIME)
            
            val database = TweetCacheDatabase.getInstance(applicationContext)
            val cachedTweetDao = database.tweetDao()
            
            // Get old cached tweets that would be deleted (excludes bookmarks and favorites)
            val oldTweets = cachedTweetDao.getOldCachedTweets(cutoff)
            
            // Count tweets before cleanup
            val totalOldTweets = oldTweets.size
            var preservedPrivateTweets = 0
            var deletedTweets = 0
            
            // Preserve private posts across account switches and logout as well.
            oldTweets.forEach { cachedTweet ->
                val tweet = cachedTweet.originalTweet
                
                if (tweet.isPrivate) {
                    preservedPrivateTweets++
                    Timber.tag("CleanUpWorker").d("Preserving private tweet: ${tweet.mid}")
                } else {
                    // Delete only this expired list membership. The same tweet may be
                    // preserved in another cache list such as bookmarks or favorites.
                    cachedTweetDao.deleteCachedTweetFromCache(cachedTweet.mid, cachedTweet.uid)
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

            // Enforce the row ceiling. Expiry alone left the cache unbounded between
            // 30-day sweeps, which matters now that read-ahead writes pages nobody
            // asked for. Rows only, no media: a row is one (tweet, cache key) pair and
            // a tweet usually has several, so a trimmed row is no evidence its media is
            // unused.
            val total = cachedTweetDao.countCachedTweets()
            if (total > TweetCacheManager.MAX_CACHED_TWEETS) {
                val excess = total - TweetCacheManager.MAX_CACHED_TWEETS
                var trimmed = 0
                var preservedPrivateOverCap = 0
                cachedTweetDao.getOldestTrimmableCachedTweets(excess).forEach { cachedTweet ->
                    val tweet = cachedTweet.originalTweet
                    // Same permanence rule as expiry above.
                    if (tweet.isPrivate) {
                        preservedPrivateOverCap++
                    } else {
                        cachedTweetDao.deleteCachedTweetFromCache(cachedTweet.mid, cachedTweet.uid)
                        trimmed++
                    }
                }
                Timber.tag("CleanUpWorker").d(
                    "Trimmed $trimmed row(s) over the ${TweetCacheManager.MAX_CACHED_TWEETS} cap " +
                        "($total before, $preservedPrivateOverCap private preserved)"
                )
            }

            cleanupMediaCaches(applicationContext)
            Timber.tag("CleanUpWorker").d("Clean up finished!!!!")

            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("CleanUpWorker").e(e.toString())
            return Result.failure()
        }
    }
}
