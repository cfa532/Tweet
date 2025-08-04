package us.fireshare.tweet.datamodel

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Dao
interface BlacklistDao {
    @Query("SELECT * FROM blacklist WHERE resourceId = :resourceId")
    suspend fun get(resourceId: String): BlacklistEntry?

    @Query("SELECT * FROM blacklist WHERE isBlacklisted = 1")
    suspend fun getAllBlacklisted(): List<BlacklistEntry>

    @Query("SELECT * FROM blacklist WHERE isBlacklisted = 0")
    suspend fun getAllCandidates(): List<BlacklistEntry>

    @Query("SELECT * FROM blacklist")
    suspend fun getAll(): List<BlacklistEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: BlacklistEntry)

    @Query("DELETE FROM blacklist WHERE resourceId = :resourceId")
    suspend fun delete(resourceId: String)

    @Query("DELETE FROM blacklist")
    suspend fun clearAll()

    @Query("DELETE FROM blacklist WHERE isBlacklisted = 0 AND :now - firstFailureTime > :maxAge")
    suspend fun deleteOldCandidates(now: Long, maxAge: Long = 2 * 7 * 24 * 60 * 60 * 1000L)
}

@Entity(tableName = "blacklist")
data class BlacklistEntry(
    @PrimaryKey val resourceId: String,
    val firstFailureTime: Long,
    val lastFailureTime: Long,
    val failureCount: Int,
    val isBlacklisted: Boolean,
    val blacklistedTime: Long?
)