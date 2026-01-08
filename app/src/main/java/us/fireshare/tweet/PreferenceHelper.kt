package us.fireshare.tweet

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import timber.log.Timber
import us.fireshare.tweet.datamodel.TW_CONST

class PreferenceHelper(context: Context) {
    // Use applicationId in preferences filename to ensure debug/release builds have separate storage
    private val prefsName = "app_prefs_${context.packageName.replace(".", "_")}"
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val currentPackageName = context.packageName
    
    init {
        Timber.tag("PreferenceHelper").d("Initialized with packageName: $currentPackageName, prefsName: $prefsName")
        val existingUserId = sharedPreferences.getString("userId", null)
        val storedPackageName = sharedPreferences.getString("_packageName", null)
        Timber.tag("PreferenceHelper").d("Existing userId: ${existingUserId ?: "null (will use GUEST_ID)"}, stored packageName: ${storedPackageName ?: "null"}")
        
        // Warn if packageName changed (might indicate data loss)
        if (storedPackageName != null && storedPackageName != currentPackageName) {
            Timber.tag("PreferenceHelper").w("⚠️ PackageName changed! Previous: '$storedPackageName', Current: '$currentPackageName' - data may have been cleared")
        }
        
        // Store current packageName for future comparison
        if (storedPackageName != currentPackageName) {
            sharedPreferences.edit { putString("_packageName", currentPackageName) }
        }
    }

    /**
     * Helper function to ensure URL has http:// prefix
     */
    private fun ensureHttpPrefix(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.isNotEmpty() -> "http://$trimmed"
            else -> trimmed
        }
    }

    fun setAppUrls(urls: Set<String>) {
        // Ensure all URLs have http:// prefix before saving
        val normalizedUrls = urls.map { ensureHttpPrefix(it) }.filter { it.isNotEmpty() }
        val urlsString = normalizedUrls.joinToString(",")
        sharedPreferences.edit() { putString("custom_urls", urlsString) }
    }
    
    fun getAppUrls(): Set<String> {
        val urlsString = sharedPreferences.getString("custom_urls", "") ?: ""
        
        val urls = if (urlsString.isNotEmpty()) {
            // Ensure all loaded URLs have http:// prefix
            urlsString.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { ensureHttpPrefix(it) }
                .toMutableSet()
        } else {
            mutableSetOf<String>()
        }
        
        // Always add BASE_URL to the set
        urls.add("http://${BuildConfig.BASE_URL}")
        return urls
    }

    fun setSpeakerMute(isMuted: Boolean) {
        sharedPreferences.edit { putBoolean("speakerMuted", isMuted) }
    }
    fun getSpeakerMute(): Boolean {
        return sharedPreferences.getBoolean("speakerMuted", true)
    }

    fun getUserId(): String {
        val userId = sharedPreferences.getString("userId", TW_CONST.GUEST_ID) ?: TW_CONST.GUEST_ID
        Timber.tag("PreferenceHelper").d("getUserId() -> '$userId'")
        return userId
    }
    fun setUserId(id: String?) {
        val previousUserId = sharedPreferences.getString("userId", null)
        Timber.tag("PreferenceHelper").w("setUserId() called: previous='$previousUserId', new='$id'")
        sharedPreferences.edit { 
            putString("userId", id)
            putString("_packageName", currentPackageName) // Update packageName on save
        }
        val verify = sharedPreferences.getString("userId", null)
        Timber.tag("PreferenceHelper").d("setUserId() saved, verify: '$verify'")
    }

    fun getTweetFeedTabIndex(): Int {
        return sharedPreferences.getInt("tweetFeedIndex", 0)
    }
    fun setTweetFeedTabIndex(id: Int) {
        sharedPreferences.edit { putInt("tweetFeedIndex", id) }
    }

    fun getCloudPort(): String? {
        return sharedPreferences.getString("cloudPort", null)
    }
    fun setCloudPort(port: String?) {
        port?.let {
            sharedPreferences.edit { putString("cloudPort", it) }
        }
    }

    // Theme preference methods
    fun getThemeMode(): String {
        return sharedPreferences.getString("theme_mode", "light") ?: "light"
    }
    fun setThemeMode(mode: String) {
        sharedPreferences.edit { putString("theme_mode", mode) }
    }
}