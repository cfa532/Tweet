package com.fireshare.tweet

import android.content.Context
import android.content.SharedPreferences
import com.fireshare.tweet.datamodel.MimeiId

class PreferencesHelper(context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun saveProfile(url: String) {
        sharedPreferences.edit().putString("profile", url).apply()
    }

    fun getProfile(): String? {
        return sharedPreferences.getString("profile", null)
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
        return sharedPreferences.getString("appUrl", "twbe.fireshare.us")
    }

    fun saveKeyPhrase(phrase: String) {
        sharedPreferences.edit().putString("keyPhrase", phrase).apply()
    }

    fun getKeyPhrase(): String? {
        return sharedPreferences.getString("keyPhrase", null)
    }

    // the default User Mid to be followed. Provided by App server
    fun getInitMimei(): String? {
        return sharedPreferences.getString("initMimei", "6-4DWxT6wpfClqZyAu0Bt4Dsx-q")
    }

    fun setAppId(id: String) {
        sharedPreferences.edit().putString("appId", id).apply()
    }
    fun getAppId(): String? {
        return sharedPreferences.getString("appId", null)
    }

    fun getUserId(): String? {
        return sharedPreferences.getString("userId", null)
    }
}