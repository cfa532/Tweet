package com.fireshare.tweet

import PreferencesHelper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.fireshare.tweet.profile.EditProfileScreen
import com.fireshare.tweet.tweet.ComposeTweetScreen
import com.fireshare.tweet.tweet.TweetFeedScreen
import com.fireshare.tweet.profile.UserProfileScreen
import com.fireshare.tweet.tweet.ComposeCommentScreen
import com.fireshare.tweet.tweet.TweetDetailScreen

@Composable
fun TweetNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val preferencesHelper = remember { PreferencesHelper(context) }

    NavHost(
        modifier = modifier,
        navController = navController,
        startDestination = TweetFeed
    ) {
        composable<TweetFeed> {
            TweetFeedScreen(navController)
        }
        composable<TweetDetail> {navBackStackEntry ->
            val tweet = navBackStackEntry.toRoute<TweetDetail>()
            TweetDetailScreen(tweet.tweetId)
        }
        composable<ComposeTweet> {
            ComposeTweetScreen(navController)
        }
        composable<ComposeComment> { navBackStackEntry ->
            val tweet = navBackStackEntry.toRoute<ComposeComment>()
            ComposeCommentScreen(navController, tweet.tweetId)
        }
        composable<UserProfile> { backStackEntry ->
            val profile = backStackEntry.toRoute<UserProfile>()
            UserProfileScreen(navController, profile.userId)
        }
        composable<ProfileEditor> {
            EditProfileScreen(navController, preferencesHelper)
        }
    }

}