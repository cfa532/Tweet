package us.fireshare.tweet

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.chat.ChatSessionRepository
import us.fireshare.tweet.navigation.TweetNavGraph
import us.fireshare.tweet.service.BadgeStateManager
import us.fireshare.tweet.service.NotificationPermissionManager
import us.fireshare.tweet.service.OrientationManager
import us.fireshare.tweet.ui.theme.ThemeManager
import us.fireshare.tweet.ui.theme.TweetTheme
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

        // Set up UI early (but don't render content until isAppReady)
        setContent {
            var isAppReady by remember { mutableStateOf(activityViewModel.isAppReady.value) }
            LaunchedEffect(Unit) {
                snapshotFlow { activityViewModel.isAppReady.value }.collect {
                    isAppReady = it
                }
            }
            val initialThemeMode = HproseInstance.preferenceHelper.getThemeMode()
            ThemeManager.updateThemeMode(initialThemeMode)

            TweetTheme(themeMode = ThemeManager.currentThemeMode) {
                if (isAppReady) {
                    TweetNavGraph(
                        appLinkIntent = activityViewModel.currentIntent.value,
                        appLinkIntentSequence = activityViewModel.currentIntentSequence.value,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Start initialization
        initJob = lifecycleScope.async {
            try {
                // Maximum 3-second splash screen timeout
                launch {
                    delay(3000)
                    if (!activityViewModel.isAppReady.value) {
                        Timber.tag("TweetActivity").d("Splash screen timeout (3s), showing UI")
                        activityViewModel.isAppReady.value = true
                    }
                }
                
                HproseInstance.init(this@TweetActivity) {
                    // AppUser loaded, show UI immediately
                    Timber.tag("TweetActivity").d("AppUser loaded, showing UI")
                    activityViewModel.isAppReady.value = true
                }
                
                // Background tasks - independent of init callback
                launch(IO) {
                    delay(5000) // Check for upgrade 5s after start
                    activityViewModel.checkForUpgrade(this@TweetActivity)
                }
                
                // Always refresh entry URLs on every startup (best-effort, non-blocking)
                launch(IO) {
                    delay(6000) // Let bootstrap start first; loader still has internal wait
                    activityViewModel.loadEntryUrls()
                }
                
                launch(IO) {
                    delay(10000) // Check messages 10s after start
                    if (::chatSessionRepository.isInitialized) {
                        checkMessagesAndUpdateBadge()
                    }
                }

                launch(IO) {
                    delay(10000)
                    HproseInstance.resumeIncompleteUploads(this@TweetActivity)
                }

                requestNotificationPermissionIfNeeded()

            } catch (e: Exception) {
                Timber.tag("TweetActivity").e(e, "Error during app initialization")
                activityViewModel.isAppReady.value = true
            }
        }
        
        // Handle initial intent
        handleIntent(intent)

        if (!BuildConfig.IS_PLAY_VERSION) lifecycleScope.launch {
            UpgradeDownloadState.installCompletedUpgrade(this@TweetActivity, fromForeground = true)
        }
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
            activityViewModel.currentIntentSequence.value += 1
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

        if (!BuildConfig.IS_PLAY_VERSION) lifecycleScope.launch {
            UpgradeDownloadState.installCompletedUpgrade(this@TweetActivity, fromForeground = true)
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
                    // CRITICAL FIX: Insert original messages first (without preview text)
                    // Group messages by partner to get the last message for each conversation
                    val messagesByPartner = trulyNewMessages.groupBy { message ->
                        if (message.authorId == HproseInstance.appUser.mid) {
                            message.receiptId  // Outgoing: use receiptId (recipient)
                        } else {
                            message.authorId   // Incoming: use authorId (sender)
                        }
                    }
                    
                    messagesByPartner.forEach { (partnerId, messages) ->
                        // Get the last (newest) message from the group
                        val lastMessage = messages.maxByOrNull { it.timestamp } ?: return@forEach
                        
                        // Insert the ORIGINAL message (not the preview) to database
                        // This prevents "Image sent" text from being saved as actual message content
                        chatSessionRepository.updateChatSessionWithMessage(
                            HproseInstance.appUser.mid,
                            partnerId,
                            lastMessage,  // Original message, preview will be applied when loading sessions
                            hasNews = true
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
    private var isDownloading = false
    private var upgradeDownloadJob: Job? = null
    val systemDomainToShare = mutableStateOf<String?>(null)
    val currentIntent = mutableStateOf<Intent?>(null)
    val currentIntentSequence = mutableStateOf(0L)

    /**
     * Load entry URLs from BuildConfig.ENTRY_URLS.
     * This should be called for all versions including Play variant.
     */
    fun loadEntryUrls() {
        viewModelScope.launch(IO) {
            try {
                // Wait for appUser to be fully initialized
                val startTime = System.currentTimeMillis()
                val timeoutMillis = 10000L
                Timber.tag("loadEntryUrls").d("Waiting for appUser to be fully initialized (timeout: ${timeoutMillis}ms)")
                while (!HproseInstance.isAppUserInitialized.value && System.currentTimeMillis() - startTime < timeoutMillis) {
                    delay(1000)
                }
                val elapsed = System.currentTimeMillis() - startTime
                if (!HproseInstance.isAppUserInitialized.value) {
                    Timber.tag("loadEntryUrls").w("Timeout waiting for appUser initialization after ${elapsed}ms, continuing best-effort entry URL refresh")
                } else {
                    Timber.tag("loadEntryUrls").d("appUser initialized after ${elapsed}ms: ${HproseInstance.appUser.baseUrl}")
                }
                
                // check for mimei of available App entry Urls. Update records in
                // preference each time the app is run.
                val mid = BuildConfig.ENTRY_URLS
                HproseInstance.getProviderIP(mid)?.let { ip ->
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
                } ?: run {
                    Timber.tag("loadEntryUrls").w("Could not get provider IP for entry URLs mid: $mid")
                }
            } catch (e: Exception) {
                Timber.tag("loadEntryUrls").e(e, "Error loading entry URLs")
            }
        }
    }

    // Direct builds trust only complete, explicitly enabled release metadata.
    fun checkForUpgrade(context: Context) {
        if (BuildConfig.IS_PLAY_VERSION) {
            Timber.tag("checkForUpgrade").d("Play version detected, skipping upgrade check")
            return
        }
        viewModelScope.launch(IO) {
            try {
                UpgradeDownloadState.clearIfAppWasUpgraded(context)
                val trackedDownload = UpgradeDownloadState.trackedDownloadId(context)
                if (trackedDownload != -1L) {
                    isDownloading = true
                    observeUpgradeDownload(context, trackedDownload)
                    return@launch
                }

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

                if (versionInfo["enabled"] != "true") {
                    Timber.tag("checkForUpgrade").d("Direct update advertisement is disabled")
                    return@launch
                }
                val release = parseUpgradeRelease(versionInfo)
                if (release.versionCode <= BuildConfig.VERSION_CODE) {
                    Timber.tag("checkForUpgrade").d(
                        "No upgrade needed (current=${BuildConfig.VERSION_CODE}, server=${release.versionCode})",
                    )
                    return@launch
                }
                val provider = HproseInstance.getProviderIP(release.packageId)
                    ?: error("No healthy provider for update package ${release.packageId}")
                val downloadUrl = packageDownloadUrl(provider, release.packageId)
                showUpdateDialog(context, release.copy(downloadUrl = downloadUrl))
            } catch (e: Exception) {
                Timber.tag("checkForUpgrade").e(e, "Error during upgrade check: ${e.message}")
            }
        }
    }

    private fun parseUpgradeRelease(values: Map<String, String>): UpgradeRelease {
        val versionCode = values["versionCode"]?.toLongOrNull()
            ?: error("Upgrade metadata has no version code")
        val versionName = values["versionName"].orEmpty()
        val packageId = values["packageId"].orEmpty()
        val size = values["size"]?.toLongOrNull() ?: error("Upgrade metadata has no package size")
        val sha256 = values["sha256"].orEmpty().lowercase()
        val mission = values["mission"].orEmpty()
        check(versionCode in 1..Int.MAX_VALUE.toLong()) { "Invalid upgrade version code" }
        check(versionName.isNotEmpty() && versionName.length <= 64 && versionName.trim() == versionName &&
            versionName.all { it.code in 0x20..0x7e }) { "Invalid upgrade version name" }
        check(packageId.matches(Regex("[A-Za-z0-9_-]{27}"))) { "Invalid upgrade package id" }
        check(size in 1..MAX_UPGRADE_BYTES) { "Invalid upgrade package size" }
        check(sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid upgrade checksum" }
        check(mission in listOf("minor", "major", "critical")) { "Invalid upgrade mission" }
        return UpgradeRelease(versionCode, versionName, packageId, size, sha256, mission)
    }

    private fun packageDownloadUrl(provider: String, packageId: String): String {
        val base = if (provider.startsWith("http://") || provider.startsWith("https://")) provider else "http://$provider"
        val uri = base.toUri()
        check(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path.orEmpty() in listOf("", "/")) {
            "Invalid update provider address"
        }
        return base.trimEnd('/') + "/mm/$packageId"
    }
    
    /**
     * Show update dialog for full version users
     */
    private fun showUpdateDialog(context: Context, release: UpgradeRelease) {
        (context as Activity).runOnUiThread {
            val dialog = AlertDialog.Builder(context)
                .setTitle(getString(context, R.string.update_available))
                .setMessage(getString(context, R.string.update_message))
                // Wire click handlers in onShow so we can customize button UX
                // and ensure the dialog only closes after explicit decision.
                .setPositiveButton(getString(context, R.string.update), null)
                .setNegativeButton(getString(context, R.string.cancel), null)
                .setCancelable(false)
                .create()

            dialog.setCanceledOnTouchOutside(false)
            dialog.setOnKeyListener { _, keyCode, event ->
                keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
            }

            dialog.setOnShowListener {
                val updateButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                val updateLabel = updateButton.text
                val cancelLabel = cancelButton.text

                applyDecisionButtonUX(updateButton)
                applyDecisionButtonUX(cancelButton)

                updateButton.setOnClickListener {
                    // Show clear initiated state before leaving the dialog.
                    updateButton.isEnabled = false
                    cancelButton.isEnabled = false
                    updateButton.text = "Starting..."
                    cancelButton.text = ""
                    updateButton.animate().alpha(1f).setDuration(80L).start()
                    downloadAndInstall(context, release)
                    updateButton.postDelayed({
                        if (dialog.isShowing) {
                            dialog.dismiss()
                        }
                        // Restore labels for potential future dialog instances.
                        updateButton.text = updateLabel
                        cancelButton.text = cancelLabel
                    }, 220L)
                }
                cancelButton.setOnClickListener {
                    // Show explicit confirmation visual on cancel decision.
                    updateButton.isEnabled = false
                    cancelButton.isEnabled = false
                    updateButton.text = ""
                    cancelButton.text = "Cancelling..."
                    cancelButton.animate().alpha(1f).setDuration(80L).start()
                    dialog.dismiss()
                }
            }

            dialog.show()
        }
    }

    private fun applyDecisionButtonUX(button: Button) {
        val verticalPaddingPx = (12 * button.resources.displayMetrics.density).toInt()
        val horizontalPaddingPx = (18 * button.resources.displayMetrics.density).toInt()
        val minHeightPx = (48 * button.resources.displayMetrics.density).toInt()

        // Increase tappable area for easier decision taps.
        button.minHeight = minHeightPx
        button.setPadding(horizontalPaddingPx, verticalPaddingPx, horizontalPaddingPx, verticalPaddingPx)

        // Add press animation for clear tactile feedback.
        button.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate()
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .alpha(0.85f)
                        .setDuration(90L)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(110L)
                        .start()
                }
            }
            false
        }
    }

    private fun downloadAndInstall(context: Context, release: UpgradeRelease) {
        if (isDownloading) {
            Timber.tag("downloadAndInstall").d("Download already in progress, showing toast")
            android.widget.Toast.makeText(context,
                context.getString(R.string.download_in_progress),
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        try {
            val downloadId = UpgradeDownloadState.enqueue(context, release)
            isDownloading = true
            Timber.tag("downloadAndInstall").d("Verified update download started with id=$downloadId")
            observeUpgradeDownload(context, downloadId)
        } catch (error: Exception) {
            Timber.tag("downloadAndInstall").e(error, "Could not start update download")
            isDownloading = false
        }
    }

    private fun observeUpgradeDownload(context: Context, downloadId: Long) {
        if (upgradeDownloadJob?.isActive == true) return
        upgradeDownloadJob = viewModelScope.launch(IO) {
            try {
                while (true) {
                    when (UpgradeDownloadState.status(context, downloadId)) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            UpgradeDownloadState.markCompleted(context, downloadId)
                            isDownloading = false
                            UpgradeDownloadState.installCompletedUpgrade(context, fromForeground = true)
                            return@launch
                        }
                        DownloadManager.STATUS_FAILED, UpgradeDownloadState.MISSING -> {
                            UpgradeDownloadState.discard(context)
                            isDownloading = false
                            Timber.tag("downloadAndInstall").e("Update download failed or disappeared")
                            return@launch
                        }
                    }
                    delay(1_000)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                isDownloading = false
                Timber.tag("downloadAndInstall").e(error, "Could not observe update download")
            }
        }
    }

    companion object {
        private const val MAX_UPGRADE_BYTES = 512L * 1024L * 1024L
    }

}
