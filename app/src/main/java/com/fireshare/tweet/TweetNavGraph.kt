package com.fireshare.tweet

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.message.MessageScreen
import com.fireshare.tweet.profile.EditProfileScreen
import com.fireshare.tweet.profile.UserProfileScreen
import com.fireshare.tweet.tweet.ComposeCommentScreen
import com.fireshare.tweet.tweet.ComposeTweetScreen
import com.fireshare.tweet.tweet.TweetDetailScreen
import com.fireshare.tweet.tweet.TweetFeedScreen
import com.fireshare.tweet.viewmodel.TweetViewModel

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

    // provide navController application-wide
    val viewModelStoreOwner =
        LocalViewModelStoreOwner.current ?: (LocalContext.current as TweetActivity)
    val viewModelProvider: ViewModelProvider = remember { ViewModelProvider(viewModelStoreOwner) }
//    val sharedViewModel = viewModel(SharedTweetViewModel::class.java)

    CompositionLocalProvider(LocalNavController provides navController) {
        CompositionLocalProvider(LocalViewModelProvider provides viewModelProvider) {
            NavHost(
                modifier = modifier,
                navController = navController,
                startDestination = NavigationItem.TweetFeed,
                route = NavRoot::class
            ) {
                composable<NavigationItem.TweetFeed> {
                    val parentEntry = remember(it) {
                        navController.getBackStackEntry(NavRoot)
                    }
                    TweetFeedScreen(navController, parentEntry, 0)
                }
                composable<NavigationItem.TweetDetail> { navBackStackEntry ->
                    val args = navBackStackEntry.toRoute<NavigationItem.TweetDetail>()
                    val parentEntry = remember(navBackStackEntry) {
                        navController.getBackStackEntry(NavRoot)
                    }
                    val viewModel =
                        hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                            parentEntry,
                            key = args.tweetId
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
                composable<NavigationItem.ComposeTweet> {
                    ComposeTweetScreen(navController)
                }
                composable<ComposeComment> { navBackStackEntry ->
                    val tweet = navBackStackEntry.toRoute<ComposeComment>()
                    ComposeCommentScreen(navController, tweet.tweetId)
                }
                composable<UserProfile> { backStackEntry ->
                    val parentEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(NavRoot)
                    }
                    val profile = backStackEntry.toRoute<UserProfile>()
                    UserProfileScreen(navController, profile.userId, parentEntry)
                }
                composable<ProfileEditor> {
                    EditProfileScreen(navController)
                }
                composable<NavigationItem.MessageBox> {
                    val parentEntry = remember(it) {
                        navController.getBackStackEntry(NavRoot)
                    }
                    MessageScreen(parentEntry, 1)
                }
            }
        }
    }
}
