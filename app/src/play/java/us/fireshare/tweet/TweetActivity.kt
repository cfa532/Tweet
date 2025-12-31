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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import us.fireshare.tweet.BuildConfig
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.navigation.TweetNavGraph
import us.fireshare.tweet.service.NotificationPermissionManager
import us.fireshare.tweet.service.OrientationManager
import us.fireshare.tweet.ui.theme.ThemeManager
import us.fireshare.tweet.ui.theme.TweetTheme

@AndroidEntryPoint
class PlayTweetActivity : ComponentActivity() {
    private lateinit var initJob: Deferred<Unit>
    private val activityViewModel: PlayActivityViewModel by viewModels()

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
            HproseInstance.init(this@PlayTweetActivity) {
                // Callback called as soon as baseUrl is ready - show UI immediately
                Timber.tag("PlayTweetActivity").d("BaseUrl ready, showing UI")
                activityViewModel.isAppReady.value = true   // app ready. Show main page now.
                
                // Resume incomplete uploads on app startup (with 10s delay)
                lifecycleScope.launch(IO) {
                    kotlinx.coroutines.delay(10000) // 10 second delay
                    HproseInstance.resumeIncompleteUploads(this@PlayTweetActivity)
                }
            }
        }

        lifecycleScope.launch {
            try {
                // Reduced timeout since UI now shows as soon as baseUrl is ready
                withTimeoutOrNull(8000) { // 8 second timeout for finding entry IP
                    initJob.await()   // wait until network ready and baseUrl is set
                } ?: run {
                    Timber.tag("PlayTweetActivity").w("Network initialization timed out, proceeding with app startup")
                    activityViewModel.isAppReady.value = true   // Show UI anyway on timeout
                }

                // activityViewModel.isAppReady is now set in the callback from HproseInstance.init()

                // Request notification permission on app startup
                requestNotificationPermissionIfNeeded()

                // Load entry URLs (required for all versions including Play)
                launch {
                    delay(15000) // Same delay as checkForUpgrade
                    activityViewModel.loadEntryUrls()
                }

                // Handle initial intent
                activityViewModel.currentIntent.value = intent
                handleIntent(intent)

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
                // Handle initial intent
                activityViewModel.currentIntent.value = intent
                handleIntent(intent)
                
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

                if (NotificationPermissionManager.isNotificationPermissionGranted(this@PlayTweetActivity)) {
                    // Permission already granted, just mark as asked
                    NotificationPermissionManager.markNotificationPermissionAsked(this@PlayTweetActivity)
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

class PlayActivityViewModel: ViewModel() {
    val isAppReady = mutableStateOf(false)
    val currentIntent = mutableStateOf<Intent?>(null)

    /**
     * Load entry URLs from BuildConfig.ENTRY_URLS.
     * This should be called for all versions including Play variant.
     */
    fun loadEntryUrls() {
        viewModelScope.launch(IO) {
            try {
                // Wait for appUser.baseUrl to be available
                val startTime = System.currentTimeMillis()
                val timeoutMillis = 10000L
                Timber.tag("loadEntryUrls").d("Waiting for appUser.baseUrl to be available (timeout: ${timeoutMillis}ms)")
                while (appUser.baseUrl.isNullOrBlank() && System.currentTimeMillis() - startTime < timeoutMillis) {
                    delay(1000)
                }
                val elapsed = System.currentTimeMillis() - startTime
                if (appUser.baseUrl.isNullOrBlank()) {
                    Timber.tag("loadEntryUrls").w("Timeout waiting for appUser.baseUrl after ${elapsed}ms, skipping loadEntryUrls")
                    return@launch
                } else {
                    Timber.tag("loadEntryUrls").d("appUser.baseUrl became available after ${elapsed}ms: ${appUser.baseUrl}")
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
}

