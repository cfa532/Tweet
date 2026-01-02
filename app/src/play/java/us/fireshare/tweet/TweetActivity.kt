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

        // Start initialization
        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            Timber.tag("PlayTweetActivity").d("⏱️ Initialization started")
            
            // Set 3-second timeout to force show UI
            val timeoutJob = launch {
                delay(3000L)
                if (!activityViewModel.isAppReady.value) {
                    val elapsedTime = System.currentTimeMillis() - startTime
                    Timber.tag("PlayTweetActivity").d("⏱️ 3s timeout reached (init took ${elapsedTime}ms), forcing UI to show")
                    activityViewModel.isAppReady.value = true
                }
            }
            
            try {
                HproseInstance.init(this@PlayTweetActivity) {
                    // AppUser loaded, show UI immediately
                    val initCompleteTime = System.currentTimeMillis()
                    val initElapsed = initCompleteTime - startTime
                    Timber.tag("PlayTweetActivity").d("⏱️ Init completed in ${initElapsed}ms, showing UI immediately")
                    
                    lifecycleScope.launch {
                        timeoutJob.cancel() // Cancel timeout since init finished
                        activityViewModel.isAppReady.value = true
                    }
                }

                // Background tasks
                launch(IO) {
                    delay(5000) // Load entry URLs 5s after init
                    activityViewModel.loadEntryUrls()
                }

                launch(IO) {
                    delay(10000)
                    HproseInstance.resumeIncompleteUploads(this@PlayTweetActivity)
                }

                requestNotificationPermissionIfNeeded()

            } catch (e: Exception) {
                timeoutJob.cancel() // Cancel timeout since we're handling error
                val elapsedTime = System.currentTimeMillis() - startTime
                Timber.tag("PlayTweetActivity").e(e, "⏱️ Error during app initialization after ${elapsedTime}ms, showing UI immediately")
                activityViewModel.isAppReady.value = true
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

