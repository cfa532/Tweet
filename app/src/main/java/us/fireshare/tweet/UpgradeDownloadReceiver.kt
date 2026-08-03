package us.fireshare.tweet

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import timber.log.Timber

class UpgradeDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            return
        }

        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (!UpgradeDownloadState.isTrackedDownload(context, downloadId)) {
            return
        }

        Timber.tag(TAG).d("Tracked upgrade download completed: $downloadId")
        UpgradeDownloadState.markCompleted(context, downloadId)
    }

    companion object {
        private const val TAG = "UpgradeDownload"
    }
}

object UpgradeDownloadState {
    private const val TAG = "UpgradeDownload"
    private const val PREFS_NAME = "upgrade_download_state"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_STARTED_VERSION_CODE = "started_version_code"
    private const val KEY_COMPLETED_DOWNLOAD_ID = "completed_download_id"
    private const val KEY_LAST_INSTALL_ATTEMPT_AT = "last_install_attempt_at"
    private const val INSTALL_ATTEMPT_DEBOUNCE_MS = 5_000L

    fun rememberDownload(context: Context, downloadId: Long) {
        val versionCode = currentVersionCode(context)
        prefs(context).edit {
            putLong(KEY_DOWNLOAD_ID, downloadId)
            putLong(KEY_STARTED_VERSION_CODE, versionCode)
            remove(KEY_COMPLETED_DOWNLOAD_ID)
            remove(KEY_LAST_INSTALL_ATTEMPT_AT)
        }
        Timber.tag(TAG).d("Remembered upgrade download id=$downloadId startedVersionCode=$versionCode")
    }

    fun isTrackedDownload(context: Context, downloadId: Long): Boolean {
        return downloadId != -1L && prefs(context).getLong(KEY_DOWNLOAD_ID, -1L) == downloadId
    }

    fun markCompleted(context: Context, downloadId: Long) {
        if (!isTrackedDownload(context, downloadId)) {
            return
        }
        prefs(context).edit {
            putLong(KEY_COMPLETED_DOWNLOAD_ID, downloadId)
        }
    }

    fun clearIfAppWasUpgraded(context: Context) {
        val startedVersionCode = prefs(context).getLong(KEY_STARTED_VERSION_CODE, -1L)
        if (startedVersionCode != -1L && currentVersionCode(context) != startedVersionCode) {
            Timber.tag(TAG).d("App version changed since upgrade download started; clearing stale upgrade state")
            clear(context)
        }
    }

    fun installCompletedUpgrade(context: Context, fromForeground: Boolean): Boolean {
        clearIfAppWasUpgraded(context)

        val downloadId = prefs(context).getLong(
            KEY_COMPLETED_DOWNLOAD_ID,
            prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)
        )
        if (downloadId == -1L) {
            return false
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        if (!isSuccessfulDownload(downloadManager, downloadId)) {
            return false
        }
        markCompleted(context, downloadId)

        val now = System.currentTimeMillis()
        val lastAttemptAt = prefs(context).getLong(KEY_LAST_INSTALL_ATTEMPT_AT, 0L)
        if (now - lastAttemptAt < INSTALL_ATTEMPT_DEBOUNCE_MS) {
            return false
        }
        prefs(context).edit { putLong(KEY_LAST_INSTALL_ATTEMPT_AT, now) }

        val downloadedApkUri = downloadManager.getUriForDownloadedFile(downloadId)
        if (downloadedApkUri == null) {
            Timber.tag(TAG).e("DownloadManager returned null APK URI for id=$downloadId")
            return false
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(downloadedApkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (!fromForeground || context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        return try {
            Timber.tag(TAG).d("Opening installer for completed upgrade download id=$downloadId")
            context.startActivity(installIntent)
            if (fromForeground) {
                clear(context)
            }
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to open installer for completed upgrade download id=$downloadId")
            false
        }
    }

    private fun isSuccessfulDownload(downloadManager: DownloadManager, downloadId: Long): Boolean {
        downloadManager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (!cursor.moveToFirst()) {
                Timber.tag(TAG).w("Tracked upgrade download id=$downloadId no longer exists")
                return false
            }
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return status == DownloadManager.STATUS_SUCCESSFUL
        }
    }

    private fun clear(context: Context) {
        prefs(context).edit { clear() }
    }

    private fun currentVersionCode(context: Context): Long {
        return context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
