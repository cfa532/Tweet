package com.fireshare.tweet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.toRoute
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.profile.EditProfileScreen
import com.fireshare.tweet.profile.UserProfileScreen
import com.fireshare.tweet.tweet.ComposeCommentScreen
import com.fireshare.tweet.tweet.ComposeTweetScreen
import com.fireshare.tweet.tweet.TweetDetailScreen
import com.fireshare.tweet.tweet.TweetFeedScreen
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.TweetViewModel

val LocalNavController = compositionLocalOf<NavController> {
    error("NavController must be provided in a CompositionLocalProvider")
}

@Composable
fun TweetNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    // provide navController application-wide
    CompositionLocalProvider(LocalNavController provides navController) {
        NavHost(
            modifier = modifier,
            navController = navController,
            startDestination = TweetFeed,
            route = NavRoot::class
        ) {
            composable<TweetFeed> {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavRoot)
                }
                val viewModel = hiltViewModel<TweetFeedViewModel>(parentEntry)
                TweetFeedScreen(navController, viewModel)
            }
//            composable<TweetDetail> { navBackStackEntry ->
//                val args = navBackStackEntry.toRoute<TweetDetail>()
//                val viewModel = hiltViewModel<TweetViewModel>(key = args.tweetId)
//                TweetDetailScreen(args.tweetId, args.commentId, viewModel)
//            }
            composable("TweetDetail?tweetId={tweetId}&commentId={commentId}",
                arguments = listOf(
                    navArgument("tweetId") { type = NavType.StringType },
                    navArgument("commentId") {
                        nullable = true
                        defaultValue = null
                        type = NavType.StringType
                    }
                )
            ) {
                val parentEntry = remember(it) {
                    navController.getBackStackEntry(NavRoot)
                }
                val tweetId = it.arguments?.getString("tweetId") as MimeiId
                val commentId = it.arguments?.getString("commentId")
                val viewModel = hiltViewModel<TweetViewModel>(parentEntry, key = tweetId)
                TweetDetailScreen(tweetId, commentId, viewModel)
            }
            composable<ComposeTweet> {
                ComposeTweetScreen(navController)
            }
            composable<ComposeComment> { navBackStackEntry ->
                val parentEntry = remember(navBackStackEntry) {
                    navController.getBackStackEntry(NavRoot)
                }
                val tweet = navBackStackEntry.toRoute<ComposeComment>()
                val viewModel = hiltViewModel<TweetViewModel>(parentEntry, key = tweet.tweetId)
                ComposeCommentScreen(navController, tweet.tweetId, viewModel)
            }
            composable<UserProfile> { backStackEntry ->
                val profile = backStackEntry.toRoute<UserProfile>()
                UserProfileScreen(navController, profile.userId)
            }
            composable<ProfileEditor> {
                EditProfileScreen(navController)
            }
        }
    }
}
