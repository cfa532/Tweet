package us.fireshare.tweet.navigation

import android.content.Context
import android.widget.Toast
import androidx.navigation.NavController
import us.fireshare.tweet.HproseInstance.appUser

fun requireAuthenticatedUser(
    context: Context,
    navController: NavController,
    message: String
): Boolean {
    if (!appUser.isGuest()) return true

    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    navController.navigate(NavTweet.Login) {
        launchSingleTop = true
    }
    return false
}
