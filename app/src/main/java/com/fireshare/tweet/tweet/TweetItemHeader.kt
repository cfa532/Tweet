package com.fireshare.tweet.tweet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.widget.UserAvatar
import java.util.concurrent.TimeUnit

// Tweet header when displayed as an item in a list.
@Composable
fun TweetItemHeader(tweet: Tweet) {
    // Use a Row to align author name and potential verification badge
    val navController = LocalNavController.current
    val author = tweet.author

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.navigate(NavTweet.UserProfile(tweet.authorId)) })
            {
                UserAvatar(author, 36)
            }
            Text(text = author?.name ?: "No One",
                modifier = Modifier.padding(horizontal = 0.dp),
                style = MaterialTheme.typography.labelMedium)
            Text(text = "@${author?.username}",
                modifier = Modifier.padding(horizontal = 2.dp),
                style = MaterialTheme.typography.labelSmall)
            Text( text = " • ", fontSize = 12.sp
            )
            Text(text = localizedTimeDifference(tweet.timestamp),
                style = MaterialTheme.typography.labelSmall)
        }

        // the 3 dots at the right end
        TweetDropdownMenu(tweet, navController)
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
        months < 12 -> stringResource(id = R.string.months_ago, months)
        else -> stringResource(id = R.string.years_ago, years)
    }
}