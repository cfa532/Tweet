package us.fireshare.tweet

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import us.fireshare.tweet.datamodel.TW_CONST

class PreferenceHelper(context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun setAppUrls(urls: Set<String>) {
        val urlsString = urls.filter { it.isNotEmpty() }.joinToString(",") { it }
        sharedPreferences.edit() { putString("custom_urls", urlsString) }
    }
    fun getAppUrls(): Set<String> {
        val urlsString = sharedPreferences.getString("custom_urls", "") ?: ""
        return if (urlsString.isNotEmpty()) {
            urlsString.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } else {
            setOf("http://${BuildConfig.BASE_URL}")
        }
    }

    fun setSpeakerMute(isMuted: Boolean) {
        sharedPreferences.edit { putBoolean("speakerMuted", isMuted) }
    }
    fun getSpeakerMute(): Boolean {
        return sharedPreferences.getBoolean("speakerMuted", true)
    }

    fun getUserId(): String? {
        return sharedPreferences.getString("userId", TW_CONST.GUEST_ID)
    }
    fun setUserId(id: String?) {
        sharedPreferences.edit { putString("userId", id) }
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