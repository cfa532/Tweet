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
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.navigation.TweetNavGraph
import us.fireshare.tweet.service.NotificationPermissionManager
import us.fireshare.tweet.service.OrientationManager
import us.fireshare.tweet.ui.theme.ThemeManager
import us.fireshare.tweet.ui.theme.TweetTheme

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

class ActivityViewModel: ViewModel() {
    val isAppReady = mutableStateOf(false)
    private val _isDownloading = MutableStateFlow(false)

    fun checkForUpgrade(context: Context) {
        viewModelScope.launch(IO) {
            try {
                delay(15000)    // delay 15s before checking for upgrade.
                val versionInfo = HproseInstance.checkUpgrade() ?: return@launch
                val currentVersion =
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        ?: return@launch
                appUser.baseUrl?.let { hostIp ->
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

    private fun downloadAndInstall(context: Context, downloadUrl: String) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(downloadUrl.toUri())
            .setMimeType("application/octet-stream") // Set appropriate MIME type if known
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "fireshare.apk")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setTitle("Downloading Update")

        val downloadId = downloadManager.enqueue(request)

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
                        // Handle download failure
                    }
                }
                cursor.close()
                delay(1000)
            }
        }
    }
}
