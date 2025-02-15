package com.fireshare.tweet

import android.content.Context
import android.content.SharedPreferences
import com.fireshare.tweet.datamodel.TW_CONST

class PreferenceHelper(context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun setAppUrls(urls: Set<String>) {
        val urlsString = urls.filter { it.isNotEmpty() }.joinToString(",") { it }
        sharedPreferences.edit().putString("custom_urls", urlsString).apply()
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
        sharedPreferences.edit().putBoolean("speakerMuted", isMuted).apply()
    }
    fun getSpeakerMute(): Boolean {
        return sharedPreferences.getBoolean("speakerMuted", true)
    }

    fun getUserId(): String? {
        return sharedPreferences.getString("userId", TW_CONST.GUEST_ID)
    }
    fun setUserId(id: String?) {
        sharedPreferences.edit().putString("userId", id).apply()
    }

    fun getTweetFeedTabIndex(): Int {
        return sharedPreferences.getInt("tweetFeedIndex", 0)
    }
    fun setTweetFeedTabIndex(id: Int) {
        sharedPreferences.edit().putInt("tweetFeedIndex", id).apply()
    }
}