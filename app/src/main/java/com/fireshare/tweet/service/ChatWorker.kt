package com.fireshare.tweet.service

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.fireshare.tweet.viewmodel.BottomBarViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ChatWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val bottomBarViewModel: BottomBarViewModel
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        Log.d("ChatWorker", "Scheduled worker checking incoming message")
        // Perform the network check here
        bottomBarViewModel.updateBadgeCount()

        // Indicate whether the work finished successfully with the Result
        return Result.success()
    }
}

class ChatWorkerFactory(
    private val bottomBarViewModel: BottomBarViewModel
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            ChatWorker::class.java.name ->
                ChatWorker(appContext, workerParameters, bottomBarViewModel)
            else -> null
        }
    }
}