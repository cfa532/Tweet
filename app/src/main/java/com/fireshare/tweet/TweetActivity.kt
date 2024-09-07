package com.fireshare.tweet

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.fireshare.tweet.navigation.TweetNavGraph
import com.fireshare.tweet.ui.theme.TweetTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@AndroidEntryPoint
class TweetActivity : ComponentActivity() {
    private lateinit var viewModelStoreOwner: ViewModelStoreOwner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch(Dispatchers.IO) {
            HproseInstance.init(TweetApplication.preferencesHelper)
            withContext(Dispatchers.Main) {
                viewModelStoreOwner = this@TweetActivity

                setContent {
                    val localViewModelStoreOwner = LocalViewModelStoreOwner.current ?: viewModelStoreOwner
                    TweetTheme {
                        CompositionLocalProvider(LocalViewModelStoreOwner provides localViewModelStoreOwner) {
                            TweetNavGraph()
                        }
                    }
                }
            }
        }
    }
}
