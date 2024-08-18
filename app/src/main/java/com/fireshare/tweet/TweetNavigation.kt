package com.fireshare.tweet

import androidx.navigation.NavHostController
import com.fireshare.tweet.TweetDestinationsArgs.TWEET_ID_ARGS
import com.fireshare.tweet.TweetScreens.FEED_SCREEN
import com.fireshare.tweet.TweetScreens.TWEET_SCREEN

private object TweetScreens {
    const val FEED_SCREEN = "tweetFeed"
    const val TWEET_SCREEN = "tweetDetail"
    const val PROFILE_SCREEN = "userProfile"
    const val EDIT_SCREEN = "tweetEditor"
    const val MESSAGE_SCREEN = "userMessages"
}

object TweetDestinationsArgs {
    const val TWEET_ID_ARGS = "tweetId"
}

object TweetDestinations {
    const val TWEET_ROUTE = "$TWEET_SCREEN?$TWEET_ID_ARGS={$TWEET_ID_ARGS}"
}

class TweetNavigationActions(private val navController: NavHostController) {

    fun navigateToFeed() {
        navController.navigate(FEED_SCREEN)
    }

    fun navigateToTweet() {

    }
}