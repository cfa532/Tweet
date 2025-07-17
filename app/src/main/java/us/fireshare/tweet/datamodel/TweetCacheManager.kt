package us.fireshare.tweet.datamodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * TweetCacheManager handles tweet and user caching with expiration management.
 * Provides methods to save, retrieve, and manage cached tweets and users with automatic cleanup.
 */
object TweetCacheManager {
    
    // Cache expiration time (30 days in milliseconds)
    private const val CACHE_EXPIRATION_TIME = 30 * 24 * 60 * 60 * 1000L
    
    // User cache expiration time (30 minutes in milliseconds)
    private const val USER_CACHE_EXPIRATION_TIME = 30 * 60 * 1000L
    
    // In-memory cache for frequently accessed tweets
    private val memoryCache = mutableMapOf<String, CachedTweet>()
    private val cacheLock = Any()
    
    // In-memory cache for users
    private val userMemoryCache = mutableMapOf<String, User>()
    private val userCacheTimestamps = mutableMapOf<String, Long>()
    private val userCacheLock = Any()
    
    /**
     * Save or update a tweet in cache
     * @param shouldCache If false, the tweet will not be cached (for profile screens)
     */
    suspend fun saveTweet(tweet: Tweet?, userId: MimeiId, shouldCache: Boolean = true) {
        if (tweet == null || !shouldCache || !isValidTweet(tweet)) {
            return
        }
        
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
    suspend fun getCachedTweet(tweetId: MimeiId): Tweet? {
        return try {
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
                
                null
            }
        } catch (e: Exception) {
            Timber.e("Error retrieving cached tweet: $e")
            null
        }
    }
    
    /**
     * Update an existing cached tweet
     * @param shouldCache If false, the tweet will not be cached (for profile screens)
     */
    suspend fun updateCachedTweet(tweet: Tweet?, userId: MimeiId, shouldCache: Boolean = true) {
        saveTweet(tweet, userId, shouldCache)
    }
    
