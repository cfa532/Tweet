package us.fireshare.tweet.tweet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.profile.UserAvatar
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.MediaPreviewGrid
import us.fireshare.tweet.widget.SelectableText
import java.util.concurrent.TimeUnit

@Composable
fun TweetItemBody(
    viewModel: TweetViewModel,
    modifier: Modifier = Modifier,
    isQuoted: Boolean = false,     // the block is a quoted tweet or not
    parentEntry: NavBackStackEntry,
    parentTweet: Tweet? = null,    // the parent tweet of the quoted original tweet
) {
    val navController = LocalNavController.current
    val tweet by viewModel.tweetState.collectAsState()
    val author by remember { derivedStateOf { tweet.author } }

    // fold text content up to 9 lines. Open it upon user click.
    Surface(
        // Apply border to the entire TweetBlock
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .padding(start = 0.dp, end = 8.dp)
            .clickable(onClick = {
                // necessary to deal with corrupted data.
                if (tweet.authorId != null && tweet.mid != null)
                    navController.navigate(NavTweet.TweetDetail(tweet.authorId, tweet.mid))
            })
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            // Left column: Avatar
            Column(
                modifier = Modifier.padding(top = 0.dp)
            ) {
                IconButton(
                    onClick = {
                        navController.navigate(NavTweet.UserProfile(tweet.authorId))
                    },
                    modifier = Modifier.width(44.dp)
                ) {
                    author?.let { UserAvatar(user = it, size = 32) }
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
                    months < 12 -> stringResource(id = R.string.months_ago, months + 1)
                    else -> stringResource(id = R.string.years_ago, years)
                }
            }

            // Right column: User info, content, and actions
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Top row: User info and dropdown menu
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    // User info text
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = author?.name ?: "No One",
                            modifier = Modifier.padding(start = 2.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = "@${author?.username}",
                            modifier = Modifier.padding(horizontal = 0.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(text = " • ", fontSize = 12.sp)
                        Text(
                            text = localizedTimeDifference(tweet.timestamp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    // Dropdown menu
                    TweetDropdownMenu(tweet, parentEntry, parentTweet)
                }

                // Tweet content
                Surface(
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .offset(y = (-20).dp)
                        .padding(start = 4.dp)
                        .fillMaxWidth()
                ) {
                    Column {
                        // Text content of the tweet
                        tweet.content?.let {
                            SelectableText(
                                text = it,
                                maxLines = 10,
                            ) { username ->
                                viewModel.viewModelScope.launch(Dispatchers.IO) {
                                    HproseInstance.getUserId(username)?.let {
                                        withContext(Dispatchers.Main) {
                                            navController.navigate(NavTweet.UserProfile(it))
                                        }
                                    }
                                }
                            }
                        }

                        // Media files
                        if (!tweet.attachments.isNullOrEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .heightIn(min = 20.dp, max = 400.dp),
                                tonalElevation = 4.dp,
                                shape = RoundedCornerShape(size = 8.dp)
                            ) {
                                MediaPreviewGrid(tweet.attachments!!, viewModel)
                            }
                        }

                        // Action buttons (only if not quoted)
                        if (!isQuoted) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 0.dp),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                LikeButton(viewModel)
                                BookmarkButton(viewModel)
                                CommentButton(viewModel)
                                RetweetButton(viewModel)
                                Spacer(modifier = Modifier.width(40.dp))
                                ShareButton(viewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}
