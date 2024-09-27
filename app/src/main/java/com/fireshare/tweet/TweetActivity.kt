package com.fireshare.tweet

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.getString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.navigation.LocalViewModelProvider
import com.fireshare.tweet.navigation.TweetNavGraph
import com.fireshare.tweet.service.NetworkCheckJobService
import com.fireshare.tweet.service.ObserveAsEvents
import com.fireshare.tweet.service.SnackbarController
import com.fireshare.tweet.ui.theme.TweetTheme
import com.fireshare.tweet.viewmodel.UserViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class TweetActivity : ComponentActivity() {

    private val activityViewModel: ActivityViewModel by viewModels()
    private lateinit var appUserViewModel: UserViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            (application as TweetApplication).initJob.await()   // wait until network ready

            scheduleNetworkCheckJob()
            activityViewModel.checkForUpgrade(this@TweetActivity)

            setContent {
                // Initialize the AppUser's userViewModel, which is a singleton needed in many UI states.
                appUserViewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
                    this@TweetActivity, key = appUser.mid
                ) { factory ->
                    factory.create(appUser.mid)
                }
                // default no to update fans and followings list of user object.
                // Do it only when opening its profile page.
                if (appUser.mid != TW_CONST.GUEST_ID)
                    appUserViewModel.updateFans()

                TweetTheme {
                    // Global snackbar host
                    val snackbarHostState = remember { SnackbarHostState() }
                    val scope = rememberCoroutineScope()
                    ObserveAsEvents(
                        flow = SnackbarController.events,
                        snackbarHostState
                    ) { event ->
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()    // dismiss old message
                            // show new snackbar
                            val result = snackbarHostState.showSnackbar(
                                message = event.message,
                                actionLabel = event.action?.name,
                                duration = SnackbarDuration.Short
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                event.action?.action?.invoke()
                            }
                        }
                    }

                    val localContext = LocalContext.current
                    val viewModelStoreOwner =
                        LocalViewModelStoreOwner.current ?: (localContext as TweetActivity)
                    val viewModelProvider: ViewModelProvider =
                        remember { ViewModelProvider(viewModelStoreOwner) }

                    CompositionLocalProvider(LocalViewModelProvider provides viewModelProvider) {
                        Scaffold(
                            snackbarHost = {
                                CustomSnackbarHost(
                                    hostState = snackbarHostState
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { innerPadding ->
                            TweetNavGraph(intent, appUserViewModel)
                            Row(modifier = Modifier
                                .fillMaxWidth()
                                .padding(innerPadding))
                            {
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun CustomSnackbarHost(hostState: SnackbarHostState) {
        val configuration = LocalConfiguration.current
        val screenHeightDp = configuration.screenHeightDp.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(bottom = screenHeightDp.minus(100.dp)) // Adjust the padding as needed
        ) {
            SnackbarHost(
                hostState = hostState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }

    private fun scheduleNetworkCheckJob() {
        val componentName = ComponentName(this, NetworkCheckJobService::class.java)
        val jobInfo = JobInfo.Builder(1, componentName)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(TimeUnit.MINUTES.toMillis(15)) // Set the interval to 15 minutes
            .setPersisted(true) // Persist the job across device reboots
            .build()

        val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        jobScheduler.schedule(jobInfo)
    }
}

class ActivityViewModel: ViewModel() {
    private val _isDownloading = MutableStateFlow<Boolean>(false)
    val isDownloading = _isDownloading.asStateFlow()

    fun checkForUpgrade(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val versionInfo = HproseInstance.checkUpdates() ?: return@launch
            val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: return@launch
            if (currentVersion.toInt() < (versionInfo["version"]?.toInt() ?: 0)) {
                val downloadUrl = "${appUser.baseUrl}/mm/${versionInfo["packageId"]}"
                showUpdateDialog(context, downloadUrl)
            }
        }
    }

    private fun showUpdateDialog(context: Context, downloadUrl: String) {
        (context as Activity).runOnUiThread {
            AlertDialog.Builder(context)
                .setTitle(getString(context, R.string.update_available))
                .setMessage(getString(context, R.string.updata_message))
                .setPositiveButton(getString(context, R.string.update)) { _, _ ->
                    downloadAndInstall(context, downloadUrl)
                }
                .setNegativeButton(getString(context, R.string.cancel), null)
                .show()
        }
    }

    private fun downloadAndInstall(context: Context, downloadUrl: String) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setMimeType("application/octet-stream") // Set appropriate MIME type if known
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "fireshare.apk")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setTitle("Downloading Update")

        val downloadId = downloadManager.enqueue(request)

        viewModelScope.launch(Dispatchers.IO) {
            var finishDownload = false
            while (!finishDownload) {
                val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = cursor.getInt(columnIndex)
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        finishDownload = true
                        _isDownloading.value = false
                        // Optionally, start the installation process here
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
