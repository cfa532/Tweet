package com.fireshare.tweet.tweet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.HproseInstance.getMediaUrl
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MimeiFileType
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.MediaViewerParams
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.navigation.SharedViewModel
import com.fireshare.tweet.share.ShareScreenshotButton
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.Gadget.buildAnnotatedText
import com.fireshare.tweet.widget.MediaItem
import com.fireshare.tweet.widget.MediaItemView
import com.fireshare.tweet.widget.UserAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TweetDetailBody(
    viewModel: TweetViewModel,
    parentEntry: NavBackStackEntry,
    gridColumns: Int)
{
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
            Surface(
                shape = MaterialTheme.shapes.small, // Inner border
                modifier = Modifier
                    .padding(start = 0.dp, top = 0.dp, bottom = 0.dp, end = 4.dp)
            ) {
                Column {
                     tweet.content?.let {
                        SelectableText(it,
                            modifier = Modifier.padding(bottom = 8.dp)
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
                    tweet.attachments?.let {
                        MediaGrid(it, viewModel, navController, gridColumns)
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
    parentTweet: Tweet? = null
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
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val appUserViewModel = sharedViewModel.appUserViewModel
    val tweetFeedViewModel = sharedViewModel.tweetFeedViewModel
    val navController = LocalNavController.current

    // Only author can delete a tweet, but if the tweet is pinned to top, it can't be deleted
    // unless the user unpins it first.
    if (tweet.authorId == appUser.mid && !appUserViewModel.hasPinned(tweet)) {
        val originTweetViewModel = if (tweet.originalTweetId != null) {
            hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                parentEntry, key = tweet.originalTweetId
            ) { factory -> factory.create(tweet.originalTweet!!) }
        } else null
//        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        DropdownMenuItem(
            modifier = Modifier.alpha(0.8f),
            onClick = {
                appUserViewModel.viewModelScope.launch(Dispatchers.IO) {
                    tweetFeedViewModel.delTweet(tweet) {
                        // if this a re-tweet, refresh the original tweet after deletion.
                        originTweetViewModel?.viewModelScope?.launch(Dispatchers.IO) {
                            originTweetViewModel.refreshTweet()
                        }
                    }
                    // if current route is TweetDetail. Go back to TweetFeed
                    if (navController.currentDestination?.route?.contains("TweetDetail") == true) {
                        withContext(Dispatchers.Main) {
                            navController.popBackStack()
                        }
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
                appUserViewModel.viewModelScope.launch(Dispatchers.IO) {
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
            },
        )
    }
}

@Composable
fun SelectableText(text: String,
                   maxLines: Int = Int.MAX_VALUE,
                   modifier: Modifier = Modifier,
                   style: TextStyle = MaterialTheme.typography.bodyLarge,
                   color: Color = MaterialTheme.colorScheme.onSurface,
                   callback: (String)->Unit = {})
{
    // fold text content up to 10 lines. Open it upon user click.
    var isExpanded by remember { mutableStateOf(false) }
    var lineCount by remember { mutableIntStateOf(0) }

    val annotatedText = buildAnnotatedText(text)
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    SelectionContainer {
        Text(
            text = annotatedText,
            maxLines = if (isExpanded) Int.MAX_VALUE else maxLines,
            onTextLayout = { textLayoutResult ->
                lineCount = textLayoutResult.lineCount
                layoutResult = textLayoutResult
            },
            style = style,
            color = color,
            modifier = modifier
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        layoutResult?.let { textLayoutResult ->
                            val position = textLayoutResult.getOffsetForPosition(offset)
                            val annotations = annotatedText.getStringAnnotations(
                                tag = "USERNAME_CLICK",
                                start = position,
                                end = position
                            )
                            if (annotations.isNotEmpty()) {
                                val username = annotations[0].item
                                callback(username)  // navigate to the user account
                            }
                        }
                    }
                },
        )
    }
    if (!isExpanded && lineCount >= maxLines) {
        Text(
            text = stringResource(R.string.show_more),
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary),
            modifier = modifier.clickable {
                isExpanded = true
            }
        )
    }
}