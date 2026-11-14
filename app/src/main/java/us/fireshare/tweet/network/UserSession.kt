package us.fireshare.tweet.network

import android.content.Context
import android.content.SharedPreferences
import us.fireshare.tweet.datamodel.HproseService
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/**
 * The caller's Leither session — the Android counterpart of iOS
 * OwnerSession.swift and TweetWeb's userSession.ts.
 *
 * A node that checks "usersid" (advertised in health as userSessions) expects
 * the caller to sign in to the node's own Leither login — a guest with the
 * username and password Leither holds for them — and to pass the session it
 * receives on every write to the caller's own data. A session is valid only on
 * the node that issued it and lives about an hour, so one is kept per account
 * per node and re-opened when the node drops it ("Session invalid").
 */
object UserSession {
    private const val TAG = "UserSession"

    /** A Leither session lives an hour; treat one as stale well before that. */
    private const val SESSION_FRESH_MS = 45L * 60 * 1000

    private data class Entry(val sid: String, val at: Long)

    private val sessions = ConcurrentHashMap<String, Entry>()

    fun cachedSession(accountId: String, nodeUrl: String): String? {
        val entry = sessions["$accountId@$nodeUrl"] ?: return null
        return if (System.currentTimeMillis() - entry.at < SESSION_FRESH_MS) entry.sid else null
    }

    fun rememberSession(accountId: String, nodeUrl: String, sid: String) {
        sessions["$accountId@$nodeUrl"] = Entry(sid, System.currentTimeMillis())
    }

    fun discardSession(accountId: String, nodeUrl: String) {
        sessions.remove("$accountId@$nodeUrl")
    }

    fun clearAll() {
        sessions.clear()
    }

    /** True when a reply says the node no longer knows the session it was given. */
    fun isSessionInvalid(reply: Any?): Boolean {
        if (reply !is Map<*, *>) return false
        val message = reply["message"] as? String ?: reply["error"] as? String
        return message == "Session invalid"
    }

    // The guest credential of the signed-in account, kept in plain
    // SharedPreferences: it exists to re-open a session for later writes.
    // iOS keeps the same material in the Keychain; this app already stores the
    // user object (with its password field) in its Room cache, so this store is
    // no weaker than the status quo. Cleared on logout together with userId.
    private const val PREFS = "tweet_owner_session"
    private const val KEY_USERNAME = "credential_username"
    private const val KEY_PASSWORD = "credential_password"
    private const val KEY_ACCOUNT = "credential_account"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun storeCredential(context: Context, accountId: String, username: String, password: String) {
        prefs(context).edit()
            .putString(KEY_ACCOUNT, accountId)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun loadCredential(context: Context, accountId: String): Pair<String, String>? {
        val p = prefs(context)
        if (p.getString(KEY_ACCOUNT, null) != accountId) return null
        val username = p.getString(KEY_USERNAME, null) ?: return null
        val password = p.getString(KEY_PASSWORD, null) ?: return null
        return username to password
    }

    fun clearCredential(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * Sign in to the node this service points at through Leither's own byname
     * login. Returns the session id, or null when the node refused.
     */
    fun leitherLogin(service: HproseService, username: String, password: String): String? {
        return try {
            val reply = service.Login(username, password)
            val sid = (reply["Sid"] ?: reply["sid"]) as? String
            if (!sid.isNullOrEmpty()) sid else {
                Timber.tag(TAG).w("Login returned no session: %s", reply)
                null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Login failed")
            null
        }
    }

    /**
     * Register a guest with Leither on the node this service points at.
     * Returns the new Leither user id, or null when the node refused.
     */
    fun leitherRegister(service: HproseService, username: String, password: String): String? {
        return try {
            val uid = service.Register(username, password, "")
            if (!uid.isNullOrEmpty()) uid else {
                Timber.tag(TAG).w("Register returned no uid")
                null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Register failed")
            null
        }
    }
}
