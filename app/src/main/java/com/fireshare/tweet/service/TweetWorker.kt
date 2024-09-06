package com.fireshare.tweet.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import jakarta.inject.Inject

@HiltWorker
class UploadTweetWorker(
    context: Context,
    workerParams: WorkerParameters) : Worker(context, workerParams)
{
    @Inject lateinit var tweetFeedViewModel: TweetFeedViewModel
    override fun doWork(): Result {
        val tweetContent = inputData.getString("tweetContent") ?: return Result.failure()
        val attachmentUris = inputData.getStringArray("attachmentUris")?.toList() ?: emptyList<Uri>()
        val contextString = inputData.getString("context") ?: return Result.failure()

        // Upload attachments using HproseInstance.uploadToIPFS for each attachment
        val attachments = (attachmentUris as List<*>).map { uri ->
            val appContext = Class.forName(contextString).getDeclaredField("applicationContext").get(null) as Context
            val inputStream = appContext.contentResolver.openInputStream(uri as Uri) ?: return@map Result.failure()
            inputStream.use { HproseInstance.uploadToIPFS(it) }
        }

        val tweet = Tweet(
            authorId = appUser.mid,
            content = tweetContent,
            attachments = attachments.mapNotNull { it.toString() }
        )

        HproseInstance.uploadTweet(tweet)?.let { newTweet: Tweet ->
            Log.d("UploadTweetWorker", tweet.toString())
            tweetFeedViewModel.addTweet(newTweet)
            return Result.success()
        }
        return Result.failure()
    }
}