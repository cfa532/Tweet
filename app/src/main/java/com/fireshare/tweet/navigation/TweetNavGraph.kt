package com.fireshare.tweet.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.message.MessageScreen
import com.fireshare.tweet.profile.EditProfileScreen
import com.fireshare.tweet.profile.LoginScreen
import com.fireshare.tweet.profile.UserProfileScreen
import com.fireshare.tweet.tweet.ComposeCommentScreen
import com.fireshare.tweet.tweet.ComposeTweetScreen
import com.fireshare.tweet.tweet.TweetDetailScreen
import com.fireshare.tweet.tweet.TweetFeedScreen
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.MediaViewerScreen

val LocalNavController = compositionLocalOf<NavController> {
    error("NavController must be provided in a CompositionLocalProvider")
}
val LocalViewModelProvider = compositionLocalOf<ViewModelProvider?> { null }

class SharedTweetViewModel : ViewModel() {
    lateinit var sharedTVMInstance: TweetViewModel
}

@Composable
fun TweetNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()

    // Used by WorkManager to update tweetFeed
    HproseInstance.tweetFeedViewModel = tweetFeedViewModel

    CompositionLocalProvider(LocalNavController provides navController) {
        NavHost(
            modifier = modifier,
            navController = navController,
            startDestination = NavTweet.TweetFeed,
            route = NavRoot::class
        ) {
            composable<NavTweet.TweetFeed> {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavRoot)
                }
                TweetFeedScreen(navController, parentEntry, 0, tweetFeedViewModel)
            }
            composable<NavTweet.TweetDetail> { navBackStackEntry ->
                val args = navBackStackEntry.toRoute<NavTweet.TweetDetail>()
                val parentEntry = remember(navBackStackEntry) {
                    navController.getBackStackEntry(NavRoot)
                }
                val viewModel =
                    hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                        parentEntry,            // The scope and key will identify
                        key = args.tweetId      // the viewModel to be injected.
                    )
                    { factory ->
                        factory.create(
                            Tweet(
                                authorId = "default",
                                content = "nothing"
                            )
                        )
                    }
                TweetDetailScreen(viewModel, parentEntry, 0)
            }
            composable<NavTweet.ComposeTweet> {
                ComposeTweetScreen(navController)
            }
            composable<ComposeComment> {
                ComposeCommentScreen(navController)
            }
            composable<NavTweet.UserProfile> {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavRoot)
                }
                val profile = it.toRoute<NavTweet.UserProfile>()
                UserProfileScreen(navController, profile.userId, parentEntry)
            }
            composable<ProfileEditor> {
                EditProfileScreen(navController)
            }
            composable<NavTweet.MessageBox> {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavRoot)
                }
                MessageScreen(parentEntry, 1)
            }
            composable<MediaViewer> {
                val md = it.toRoute<MediaViewer>()
                MediaViewerScreen(md.midList, md.index)
            }
            composable<NavTweet.Login> {
                LoginScreen()
            }
            composable<NavTweet.Registration> {
                EditProfileScreen(navController)
            }
        }
    }
}
