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
import androidx.core.content.FileProvider
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
import us.fireshare.tweet.chat.ChatSessionRepository
import us.fireshare.tweet.navigation.TweetNavGraph
import us.fireshare.tweet.service.BadgeStateManager
import us.fireshare.tweet.service.NotificationPermissionManager
import us.fireshare.tweet.service.OrientationManager
import us.fireshare.tweet.ui.theme.ThemeManager
import us.fireshare.tweet.ui.theme.TweetTheme
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class TweetActivity : ComponentActivity() {
    private lateinit var initJob: Deferred<Unit>
    private val activityViewModel: ActivityViewModel by viewModels()
    
    @Inject
    lateinit var chatSessionRepository: ChatSessionRepository

    // Register activity result launcher for notification permission
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Timber.d("Notification permission result: $isGranted")
        NotificationPermissionManager.markNotificationPermissionAsked(this)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lock the app to portrait orientation by default
        OrientationManager.lockToPortrait(this)

        installSplashScreen().apply {
            setKeepOnScreenCondition {
                !activityViewModel.isAppReady.value
            }
        }

        initJob = CoroutineScope(IO).async {
            HproseInstance.init(this@TweetActivity) {
                // Callback called after initialization completes
                // Check for new messages and update badge on startup
                lifecycleScope.launch(IO) {
                    activityViewModel.checkForUpgrade(this@TweetActivity)
                }

                // Resume incomplete uploads on app startup (with 10s delay)
                lifecycleScope.launch(IO) {
                    kotlinx.coroutines.delay(10000) // 10 second delay
                    HproseInstance.resumeIncompleteUploads(this@TweetActivity)
                }
            }
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

                // Check for new messages and update badge after initialization completes
                // This ensures badge is updated even if the callback in init() didn't run
                // (e.g., if chatSessionRepository wasn't initialized yet)
                launch(IO) {
                    if (::chatSessionRepository.isInitialized) {
                        checkMessagesAndUpdateBadge()
                        Timber.tag("TweetActivity").d("Checked messages after initialization complete")
                    } else {
                        Timber.tag("TweetActivity").d("ChatSessionRepository not initialized yet after initJob completion")
                    }
                }

                // Request notification permission on app startup
                requestNotificationPermissionIfNeeded()

                setContent {
                    // Initialize theme manager with current preference
                    val initialThemeMode = HproseInstance.preferenceHelper.getThemeMode()
                    ThemeManager.updateThemeMode(initialThemeMode)

                    TweetTheme(themeMode = ThemeManager.currentThemeMode) {
                        Scaffold(
                            modifier = Modifier.fillMaxWidth()
                        ) { innerPadding ->
                            TweetNavGraph(
                                appLinkIntent = activityViewModel.currentIntent.value,
                                modifier = Modifier.padding(innerPadding)
                            )
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
                            TweetNavGraph(
                                appLinkIntent = activityViewModel.currentIntent.value,
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
        
        // Handle initial intent
        activityViewModel.currentIntent.value = intent
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the intent so getIntent() returns the latest one
        handleIntent(intent)
    }

    /**
     * Handle deep link intent
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            activityViewModel.currentIntent.value = intent
        }
    }

    override fun onResume() {
        super.onResume()
        // Resume incomplete uploads when app comes to foreground
        if (::initJob.isInitialized && initJob.isCompleted) {
            HproseInstance.resumeIncompleteUploads(this)
        }
        
        // Always check for new messages and update badge when app comes to foreground
        // This ensures badge is updated even if initialization is still in progress
        lifecycleScope.launch(IO) {
            if (::chatSessionRepository.isInitialized) {
                checkMessagesAndUpdateBadge()
            } else {
                Timber.tag("TweetActivity").d("ChatSessionRepository not initialized yet, will check messages after initialization")
            }
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


    /**
     * Check for new messages and update the badge count based on unread sessions
     */
    private suspend fun checkMessagesAndUpdateBadge() {
        if (HproseInstance.appUser.isGuest()) {
            Timber.tag("TweetActivity").d("Skipping message check - user is guest")
            return
        }

        // Ensure repository is initialized
        if (!::chatSessionRepository.isInitialized) {
            Timber.tag("TweetActivity").w("ChatSessionRepository not initialized yet, skipping badge update")
            return
        }

        try {
            // Check for new messages from server
            val newMessages = HproseInstance.checkNewMessages()
            if (newMessages != null && newMessages.isNotEmpty()) {
                Timber.tag("TweetActivity").d("Found ${newMessages.size} new messages, updating sessions")
                
                // Filter out messages that already exist in local database
                val trulyNewMessages = chatSessionRepository.filterExistingMessages(newMessages)
                
                if (trulyNewMessages.isNotEmpty()) {
                    // Get existing sessions
                    val existingSessions = chatSessionRepository.getAllSessions()
                    
                    // Merge new messages with existing sessions
                    val updatedSessions = chatSessionRepository.mergeMessagesWithSessions(
                        existingSessions,
                        trulyNewMessages
                    )
                    
                    // Update chat session database - create/update sessions and save messages for badge counting
                    // Messages are saved here so the session can be created with hasNews flag
                    // fetchNewMessage() will filter out existing messages using filterExistingMessages
                    updatedSessions.forEach { chatSession ->
                        // Use updateChatSessionWithMessage to create session and save message
                        // This ensures the session exists with hasNews flag for badge counting
                        chatSessionRepository.updateChatSessionWithMessage(
                            HproseInstance.appUser.mid,
                            chatSession.receiptId,
                            chatSession.lastMessage,
                            hasNews = chatSession.hasNews
                        )
                    }
                }
            }
            
            // Count unread sessions (sessions with hasNews = true)
            val allSessions = chatSessionRepository.getAllSessions()
            val unreadCount = allSessions.count { it.hasNews }
            
            Timber.tag("TweetActivity").d("Unread sessions count: $unreadCount")
            
            // Update badge count
            withContext(Main) {
                BadgeStateManager.updateBadgeCount(unreadCount)
            }
        } catch (e: Exception) {
            Timber.tag("TweetActivity").e(e, "Error checking messages and updating badge")
        }
    }
}

@HiltViewModel
class ActivityViewModel  @Inject constructor(): ViewModel() {
    val isAppReady = mutableStateOf(false)
    private val _isDownloading = MutableStateFlow(false)
    val systemDomainToShare = mutableStateOf<String?>(null)
    val currentIntent = mutableStateOf<Intent?>(null)

    /**
     * Load entry URLs from BuildConfig.ENTRY_URLS.
     * This should be called for all versions including Play variant.
     */
    fun loadEntryUrls() {
        viewModelScope.launch(IO) {
            try {
                // check for mimei of available App entry Urls. Update records in
                // preference each time the app is run.
                val mid = BuildConfig.ENTRY_URLS
                HproseInstance.getProviderIP(mid).let { ip ->
                    val response = HproseInstance.httpClient.get("http://$ip/mm/$mid")
                    if (response.status == HttpStatusCode.OK) {
                        val newUrls = response.bodyAsText().split(System.lineSeparator())
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()
                        if (newUrls.isNotEmpty()) {
                            HproseInstance.preferenceHelper.setAppUrls(newUrls)
                            Timber.tag("loadEntryUrls").d("✅ Updated entry URLs from network: $newUrls")
                        } else {
                            Timber.tag("loadEntryUrls").w("Received empty entry URLs from network")
                        }
                    } else {
                        Timber.tag("loadEntryUrls").w("Failed to fetch entry URLs: HTTP ${response.status}")
                    }
                }
            } catch (e: Exception) {
                Timber.tag("loadEntryUrls").e(e, "Error loading entry URLs")
            }
        }
    }

    // Check for upgrade using versionName comparison (for all versions except play)
    fun checkForUpgrade(context: Context) {
        // Play version doesn't support upgrades
        if (BuildConfig.IS_PLAY_VERSION) {
            Timber.tag("checkForUpgrade").d("Play version detected, skipping upgrade check")
            return
        }
        viewModelScope.launch(IO) {
            try {
                delay(3000)    // delay 3s before checking for upgrade.
                
                // Get current version
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val currentVersionName = packageInfo.versionName
                val currentVersionString = currentVersionName?.removeSuffix("-mini")
                if (currentVersionString == null) {
                    Timber.tag("checkForUpgrade").e("Failed to get current versionName")
                    return@launch
                }
                Timber.tag("checkForUpgrade").d("Current versionName: $currentVersionName (comparing as: $currentVersionString)")
                
                // Query server for upgrade info
                val versionInfo = HproseInstance.checkUpgrade()
                if (versionInfo == null) {
                    Timber.tag("checkForUpgrade").e("Server returned null version info")
                    return@launch
                }
                Timber.tag("checkForUpgrade").d("Server versionInfo: $versionInfo")

                // Store system domainToShare from backend response
                versionInfo["domain"]?.let { domain ->
                    systemDomainToShare.value = domain
                    Timber.tag("checkForUpgrade").d("Retrieved system domainToShare: $domain")
                }
                
                // Get server version
                val serverVersionString = versionInfo["version"]
                if (serverVersionString == null) {
                    Timber.tag("checkForUpgrade").e("Server versionInfo missing 'version' key")
                    return@launch
                }
                
                // Parse and compare versions
                val currentVersion = try {
                    currentVersionString.toInt()
                } catch (e: NumberFormatException) {
                    Timber.tag("checkForUpgrade").e(e, "Failed to parse current version as Int: $currentVersionString")
                    return@launch
                }
                
                val serverVersion = try {
                    serverVersionString.toInt()
                } catch (e: NumberFormatException) {
                    Timber.tag("checkForUpgrade").e(e, "Failed to parse server version as Int: $serverVersionString")
                    return@launch
                }
                
                Timber.tag("checkForUpgrade").d("Version comparison: current=$currentVersion, server=$serverVersion")
                
                val hostIp = HproseInstance.appUser.baseUrl
                if (hostIp == null) {
                    Timber.tag("checkForUpgrade").e("Cannot check upgrade: baseUrl is null")
                    return@launch
                }
                
                if (currentVersion < serverVersion) {
                    Timber.tag("checkForUpgrade").d("✅ Upgrade available! current=$currentVersion < server=$serverVersion")
                    
                    val packageId = versionInfo["packageId"]
                    if (packageId == null) {
                        Timber.tag("checkForUpgrade").e("Cannot show upgrade dialog: packageId is null")
                        return@launch
                    }
                    
                    val downloadUrl = "$hostIp/mm/$packageId"
                    Timber.tag("checkForUpgrade").d("Showing upgrade dialog with URL: $downloadUrl")
                    showUpdateDialog(context, downloadUrl)
                } else {
                    Timber.tag("checkForUpgrade").d("No upgrade needed (current=$currentVersion >= server=$serverVersion)")

                    // Load entry URLs (works for all versions including Play)
                    loadEntryUrls()
                }
            } catch (e: Exception) {
                Timber.tag("checkForUpgrade").e(e, "Error during upgrade check: ${e.message}")
            }
        }
    }
    
    // Check for upgrade using versionCode comparison (for mini version users, not play)
    fun checkForMiniUpgrade(context: Context) {
        // Play version doesn't support upgrades
        if (BuildConfig.IS_PLAY_VERSION) {
            return
        }
        
        // Check if download is already in progress
        if (_isDownloading.value) {
            Timber.tag("checkForMiniUpgrade").d("Download already in progress, showing toast")
            android.widget.Toast.makeText(context,
                context.getString(R.string.download_in_progress),
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        
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

                val downloadUrl = "http://$providerIp/mm/$packageId"
                Timber.tag("checkForMiniUpgrade").d("Downloading APK to check version: $downloadUrl")
                
                // Set download state to true
                _isDownloading.value = true
                
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
                            // Don't reset download state yet - wait for installation to complete
                            
                            // Get the downloaded APK file URI from the download record
                            val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            val localUri = cursor.getString(localUriIndex)
                            val apkFile = File(localUri.toUri().path ?: "")
                            
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
                                    // Reset download state after installation is triggered
                                    _isDownloading.value = false
                                } else {
                                    Timber.tag("checkForMiniUpgrade").d("No update needed: current version is up to date")
                                    withContext(Main) {
                                        android.widget.Toast.makeText(context,
                                            "You already have the latest version",
                                            android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    // Clean up temp file
                                    apkFile.delete()
                                    // Reset download state
                                    _isDownloading.value = false
                                }
                            } else {
                                Timber.tag("checkForMiniUpgrade").e("Failed to read package info from downloaded APK")
                                withContext(Main) {
                                    android.widget.Toast.makeText(context,
                                        "Failed to verify downloaded package",
                                        android.widget.Toast.LENGTH_LONG).show()
                                }
                                apkFile.delete()
                                // Reset download state
                                _isDownloading.value = false
                            }
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            finishDownload = true
                            _isDownloading.value = false
                            // Handle download failure
                        }
                    }
                    cursor.close()
                    delay(1000)
                }
                
            } catch (e: Exception) {
                _isDownloading.value = false
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
        
        // Check if download is already in progress
        if (_isDownloading.value) {
            Timber.tag("downloadAndInstall").d("Download already in progress, showing toast")
            android.widget.Toast.makeText(context,
                context.getString(R.string.download_in_progress),
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        
        // Set download state to true
        _isDownloading.value = true
        
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
            
            val apkUri =
                FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }
            
            Timber.tag("installApkFromFile").d("Starting installation with URI: $apkUri")
            withContext(Main) {
                android.widget.Toast.makeText(context,
                    "Starting installation...",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
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


