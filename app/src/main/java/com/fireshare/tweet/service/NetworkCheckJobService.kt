package com.fireshare.tweet.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.fireshare.tweet.viewmodel.BottomBarViewModel
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import dagger.hilt.android.AndroidEntryPoint
import jakarta.inject.Inject

@AndroidEntryPoint
class NetworkCheckJobService : JobService() {

    @Inject
    lateinit var bottomBarViewModel: BottomBarViewModel
    @Inject
    lateinit var tweetFeedViewModel: TweetFeedViewModel

    override fun onStartJob(params: JobParameters?): Boolean {
        // Perform the network check here
        Log.d("NetworkCheckJobService", "Performing network check...")

        // Simulate network check
        // You can replace this with actual network check logic
        val isNetworkAvailable = checkNetworkAvailability()

        bottomBarViewModel.updateBadgeCount()
        tweetFeedViewModel.loadNewerTweets()

        // Job finished
        jobFinished(params, !isNetworkAvailable)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // Handle job stop
        Log.d("NetworkCheckJobService", "Job stopped before completion")
        return true
    }

    private fun checkNetworkAvailability(): Boolean {
        // Implement your network check logic here
        return true
    }
}