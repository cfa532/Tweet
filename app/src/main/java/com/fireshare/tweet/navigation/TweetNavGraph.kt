package com.fireshare.tweet.navigation

import android.content.Intent
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
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.chat.ChatListScreen
import com.fireshare.tweet.chat.ChatScreen
import com.fireshare.tweet.datamodel.TW_CONST
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
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.viewmodel.UserViewModel
import com.fireshare.tweet.widget.MediaBrowser
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
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    var startDestination: NavTweet = NavTweet.TweetFeed
    lateinit var appUserViewModel: UserViewModel

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
            modifier = modifier,
            navController = navController,
            startDestination = startDestination,
            route = NavTwee::class
        ) {
            composable<NavTweet.TweetFeed> {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavTwee)
                }
                // Initialize the AppUser's userViewModel, which is a singleton needed in many UI states.
                appUserViewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
                    parentEntry, key = appUser.mid
                ) { factory ->
                    factory.create(appUser.mid)
                }
                // By default NOT to update fans and followings list of an user object.
                // Do it only when opening the user's profile page.
                if (appUser.mid != TW_CONST.GUEST_ID)
                    // Only get current user's fans list when opening the app.
                    appUserViewModel.updateFollowingsAndFans()

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
                        factory.create(Tweet(authorId = authorId, content = "", mid = tweetId))
                    }
                TweetDetailScreen(viewModel, parentEntry)
            }
        }
    }
}
