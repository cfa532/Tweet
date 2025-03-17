package us.fireshare.tweet.tweet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.dao
import us.fireshare.tweet.HproseInstance.getMediaUrl
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaItem
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.MediaViewerParams
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.profile.UserAvatar
import us.fireshare.tweet.share.ShareScreenshotButton
import us.fireshare.tweet.viewmodel.TweetFeedViewModel
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.AudioPlayer
import us.fireshare.tweet.widget.MediaItemView
import us.fireshare.tweet.widget.SelectableText

@Composable
fun TweetDetailBody(
    viewModel: TweetViewModel,
    parentEntry: NavBackStackEntry,
    gridColumns: Int,
) {
    val tweet by viewModel.tweetState.collectAsState()
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
                        UserAvatar(user = author ?: User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl), size = 40)
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
            Surface(
                shape = MaterialTheme.shapes.small, // Inner border
                modifier = Modifier
                    .padding(start = 0.dp, top = 0.dp, bottom = 0.dp, end = 4.dp)
            ) {
                Column {
                     tweet.content?.let {
                        SelectableText(
                            modifier = Modifier.padding(bottom = 8.dp),
                            text = it
                        ) { username ->
                            viewModel.viewModelScope.launch(IO) {
                                HproseInstance.getUserId(username)?.let {
                                    withContext(Dispatchers.Main) {
                                        navController.navigate(NavTweet.UserProfile(it))
                                    }
                                }
                            }
                        }
                    }
                    // if all attachments are audio files

                    if (! tweet.attachments.isNullOrEmpty()) {
                        val attachments = tweet.attachments!!
                        val isAllAudio = attachments.all { it.type == MediaType.Audio }
                        if (isAllAudio) {
                            attachments.forEach {
                                it.url = getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty())
                            }
                            AudioPlayer(attachments)
                        } else
                            MediaGrid(attachments, viewModel, navController, gridColumns)
                    }

                    // This is a retweet. Display the original tweet in quote box.
                    if (tweet.originalTweet != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            tonalElevation = 2.dp,
                            modifier = Modifier.padding(start = 8.dp, top = 12.dp, end = 0.dp)
                        ) {
                            TweetItemBody(
                                hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                                    parentEntry, key = tweet.originalTweetId
                                ) { factory -> factory.create(tweet.originalTweet!!) },
                                true,
                                parentEntry
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
fun MediaGrid(
    mediaItems: List<MimeiFileType>,
    viewModel: TweetViewModel,
    navController: NavController,
    gridColumns: Int, containerWidth: Dp = 400.dp
) {
    val tweet by viewModel.tweetState.collectAsState()
    Box(
        modifier = Modifier
            .padding(top = 0.dp)
            .fillMaxWidth()
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns),
            modifier = Modifier
                .padding(top = 0.dp)
                .fillMaxWidth()
                .background(Color.Transparent)
                .border(1.dp, Color.Transparent)
                .heightIn(max = 20000.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val modifier =
                if (gridColumns == 1)
                    Modifier.fillMaxWidth()
                else Modifier.size(containerWidth / gridColumns)
            itemsIndexed(mediaItems) { index, _ ->
                MediaItemView(
                    mediaItems,
                    modifier.clickable {
                        val params =MediaViewerParams(
                            mediaItems.map {
                                MediaItem(
                                    getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty()).toString(),
                                    it.type
                                )
                            }, index, tweet.mid, tweet.authorId)
                        navController.navigate(
                            NavTweet.MediaViewer(params)
                        )
                    },
                    index,
                    0,
                    autoPlay = true,
                    inPreviewGrid = false,
                    viewModel
                )
            }
        }
    }
}

@Composable
fun TweetDropdownMenu(
    tweet: Tweet,
    parentEntry: NavBackStackEntry,
    parentTweet: Tweet? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            modifier = Modifier
                .width(32.dp)
                .alpha(0.8f)
                .rotate(-90f)
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
                .border(
                    width = 1.dp, // Border width
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ),
        ) {
            if (parentTweet != null) {
                // this is a retweet. Allow the author to delete it.
                if (parentTweet.authorId == appUser.mid) {
                    TweetDropdownMenuItems(parentTweet, parentEntry) {
                        expanded = false
                    }
                }
            } else {
                TweetDropdownMenuItems(tweet, parentEntry) {
                    expanded = false
                }
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
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val appUserViewModel = sharedViewModel.appUserViewModel
    val context = LocalContext.current
    val navController = LocalNavController.current
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()
    val originTweetViewModel = if (tweet.originalTweetId != null && tweet.originalTweet != null) {
        hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
            parentEntry, key = tweet.originalTweetId
        ) { factory -> factory.create(tweet.originalTweet!!) }
    } else null

    // Only author can delete a tweet
    if (tweet.authorId == appUser.mid) {
        DropdownMenuItem(
            modifier = Modifier.alpha(0.8f),
            onClick = {
                tweetFeedViewModel.delTweet(context, tweet.mid) {
                    tweetFeedViewModel.viewModelScope.launch(IO) {
                        originTweetViewModel?.updateRetweetCount(
                            tweet.originalTweet!!,      // original tweet
                            tweet.mid,      // retweet Id
                            -1
                        )
                    }
                    if (navController.currentDestination?.route?.contains("TweetDetail") == true) {
                        navController.popBackStack()
                    } else {
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
                appUserViewModel.viewModelScope.launch(IO) {
                    appUserViewModel.pinToTop(tweet.mid)
                    onDismissRequest()
                }
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
