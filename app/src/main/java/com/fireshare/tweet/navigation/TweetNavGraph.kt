package com.fireshare.tweet.navigation

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.fireshare.tweet.HproseInstance
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
import com.fireshare.tweet.widget.MediaBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.reflect.typeOf

val LocalNavController = compositionLocalOf<NavController> {
    error("NavController must be provided in a CompositionLocalProvider")
}
val LocalViewModelProvider = compositionLocalOf<ViewModelProvider?> { null }

class SharedTweetViewModel : ViewModel() {
    lateinit var sharedTVMInstance: TweetViewModel
}

@Composable
fun TweetNavGraph(
    appLinkIntent: Intent,
    appUserViewModel: UserViewModel,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    var startDestination: NavTweet = NavTweet.TweetFeed

    if (appLinkIntent.action == Intent.ACTION_VIEW) {
        val appLinkData = appLinkIntent.data
        if (appLinkData != null) {
            val pathSegments = appLinkData.pathSegments
            if (pathSegments.size >= 3) { // Check if enough segments are present
                val authorId = pathSegments[1] // Get the second segment (authorId)
                val tweetId = pathSegments[2] // Get the third segment (tweetId)
                tweetId?.let { startDestination = NavTweet.DeepLink(tweetId, authorId) }
            }
        }
    }
    CompositionLocalProvider(LocalNavController provides navController) {
        NavHost(
            modifier = modifier,
            navController = navController,
            startDestination = startDestination,
            route = NavTwee::class
        ) {
            composable<NavTweet.TweetFeed> {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavTwee)
                }
                TweetFeedScreen(navController, parentEntry, 0)
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
                UserProfileScreen(navController, profile.userId, parentEntry, appUserViewModel)
            }
            composable<ProfileEditor> {
                EditProfileScreen(navController, appUserViewModel)
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
            composable<NavTweet.MediaViewer>(
                typeMap = mapOf(typeOf<MediaViewerParams>() to TweetNavType.MediaViewerType)
            ) { navBackStackEntry ->
                val parentEntry = remember(navBackStackEntry) {
                    navController.getBackStackEntry(NavTwee)
                }
                val md = navBackStackEntry.toRoute<NavTweet.MediaViewer>()
                MediaBrowser(
                    parentEntry,
                    navController,
                    md.params.mediaItems,
                    md.params.index,
                    md.params.tweetId
                )
            }
            composable<NavTweet.Login> {
                LoginScreen()
            }
            composable<NavTweet.Registration> {
                EditProfileScreen(navController, appUserViewModel)
            }
            composable<NavTweet.Following> {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavTwee)
                }
                val user = it.toRoute<NavTweet.Following>()
                FollowingScreen(user.userId, parentEntry, appUserViewModel)
            }
            composable<NavTweet.Follower> {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavTwee)
                }
                val user = it.toRoute<NavTweet.Following>()
                val userViewModel =
                    hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
                        parentEntry,
                        key = user.userId
                    ) { factory ->
                        factory.create(user.userId)
                    }
                FollowerScreen(userViewModel, appUserViewModel)
            }
            /**
             * Deeplink carries the tweetId only. Need to attract tweet data, then authorId.
             * */
            composable<NavTweet.DeepLink> {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavTwee)
                }
                val tweetId = it.toRoute<NavTweet.DeepLink>().tweetId
                val authorId = it.toRoute<NavTweet.DeepLink>().authorId
//                val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()
                println("authorId: $authorId, tweetId: $tweetId")
                val scope = rememberCoroutineScope()
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val tweet = HproseInstance.getTweet(tweetId, authorId)
                        println(tweet)
                    }
                }
                val vm = TweetViewModel(Tweet(authorId = authorId, content = "", mid = tweetId), SavedStateHandle())
                val viewModel =
                    hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                        parentEntry, key = tweetId
                    ) { factory ->
                        factory.create(Tweet(authorId = authorId, content = ""))
                    }
                TweetDetailScreen(vm, parentEntry)
            }
        }
    }
}
