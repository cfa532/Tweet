package us.fireshare.tweet.datamodel

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.appUser
import java.util.Date

// cache for tweets
@Entity(indices = [Index(value = ["uid"])])
data class CachedTweet(
    @PrimaryKey val mid: MimeiId,   // Tweet's mimei Id
    val uid: MimeiId,       // user Id
    val originalTweet: Tweet,   // tweet object.
    val timestamp: Date = Date() // Automatically set to the current date and time
)

@Entity
data class CachedUser(
    @PrimaryKey val userId: MimeiId = appUser.mid,
    val user: User,
)

class UserConverter {
    private val gson = Gson().newBuilder()
        .excludeFieldsWithoutExposeAnnotation()
        .create()
    
    @TypeConverter
    fun fromUser(user: User): String {
        return gson.toJson(user)
    }

    @TypeConverter
    fun toUser(str: String): User? {
        return try {
            gson.fromJson(str, object : TypeToken<User?>() {}.type)
        } catch (e: Exception) {
            Timber.tag("toUser").e("$e")
            null
        }
    }
}

class TweetConverter {
    private val gson = Gson().newBuilder()
        .excludeFieldsWithoutExposeAnnotation()
        .create()
    
    @TypeConverter
    fun fromTweet(tweet: Tweet): String {
        return gson.toJson(tweet)
    }

    @TypeConverter
    fun toTweet(str: String): Tweet? {
        return try {
            gson.fromJson(str, object : TypeToken<Tweet?>() {}.type)
        } catch (e: Exception) {
            Timber.tag("toTweet").e("$e")
            null
        }
    }
}

class DateConverter {
    @TypeConverter
    fun fromDate(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun toDate(timestamp: Long?): Date? {
        return timestamp?.let { Date(it) }
    }
}

@Dao
interface CachedTweetDao {
    /**
     * Cache of User list.
     * */
    @Insert(onConflict = OnConflictStrategy.REPLACE) // Use REPLACE strategy to overwrite existing data
    fun insertOrUpdateCachedUser(cachedUser: CachedUser)

    @Query("SELECT * FROM CachedUser WHERE userId = :userId")
    fun getCachedUser(userId: MimeiId): CachedUser?

    @Query("DELETE FROM CachedUser WHERE userId = :userId")
    fun deleteCachedUser(userId: MimeiId)

    @Query("DELETE FROM CachedUser")
    fun clearAllCachedUsers()

    /**
     * Cache of tweets. Clear tweets cached more than a month ago with Cleanup workerManager.
     * */
    @Insert(onConflict = OnConflictStrategy.REPLACE) // Use REPLACE strategy
    fun insertOrUpdateCachedTweet(cachedTweet: CachedTweet)

    @Query("SELECT * FROM CachedTweet WHERE mid = :tweetId")
    fun getCachedTweet(tweetId: MimeiId): CachedTweet?

    @Query("SELECT * FROM CachedTweet ORDER BY timestamp DESC LIMIT :count OFFSET :offset")
    fun getCachedTweets(offset: Int, count: Int): List<CachedTweet>

    @Query("SELECT * FROM CachedTweet WHERE uid = :userId ORDER BY timestamp DESC" +
            " LIMIT :count OFFSET :offset")
    fun getCachedTweetsByUser(userId: MimeiId, offset: Int, count: Int): List<CachedTweet>

    // Delete tweets older than 30 days.
    @Query("DELETE FROM CachedTweet WHERE timestamp < :oneMonthAgo")
    fun deleteOldCachedTweets(oneMonthAgo: Date)

    @Query("DELETE FROM CachedTweet")
    fun clearAllCachedTweets()

    @Query("DELETE FROM CachedTweet WHERE mid = :tweetId")
    fun deleteCachedTweet(tweetId: MimeiId)
}

@Database(entities = [CachedTweet::class, CachedUser::class, BlacklistedMid::class], version = 8)
@TypeConverters(DateConverter::class, TweetConverter::class, UserConverter::class)
abstract class TweetCacheDatabase : RoomDatabase() {
    abstract fun tweetDao(): CachedTweetDao
    abstract fun blacklistedMidDao(): BlacklistedMidDao

    companion object {
        @Volatile
        private var INSTANCE: TweetCacheDatabase? = null

        fun getInstance(context: Context): TweetCacheDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TweetCacheDatabase::class.java,
                    "tweet_cache_database"
                )
                    .fallbackToDestructiveMigration(false)
                    .addMigrations(MIGRATION_7_8)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create new table with updated schema
                db.execSQL(
                    "CREATE TABLE blacklisted_mid_new (" +
                    "mid TEXT NOT NULL PRIMARY KEY, " +
                    "lastSuccessfulAccess INTEGER NOT NULL, " +
                    "lastFailureTime INTEGER NOT NULL)"
                )
                
                // Copy data from old table to new table
                // Map firstDetected -> lastSuccessfulAccess and lastChecked -> lastFailureTime
                db.execSQL(
                    "INSERT INTO blacklisted_mid_new (mid, lastSuccessfulAccess, lastFailureTime) " +
                    "SELECT mid, firstDetected, lastChecked FROM blacklisted_mid"
                )
                
                // Drop old table
                db.execSQL("DROP TABLE blacklisted_mid")
                
                // Rename new table to original name
                db.execSQL("ALTER TABLE blacklisted_mid_new RENAME TO blacklisted_mid")
            }
        }
    }
}
