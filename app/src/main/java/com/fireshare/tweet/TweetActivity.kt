package com.fireshare.tweet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fireshare.tweet.network.HproseInstance
import com.fireshare.tweet.ui.theme.TweetTheme
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TweetActivity : ComponentActivity() {

    @Inject
    lateinit var tweetFeedViewModel: TweetFeedViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TweetTheme {
                TweetNavGraph()
            }
        }
    }
}