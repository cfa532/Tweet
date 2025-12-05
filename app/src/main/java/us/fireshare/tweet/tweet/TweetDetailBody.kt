package us.fireshare.tweet.tweet

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.getMediaUrl
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.profile.UserAvatar
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.AudioPlayer
import us.fireshare.tweet.widget.SelectableText

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun TweetDetailBody(
    viewModel: TweetViewModel,
    parentEntry: NavBackStackEntry,
    onExpandReply: (() -> Unit)? = null
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
                // the 3 dots at the right end
                TweetDropdownMenu(tweet, parentEntry, context = "default")
            }
            // Tweet detail's content
            Surface(
                shape = MaterialTheme.shapes.small, // Inner border
                modifier = Modifier
                    .padding(start = 4.dp, top = 0.dp, bottom = 0.dp, end = 4.dp)
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
                        } else {
                            AttachmentBrowser(
                                mediaItems = attachments,
                                viewModel = viewModel
                            )
                        }
                    }

                    // This is a retweet. Display the original tweet in quote box.
                    if (tweet.originalTweetId != null) {
                        // Load original tweet dynamically
                        var originalTweet by remember { mutableStateOf<Tweet?>(null) }
                        var isLoadingOriginal by remember { mutableStateOf(true) }
                        
                        val currentTweet by viewModel.tweetState.collectAsState()
                        
                        LaunchedEffect(tweet.originalTweetId, currentTweet) {
                            if (tweet.originalTweetId != null && tweet.originalAuthorId != null) {
                                // Store IDs in local variables to avoid smart cast issues
                                val originalTweetId = tweet.originalTweetId ?: ""
                                val originalAuthorId = tweet.originalAuthorId ?: ""
                                
                                // Force refresh of original tweet by using refreshTweet directly
                                // This ensures we get the latest version from backend
                                originalTweet = HproseInstance.refreshTweet(
                                    originalTweetId,
                                    originalAuthorId
                                )
                                isLoadingOriginal = false
                            }
                        }
                        
                        if (isLoadingOriginal) {
                            // Show loading state for quoted tweet with spinner
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                tonalElevation = 2.dp,
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp, end = 0.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        } else if (originalTweet != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                tonalElevation = 2.dp,
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp, end = 0.dp)
                            ) {
                                // Use activity scope to ensure same ViewModel instance is shared
                                val activity = LocalActivity.current as ComponentActivity
                                TweetItemBody(
                                    hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                                        viewModelStoreOwner = activity, key = tweet.originalTweetId
                                    ) { factory -> factory.create(originalTweet!!) },
                                    parentEntry = parentEntry,
                                    isQuoted = true
                                )
                            }
                        } else {
                            // Original tweet not available - this quoted tweet should be removed from the list
                            // Return empty content to effectively hide this item
                            Box(modifier = Modifier.size(0.dp))
                        }
                    }

                    // Actions Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        // State hoist
                        CommentButton(viewModel, onExpandReply = onExpandReply)
                        RetweetButton(viewModel)
                        LikeButton(viewModel)
                        BookmarkButton(viewModel)
                        Spacer(modifier = Modifier.width(20.dp))
                        ShareButton(viewModel)
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AttachmentBrowser(
    mediaItems: List<MimeiFileType>,
    viewModel: TweetViewModel
) {
    // Stabilize attachments to prevent recomposition issues (like iOS implementation)
    val stableAttachments = remember(mediaItems.map { it.mid }) {
        mediaItems
    }
    
    // Calculate fixed aspect ratio to prevent height jumping (like iOS calculateFixedAspectRatio)
    val fixedAspectRatio = remember(stableAttachments.map { it.mid }) {
        calculateFixedAspectRatio(stableAttachments)
    }
    
    val pagerState = rememberPagerState(pageCount = { stableAttachments.size })

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            userScrollEnabled = stableAttachments.size > 1
        ) { page ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(fixedAspectRatio)
            ) {
                MediaItemView(
                    mediaItems = stableAttachments,
                    modifier = Modifier.fillMaxSize(),
                    index = page,
                    autoPlay = pagerState.currentPage == page,
                    inPreviewGrid = false,
                    loadOriginalImage = false,
                    viewModel = viewModel,
                    onVideoCompleted = null,
                    useIndependentVideoMute = true,
                    enableTapToShowControls = true
                )
            }
        }

        if (stableAttachments.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(stableAttachments.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (isSelected) 10.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            )
                    )
                }
            }
        }
    }
}

/**
 * Calculate a fixed aspect ratio for all attachments to prevent height jumping.
 * Similar to iOS calculateFixedAspectRatio implementation:
 * - If all same orientation, use average
 * - If mixed orientations, use minimum aspect ratio (ensures container is tall enough)
 * - Clamp to reasonable bounds (0.5 to 2.0)
 */
private fun calculateFixedAspectRatio(attachments: List<MimeiFileType>): Float {
    if (attachments.isEmpty()) return 1.0f
    
    // Collect all aspect ratios
    val aspectRatios = attachments.map { attachment ->
        when {
            attachment.type == MediaType.Video || attachment.type == MediaType.HLS_VIDEO -> {
                attachment.aspectRatio?.takeIf { it > 0f } ?: (4f / 3f)
            }
            attachment.type == MediaType.Image -> {
                attachment.aspectRatio?.takeIf { it > 0f } ?: 1.0f
            }
            else -> 1.0f
        }
    }
    
    // Separate portrait and landscape
    val portraits = aspectRatios.filter { it < 1.0f }
    val landscapes = aspectRatios.filter { it >= 1.0f }
    
    // If all are same orientation, use average
    if (portraits.isEmpty() || landscapes.isEmpty()) {
        val average = aspectRatios.average().toFloat()
        // Clamp to reasonable bounds (0.5 to 2.0)
        return maxOf(0.5f, minOf(2.0f, average))
    }
    
    // Mixed orientations: use the minimum aspect ratio
    // This ensures the container is tall enough for all content
    // (minimum aspect ratio = tallest content = maximum height needed)
    val minAspectRatio = aspectRatios.minOrNull() ?: 1.0f
    
    // Clamp to reasonable bounds
    return maxOf(0.5f, minOf(2.0f, minAspectRatio))
}

@Composable
fun TweetDropdownMenu(
    tweet: Tweet,
    parentEntry: NavBackStackEntry,
    parentTweet: Tweet? = null,
    context: String = "default"
) {
    // Use tweet.mid as key to ensure state is reset when tweet changes
    var expanded by remember(tweet.mid) { mutableStateOf(false) }

    // Dismiss popup menu when tweet is deleted or becomes unavailable
    LaunchedEffect(tweet.mid) {
        // Reset expanded state when tweet changes
        expanded = false
    }

    Box(
        modifier = Modifier.padding(end = 8.dp)
    ) {
        IconButton(
            modifier = Modifier
                .size(16.dp)
                .alpha(0.7f),
            onClick = { expanded = !expanded }) {
            Icon(
                painter = painterResource(R.drawable.ellipsis),
                contentDescription = stringResource(R.string.more_options),
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
                // this is a retweet. Show menu for the retweet if current user is the retweet author
                if (parentTweet.authorId == appUser.mid) {
                    TweetDropdownMenuItems(parentTweet, parentEntry, {
                        expanded = false
                    }, context)
                }
            } else {
                TweetDropdownMenuItems(tweet, parentEntry, {
                    expanded = false
                }, context)
            }
        }
    }
}
