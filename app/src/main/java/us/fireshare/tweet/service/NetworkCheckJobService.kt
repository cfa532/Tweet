package us.fireshare.tweet.service

import android.app.job.JobParameters
import android.app.job.JobService
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.viewmodel.TweetFeedViewModel

@AndroidEntryPoint
class NetworkCheckJobService : JobService() {

    // BadgeStateManager is now a singleton object, no injection needed
    @Inject
    lateinit var tweetFeedViewModel: TweetFeedViewModel

    override fun onStartJob(params: JobParameters?): Boolean {
        // Perform the network check here
        Timber.tag("NetworkCheckJobService").d("Performing network check...")

        // Simulate network check
        // You can replace this with actual network check logic
        val isNetworkAvailable = checkNetworkAvailability()

        // Use runBlocking to call suspend function
        kotlinx.coroutines.runBlocking {
            val newMessages = HproseInstance.checkNewMessages()
            BadgeStateManager.updateBadgeCount(newMessages?.size ?: 0)
        }
        tweetFeedViewModel.viewModelScope.launch(Dispatchers.IO) {
            tweetFeedViewModel.fetchTweets(0)
        }

        // Job finished
        jobFinished(params, !isNetworkAvailable)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // Handle job stop
        Timber.tag("NetworkCheckJobService").d("Job stopped before completion")
        return true
    }

    private fun checkNetworkAvailability(): Boolean {
        // Implement your network check logic here
        return true
    }
}