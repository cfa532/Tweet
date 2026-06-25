package us.fireshare.tweet.tweet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
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
    val avatarItems = newTweetsAvatarItems(pendingTweets)
    val shouldShowTitle = avatarItems.size <= 3

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
        modifier = modifier
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
                    .height(44.dp)
                    .padding(start = 12.dp, end = 14.dp),
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
                    avatarItems = avatarItems,
                    modifier = Modifier
                        .padding(start = 0.dp, end = 5.dp)
                )
                if (shouldShowTitle) {
                    Text(
                        text = if (pendingTweets.size == 1) "1 new tweet" else "${pendingTweets.size} new tweets",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Normal)
                    )
                }
            }
        }
    }
}

private data class AvatarClusterItem(
    val user: User?,
    val opacity: Float
)

private fun newTweetsAvatarItems(pendingTweets: List<Tweet>): List<AvatarClusterItem> {
    val authors = pendingTweets
        .map { tweet -> tweet.author ?: User.getInstance(tweet.authorId) }
        .distinctBy { it.mid }

    if (authors.isEmpty()) {
        return listOf(AvatarClusterItem(user = null, opacity = 1f))
    }

    if (authors.size <= 5) {
        return authors.map { author ->
            AvatarClusterItem(user = author, opacity = 1f)
        }
    }

    return authors.take(2).map { author ->
        AvatarClusterItem(user = author, opacity = 1f)
    } + listOf(
        AvatarClusterItem(user = null, opacity = 0.42f),
        AvatarClusterItem(user = null, opacity = 0.42f),
        AvatarClusterItem(user = authors.last(), opacity = 1f)
    )
}

@Composable
private fun NewTweetsAvatarCluster(
    avatarItems: List<AvatarClusterItem>,
    modifier: Modifier = Modifier
) {
    val avatarCount = avatarItems.size.coerceAtLeast(1)
    val avatarSize = 32
    val overlap = 12
    val clusterWidth = avatarSize + (avatarCount - 1) * overlap

    Box(
        modifier = modifier
            .width(clusterWidth.dp)
            .height(avatarSize.dp)
    ) {
        avatarItems.forEachIndexed { index, item ->
            NewTweetsAvatar(
                item = item,
                modifier = Modifier
                    .offset(x = (index * overlap).dp)
                    .zIndex((avatarCount - index).toFloat())
            )
        }
    }
}

@Composable
private fun NewTweetsAvatar(
    item: AvatarClusterItem,
    modifier: Modifier = Modifier
) {
    if (item.user != null) {
        Box(
            modifier = modifier
                .size(32.dp)
                .alpha(item.opacity)
        ) {
            UserAvatar(user = item.user, size = 32)
        }
    } else {
        Box(
            modifier = modifier
                .size(32.dp)
                .alpha(item.opacity)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}
