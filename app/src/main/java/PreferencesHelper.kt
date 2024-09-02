import android.content.Context
import android.content.SharedPreferences

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

    fun saveBaseUrl(baseUrl: String) {
        return sharedPreferences.edit().putString("baseUrl", baseUrl).apply()
    }
    fun getBaseUrl(): String? {
        return sharedPreferences.getString("baseUrl", "twbe.fireshare.us")
    }
}