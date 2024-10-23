package com.fireshare.tweet.tweet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.HproseInstance.getMediaUrl
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.MediaViewerParams
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.share.ShareScreenshotButton
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.viewmodel.UserViewModel
import com.fireshare.tweet.widget.MediaItem
import com.fireshare.tweet.widget.MediaItemPreview
import com.fireshare.tweet.widget.UserAvatar

@Composable
fun TweetDetailBody(tweet: Tweet, viewModel: TweetViewModel, parentEntry: NavBackStackEntry) {
    val navController = LocalNavController.current

    Surface(
        // Apply border to the entire TweetBlock
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            // Tweet detail Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    val author = tweet.author
                    IconButton(onClick = {
                        navController.navigate(NavTweet.UserProfile(tweet.authorId))
                    }) {
                        UserAvatar(author, 40)
                    }
                    Text(
                        text = author?.name ?: "No One",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                    Text(text = "@${author?.username}", style = MaterialTheme.typography.bodySmall)
                }
                // the 3 dots at the right end
                TweetDropdownMenu(tweet, parentEntry)
            }
            // Tweet detail's content
//            Spacer(modifier = Modifier.padding(2.dp))
            Surface(
                shape = MaterialTheme.shapes.small, // Inner border
                modifier = Modifier
                    .padding(start = 16.dp, top = 0.dp, bottom = 0.dp, end = 0.dp)
            ) {
                Column {
                    if (tweet.content?.isNotEmpty() == true) {
                        Text(text = tweet.content!!,
                            modifier = Modifier.padding(bottom = 8.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    val mediaItems = tweet.attachments?.mapNotNull {
                        tweet.author?.baseUrl?.let { it1 -> getMediaUrl(it.mid, it1).toString() }
                            ?.let { it2 -> MediaItem(it2, it.type) }
                    }
                    mediaItems?.let {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(max = 800.dp) // Set a specific height for the grid
                        ) {
                            itemsIndexed(it as List<MediaItem>) { index, mi ->
                                MediaItemPreview(
                                    it,
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            val params =
                                                MediaViewerParams(mediaItems, index, tweet.mid!!)
                                            navController.navigate(
                                                NavTweet.MediaViewer(params)
                                            )
                                        },
                                    false, index, false, tweet.mid
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 1.dp),
                                    thickness = 0.8.dp,
                                    color = Color.LightGray
                                )
                            }
                        }
                    }

                    // This is a retweet
                    if (tweet.originalTweet != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
//                            border = BorderStroke(0.4.dp,
//                                color = MaterialTheme.colorScheme.surfaceTint),
                            tonalElevation = 2.dp,
                            modifier = Modifier.padding(start = 8.dp, top = 12.dp, end = 0.dp)
                        ) {
                            TweetBlock(
                                hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                                    parentEntry, key = tweet.originalTweetId
                                ) { factory -> factory.create(tweet.originalTweet!!) },
                                parentEntry,
                                true
                            )
                        }
                    }

                    // Actions Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        // State hoist
                        LikeButton(viewModel)
                        BookmarkButton(viewModel)
                        CommentButton(viewModel)
                        RetweetButton(viewModel)
                        Spacer(modifier = Modifier.width(20.dp))
                        ShareScreenshotButton(viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun TweetDropdownMenu(
    tweet: Tweet,
    parentEntry: NavBackStackEntry,
    parentTweet: Tweet? = null
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            modifier = Modifier.width(32.dp).alpha(0.8f).rotate(-90f)
                .padding(end = 12.dp),
            onClick = { expanded = !expanded }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .wrapContentWidth(align = Alignment.End)
                .height(IntrinsicSize.Min)
        ) {
            if (parentTweet != null) {
                // this is a retweet.
                if (parentTweet.authorId == appUser.mid) {
                    TweetDropdownMenuItems(parentTweet, parentEntry) { expanded = false }
                }
            } else {
                TweetDropdownMenuItems(tweet, parentEntry) { expanded = false }
            }
        }
    }
}

@Composable
fun TweetDropdownMenuItems(
    tweet: Tweet,
    parentEntry: NavBackStackEntry,
    onDismissRequest: () -> Unit,
) {
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()
    val appUserViewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
        parentEntry, key = appUser.mid
    ) { factory ->
        factory.create(appUser.mid)
    }
    val navController = LocalNavController.current

    // Only author can delete a tweet, but if the tweet is pinned to top, it can't be deleted
    // unless the user unpins it first.
    if (tweet.authorId == appUser.mid && !appUserViewModel.hasPinned(tweet)) {
        DropdownMenuItem(
            modifier = Modifier.alpha(0.8f),
            onClick = {
                tweet.mid?.let {
                    tweetFeedViewModel.delTweet(it)
                    // if current route is TweetDetail. Go to TweetFeed
                    if (navController.currentDestination?.route?.contains("TweetDetail") == true) {
                        navController.navigate(NavTweet.TweetFeed)
                    } else {
                        // close the dropdown menu
                        onDismissRequest()
                    }
                }
            },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete",
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
                appUserViewModel.pinToTop(tweet)
                onDismissRequest()
            },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "Pin to top",
                        tint = MaterialTheme.colorScheme.surfaceTint
                    )
                    Spacer(modifier = Modifier.width(8.dp)) // Add some space between the icon and the text
                    Text(
                        text = if (appUserViewModel.hasPinned(tweet)) stringResource(R.string.unpin)
                        else stringResource(R.string.pinToTop),
                        color = MaterialTheme.colorScheme.surfaceTint
                    )
                }
            }
        )
    }
}