package us.fireshare.tweet

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat.getString
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.navigation.TweetNavGraph
import us.fireshare.tweet.service.NotificationPermissionManager
import us.fireshare.tweet.service.OrientationManager
import us.fireshare.tweet.ui.theme.ThemeManager
import us.fireshare.tweet.ui.theme.TweetTheme
import javax.inject.Inject

@AndroidEntryPoint
class TweetActivity : ComponentActivity() {
    private lateinit var initJob: Deferred<Unit>
    private val activityViewModel: ActivityViewModel by viewModels()

    // Register activity result launcher for notification permission
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Timber.d("Notification permission result: $isGranted")
        NotificationPermissionManager.markNotificationPermissionAsked(this)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
//        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Lock the app to portrait orientation by default
        OrientationManager.lockToPortrait(this)

//        setTheme(R.style.Theme_Tweet)
        installSplashScreen().apply {
            setKeepOnScreenCondition {
                !activityViewModel.isAppReady.value
            }
        }

        initJob = CoroutineScope(IO).async {
            HproseInstance.init(this@TweetActivity)
        }

        lifecycleScope.launch {
            try {
                // Add timeout for network initialization
                withTimeoutOrNull(10000) { // 10 second timeout
                    initJob.await()   // wait until network ready
                } ?: run {
                    Timber.tag("TweetActivity").w("Network initialization timed out, proceeding with app startup")
                }
                
                activityViewModel.isAppReady.value = true   // app ready. Show main page now.

                // Request notification permission on app startup
                requestNotificationPermissionIfNeeded()

                // Check for server upgrades (works for both mini and full versions)
                launch {
                    delay(15000)
                    activityViewModel.checkForUpgrade(this@TweetActivity)
                }

                setContent {
                    // Initialize theme manager with current preference
                    val initialThemeMode = HproseInstance.preferenceHelper.getThemeMode()
                    ThemeManager.updateThemeMode(initialThemeMode)

                    TweetTheme(themeMode = ThemeManager.currentThemeMode) {
                        Scaffold(
                            modifier = Modifier.fillMaxWidth()
                        ) { innerPadding ->
                            TweetNavGraph(intent, modifier = Modifier.padding(innerPadding))
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag("TweetActivity").e(e, "Error during app initialization")
                // Still show the app even if initialization fails
                activityViewModel.isAppReady.value = true
                setContent {
                    val initialThemeMode = HproseInstance.preferenceHelper.getThemeMode()
                    ThemeManager.updateThemeMode(initialThemeMode)

                    TweetTheme(themeMode = ThemeManager.currentThemeMode) {
                        Scaffold(
                            modifier = Modifier.fillMaxWidth()
                        ) { innerPadding ->
                            TweetNavGraph(intent, modifier = Modifier.padding(innerPadding))
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Resume incomplete uploads when app comes to foreground
        if (::initJob.isInitialized && initJob.isCompleted) {
            HproseInstance.resumeIncompleteUploads(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    /**
     * Request notification permission if needed (on app install or upgrade)
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (NotificationPermissionManager.shouldRequestNotificationPermission(this)) {
            // Add a small delay to ensure the app is fully loaded
            lifecycleScope.launch {
                delay(2000) // 2 second delay

                if (NotificationPermissionManager.isNotificationPermissionGranted(this@TweetActivity)) {
                    // Permission already granted, just mark as asked
                    NotificationPermissionManager.markNotificationPermissionAsked(this@TweetActivity)
                    Timber.d("Notification permission already granted")
                } else {
                    // Request permission
                    NotificationPermissionManager.requestNotificationPermission(
                        notificationPermissionLauncher
                    ) { isGranted ->
                        Timber.d("Notification permission request completed: $isGranted")
                    }
                }
            }
        }
    }
}

@HiltViewModel
class ActivityViewModel  @Inject constructor(): ViewModel() {
    val isAppReady = mutableStateOf(false)
    private val _isDownloading = MutableStateFlow(false)

    fun checkForUpgrade(context: Context, immediate: Boolean = false) {
        viewModelScope.launch(IO) {
            try {
                if (!immediate) {
                    delay(15000)    // delay 15s before checking for upgrade (automatic check only)
                }
                
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val currentVersionString = packageInfo.versionName ?: return@launch
                val currentVersionCode = packageInfo.longVersionCode.toInt()
                
                // Check if this is a mini version (has "-mini" suffix)
                val isMiniVersion = currentVersionString.contains("-mini")
                
                // If mini user manually requests upgrade, download and verify versionCode from APK
                if (isMiniVersion && immediate) {
                    Timber.tag("checkForUpgrade").d("Mini user requesting upgrade: $currentVersionString (code=$currentVersionCode)")
                    // Query server for full version package
                    val versionInfo = HproseInstance.checkUpgrade()
                    if (versionInfo != null && versionInfo["packageId"] != null) {
                        appUser.baseUrl?.let { hostIp ->
                            val downloadUrl = "$hostIp/mm/${versionInfo["packageId"]}"
                            Timber.tag("checkForUpgrade").d("Starting download to verify versionCode: $downloadUrl")
                            // Download and verify versionCode from APK file before installing
                            downloadAndVerifyBeforeInstall(context, downloadUrl, currentVersionCode)
                        }
                    } else {
                        Timber.tag("checkForUpgrade").w("Server didn't return package info for upgrade")
                    }
                    return@launch
                }
                
                // For full version or automatic checks, compare version numbers
                val versionInfo = HproseInstance.checkUpgrade() ?: return@launch
                val currentVersion = currentVersionString.replace("-mini", "").toIntOrNull() ?: return@launch
                val serverVersion = versionInfo["version"]?.toIntOrNull() ?: return@launch
                
                appUser.baseUrl?.let { hostIp ->
                    if (currentVersion < serverVersion) {
                        // Server has newer version
                        Timber.tag("checkForUpgrade").d("Update available: current=$currentVersion (code=$currentVersionCode), server=$serverVersion")
                        // For automatic checks, show dialog and verify versionCode after download
                        val downloadUrl = "$hostIp/mm/${versionInfo["packageId"]}"
                        showUpdateDialogWithVerification(context, downloadUrl, currentVersionCode)
                    } else {
                        Timber.tag("checkForUpgrade").d("No update needed: current=$currentVersion, server=$serverVersion")
                        // check for mimei of available App entry Urls. Update records in
                        // preference each time the app is run.
                        val mid = BuildConfig.ENTRY_URLS
                        HproseInstance.getProviderIP(mid)?.let { ip ->
                            val response = HproseInstance.httpClient.get("http://$ip/mm/$mid")
                            if (response.status == HttpStatusCode.OK) {
                                val newUrls =
                                    response.bodyAsText().split(System.lineSeparator()).toSet()
                                HproseInstance.preferenceHelper.setAppUrls(newUrls)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag("checkForUpgrade").e(e)
            }
        }
    }

    private fun showUpdateDialogWithVerification(context: Context, downloadUrl: String, currentVersionCode: Int) {
        (context as Activity).runOnUiThread {
            AlertDialog.Builder(context)
                .setTitle(getString(context, R.string.update_available))
                .setMessage(getString(context, R.string.update_message))
                .setPositiveButton(getString(context, R.string.update)) { _, _ ->
                    downloadAndVerifyBeforeInstall(context, downloadUrl, currentVersionCode)
                }
                .setNegativeButton(getString(context, R.string.cancel), null)
                .show()
        }
    }
    
    /**
     * Download APK, verify versionCode from the file, then install only if higher
     */
    private fun downloadAndVerifyBeforeInstall(context: Context, downloadUrl: String, currentVersionCode: Int) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(downloadUrl.toUri())
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(context, null, "fireshare.apk")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setTitle("Downloading Update")

        val downloadId = downloadManager.enqueue(request)

        viewModelScope.launch(IO) {
            var finishDownload = false
            while (!finishDownload) {
                val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = cursor.getInt(columnIndex)
                    
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        finishDownload = true
                        _isDownloading.value = false

                        // Get the downloaded APK file URI
                        val downloadedApkUri = downloadManager.getUriForDownloadedFile(downloadId)
                        
                        // Get file path to check versionCode (use app's private directory)
                        val downloadedFilePath = context.getExternalFilesDir(null)?.absolutePath + "/fireshare.apk"
                        val apkFile = java.io.File(downloadedFilePath)
                        
                        if (apkFile.exists()) {
                            Timber.tag("checkForUpgrade").d("APK file exists at: ${apkFile.absolutePath}, size: ${apkFile.length()} bytes")
                            
                            // Read versionCode from the downloaded APK
                            val apkPackageInfo = context.packageManager.getPackageArchiveInfo(
                                apkFile.absolutePath,
                                0
                            )
                            
                            if (apkPackageInfo == null) {
                                Timber.tag("checkForUpgrade").e("getPackageArchiveInfo returned null for: ${apkFile.absolutePath}")
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    android.widget.Toast.makeText(context,
                                        "Failed to read APK file",
                                        android.widget.Toast.LENGTH_LONG).show()
                                }
                                return@launch
                            }
                            
                            val apkVersionCode = apkPackageInfo.longVersionCode.toInt()
                            val apkVersionName = apkPackageInfo.versionName
                            
                            Timber.tag("checkForUpgrade").d("Downloaded APK - versionCode: $apkVersionCode, versionName: $apkVersionName, current: $currentVersionCode")
                            
                            if (apkVersionCode > currentVersionCode) {
                                // ✅ Version code is higher - proceed with installation
                                Timber.tag("checkForUpgrade").d("APK versionCode ($apkVersionCode) > current ($currentVersionCode), installing")
                                
                                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(downloadedApkUri, "application/vnd.android.package-archive")
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                                           Intent.FLAG_GRANT_READ_URI_PERMISSION or 
                                           Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                }
                                context.startActivity(installIntent)
                            } else {
                                // ❌ Version code is not higher - deny installation
                                Timber.tag("checkForUpgrade").w("APK versionCode ($apkVersionCode) not higher than current ($currentVersionCode), deleting file")
                                apkFile.delete()
                                
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    android.widget.Toast.makeText(context,
                                        context.getString(R.string.no_upgrade_available),
                                        android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            Timber.tag("checkForUpgrade").e("Downloaded APK file not found")
                        }
                        
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        finishDownload = true
                        _isDownloading.value = false
                        Timber.tag("checkForUpgrade").e("Download failed")
                    }
                }
                cursor.close()
                delay(1000)
            }
        }
    }

}