    /**
     * Remove a tweet from cache
     */
    suspend fun removeCachedTweet(tweetId: MimeiId) {
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
    suspend fun clearAllCachedTweets() {
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
    suspend fun cleanupExpiredTweets() {
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
     * Save a user to cache (runs database operations on background thread)
     */
    suspend fun saveUser(user: User) {
        try {
            synchronized(userCacheLock) {
                // Update memory cache
                userMemoryCache[user.mid] = user
                userCacheTimestamps[user.mid] = System.currentTimeMillis()
                
                // Update database cache
                HproseInstance.dao.insertOrUpdateCachedUser(CachedUser(user.mid, user))
                
                Timber.d("User cached: ${user.mid}")
            }
        } catch (e: Exception) {
            Timber.e("Error saving user to cache: $e")
        }
    }
    
    /**
     * Get a cached user by ID
     */
    suspend fun getCachedUser(userId: MimeiId): User? {
        return try {
            // Check memory cache first (outside synchronized block)
            val memoryUser = synchronized(userCacheLock) {
                userMemoryCache[userId]
            }
            
            memoryUser?.let { user ->
                if (!isUserExpired(user)) {
                    Timber.d("User found in memory cache: $userId")
                    return user
                } else {
                    // Remove expired user from memory cache
                    synchronized(userCacheLock) {
                        userMemoryCache.remove(userId)
                        userCacheTimestamps.remove(userId)
                    }
                }
            }
            
            // Check database cache
            val dbCachedUser = HproseInstance.dao.getCachedUser(userId)
            dbCachedUser?.let { cachedUser ->
                val user = cachedUser.user
                if (!isUserExpired(user)) {
                    // Add to memory cache for faster access
                    synchronized(userCacheLock) {
                        userMemoryCache[userId] = user
                        userCacheTimestamps[userId] = System.currentTimeMillis()
                    }
                    Timber.d("User found in database cache: $userId")
                    return user
                } else {
                    // Remove expired user from database (outside synchronized block)
                    removeCachedUserInternal(userId)
                    Timber.d("Expired user removed from cache: $userId")
                }
            }
            
            null
        } catch (e: Exception) {
            Timber.e("Error retrieving cached user: $e")
            null
        }
    }
    
    /**
     * Remove a user from cache
     */
    suspend fun removeCachedUser(userId: MimeiId) {
        removeCachedUserInternal(userId)
    }
    
    /**
     * Internal method to remove a user from cache (non-suspend)
     */
    private suspend fun removeCachedUserInternal(userId: MimeiId) {
        try {
            synchronized(userCacheLock) {
                // Remove from memory cache
                userMemoryCache.remove(userId)
                userCacheTimestamps.remove(userId)
            }
            
            // Remove from database cache (outside synchronized block)
            HproseInstance.dao.deleteCachedUser(userId)
            
            Timber.d("User removed from cache: $userId")
        } catch (e: Exception) {
            Timber.e("Error removing cached user: $e")
        }
    }
    
    /**
     * Clean up expired users from cache
     */
    suspend fun cleanupExpiredUsers() {
        try {
            val expiredUserIds = synchronized(userCacheLock) {
                userMemoryCache.filter { (_, user) ->
                    isUserExpired(user)
                }.keys.toList()
            }

            expiredUserIds.forEach { userId ->
                removeCachedUserInternal(userId)
                Timber.d("Removed expired user: $userId")
            }

            if (expiredUserIds.isNotEmpty()) {
                Timber.d("Cleaned up ${expiredUserIds.size} expired users")
            }
        } catch (e: Exception) {
            Timber.e("Error cleaning up expired users: $e")
        }
    }
    
    /**
     * Get user cache timestamp
     */
    fun getUserCacheTimestamp(userId: MimeiId): Long {
        return synchronized(userCacheLock) {
            userCacheTimestamps[userId] ?: 0L
        }
    }
    
    /**
     * Get user cache statistics
     */
    fun getUserCacheStats(): UserCacheStats {
        return try {
            synchronized(userCacheLock) {
                val totalUsers = userMemoryCache.size
                val expiredUsers = userMemoryCache.count { (_, user) -> isUserExpired(user) }
                val validUsers = totalUsers - expiredUsers

                UserCacheStats(
                    totalUsers = totalUsers,
                    validUsers = validUsers,
                    expiredUsers = expiredUsers,
                    expirationTimeMs = USER_CACHE_EXPIRATION_TIME
                )
            }
        } catch (e: Exception) {
            Timber.e("Error getting user cache stats: $e")
            UserCacheStats(0, 0, 0, USER_CACHE_EXPIRATION_TIME)
        }
    }

    /**
     * Check if a tweet is valid for caching
     */
    private fun isValidTweet(tweet: Tweet): Boolean {
        // Check if tweet has valid ID and author
        if (tweet.mid.isBlank() || tweet.authorId.isBlank()) {
            return false
        }
        
        // Check if tweet has content or attachments
        if (tweet.content.isNullOrBlank() && (tweet.attachments.isNullOrEmpty())) {
            return false
        }
        
        // Check if tweet has a valid author
        if (tweet.author == null) {
            return false
        }
        
        return true
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
     * Check if a cached user is expired
     */
    private fun isUserExpired(user: User): Boolean {
        val currentTime = System.currentTimeMillis()
        val cacheTime = userCacheTimestamps[user.mid] ?: 0L
        return (currentTime - cacheTime) > USER_CACHE_EXPIRATION_TIME
    }
    
    /**
     * Get cache statistics
     */
    suspend fun getCacheStats(): CacheStats {
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
    
    /**
     * User cache statistics data class
     */
    data class UserCacheStats(
        val totalUsers: Int,
        val validUsers: Int,
        val expiredUsers: Int,
        val expirationTimeMs: Long
    )
} 