package us.fireshare.tweet

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Scaffold

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.navigation.TweetNavGraph
import us.fireshare.tweet.service.NetworkCheckJobService
import us.fireshare.tweet.service.ObserveAsEvents

import us.fireshare.tweet.ui.theme.TweetTheme
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class TweetActivity : ComponentActivity() {
    private lateinit var initJob: Deferred<Unit>
    private val activityViewModel: ActivityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
//        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

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
            initJob.await()   // wait until network ready
            activityViewModel.isAppReady.value = true   // app ready. Show main page now.

            launch {
                delay(60000)
                scheduleNetworkCheckJob()
                activityViewModel.checkForUpgrade(this@TweetActivity)
            }

            setContent {
                TweetTheme {
                    Scaffold(
                        modifier = Modifier.fillMaxWidth()
                    ) { innerPadding ->
                        TweetNavGraph(intent, modifier = Modifier.padding(innerPadding))
                    }
                }
            }
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
    val isAppReady = mutableStateOf(false)
    private val _isDownloading = MutableStateFlow(false)

    fun checkForUpgrade(context: Context) {
        viewModelScope.launch(IO) {
            try {
                delay(30000)
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
                val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
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
                            setDataAndType(downloadedApkUri, "application/vnd.android.package-archive")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
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
