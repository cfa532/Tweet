package us.fireshare.tweet.tweet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.profile.SimpleAvatar
import us.fireshare.tweet.viewmodel.TweetViewModel
import java.util.concurrent.TimeUnit

// Tweet header when displayed as an item in a list.
@Composable
fun TweetItemHeader(
    viewModel: TweetViewModel,
    parentEntry: NavBackStackEntry,
    parentTweet: Tweet? = null,
) {
    val navController = LocalNavController.current
    val tweet by viewModel.tweetState.collectAsState()
    val author by remember { derivedStateOf { tweet.author } }

    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(end = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            /**
             * Navigate to the user's profile when avatar is tapped.
             * Simplified logic to always navigate to avoid potential issues.
             * */
            IconButton(
                onClick = {
                    // Always navigate to the user's profile when avatar is tapped
                    navController.navigate(NavTweet.UserProfile(tweet.authorId))
                },
                modifier = Modifier.width(40.dp)
            ) {
                author?.let { SimpleAvatar(user = it, size = 32) }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset(y = (-8).dp)
            ) {
                Text(text = author?.name ?: "No One",
                    modifier = Modifier.padding(start = 2.dp),
                    style = MaterialTheme.typography.labelLarge)
                Text(text = "@${author?.username}",
                    modifier = Modifier.padding(horizontal = 0.dp),
                    style = MaterialTheme.typography.labelMedium)
                Text( text = " • ", fontSize = 12.sp)
                Text(text = localizedTimeDifference(tweet.timestamp),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        /**
         * The 3 dots at the right end - aligned with tweet body content
         * */
        TweetDropdownMenu(tweet, parentEntry, parentTweet)
    }
}

@Composable
fun localizedTimeDifference(timestamp: Long): String {
    val currentTime = System.currentTimeMillis()
    val diffInMillis = currentTime - timestamp

    val seconds = TimeUnit.MILLISECONDS.toSeconds(diffInMillis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diffInMillis)
    val hours = TimeUnit.MILLISECONDS.toHours(diffInMillis)
    val days = TimeUnit.MILLISECONDS.toDays(diffInMillis)
    val weeks = days / 7
    val months = days / 30
    val years = days / 365

    return when {
        seconds < 60 -> stringResource(id = R.string.seconds_ago, seconds)
        minutes < 60 -> stringResource(id = R.string.minutes_ago, minutes)
        hours < 24 -> stringResource(id = R.string.hours_ago, hours)
        days < 7 -> stringResource(id = R.string.days_ago, days)
        weeks < 4 -> stringResource(id = R.string.weeks_ago, weeks)
        months < 12 -> stringResource(id = R.string.months_ago, months+1)
        else -> stringResource(id = R.string.years_ago, years)
    }
}