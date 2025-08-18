package us.fireshare.tweet.tweet

import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.TweetApplication.Companion.applicationScope
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.viewmodel.TweetFeedViewModel
import us.fireshare.tweet.viewmodel.TweetViewModel

@Composable
fun TweetDropdownMenuItems(
    tweet: Tweet,
    parentEntry: NavBackStackEntry,
    onDismissRequest: () -> Unit,
    contextType: String = "default" // Context to determine where this dropdown is shown
) {
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val appUserViewModel = sharedViewModel.appUserViewModel
    val navController = LocalNavController.current
    // Use the singleton TweetFeedViewModel from AppModule
    val tweetFeedViewModel: TweetFeedViewModel = hiltViewModel()
    val originTweetViewModel =
        if (tweet.originalTweetId != null && tweet.originalAuthorId != null) {
            hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                parentEntry, key = tweet.originalTweetId
            ) { factory ->
                // Create a temporary tweet for the ViewModel, the actual tweet will be loaded by the ViewModel
                factory.create(
                    Tweet(
                        mid = tweet.originalTweetId!!,
                        authorId = tweet.originalAuthorId!!
                    )
                )
            }
        } else null
    val context = LocalContext.current

    // Only show delete button if tweet author is current user AND we're in allowed contexts
    val shouldShowDeleteButton = when (contextType) {
        "followingsTweet" -> true // Show delete for all tweets in main feed
        "appUserProfile" -> tweet.authorId == appUser.mid // Show delete only for app user's tweets in profile
        else -> false // Don't show delete in other contexts
    }
    
    if (shouldShowDeleteButton) {
        DropdownMenuItem(
            modifier = Modifier.alpha(0.8f),
            onClick = {
                Toast.makeText(
                    context,
                    context.getString(R.string.delete_tweet),
                    Toast.LENGTH_SHORT
                ).show()
                // Dismiss popup immediately for better UX
                onDismissRequest()

                tweetFeedViewModel.viewModelScope.launch(IO) {
                    try {
                        tweetFeedViewModel.delTweet(navController, tweet.mid, {
                            applicationScope.launch(IO) {
                                if (tweet.originalTweetId != null && tweet.originalAuthorId != null) {
                                    val originalTweet = HproseInstance.fetchTweet(
                                        tweet.originalTweetId!!,
                                        tweet.originalAuthorId!!,
                                        shouldCache = false
                                    )
                                    originalTweet?.let {
                                        originTweetViewModel?.updateRetweetCount(
                                            it,      // original tweet
                                            tweet.mid,      // retweet Id
                                            -1
                                        )
                                    }
                                }
                            }
                        }, appUserViewModel)
                    } catch (e: Exception) {
                        Timber.tag("TweetDropdownMenuItems")
                            .e(e, "Error deleting tweet: ${e.message}")
                    }
                }
            },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp)) // Add some space between the icon and the text
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        )
    }
    // Only author can pin the current Tweet to top list
    if (tweet.authorId == appUser.mid) {
        DropdownMenuItem(
            modifier = Modifier.alpha(1f),
            onClick = {
                appUserViewModel.viewModelScope.launch(IO) {
                    onDismissRequest()
                    appUserViewModel.pinToTop(tweet)
                }
            },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = stringResource(R.string.pin_to_top),
                        tint = MaterialTheme.colorScheme.surfaceTint
                    )
                    Spacer(modifier = Modifier.width(8.dp)) // Add some space between the icon and the text
                    Text(
                        text = if (appUserViewModel.hasPinned(tweet)) stringResource(R.string.unpin)
                        else stringResource(R.string.pin),
                        color = MaterialTheme.colorScheme.surfaceTint
                    )
                }
            }
        )
    }
    DropdownMenuItem(
        modifier = Modifier.alpha(1f),
        onClick = {
            onDismissRequest()
        },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tweet.mid,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    )
}
