package com.fireshare.tweet

import android.content.Context
import android.content.SharedPreferences
import com.fireshare.tweet.datamodel.TW_CONST

class PreferenceHelper(context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun saveProfile(profile: String) {
        sharedPreferences.edit().putString("profile", profile).apply()
    }

    fun getProfile(): String? {
        return sharedPreferences.getString("profile", " ")
    }

    fun saveUsername(username: String) {
        sharedPreferences.edit().putString("username", username).apply()
    }

    fun getUsername(): String? {
        return sharedPreferences.getString("username", null)
    }

    fun saveName(name: String) {
        sharedPreferences.edit().putString("name", name).apply()
    }

    fun getName(): String? {
        return sharedPreferences.getString("name", null)
    }

    fun saveAppUrl(baseUrl: String) {
        return sharedPreferences.edit().putString("appUrl", baseUrl).apply()
    }
    fun getAppUrl(): String? {
        return sharedPreferences.getString("appUrl", "twbe.fireshare.uk")
    }

    fun saveKeyPhrase(phrase: String) {
        sharedPreferences.edit().putString("keyPhrase", phrase).apply()
    }

    fun getKeyPhrase(): String? {
        return sharedPreferences.getString("keyPhrase", null)
    }

    fun setSpeakerMute(isMuted: Boolean) {
        sharedPreferences.edit().putBoolean("speakerMuted", isMuted).apply()
    }
    fun getSpeakerMute(): Boolean? {
        return sharedPreferences.getBoolean("speakerMuted", true)
    }

    fun getUserId(): String {
        var uid = sharedPreferences.getString("userId", null)
        if (uid == null) uid = TW_CONST.GUEST_ID
        return uid
    }
    fun setUserId(id: String?) {
        sharedPreferences.edit().putString("userId", id).apply()
    }
}