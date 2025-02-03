package com.fireshare.tweet

import android.content.Context
import android.content.SharedPreferences
import com.fireshare.tweet.datamodel.TW_CONST

class PreferenceHelper(context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun setAppUrl(baseUrl: String) {
        return sharedPreferences.edit().putString("appUrl", baseUrl).apply()
    }
    fun getAppUrl(): String? {
        return sharedPreferences.getString("appUrl", BuildConfig.BASE_URL)
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
}