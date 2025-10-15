package us.fireshare.tweet

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
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
import us.fireshare.tweet.BuildConfig
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
                    // All versions use checkForUpgrade for automatic checks
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
    
    
    // Check for upgrade using versionName comparison (for all versions)
    fun checkForUpgrade(context: Context) {
        viewModelScope.launch(IO) {
            try {
                delay(15000)    // delay 15s before checking for upgrade.
                val versionInfo = HproseInstance.checkUpgrade() ?: return@launch
                val currentVersion =
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        ?.removeSuffix("-mini") ?: return@launch
                
                HproseInstance.appUser.baseUrl?.let { hostIp ->
                    if (currentVersion.toInt() < (versionInfo["version"]?.toInt() ?: 0)) {
                        showUpdateDialog(context, "$hostIp/mm/${versionInfo["packageId"]}")
                    } else {
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
    
    // Check for upgrade using versionCode comparison (for mini version users)
    fun checkForMiniUpgrade(context: Context) {
        Timber.tag("checkForMiniUpgrade").d("Function called")
        viewModelScope.launch(IO) {
            Timber.tag("checkForMiniUpgrade").d("Coroutine started")
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val currentVersionString = packageInfo.versionName ?: return@launch
                val currentVersionCode = packageInfo.longVersionCode.toInt()
                
                Timber.tag("checkForMiniUpgrade").d("Mini user requesting upgrade: $currentVersionString (code=$currentVersionCode)")
                
                // Query server for full version package
                Timber.tag("checkForMiniUpgrade").d("Calling HproseInstance.checkUpgrade()")
                val versionInfo = HproseInstance.checkUpgrade()
                Timber.tag("checkForMiniUpgrade").d("checkUpgrade() returned: $versionInfo")
                
                if (versionInfo == null) {
                    Timber.tag("checkForMiniUpgrade").e("Server returned null version info")
                    withContext(Main) {
                        android.widget.Toast.makeText(context,
                            "Server returned null version info",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                
                if (versionInfo["packageId"] == null) {
                    Timber.tag("checkForMiniUpgrade").e("Server version info missing packageId")
                    withContext(Main) {
                        android.widget.Toast.makeText(context,
                            "Server missing package ID",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                
                val packageId = versionInfo["packageId"] as String
                Timber.tag("checkForMiniUpgrade").d("Got packageId: $packageId")
                
                // Get provider IP and download APK to private directory first
                Timber.tag("checkForMiniUpgrade").d("Calling HproseInstance.getProviderIP($packageId)")
                val providerIp = HproseInstance.getProviderIP(packageId)
                Timber.tag("checkForMiniUpgrade").d("getProviderIP() returned: $providerIp")
                
                if (providerIp == null) {
                    Timber.tag("checkForMiniUpgrade").e("No provider IP available for packageId: $packageId")
                    withContext(Main) {
                        android.widget.Toast.makeText(context,
                            "No provider IP available",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                
                val downloadUrl = "http://$providerIp/mm/$packageId"
                Timber.tag("checkForMiniUpgrade").d("Downloading APK to check version: $downloadUrl")
                
                // Use exact same download logic as working downloadAndInstall
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val request = DownloadManager.Request(downloadUrl.toUri())
                    .setMimeType("application/octet-stream")
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "temp_upgrade.apk")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setTitle("Checking for updates")

                val downloadId = downloadManager.enqueue(request)

                var finishDownload = false
                while (!finishDownload) {
                    val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
                    if (cursor.moveToFirst()) {
                        val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = cursor.getInt(columnIndex)
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            finishDownload = true
                            
                            // Get the downloaded APK file URI from the download record
                            val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            val localUri = cursor.getString(localUriIndex)
                            val apkFile = File(Uri.parse(localUri).path ?: "")
                            
                            Timber.tag("checkForMiniUpgrade").d("Downloaded file path: ${apkFile.absolutePath}")
                            Timber.tag("checkForMiniUpgrade").d("Downloaded file exists: ${apkFile.exists()}")
                            Timber.tag("checkForMiniUpgrade").d("Downloaded file size: ${apkFile.length()} bytes")
                            
                            // Get versionCode from downloaded APK
                            val downloadedPackageInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                            if (downloadedPackageInfo != null) {
                                val downloadedVersionCode = downloadedPackageInfo.longVersionCode.toInt()
                                Timber.tag("checkForMiniUpgrade").d("Version comparison: current=$currentVersionCode, downloaded=$downloadedVersionCode")
                                
                                if (downloadedVersionCode > currentVersionCode) {
                                    Timber.tag("checkForMiniUpgrade").d("Update available, starting installation")
                                    // Install the downloaded APK
                                    installApkFromFile(context, apkFile)
                                } else {
                                    Timber.tag("checkForMiniUpgrade").d("No update needed: current version is up to date")
                                    withContext(Main) {
                                        android.widget.Toast.makeText(context,
                                            "You already have the latest version",
                                            android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    // Clean up temp file
                                    apkFile.delete()
                                }
                            } else {
                                Timber.tag("checkForMiniUpgrade").e("Failed to read package info from downloaded APK")
                                withContext(Main) {
                                    android.widget.Toast.makeText(context,
                                        "Failed to verify downloaded package",
                                        android.widget.Toast.LENGTH_LONG).show()
                                }
                                apkFile.delete()
                            }
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            finishDownload = true
                            // Handle download failure
                        }
                    }
                    cursor.close()
                    delay(1000)
                }
                
            } catch (e: Exception) {
                Timber.tag("checkForMiniUpgrade").e(e, "Unexpected error during upgrade check")
                withContext(Main) {
                    android.widget.Toast.makeText(context,
                        context.getString(R.string.upgrade_failed_unknown),
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Show update dialog for full version users
     */
    private fun showUpdateDialog(context: Context, downloadUrl: String) {
        (context as Activity).runOnUiThread {
            AlertDialog.Builder(context)
                .setTitle(getString(context, R.string.update_available))
                .setMessage(getString(context, R.string.update_message))
                .setPositiveButton(getString(context, R.string.update)) { _, _ ->
                    downloadAndInstall(context, downloadUrl)
                }
                .setNegativeButton(getString(context, R.string.cancel), null)
                .show()
        }
    }

    /**
     * Download and install APK using DownloadManager (simple approach from MiniVersion branch)
     */
    private fun downloadAndInstall(context: Context, downloadUrl: String) {
        Timber.tag("downloadAndInstall").d("Function called with URL: $downloadUrl")
        
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(downloadUrl.toUri())
            .setMimeType("application/octet-stream") // Set appropriate MIME type if known
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "fireshare.apk")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setTitle("Downloading Update")

        val downloadId = downloadManager.enqueue(request)
        Timber.tag("downloadAndInstall").d("Download started with ID: $downloadId")

        viewModelScope.launch(IO) {
            var finishDownload = false
            while (!finishDownload) {
                val cursor =
                    downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = cursor.getInt(columnIndex)
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        finishDownload = true
                        _isDownloading.value = false
                        Timber.tag("downloadAndInstall").d("Download completed successfully")

                        // Get the downloaded APK file URI
                        val downloadedApkUri = downloadManager.getUriForDownloadedFile(downloadId)

                        // Create an intent to install the APK
                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                downloadedApkUri,
                                "application/vnd.android.package-archive"
                            )
                            flags =
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        }
                        // Start the installation activity
                        context.startActivity(installIntent)

                    } else if (status == DownloadManager.STATUS_FAILED) {
                        finishDownload = true
                        _isDownloading.value = false
                        Timber.tag("downloadAndInstall").e("Download failed")
                        // Handle download failure
                    }
                }
                cursor.close()
                delay(1000)
            }
        }
    }
    
    /**
     * Install APK from file using FileProvider
     */
    private suspend fun installApkFromFile(context: Context, apkFile: File) {
        try {
            Timber.tag("installApkFromFile").d("Installing APK from: ${apkFile.absolutePath}")
            Timber.tag("installApkFromFile").d("APK file size: ${apkFile.length()} bytes")
            Timber.tag("installApkFromFile").d("APK file exists: ${apkFile.exists()}")
            
            // Verify APK is valid before trying to install
            val packageInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (packageInfo == null) {
                Timber.tag("installApkFromFile").e("APK file is corrupted or invalid")
                withContext(Main) {
                    android.widget.Toast.makeText(context,
                        "Downloaded APK is corrupted. Please try again.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
                return
            }
            
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
            } else {
                Uri.fromFile(apkFile)
            }
            
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }
            
            Timber.tag("installApkFromFile").d("Starting installation with URI: $apkUri")
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Timber.tag("installApkFromFile").e(e, "Failed to install APK: ${e.message}")
            withContext(Main) {
                android.widget.Toast.makeText(context,
                    "Installation failed: ${e.message}",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

}

