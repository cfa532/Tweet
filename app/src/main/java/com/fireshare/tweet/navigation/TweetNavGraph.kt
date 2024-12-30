package com.fireshare.tweet.navigation

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.chat.ChatListScreen
import com.fireshare.tweet.chat.ChatScreen
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.profile.EditProfileScreen
import com.fireshare.tweet.profile.FollowerScreen
import com.fireshare.tweet.profile.FollowingScreen
import com.fireshare.tweet.profile.LoginScreen
import com.fireshare.tweet.profile.UserProfileScreen
import com.fireshare.tweet.service.SearchScreen
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.reflect.typeOf

val LocalNavController = compositionLocalOf<NavController> {
    error("NavController must be provided in a CompositionLocalProvider")
}

@Composable
fun TweetNavGraph(
    appLinkIntent: Intent,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    var startDestination: NavTweet = NavTweet.TweetFeed
    val sharedViewModel: SharedViewModel = hiltViewModel()
    sharedViewModel.appUserViewModel =
        hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
            LocalContext.current as ComponentActivity, key = appUser.mid
        ){ factory ->
            factory.create(appUser.mid)
    }
    sharedViewModel.tweetFeedViewModel.tweetActionListener = sharedViewModel.appUserViewModel

    // Handle deeplink
    if (appLinkIntent.action == Intent.ACTION_VIEW) {
        val appLinkData = appLinkIntent.data
        if (appLinkData != null) {
            val pathSegments = appLinkData.pathSegments
            if (pathSegments.size >= 3) { // Check if enough segments are present
                val tweetId = pathSegments[1] // Get the 2nd segment (tweetId)
                val authorId = pathSegments[2] // Get the 3rd segment (authorId)
                tweetId?.let { startDestination = NavTweet.DeepLink(tweetId, authorId) }
            }
        }
    }
    CompositionLocalProvider(LocalNavController provides navController) {
        NavHost(
            modifier = Modifier,
            navController = navController,
            startDestination = startDestination,
            route = NavTwee::class
        ) {
            composable<NavTweet.TweetFeed> {
                val parentEntry = remember(navController) {
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
                            Tweet(mid = args.tweetId, authorId = args.authorId)
                        )
                    }
                TweetDetailScreen(viewModel, parentEntry)
            }
            composable<NavTweet.ComposeTweet> {
                ComposeTweetScreen(navController)
            }
            composable<ComposeComment> {
                ComposeCommentScreen {
                    navController.popBackStack()
                }
            }
            composable<NavTweet.UserProfile> {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavTwee)
                }
                val userId = it.toRoute<NavTweet.UserProfile>().userId
                // reassign the appUserViewModel here. It maybe after the user login with
                // a different username.
                sharedViewModel.appUserViewModel =
                    hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
                        LocalContext.current as ComponentActivity, key = appUser.mid
                    ) { factory ->
                        factory.create(appUser.mid)
                    }
                UserProfileScreen(navController, userId, parentEntry, sharedViewModel.appUserViewModel)
            }
            composable<ProfileEditor> {
                EditProfileScreen(navController, sharedViewModel.appUserViewModel)
            }
            composable<NavTweet.ChatBox> {
                // go to individual chatbox
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavTwee)
                }
                val args = it.toRoute<NavTweet.ChatBox>()
                val viewModel = hiltViewModel<ChatViewModel, ChatViewModel.ChatViewModelFactory>(
                    key = args.receiptId
                ) { factory ->
                    factory.create(receiptId = args.receiptId)
                }
                viewModel.chatListViewModel = hiltViewModel<ChatListViewModel>(parentEntry)
                ChatScreen(viewModel)
            }
            composable<NavTweet.ChatList> {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavTwee)
                }
                val viewModel = hiltViewModel<ChatListViewModel>(parentEntry)
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
                    md.params.index,
                    md.params.tweetId
                )
            }
            composable<NavTweet.Login> {
                val register: ()->Unit = {
                    navController.navigate(NavTweet.Registration)
                }
                val scope = rememberCoroutineScope()
                LoginScreen(register) {
                    scope.launch(Dispatchers.Main) {
                        navController.popBackStack()
                    }
                }
            }
            composable<NavTweet.Registration> {
                EditProfileScreen(navController, sharedViewModel.appUserViewModel)
            }
            composable<NavTweet.Following> {
                val user = it.toRoute<NavTweet.Following>()
                FollowingScreen(user.userId, sharedViewModel.appUserViewModel)
            }
            composable<NavTweet.Follower> {
                val user = it.toRoute<NavTweet.Following>()
                FollowerScreen(user.userId, sharedViewModel.appUserViewModel)
            }
            composable<NavTweet.Search> {
                SearchScreen()
            }
            /**
             * Deeplink carries the tweetId and authorId only.
             * Need to extract tweet data from Mimei DB.
             * */
            composable<NavTweet.DeepLink> {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavTwee)
                }
                val tweetId = it.toRoute<NavTweet.DeepLink>().tweetId
                val authorId = it.toRoute<NavTweet.DeepLink>().authorId
                val viewModel =
                    hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                        parentEntry, key = tweetId
                    ) { factory ->
                        factory.create(Tweet(authorId = authorId, mid = tweetId))
                    }
                TweetDetailScreen(viewModel, parentEntry)
            }
        }
    }
}

@HiltViewModel
class SharedViewModel @Inject constructor(
    val tweetFeedViewModel: TweetFeedViewModel  // make sharedViewModel singleton
) : ViewModel() {
    lateinit var appUserViewModel: UserViewModel
    lateinit var tweetViewModel: TweetViewModel
}
