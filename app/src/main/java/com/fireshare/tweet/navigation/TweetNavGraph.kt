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
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.chat.ChatListScreen
import com.fireshare.tweet.chat.ChatScreen
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.profile.EditProfileScreen
import com.fireshare.tweet.profile.FollowerScreen
import com.fireshare.tweet.profile.FollowingScreen
import com.fireshare.tweet.profile.LoginScreen
import com.fireshare.tweet.profile.UserProfileScreen
import com.fireshare.tweet.tweet.ComposeCommentScreen
import com.fireshare.tweet.tweet.ComposeTweetScreen
import com.fireshare.tweet.tweet.TweetDetailScreen
import com.fireshare.tweet.tweet.TweetFeedScreen
import com.fireshare.tweet.viewmodel.ChatListViewModel
import com.fireshare.tweet.viewmodel.ChatViewModel
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.viewmodel.UserViewModel
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
            route = NavTwee::class
        ) {
            composable<NavTweet.TweetFeed> {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavTwee)
                }
                TweetFeedScreen(navController, parentEntry, 0, tweetFeedViewModel)
            }
            composable<NavTweet.TweetDetail> { navBackStackEntry ->
                val args = navBackStackEntry.toRoute<NavTweet.TweetDetail>()
                val parentEntry = remember(navBackStackEntry) {
                    navController.getBackStackEntry(NavTwee)
                }
                val viewModel =
                    hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                        parentEntry,            // The scope and key will identify
                        key = args.tweetId      // the viewModel to be injected.
                    )
                    { factory ->
                        factory.create(
                            // The tweet is surely created. The right key will locate it.
                            // so the init value do not matter here.
                            Tweet(
                                authorId = "default",
                                content = "nothing"
                            )
                        )
                    }
                TweetDetailScreen(viewModel, parentEntry)
            }
            composable<NavTweet.ComposeTweet> {
                ComposeTweetScreen(navController)
            }
            composable<ComposeComment> {
                ComposeCommentScreen(navController)
            }
            composable<NavTweet.UserProfile> {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavTwee)
                }
                val profile = it.toRoute<NavTweet.UserProfile>()
                UserProfileScreen(navController, profile.userId, parentEntry)
            }
            composable<ProfileEditor> {
                EditProfileScreen(navController)
            }
            composable<NavTweet.ChatBox> {
                // go to individual chatbox
                val args = it.toRoute<NavTweet.ChatBox>()
                val viewModel = hiltViewModel<ChatViewModel, ChatViewModel.ChatViewModelFactory>(
                    key = args.receiptId ) { factory ->
                    factory.create(receiptId = args.receiptId)
                }
                ChatScreen(viewModel)
            }
            composable<NavTweet.ChatList> {
                // chatbox list
                val viewModel = hiltViewModel<ChatListViewModel>()
                ChatListScreen(viewModel)
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
            composable<NavTweet.Following> {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavTwee)
                }
                val user = it.toRoute<NavTweet.Following>()
                val userViewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(parentEntry, key = user.userId) {
                        factory -> factory.create(user.userId)
                }
                FollowingScreen(userViewModel, parentEntry)
            }
            composable<NavTweet.Follower> {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavTwee)
                }
                val user = it.toRoute<NavTweet.Following>()
                val userViewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(parentEntry, key = user.userId) {
                        factory -> factory.create(user.userId)
                }
                FollowerScreen(userViewModel, parentEntry)
            }
        }
    }
}
