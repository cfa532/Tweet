package us.fireshare.tweet.tweet

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/**
 * Shorten tweet ID by showing first 6 and last 6 characters with ellipsis in the middle
 */
private fun shortenTweetId(tweetId: String): String {
    return if (tweetId.length > 12) {
        "${tweetId.take(6)}...${tweetId.takeLast(6)}"
    } else {
        tweetId
    }
}

@Composable
fun TweetDropdownMenuItems(
    tweet: Tweet,
    parentEntry: NavBackStackEntry,
    onDismissRequest: () -> Unit,
    contextType: String = "default" // Context to determine where this dropdown is shown
) {
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val appUserViewModel = sharedViewModel.appUserViewModel

    // Use the singleton TweetFeedViewModel from AppModule
    val tweetFeedViewModel: TweetFeedViewModel = hiltViewModel()
    val context = LocalContext.current
    // Capture string resources at composable level to avoid Android Studio warnings
    val deleteTweetText = stringResource(R.string.delete_tweet)
    val deleteFailedText = stringResource(R.string.delete_failed)
    val networkErrorText = stringResource(R.string.network_error_connection_lost)

    // Show delete button based on context
    val shouldShowDeleteButton = when (contextType) {
        "followingsTweet" -> true // Show delete for all tweets in main feed
        "appUserProfile" -> tweet.authorId == appUser.mid // Show delete only for app user's tweets in profile
        "tweetDetail" -> tweet.authorId == appUser.mid // Show delete for user's own tweets in detail view
        else -> false // Don't show delete in other contexts
    }
    val originTweetViewModel = if (tweet.originalTweetId != null) hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
        parentEntry, key = tweet.originalTweetId
    ) { factory ->
        factory.create(Tweet.getInstance(tweet.originalTweetId!!, tweet.originalAuthorId!!))
    } else null

    if (shouldShowDeleteButton) {
        DropdownMenuItem(
            modifier = Modifier.alpha(0.8f),
            onClick = {
                // Validate that we can actually delete this tweet
                if (tweet.mid.isBlank()) {
                    Timber.tag("TweetDropdownMenuItems").w("Cannot delete tweet: tweet.mid is null or blank")
                    Toast.makeText(
                        context,
                        deleteFailedText,
                        Toast.LENGTH_SHORT
                    ).show()
                    onDismissRequest()
                    return@DropdownMenuItem
                }

                // Check if hproseService is available
                if (appUser.hproseService == null) {
                    Timber.tag("TweetDropdownMenuItems").w("Cannot delete tweet: hproseService is null")
                    Toast.makeText(
                        context,
                        networkErrorText,
                        Toast.LENGTH_LONG
                    ).show()
                    onDismissRequest()
                    return@DropdownMenuItem
                }

                // Dismiss menu immediately for better UX
                onDismissRequest()

                // OPTIMISTIC DELETE: Tweet will be removed from UI immediately
                // If deletion fails, it will be restored and error shown
                applicationScope.launch(IO) {
                    try {
                        Timber.tag("TweetDropdownMenuItems").d("Starting optimistic deletion of tweet: ${tweet.mid}")
                        
                        tweetFeedViewModel.delTweet(tweet.mid, appUserViewModel) {
                            // Deletion completed successfully
                            Timber.tag("TweetDropdownMenuItems").d("Tweet ${tweet.mid} deleted successfully")

                            // Update retweet count of original tweet if this is a retweet
                            if (originTweetViewModel != null) {
                                applicationScope.launch(IO) {
                                    HproseInstance.updateRetweetCount(originTweetViewModel.tweetState.value, tweet.mid, -1)?.let { updatedOriginTweet ->
                                        // Cache updated original tweet by authorId (matches iOS)
                                        HproseInstance.updateCachedTweet(updatedOriginTweet, userId = updatedOriginTweet.authorId)
                                        originTweetViewModel.updateRetweetCount(updatedOriginTweet)
                                    }
                                }
                            }
                            
                            // Show success message on Main thread
                            applicationScope.launch(Main) {
                                Toast.makeText(
                                    context,
                                    deleteTweetText,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        // Deletion failed - tweet has been restored
                        Timber.tag("TweetDropdownMenuItems")
                            .e(e, "Failed to delete tweet: ${e.message}")
                        
                        // Show error toast on Main thread
                        withContext(Main) {
                            val errorMessage = e.message ?: deleteFailedText
                            // Clean up the error message
                            val displayMessage = when {
                                errorMessage.contains("Failed to delete tweet:") -> {
                                    // Extract the actual error after the prefix
                                    errorMessage.substringAfter("Failed to delete tweet: ").let {
                                        if (it.length > 80) it.take(80) + "..." else it
                                    }
                                }
                                errorMessage.length > 80 -> errorMessage.take(80) + "..."
                                else -> errorMessage
                            }
                            
                            Toast.makeText(
                                context,
                                "$deleteFailedText: $displayMessage",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Throwable) {
                        // Catch any other errors (including cancellation exceptions)
                        Timber.tag("TweetDropdownMenuItems")
                            .e(e, "Unexpected error deleting tweet: ${e.message}")
                        
                        withContext(Main) {
                            Toast.makeText(
                                context,
                                deleteFailedText,
                                Toast.LENGTH_LONG
                            ).show()
                        }
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
            // Copy tweet ID to clipboard
            val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText("Tweet ID", tweet.mid)
            clipboardManager.setPrimaryClip(clipData)
            
            // Show confirmation toast
            Toast.makeText(
                context,
                "Tweet ID copied to clipboard",
                Toast.LENGTH_SHORT
            ).show()
            
            onDismissRequest()
        },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.copy_tweet_id),
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.width(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = shortenTweetId(tweet.mid),
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    )
}
