package us.fireshare.tweet.datamodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import java.util.Date
import java.util.Locale

/**
 * TweetCacheManager handles tweet and user caching with expiration management.
 * Provides methods to save, retrieve, and manage cached tweets and users with automatic cleanup.
 */
object TweetCacheManager {

    // Cache expiration time (30 days in milliseconds)
    private const val CACHE_EXPIRATION_TIME = 30 * 24 * 60 * 60 * 1000L

    // User cache expiration time (30 minutes in milliseconds)
    internal const val USER_CACHE_EXPIRATION_TIME = 30 * 60 * 1000L

    // In-memory cache for frequently accessed tweets
    private val memoryCache = mutableMapOf<String, CachedTweet>()
    private val cacheLock = Any()

    // In-memory cache for users
    private val userMemoryCache = mutableMapOf<String, User>()
    private val userCacheTimestamps = mutableMapOf<String, Long>()
    private val userCacheLock = Any()
    
    // StateFlows for reactive user updates - allows UI to observe user changes
    private val userStateFlows = mutableMapOf<String, MutableStateFlow<User?>>()
    private val userStateFlowsLock = Any()

    /**
     * Save or update a tweet in cache
     * @param shouldCache If false, the tweet will not be cached (for profile screens)
     */
    fun saveTweet(tweet: Tweet?, userId: MimeiId) {
        if (tweet == null) {
            Timber.w("Should not cache: $tweet")
            return
        }

        try {
            val cachedTweet = synchronized(cacheLock) {
                val cached = CachedTweet(
                    mid = tweet.mid,
                    uid = userId,
                    originalTweet = tweet,
                    timestamp = Date(),
                    tweetTimestamp = tweet.timestamp  // Store tweet's actual creation timestamp for ordering
                )

                // Always update memory cache
                memoryCache[tweet.mid] = cached
                cached
            }
            
            // Always update database cache on IO thread
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    HproseInstance.dao.insertOrUpdateCachedTweet(cachedTweet)
                } catch (e: Exception) {
                    Timber.e("Error saving tweet to database: $e")
                }
            }
        } catch (e: Exception) {
            Timber.e("Error saving tweet to cache: $e")
        }
    }

    /**
     * Get a cached tweet by ID
     * 
     * IMPORTANT: Searches across ALL user caches, not just a specific user's cache.
     * This is necessary because:
     * - All tweets are cached under their authorId
     * - When we only have a tweet mid, we don't know which author's cache it's in
     * 
     * The search is performed by tweet mid (primary key) only, not filtered by uid.
     * 
     * Note: Expiration checks are not performed when fetching tweets.
     * Timestamps are only used by the system for cleanup purposes.
     * Cached tweets are expired by timestamp (30 days) or manually by user via settings.
     * This expiration applies to all caches regardless of which uid they're stored under.
     */
    fun getCachedTweet(tweetId: MimeiId): Tweet? {
        return try {
            synchronized(cacheLock) {
                // Check memory cache first (searches across all tweets by mid)
                memoryCache[tweetId]?.let { cachedTweet ->
                    return cachedTweet.originalTweet
                }

                // Check database cache (searches across all caches by mid, not filtered by uid)
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
     */
    fun updateCachedTweet(tweet: Tweet?, userId: MimeiId) {
        saveTweet(tweet, userId)
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
     * Get or create a StateFlow for observing a specific user.
     * This allows UI components to reactively update when user data changes.
     */
    fun getUserStateFlow(userId: MimeiId): StateFlow<User?> {
        synchronized(userStateFlowsLock) {
            val existingFlow = userStateFlows[userId]
            if (existingFlow != null) {
                return existingFlow.asStateFlow()
            }
            
            // Create new StateFlow with current cached user (or null)
            val currentUser = synchronized(userCacheLock) {
                userMemoryCache[userId]
            }
            val newFlow = MutableStateFlow<User?>(currentUser)
            userStateFlows[userId] = newFlow
            return newFlow.asStateFlow()
        }
    }

    /**
     * Save a user to cache (runs database operations on background thread)
     * Also updates the StateFlow so all observers are notified of the change.
     */
    fun saveUser(user: User) {
        Timber.tag("TweetCacheManager").d("=== SAVING USER TO CACHE === userId: ${user.mid}, username: ${user.username}")
        try {
            val cachedUser = synchronized(userCacheLock) {
                val currentTime = System.currentTimeMillis()
                
                // Update memory cache
                userMemoryCache[user.mid] = user
                userCacheTimestamps[user.mid] = currentTime
                Timber.tag("TweetCacheManager").d("💾 USER SAVED TO MEMORY CACHE: userId: ${user.mid}, timestamp: $currentTime")

                CachedUser(user.mid, user, Date(currentTime))
            }
            
            // Update database cache with timestamp on IO thread
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    HproseInstance.dao.insertOrUpdateCachedUser(cachedUser)
                    Timber.tag("TweetCacheManager").d("💾 USER SAVED TO DATABASE CACHE: userId: ${user.mid}")
                } catch (e: Exception) {
                    Timber.tag("TweetCacheManager").e("❌ ERROR SAVING USER TO DATABASE: userId: ${user.mid}, error: $e")
                }
            }
            
            // Update StateFlow to notify observers
            synchronized(userStateFlowsLock) {
                userStateFlows[user.mid]?.value = user
                Timber.tag("TweetCacheManager").d("📡 USER STATEFLOW UPDATED: userId: ${user.mid}")
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
    suspend fun getCachedUser(userId: MimeiId): User? {
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
                    // User is expired but still return it (stale data is better than no data)
                    // The caller (fetchUser) will handle background refresh
                    Timber.tag("TweetCacheManager").d("⏰ MEMORY CACHE EXPIRED (but returning): userId: $userId, username: ${user.username}")
                    return user
                }
            }

            // Check database cache (only if not found in memory)
            Timber.tag("TweetCacheManager").d("🔍 CHECKING DATABASE CACHE: userId: $userId")
            val dbCachedUser = withContext(Dispatchers.IO) {
                HproseInstance.dao.getCachedUser(userId)
            }
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
                    // Cache is expired but still return it (stale data is better than no data)
                    // Add back to memory cache so hasExpired check works correctly
                    Timber.tag("TweetCacheManager").d("⏰ DATABASE CACHE EXPIRED (but returning): userId: $userId, username: ${user.username}, cacheAge: ${cacheAge}ms")
                    synchronized(userCacheLock) {
                        userMemoryCache[userId] = user
                        userCacheTimestamps[userId] = cachedUser.timestamp.time
                    }
                    return user
                }
            }

            // User not found in cache or was expired and removed
            Timber.tag("TweetCacheManager").d("❌ CACHE MISS: userId: $userId not found in any cache")
            null
        } catch (e: Exception) {
            e.printStackTrace()
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
            
            // Update StateFlow to notify observers that user is no longer available
            synchronized(userStateFlowsLock) {
                userStateFlows[userId]?.value = null
            }

            Timber.d("User completely removed from cache (memory + database): $userId")
        } catch (e: Exception) {
            Timber.e("Error removing cached user $userId: $e")
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
            
            // Clear all StateFlows
            synchronized(userStateFlowsLock) {
                userStateFlows.values.forEach { it.value = null }
                userStateFlows.clear()
            }
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
     * Perform a partial search across locally cached users (memory + database).
     * Matches both username and display name, case-insensitively.
     */
    suspend fun searchUsers(query: String, limit: Int = 20): List<User> = withContext(Dispatchers.IO) {
        if (query.isBlank() || limit <= 0) return@withContext emptyList()

        val normalizedQuery = query.normalizedLowercase()
        val results = LinkedHashMap<String, Pair<Int, User>>(limit)

        fun consider(user: User?) {
            if (user == null) return
            val score = user.matchScore(normalizedQuery) ?: return
            val existing = results[user.mid]
            if (existing == null || score < existing.first) {
                results[user.mid] = score to user
            }
        }

        val memoryUsers = synchronized(userCacheLock) { userMemoryCache.values.toList() }
        memoryUsers.forEach(::consider)

        if (results.size < limit) {
            try {
                HproseInstance.dao.getAllCachedUsers().forEach { cachedUser ->
                    consider(cachedUser.user)
                    if (results.size >= limit) return@forEach
                }
            } catch (e: Exception) {
                Timber.e(e, "searchUsers: Unable to read cached users from database")
            }
        }

        if (results.size < limit) {
            try {
                val candidateIds = mutableSetOf<String>()

                synchronized(userCacheLock) {
                    candidateIds.addAll(userMemoryCache.keys)
                }

                synchronized(cacheLock) {
                    memoryCache.values.forEach { cachedTweet ->
                        candidateIds.add(cachedTweet.originalTweet.authorId)
                    }
                }

                HproseInstance.dao.getRecentCachedTweets(200).forEach { cachedTweet ->
                    candidateIds.add(cachedTweet.originalTweet.authorId)
                }

                for (userId in candidateIds) {
                    if (results.size >= limit || results.containsKey(userId)) continue
                    try {
                        val user = HproseInstance.fetchUser(userId)
                        consider(user)
                    } catch (e: Exception) {
                        Timber.tag("TweetCacheManager").v(e, "searchUsers: Failed to fetch user $userId")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "searchUsers: Failed to enrich results with tweet authors")
            }
        }

        return@withContext results.values
            .sortedWith(
                compareBy(
                    { it.first },
                    {
                        it.second.username?.normalizedLowercase()
                            ?: it.second.name?.normalizedLowercase()
                            ?: it.second.mid
                    }
                )
            )
            .map { it.second }
            .take(limit)
    }

    /**
     * Perform a partial search across locally cached tweets (memory + database).
     * Matches tweet content, title, and author metadata case-insensitively.
     */
    suspend fun searchTweets(query: String, limit: Int = 40): List<Tweet> = withContext(Dispatchers.IO) {
        if (query.isBlank() || limit <= 0) return@withContext emptyList()

        val normalizedQuery = query.normalizedLowercase()
        val results = LinkedHashMap<String, Pair<Int, Tweet>>(limit)

        fun consider(tweet: Tweet?) {
            if (tweet == null || tweet.isPrivate) return
            val score = tweet.matchScore(normalizedQuery) ?: return
            val existing = results[tweet.mid]
            if (existing == null || score < existing.first) {
                results[tweet.mid] = score to tweet
            }
        }

        synchronized(cacheLock) {
            memoryCache.values.forEach { cachedTweet ->
                consider(cachedTweet.originalTweet)
            }
        }

        if (results.size < limit) {
            try {
                HproseInstance.dao.getRecentCachedTweets(400).forEach { cachedTweet ->
                    consider(cachedTweet.originalTweet)
                    if (results.size >= limit) return@forEach
                }
            } catch (e: Exception) {
                Timber.e(e, "searchTweets: Unable to read cached tweets from database")
            }
        }

        val sortedTweets = results.values
            .sortedWith(compareBy({ it.first }, { -it.second.timestamp }))
            .map { it.second }
            .take(limit)
            .toMutableList()

        for (tweet in sortedTweets) {
            if (tweet.author == null) {
                try {
                    // Check cache first before fetching from server
                    tweet.author = getCachedUser(tweet.authorId) ?: HproseInstance.fetchUser(tweet.authorId)
                } catch (e: Exception) {
                    Timber.tag("TweetCacheManager").v(e, "searchTweets: Failed to fetch author ${tweet.authorId}")
                }
            }
        }

        return@withContext sortedTweets
    }

    private fun String.normalizedLowercase(): String =
        lowercase(Locale.getDefault()).trim()

    private fun User.matchScore(query: String): Int? {
        // Validate username exists (like iOS) - skip users without valid username
        val usernameValue = username?.takeIf { it.isNotBlank() }?.normalizedLowercase() ?: return null
        val nameValue = name?.normalizedLowercase() ?: ""

        return when {
            usernameValue.contains(query) -> if (usernameValue.startsWith(query)) 0 else 1
            nameValue.contains(query) -> if (nameValue.startsWith(query)) 2 else 3
            else -> null
        }
    }

    private fun Tweet.matchScore(query: String): Int? {
        // Search only in content and title, not in author username/name
        val contentValue = content?.normalizedLowercase() ?: ""
        val titleValue = title?.normalizedLowercase() ?: ""

        return when {
            contentValue.contains(query) -> if (contentValue.startsWith(query)) 0 else 1
            titleValue.contains(query) -> if (titleValue.startsWith(query)) 2 else 3
            else -> null
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