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
import com.fireshare.tweet.TweetActivity.*
import com.fireshare.tweet.profile.UserProfileScreen

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
        startDestination = DestTweetFeed
    ) {
        composable<DestTweetFeed> {
            TweetFeedScreen(navController)
        }
        composable<DestComposeTweet> {
            ComposeTweetScreen(navController)
        }
//        composable<DestUserProfile> {backStackEntry ->
//            val profile: DestUserProfile = backStackEntry.toRoute()
//            UserProfileScreen(navController, profile.user.mid)
//        }
        composable<DestProfileEditor> {
            EditProfileScreen(navController, preferencesHelper)
        }
    }

}