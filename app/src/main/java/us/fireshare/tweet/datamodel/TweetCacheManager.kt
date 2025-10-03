package us.fireshare.tweet.datamodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import java.util.Date

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
    fun saveTweet(tweet: Tweet?, userId: MimeiId, shouldCache: Boolean = true) {
        if (tweet == null) {
            Timber.w("Should not cache: $tweet")
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

                // Always update memory cache
                memoryCache[tweet.mid] = cachedTweet

                // Only update database cache if shouldCache is true
                if (shouldCache) {
                    HproseInstance.dao.insertOrUpdateCachedTweet(cachedTweet)
                }
            }
        } catch (e: Exception) {
            Timber.e("Error saving tweet to cache: $e")
        }
    }

    /**
     * Get a cached tweet by ID
     * Note: Expiration checks are not performed when fetching tweets.
     * Timestamps are only used by the system for cleanup purposes.
     */
    fun getCachedTweet(tweetId: MimeiId): Tweet? {
        return try {
            synchronized(cacheLock) {
                // Check memory cache first
                memoryCache[tweetId]?.let { cachedTweet ->
                    return cachedTweet.originalTweet
                }

                // Check database cache
                val dbCachedTweet = HproseInstance.dao.getCachedTweet(tweetId)
                dbCachedTweet?.let { cachedTweet ->
                    // Add to memory cache for faster access
                    memoryCache[tweetId] = cachedTweet
                    return cachedTweet.originalTweet
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
    fun updateCachedTweet(tweet: Tweet?, userId: MimeiId, shouldCache: Boolean = true) {
        saveTweet(tweet, userId, shouldCache)
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


            }
        } catch (e: Exception) {
            Timber.e("Error cleaning up expired tweets: $e")
        }
    }

    /**
     * Save a user to cache (runs database operations on background thread)
     */
    fun saveUser(user: User) {
        Timber.tag("TweetCacheManager").d("=== SAVING USER TO CACHE === userId: ${user.mid}, username: ${user.username}")
        try {
            synchronized(userCacheLock) {
                val currentTime = System.currentTimeMillis()
                
                // Update memory cache
                userMemoryCache[user.mid] = user
                userCacheTimestamps[user.mid] = currentTime
                Timber.tag("TweetCacheManager").d("💾 USER SAVED TO MEMORY CACHE: userId: ${user.mid}, timestamp: $currentTime")

                // Update database cache with timestamp
                HproseInstance.dao.insertOrUpdateCachedUser(CachedUser(user.mid, user, Date(currentTime)))
                Timber.tag("TweetCacheManager").d("💾 USER SAVED TO DATABASE CACHE: userId: ${user.mid}")
            }
        } catch (e: Exception) {
            Timber.tag("TweetCacheManager").e("❌ ERROR SAVING USER TO CACHE: userId: ${user.mid}, error: $e")
        }
    }

    /**
     * Get a cached user by ID
     * Returns a valid user or null, ensuring expired users are cleared from cache
     * 
     * Optimization: If memory copy is expired, database copy must also be expired
     * since they share the same timestamp, so we clear both immediately.
     */
    fun getCachedUser(userId: MimeiId): User? {
        Timber.tag("TweetCacheManager").d("=== GETTING CACHED USER === userId: $userId")
        return try {
            // Check memory cache first
            val memoryUser = synchronized(userCacheLock) {
                userMemoryCache[userId]
            }

            memoryUser?.let { user ->
                if (!isUserExpired(user)) {
                    Timber.tag("TweetCacheManager").d("✅ MEMORY CACHE HIT: userId: $userId, username: ${user.username}")
                    return user
                } else {
                    // Remove expired user from both memory and database cache
                    // If memory copy is expired, database copy must also be expired
                    Timber.tag("TweetCacheManager").d("⏰ MEMORY CACHE EXPIRED: userId: $userId, removing from cache")
                    removeCachedUserInternal(userId)
                }
            }

            // Check database cache (only if not found in memory)
            Timber.tag("TweetCacheManager").d("🔍 CHECKING DATABASE CACHE: userId: $userId")
            val dbCachedUser = HproseInstance.dao.getCachedUser(userId)
            dbCachedUser?.let { cachedUser ->
                val user = cachedUser.user
                val cacheAge = System.currentTimeMillis() - cachedUser.timestamp.time
                
                if (cacheAge < USER_CACHE_EXPIRATION_TIME) {
                    // Cache is still valid, add to memory cache for faster access
                    Timber.tag("TweetCacheManager").d("✅ DATABASE CACHE HIT: userId: $userId, username: ${user.username}, cacheAge: ${cacheAge}ms")
                    synchronized(userCacheLock) {
                        userMemoryCache[userId] = user
                        userCacheTimestamps[userId] = cachedUser.timestamp.time
                    }
                    return user
                } else {
                    // Cache is expired, remove from database cache
                    Timber.tag("TweetCacheManager").d("⏰ DATABASE CACHE EXPIRED: userId: $userId, cacheAge: ${cacheAge}ms, removing from cache")
                    removeCachedUserInternal(userId)
                }
            }

            // User not found in cache or was expired and removed
            Timber.tag("TweetCacheManager").d("❌ CACHE MISS: userId: $userId not found in any cache")
            null
        } catch (e: Exception) {
            Timber.tag("TweetCacheManager").e("❌ ERROR RETRIEVING CACHED USER: userId: $userId, error: $e")
            null
        }
    }

    /**
     * Remove a user from cache
     */
    fun removeCachedUser(userId: MimeiId) {
        removeCachedUserInternal(userId)
    }

    /**
     * Internal method to remove a user from cache (non-suspend)
     * Ensures complete removal from both memory and database cache
     */
    private fun removeCachedUserInternal(userId: MimeiId) {
        try {
            // Remove from memory cache
            synchronized(userCacheLock) {
                userMemoryCache.remove(userId)
                userCacheTimestamps.remove(userId)
            }

            // Remove from database cache
            HproseInstance.dao.deleteCachedUser(userId)

            Timber.d("User completely removed from cache (memory + database): $userId")
        } catch (e: Exception) {
            Timber.e("Error removing cached user $userId: $e")
        }
    }

    /**
     * Clean up expired users from cache
     */
    fun cleanupExpiredUsers() {
        try {
            // Remove expired users from database cache
            val cutoffTime = Date(System.currentTimeMillis() - USER_CACHE_EXPIRATION_TIME)
            HproseInstance.dao.deleteOldCachedUsers(cutoffTime)
            
            // Remove expired users from memory cache
            val expiredUserIds = synchronized(userCacheLock) {
                userMemoryCache.filter { (_, user) ->
                    isUserExpired(user)
                }.keys.toList()
            }

            expiredUserIds.forEach { userId ->
                removeCachedUserInternal(userId)
            }
        } catch (e: Exception) {
            Timber.e("Error cleaning up expired users: $e")
        }
    }

    /**
     * Clear all cached users (memory + database)
     */
    fun clearAllCachedUsers() {
        try {
            synchronized(userCacheLock) {
                // Clear memory cache
                userMemoryCache.clear()
                userCacheTimestamps.clear()
            }

            // Clear database cache using bulk delete
            HproseInstance.dao.clearAllCachedUsers()
        } catch (e: Exception) {
            Timber.e("Error clearing cached users: $e")
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
    suspend fun getCacheStats(): CacheStats = withContext(Dispatchers.IO) {
        return@withContext try {
            synchronized(cacheLock) {
                val dbTweets = HproseInstance.dao.getCachedTweets(0, Int.MAX_VALUE)
                
                CacheStats(
                    memoryCacheSize = memoryCache.size,
                    databaseCacheSize = dbTweets.size,
                    expirationTimeMs = CACHE_EXPIRATION_TIME
                )
            }
        } catch (e: Exception) {
            Timber.e("Error getting cache stats: $e")
            CacheStats(0, 0, 0)
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