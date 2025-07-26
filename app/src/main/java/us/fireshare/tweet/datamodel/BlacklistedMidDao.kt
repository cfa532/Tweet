package us.fireshare.tweet.datamodel

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BlacklistedMidDao {
    @Query("SELECT * FROM blacklisted_mid WHERE mid = :mid")
    suspend fun get(mid: String): BlacklistedMid?

    @Query("SELECT * FROM blacklisted_mid")
    suspend fun getAll(): List<BlacklistedMid>

    @Query("SELECT * FROM blacklisted_mid WHERE :now - firstDetected >= :threeDaysMillis")
    suspend fun getActiveBlacklist(now: Long, threeDaysMillis: Long = 3 * 24 * 60 * 60 * 1000L): List<BlacklistedMid>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(blacklisted: BlacklistedMid)

    @Query("DELETE FROM blacklisted_mid WHERE mid = :mid")
    suspend fun delete(mid: String)

    @Query("DELETE FROM blacklisted_mid")
    suspend fun clearAll()
} 