package us.fireshare.tweet.network

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import timber.log.Timber
import us.fireshare.tweet.datamodel.HproseService

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

    // The backup rules exclude this whole file, including credentials from
    // older installs. The device-bound key stays in Android Keystore.
    private const val PREFS = "tweet_owner_session"
    private const val KEY_USERNAME = "credential_username"
    private const val KEY_ACCOUNT = "credential_account"
    private const val KEY_CIPHERTEXT = "credential_ciphertext"
    private const val KEY_IV = "credential_iv"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "tweet_guest_credential"
    private const val CIPHER = "AES/GCM/NoPadding"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun credentialKey(): SecretKey? = KeyStore.getInstance(KEYSTORE).run {
        load(null)
        getKey(KEY_ALIAS, null) as? SecretKey
    }

    @Synchronized
    fun storeCredential(context: Context, accountId: String, username: String, password: String) {
        try {
            val key = credentialKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                init(spec)
                generateKey()
            }
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            prefs(context).edit().clear()
                .putString(KEY_ACCOUNT, accountId)
                .putString(KEY_USERNAME, username)
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()
        } catch (_: Exception) {
            Timber.tag(TAG).w("Could not save the encrypted guest credential")
        }
    }

    @Synchronized
    fun loadCredential(context: Context, accountId: String): Pair<String, String>? {
        val p = prefs(context)
        if (p.getString(KEY_ACCOUNT, null) != accountId) return null
        val username = p.getString(KEY_USERNAME, null) ?: return null
        val ciphertext = p.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = p.getString(KEY_IV, null) ?: return null
        return try {
            val key = credentialKey() ?: return null
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            username to cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            Timber.tag(TAG).w("Could not read the encrypted guest credential")
            null
        }
    }

    @Synchronized
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
                Timber.tag(TAG).w("Login returned no session")
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
