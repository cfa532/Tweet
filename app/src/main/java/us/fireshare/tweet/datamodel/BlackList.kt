package us.fireshare.tweet.datamodel

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * BlackList object manages resources that are consistently inaccessible.
 * 
 * Logic:
 * 1. When a resource fails to access, add it to candidates
 * 2. When a resource is successfully accessed, remove it from candidates
 * 3. If a candidate stays for more than a week and has over 14 failed accesses, move to blacklist
 * 4. Blacklisted resources are ignored in future requests to save resources
 * 5. Data is persisted in database across app restarts
 */
object BlackList {
    
    // Database DAO
    private var blacklistDao: BlacklistDao? = null
    
    // Mutex for thread-safe operations
    private val mutex = Mutex()
    
    // Constants
    private const val WEEK_IN_MILLIS = 7 * 24 * 60 * 60 * 1000L
    private const val MAX_FAILURE_COUNT = 14
    
    /**
     * Initialize the blacklist with database access
     */
    fun initialize(context: Context) {
        if (blacklistDao == null) {
            blacklistDao = TweetCacheDatabase.getInstance(context).blacklistDao()
            Timber.tag("BlackList").d("BlackList initialized with database")
        }
    }
    
    /**
     * Record a failed access attempt for a resource
     */
    suspend fun recordFailure(resourceId: String) {
        mutex.withLock {
            val dao = blacklistDao ?: run {
                Timber.tag("BlackList").e("BlackList not initialized")
                return@withLock
            }
            
            val now = System.currentTimeMillis()
            val existing = dao.get(resourceId)
            
            if (existing != null) {
                // Update existing entry
                val updatedFailureCount = existing.failureCount + 1
                val updatedEntry = existing.copy(
                    lastFailureTime = now,
                    failureCount = updatedFailureCount
                )
                
                // Check if it should be moved to blacklist
                if (shouldMoveToBlacklist(updatedEntry)) {
                    val blacklistedEntry = updatedEntry.copy(
                        isBlacklisted = true,
                        blacklistedTime = now
                    )
                    dao.insert(blacklistedEntry)
                    Timber.tag("BlackList").w("Moved $resourceId to blacklist after $updatedFailureCount failures over ${now - updatedEntry.firstFailureTime}ms")
                } else {
                    dao.insert(updatedEntry)
                    Timber.tag("BlackList").d("Updated candidate $resourceId: failures=$updatedFailureCount, timeSinceFirst=${now - updatedEntry.firstFailureTime}ms")
                }
            } else {
                // Add new candidate
                val newEntry = BlacklistEntry(
                    resourceId = resourceId,
                    firstFailureTime = now,
                    lastFailureTime = now,
                    failureCount = 1,
                    isBlacklisted = false,
                    blacklistedTime = null
                )
                dao.insert(newEntry)
                Timber.tag("BlackList").d("Added new candidate $resourceId")
            }
        }
    }
    
    /**
     * Record a successful access for a resource
     */
    suspend fun recordSuccess(resourceId: String) {
        mutex.withLock {
            val dao = blacklistDao ?: run {
                Timber.tag("BlackList").e("BlackList not initialized")
                return@withLock
            }
            
            // Remove from candidates if present (but keep blacklisted entries)
            val existing = dao.get(resourceId)
            if (existing != null && !existing.isBlacklisted) {
                dao.delete(resourceId)
                Timber.tag("BlackList").d("Removed candidate $resourceId after successful access (had ${existing.failureCount} failures)")
            }
            
            // Note: We don't remove from blacklist on success to maintain the blacklist
            // This prevents resources from being repeatedly added/removed
        }
    }
    
    /**
     * Check if a resource is blacklisted
     */
    suspend fun isBlacklisted(resourceId: String): Boolean {
        return mutex.withLock {
            val dao = blacklistDao ?: run {
                Timber.tag("BlackList").e("BlackList not initialized")
                return@withLock false
            }
            
            val entry = dao.get(resourceId)
            entry?.isBlacklisted == true
        }
    }
    
    /**
     * Clear all data (for testing or reset purposes)
     */
    suspend fun clear() {
        mutex.withLock {
            val dao = blacklistDao ?: run {
                Timber.tag("BlackList").e("BlackList not initialized")
                return@withLock
            }
            
            dao.clearAll()
            Timber.tag("BlackList").d("Cleared all blacklist data")
        }
    }
    
    /**
     * Check if a candidate should be moved to blacklist
     */
    private fun shouldMoveToBlacklist(entry: BlacklistEntry): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceFirst = now - entry.firstFailureTime
        
        return timeSinceFirst > WEEK_IN_MILLIS && entry.failureCount >= MAX_FAILURE_COUNT
    }
} 