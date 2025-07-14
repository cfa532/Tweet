package us.fireshare.tweet.datamodel

import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * TweetCacheManager handles tweet caching with expiration management.
 * Provides methods to save, retrieve, and manage cached tweets with automatic cleanup.
 */
object TweetCacheManager {
    
    // Cache expiration time (30 days in milliseconds)
    private const val CACHE_EXPIRATION_TIME = 30 * 24 * 60 * 60 * 1000L
    
    // In-memory cache for frequently accessed tweets
    private val memoryCache = mutableMapOf<String, CachedTweet>()
    private val cacheLock = Any()
    
    /**
     * Save a tweet to cache with current timestamp
     */
    fun saveTweet(tweet: Tweet, userId: MimeiId) {
        try {
            synchronized(cacheLock) {
                val cachedTweet = CachedTweet(
                    mid = tweet.mid,
                    uid = userId,
                    originalTweet = tweet,
                    timestamp = Date()
                )
                
                // Update memory cache
                memoryCache[tweet.mid] = cachedTweet
                
                // Update database cache
                HproseInstance.dao.insertOrUpdateCachedTweet(cachedTweet)
                
                Timber.d("Tweet cached: ${tweet.mid}")
            }
        } catch (e: Exception) {
            Timber.e("Error saving tweet to cache: $e")
        }
    }
    
    /**
     * Get a cached tweet by ID
     */
    fun getCachedTweet(tweetId: MimeiId): Tweet? {
        try {
            synchronized(cacheLock) {
                // Check memory cache first
                memoryCache[tweetId]?.let { cachedTweet ->
                    if (!isExpired(cachedTweet)) {
                        Timber.d("Tweet found in memory cache: $tweetId")
                        return cachedTweet.originalTweet
                    } else {
                        // Remove expired tweet from memory cache
                        memoryCache.remove(tweetId)
                    }
                }
                
                // Check database cache
                val dbCachedTweet = HproseInstance.dao.getCachedTweet(tweetId)
                dbCachedTweet?.let { cachedTweet ->
                    if (!isExpired(cachedTweet)) {
                        // Add to memory cache for faster access
                        memoryCache[tweetId] = cachedTweet
                        Timber.d("Tweet found in database cache: $tweetId")
                        return cachedTweet.originalTweet
                    } else {
                        // Remove expired tweet from database
                        HproseInstance.dao.deleteCachedTweet(tweetId)
                        Timber.d("Expired tweet removed from cache: $tweetId")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e("Error retrieving cached tweet: $e")
        }
        
        return null
    }
    
    /**
     * Update an existing cached tweet
     */
    fun updateCachedTweet(tweet: Tweet?, userId: MimeiId) {
        if (tweet == null) {
            Timber.w("Attempted to update cached tweet with null tweet")
            return
        }
        
        try {
            synchronized(cacheLock) {
                val existingCachedTweet = HproseInstance.dao.getCachedTweet(tweet.mid)
                val cachedTweet = CachedTweet(
                    mid = tweet.mid,
                    uid = userId,
                    originalTweet = tweet,
                    timestamp = existingCachedTweet?.timestamp ?: Date()
                )
                
                // Update memory cache
                memoryCache[tweet.mid] = cachedTweet
                
                // Update database cache
                HproseInstance.dao.insertOrUpdateCachedTweet(cachedTweet)
                
                Timber.d("Cached tweet updated: ${tweet.mid}")
            }
        } catch (e: Exception) {
            Timber.e("Error updating cached tweet: $e")
        }
    }
    
    /**
     * Remove a tweet from cache
     */
    fun removeCachedTweet(tweetId: MimeiId) {
        try {
            synchronized(cacheLock) {
                // Remove from memory cache
                memoryCache.remove(tweetId)
                
                // Remove from database cache
                HproseInstance.dao.deleteCachedTweet(tweetId)
                
                Timber.d("Tweet removed from cache: $tweetId")
            }
        } catch (e: Exception) {
            Timber.e("Error removing cached tweet: $e")
        }
    }
    
    /**
     * Clear all cached tweets
     */
    fun clearAllCachedTweets() {
        try {
            synchronized(cacheLock) {
                // Clear memory cache
                memoryCache.clear()
                
                // Clear database cache
                HproseInstance.dao.clearAllCachedTweets()
                
                Timber.d("All cached tweets cleared")
            }
        } catch (e: Exception) {
            Timber.e("Error clearing cached tweets: $e")
        }
    }
    
    /**
     * Clean up expired tweets
     */
    fun cleanupExpiredTweets() {
        try {
            synchronized(cacheLock) {
                val cutoffDate = Date(System.currentTimeMillis() - CACHE_EXPIRATION_TIME)
                
                // Remove expired tweets from database
                HproseInstance.dao.deleteOldCachedTweets(cutoffDate)
                
                // Remove expired tweets from memory cache
                val expiredKeys = memoryCache.filter { (_, cachedTweet) ->
                    isExpired(cachedTweet)
                }.keys
                
                expiredKeys.forEach { key ->
                    memoryCache.remove(key)
                }
                
                Timber.d("Cleanup completed. Removed ${expiredKeys.size} expired tweets from memory cache")
            }
        } catch (e: Exception) {
            Timber.e("Error cleaning up expired tweets: $e")
        }
    }
    
    /**
     * Save a user to cache
     */
    fun saveUser(user: User) {
        try {
            synchronized(cacheLock) {
                // Update database cache
                HproseInstance.dao.insertOrUpdateCachedUser(CachedUser(user.mid, user))
                
                Timber.d("User cached: ${user.mid}")
            }
        } catch (e: Exception) {
            Timber.e("Error saving user to cache: $e")
        }
    }

    /**
     * Check if a cached tweet is expired
     */
    private fun isExpired(cachedTweet: CachedTweet): Boolean {
        val currentTime = System.currentTimeMillis()
        val cacheTime = cachedTweet.timestamp.time
        return (currentTime - cacheTime) > CACHE_EXPIRATION_TIME
    }
    
    /**
     * Get cache statistics
     */
    fun getCacheStats(): CacheStats {
        return try {
            synchronized(cacheLock) {
                CacheStats(
                    memoryCacheSize = memoryCache.size,
                    databaseCacheSize = HproseInstance.dao.getCachedTweets(0, Int.MAX_VALUE).size,
                    expirationTimeMs = CACHE_EXPIRATION_TIME
                )
            }
        } catch (e: Exception) {
            Timber.e("Error getting cache stats: $e")
            CacheStats(0, 0, CACHE_EXPIRATION_TIME)
        }
    }
    
    /**
     * Cache statistics data class
     */
    data class CacheStats(
        val memoryCacheSize: Int,
        val databaseCacheSize: Int,
        val expirationTimeMs: Long
    )
} 