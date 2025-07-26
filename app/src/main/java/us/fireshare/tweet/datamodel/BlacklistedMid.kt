package us.fireshare.tweet.datamodel

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blacklisted_mid")
data class BlacklistedMid(
    @PrimaryKey val mid: String,
    val firstDetected: Long,
    val lastChecked: Long
) 