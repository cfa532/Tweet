package com.fireshare.tweet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
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
        ) {
            composable<TweetFeed> {
                val viewModel = hiltViewModel<TweetFeedViewModel>()
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
                val tweetId = it.arguments?.getString("tweetId") as MimeiId
                val commentId = it.arguments?.getString("commentId")
                val viewModel = hiltViewModel<TweetViewModel>(key = tweetId)
                TweetDetailScreen(tweetId, commentId, viewModel)
            }
            composable<ComposeTweet> {
                ComposeTweetScreen(navController)
            }
            composable<ComposeComment> { navBackStackEntry ->
                val tweet = navBackStackEntry.toRoute<ComposeComment>()
                val viewModel = hiltViewModel<TweetViewModel>(key = tweet.tweetId)
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
