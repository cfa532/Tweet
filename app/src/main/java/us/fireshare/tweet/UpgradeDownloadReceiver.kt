package us.fireshare.tweet

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

data class UpgradeRelease(
    val versionCode: Long,
    val versionName: String,
    val packageId: String,
    val size: Long,
    val sha256: String,
    val mission: String,
    val downloadUrl: String = "",
)

class UpgradeDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (UpgradeDownloadState.isTrackedDownload(context, downloadId)) {
            UpgradeDownloadState.markCompleted(context, downloadId)
        }
    }
}

/** Persistent, verified state for full-flavor APK updates. */
object UpgradeDownloadState {
    const val MISSING = -100
    private const val TAG = "UpgradeDownload"
    private const val PREFS_NAME = "upgrade_download_state"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_STARTED_VERSION_CODE = "started_version_code"
    private const val KEY_COMPLETED_DOWNLOAD_ID = "completed_download_id"
    private const val KEY_LAST_INSTALL_ATTEMPT_AT = "last_install_attempt_at"
    private const val KEY_TARGET_VERSION_CODE = "target_version_code"
    private const val KEY_TARGET_VERSION_NAME = "target_version_name"
    private const val KEY_PACKAGE_ID = "package_id"
    private const val KEY_PACKAGE_SIZE = "package_size"
    private const val KEY_PACKAGE_SHA256 = "package_sha256"
    private const val KEY_MISSION = "mission"
    private const val INSTALL_ATTEMPT_DEBOUNCE_MS = 5_000L
    private val installMutex = Mutex()

    fun enqueue(context: Context, release: UpgradeRelease): Long {
        discard(context)
        target(context, release.versionCode).delete()
        val request = DownloadManager.Request(release.downloadUrl.toUri())
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                fileName(release.versionCode),
            )
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setTitle("Downloading Tweet ${release.versionName}")
        val downloadId = manager(context).enqueue(request)
        prefs(context).edit {
            putLong(KEY_DOWNLOAD_ID, downloadId)
            putLong(KEY_STARTED_VERSION_CODE, currentVersionCode(context))
            putLong(KEY_TARGET_VERSION_CODE, release.versionCode)
            putString(KEY_TARGET_VERSION_NAME, release.versionName)
            putString(KEY_PACKAGE_ID, release.packageId)
            putLong(KEY_PACKAGE_SIZE, release.size)
            putString(KEY_PACKAGE_SHA256, release.sha256)
            putString(KEY_MISSION, release.mission)
            remove(KEY_COMPLETED_DOWNLOAD_ID)
            remove(KEY_LAST_INSTALL_ATTEMPT_AT)
        }
        return downloadId
    }

    fun trackedDownloadId(context: Context): Long = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)

    fun isTrackedDownload(context: Context, downloadId: Long): Boolean =
        downloadId != -1L && trackedDownloadId(context) == downloadId

    fun markCompleted(context: Context, downloadId: Long) {
        if (isTrackedDownload(context, downloadId)) {
            prefs(context).edit { putLong(KEY_COMPLETED_DOWNLOAD_ID, downloadId) }
        }
    }

    fun status(context: Context, downloadId: Long): Int {
        manager(context).query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (!cursor.moveToFirst()) return MISSING
            return cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
    }

    fun clearIfAppWasUpgraded(context: Context) {
        val startedVersionCode = prefs(context).getLong(KEY_STARTED_VERSION_CODE, -1L)
        if (startedVersionCode != -1L && currentVersionCode(context) != startedVersionCode) {
            discard(context)
        }
    }

    suspend fun installCompletedUpgrade(context: Context, fromForeground: Boolean): Boolean = installMutex.withLock {
        installCompletedUpgradeLocked(context, fromForeground)
    }

    private suspend fun installCompletedUpgradeLocked(context: Context, fromForeground: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            clearIfAppWasUpgraded(context)
            val downloadId = prefs(context).getLong(KEY_COMPLETED_DOWNLOAD_ID, trackedDownloadId(context))
            if (downloadId == -1L || status(context, downloadId) != DownloadManager.STATUS_SUCCESSFUL) {
                return@withContext false
            }
            markCompleted(context, downloadId)

            val now = System.currentTimeMillis()
            if (now - prefs(context).getLong(KEY_LAST_INSTALL_ATTEMPT_AT, 0L) < INSTALL_ATTEMPT_DEBOUNCE_MS) {
                return@withContext false
            }
            val release = storedRelease(context)
            if (release == null) {
                Timber.tag(TAG).e("Downloaded update has no verification metadata")
                discard(context)
                return@withContext false
            }
            val file = target(context, release.versionCode)
            val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            val valid = file.isFile && file.length() == release.size &&
                file.sha256() == release.sha256 && archive != null &&
                archive.packageName == context.packageName && archive.longVersionCode == release.versionCode
            if (!valid) {
                Timber.tag(TAG).e("Downloaded update failed size, checksum, package, or version verification")
                discard(context)
                return@withContext false
            }

            val uri = manager(context).getUriForDownloadedFile(downloadId)
            if (uri == null) {
                Timber.tag(TAG).e("DownloadManager returned no APK URI for id=$downloadId")
                return@withContext false
            }
            prefs(context).edit { putLong(KEY_LAST_INSTALL_ATTEMPT_AT, now) }
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (!fromForeground || context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                withContext(Dispatchers.Main) { context.startActivity(installIntent) }
                if (fromForeground) clearDownloadTracking(context)
                true
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "Failed to open installer for download id=$downloadId")
                false
            }
        }

    fun discard(context: Context) {
        val downloadId = trackedDownloadId(context)
        val release = storedRelease(context)
        if (downloadId != -1L) manager(context).remove(downloadId)
        release?.let { target(context, it.versionCode).delete() }
        prefs(context).edit { clear() }
    }

    private fun storedRelease(context: Context): UpgradeRelease? {
        val values = prefs(context)
        val versionCode = values.getLong(KEY_TARGET_VERSION_CODE, -1L)
        val versionName = values.getString(KEY_TARGET_VERSION_NAME, null) ?: return null
        val packageId = values.getString(KEY_PACKAGE_ID, null) ?: return null
        val size = values.getLong(KEY_PACKAGE_SIZE, -1L)
        val sha256 = values.getString(KEY_PACKAGE_SHA256, null) ?: return null
        val mission = values.getString(KEY_MISSION, null) ?: return null
        if (versionCode < 1 || size < 1) return null
        return UpgradeRelease(versionCode, versionName, packageId, size, sha256, mission)
    }

    private fun clearDownloadTracking(context: Context) {
        prefs(context).edit {
            remove(KEY_DOWNLOAD_ID)
            remove(KEY_COMPLETED_DOWNLOAD_ID)
            remove(KEY_LAST_INSTALL_ATTEMPT_AT)
        }
    }

    private fun currentVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    private fun manager(context: Context) =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun fileName(versionCode: Long) = "Tweet-update-$versionCode.apk"

    private fun target(context: Context, versionCode: Long): File {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("Android could not prepare the update download folder")
        return File(directory, fileName(versionCode))
    }

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
