package us.fireshare.tweet.datamodel

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blacklisted_mid")
data class BlacklistedMid(
    @PrimaryKey val mid: String,
    val lastSuccessfulAccess: Long,  // Timestamp of last successful access
    val lastFailureTime: Long        // Timestamp of last failed access attempt
) 