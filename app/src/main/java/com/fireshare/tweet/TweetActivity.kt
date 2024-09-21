package com.fireshare.tweet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.fireshare.tweet.navigation.LocalViewModelProvider
import com.fireshare.tweet.navigation.TweetNavGraph
import com.fireshare.tweet.service.ChatWorker
import com.fireshare.tweet.service.ObserveAsEvents
import com.fireshare.tweet.service.SnackbarController
import com.fireshare.tweet.ui.theme.TweetTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class TweetActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            (application as TweetApplication).initJob.await()
            scheduleNetworkCheck()

            setContent {
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
                                SnackbarHost(
                                    hostState = snackbarHostState
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { innerPadding ->
                            TweetNavGraph()
                            Row(modifier = Modifier.fillMaxWidth().padding(innerPadding))
                            {
                            }
                        }
                    }
                }
            }
        }
    }
//    private fun scheduleNetworkCheck() {
//        val networkCheckRequest = OneTimeWorkRequestBuilder<ChatWorker>()
//            .build()
//
//        WorkManager.getInstance(this).enqueue(networkCheckRequest)
//    }

    private fun scheduleNetworkCheck() {
        val networkCheckRequest = PeriodicWorkRequestBuilder<ChatWorker>(15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ChatWork",
            ExistingPeriodicWorkPolicy.UPDATE,
            networkCheckRequest
        )
    }
}
