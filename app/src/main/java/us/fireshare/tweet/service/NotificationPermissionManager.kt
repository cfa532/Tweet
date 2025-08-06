package us.fireshare.tweet.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import timber.log.Timber
import us.fireshare.tweet.R

object NotificationPermissionManager {
    private const val PREF_NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"
    private const val PREF_LAST_ASKED_VERSION = "last_asked_version"

    /**
     * Check if notification permission should be requested
     * Returns true if permission hasn't been asked for this version
     */
    fun shouldRequestNotificationPermission(context: Context): Boolean {
        val prefs = context.getSharedPreferences("notification_permissions", Context.MODE_PRIVATE)
        val lastAskedVersion = prefs.getString(PREF_LAST_ASKED_VERSION, "")
        val currentVersion = getAppVersion(context)
        
        // Ask on first install or version upgrade
        return lastAskedVersion != currentVersion
    }

    /**
     * Mark that notification permission has been asked for this version
     */
    fun markNotificationPermissionAsked(context: Context) {
        val prefs = context.getSharedPreferences("notification_permissions", Context.MODE_PRIVATE)
        val currentVersion = getAppVersion(context)
        prefs.edit { putString(PREF_LAST_ASKED_VERSION, currentVersion) }
    }

    /**
     * Check if notification permission is granted
     */
    fun isNotificationPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // For older versions, check if notifications are enabled
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    /**
     * Request notification permission using the new permission launcher
     */
    fun requestNotificationPermission(
        activity: ComponentActivity,
        onPermissionResult: (Boolean) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Use the new permission launcher for Android 13+
            val permissionLauncher = activity.registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                Timber.d("Notification permission result: $isGranted")
                markNotificationPermissionAsked(activity)
                onPermissionResult(isGranted)
            }
            
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // For older versions, just mark as asked and don't open settings
            // Let users enable notifications manually if they want
            markNotificationPermissionAsked(activity)
            onPermissionResult(false)
        }
    }



    /**
     * Open notification settings for the app with improved navigation
     */
    fun openNotificationSettings(context: Context) {
        try {
            val intent = Intent().apply {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                        // For Android 8+ (API 26+), use the specific notification settings
                        action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        // Add flags to ensure proper navigation
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    else -> {
                        // For older versions, open app info where user can enable notifications
                        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to open notification settings")
            // Fallback to general app settings
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                Timber.e(e2, "Failed to open app settings")
            }
        }
    }

    /**
     * Get current app version string
     */
    private fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName}-${packageInfo.versionCode}"
        } catch (e: Exception) {
            Timber.e(e, "Failed to get app version")
            "unknown"
        }
    }
} 