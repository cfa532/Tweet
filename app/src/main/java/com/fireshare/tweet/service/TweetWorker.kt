package com.fireshare.tweet.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.work.CoroutineWorker
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import jakarta.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class UploadTweetWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    @Inject
    lateinit var tweetFeedViewModel: TweetFeedViewModel

    override suspend fun doWork(): Result {
        return try {
            val tweetContent = inputData.getString("tweetContent")
            val attachmentUris = inputData.getStringArray("attachmentUris")?.toList() ?: emptyList<Uri>()

            val attachments = attachmentUris.map { uri ->
                val inputStream = applicationContext.contentResolver.openInputStream(Uri.parse(uri.toString()))
                if (inputStream == null) {
                    Log.e("UploadTweetWorker", "Failed to open input stream for URI: $uri")
                    return@map Result.failure()
                }
                inputStream.use { HproseInstance.uploadToIPFS(it) }
            }

            val tweet = Tweet(
                authorId = appUser.mid,
                content = tweetContent ?: " ",
                attachments = attachments.mapNotNull { it.toString() }
            )

            HproseInstance.uploadTweet(tweet)?.let { newTweet: Tweet ->
                Log.d("UploadTweetWorker", tweet.toString())
                withContext(Dispatchers.Main) {
                    Log.d("UploadTweetWorker", tweetFeedViewModel.toString())
//                    tweetFeedViewModel.addTweet(newTweet)
                }
                return Result.success()
            }

            Result.failure()
        } catch (e: Exception) {
            Log.e("UploadTweetWorker", "Error in doWork", e)
            Result.failure()
        }
    }
}