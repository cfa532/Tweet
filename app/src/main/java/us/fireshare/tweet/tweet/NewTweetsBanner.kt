package us.fireshare.tweet.tweet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.profile.UserAvatar

@Composable
fun NewTweetsBanner(
    pendingTweets: List<Tweet>,
    visible: Boolean,
    onClick: () -> Unit,
    onAutoHide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pendingTweetIds = pendingTweets.joinToString(separator = "|") { it.mid }
    val authors = pendingTweets
        .map { tweet -> tweet.author ?: User.getInstance(tweet.authorId) }
        .distinctBy { it.mid }
        .take(5)
    val shouldShowTitle = authors.size <= 3
    val countLabel = if (pendingTweets.size > 9) "9+" else pendingTweets.size.toString()

    LaunchedEffect(visible, pendingTweetIds) {
        if (visible && pendingTweets.isNotEmpty()) {
            delay(10_000)
            onAutoHide()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        // Keep the banner below the status bar: the app is edge-to-edge, so without
        // this inset the pill lands in the status-bar gesture region and misses taps.
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 12.dp)
            .wrapContentWidth()
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onClick)
        ) {
            Row(
                modifier = Modifier
                    .padding(start = 12.dp, top = 4.dp, end = 14.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                NewTweetsAvatarCluster(
                    authors = authors,
                    modifier = Modifier
                        .padding(start = 0.dp, end = 5.dp)
                )
                if (shouldShowTitle) {
                    Text(
                        text = stringResource(
                            if (pendingTweets.size == 1) R.string.new_tweets_banner_one
                            else R.string.new_tweets_banner_many,
                            countLabel
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Normal)
                    )
                }
            }
        }
    }
}

@Composable
private fun NewTweetsAvatarCluster(
    authors: List<User>,
    modifier: Modifier = Modifier
) {
    val avatarCount = authors.size
    val avatarSize = 32
    val overlap = 12
    val clusterWidth = avatarSize + (avatarCount - 1) * overlap

    Box(
        modifier = modifier
            .width(clusterWidth.dp)
            .height(avatarSize.dp)
    ) {
        authors.forEachIndexed { index, author ->
            Box(
                modifier = Modifier
                    .offset(x = (index * overlap).dp)
                    .zIndex((avatarCount - index).toFloat())
                    .size(avatarSize.dp)
            ) {
                UserAvatar(user = author, size = avatarSize)
            }
        }
    }
}
