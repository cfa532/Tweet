package us.fireshare.tweet.tweet

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.profile.UserAvatar
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.AudioPlayer
import us.fireshare.tweet.widget.DocumentAttachmentsView
import us.fireshare.tweet.widget.SelectableText
import us.fireshare.tweet.widget.inferMediaTypeFromAttachment

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun TweetDetailBody(
    viewModel: TweetViewModel,
    parentEntry: NavBackStackEntry,
    parentTweetId: String? = null,
    parentAuthorId: String? = null,
    onExpandReply: (() -> Unit)? = null,
    onVideoVisibilityChanged: ((Boolean) -> Unit)? = null
) {
    val tweet by viewModel.tweetState.collectAsState()
    val navController = LocalNavController.current
    
    // Observe author changes reactively via StateFlow
    // This ensures the tweet detail updates when user data becomes available
    val authorStateFlow = remember(tweet.authorId) {
        TweetCacheManager.getUserStateFlow(tweet.authorId)
    }
    val author by authorStateFlow.collectAsState()

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
                    IconButton(onClick = {
                        navController.navigate(NavTweet.UserProfile(tweet.authorId))
                    }) {
                        UserAvatar(user = author ?: User(mid = TW_CONST.GUEST_ID, baseUrl = appUser.baseUrl), size = 40)
                    }
                    Text(
                        text = author?.name ?: "No One",
                        modifier = Modifier.padding(start = 2.dp),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = " @${author?.username ?: "unknown"}",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = " · ${localizedTimeDifference(tweet.timestamp)}",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // the 3 dots at the right end
                TweetDropdownMenu(tweet, parentEntry, context = "tweetDetail", viewModel = viewModel)
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
                            text = it,
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
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
                        
                        // Separate media (visual) from documents (like iOS)
                        val mediaAttachments = attachments.filter { attachment ->
                            val type = inferMediaTypeFromAttachment(attachment)
                            isMediaType(type)
                        }
                        val documentAttachments = attachments.filter { attachment ->
                            val type = inferMediaTypeFromAttachment(attachment)
                            isDocumentType(type)
                        }
                        
                        // Handle media attachments
                        if (mediaAttachments.isNotEmpty()) {
                            val isAllAudio = mediaAttachments.all { 
                                val type = inferMediaTypeFromAttachment(it)
                                type == MediaType.Audio 
                            }
                            if (isAllAudio) {
                                mediaAttachments.forEach {
                                    it.url = getMediaUrl(it.mid, tweet.author?.baseUrl.orEmpty())
                                }
                                AudioPlayer(mediaAttachments)
                            } else {
                                AttachmentBrowser(
                                    mediaItems = mediaAttachments,
                                    viewModel = viewModel,
                                    onVideoVisibilityChanged = onVideoVisibilityChanged
                                )
                            }
                        }
                        
                        // Handle document attachments (below media)
                        if (documentAttachments.isNotEmpty()) {
                            DocumentAttachmentsView(
                                documents = documentAttachments,
                                baseUrl = tweet.author?.baseUrl,
                                maxDocuments = null // Show all documents in detail view
                            )
                        }
                    }

                    // This is a retweet. Display the original tweet in quote box.
                    if (tweet.originalTweetId != null) {
                        // Show cached version immediately, then refresh in background
                        val cachedOriginal = remember(tweet.originalTweetId) {
                            tweet.originalTweetId?.let { TweetCacheManager.getCachedTweetMemoryOnly(it) }
                        }
                        var originalTweet by remember { mutableStateOf<Tweet?>(cachedOriginal) }
                        var isLoadingOriginal by remember { mutableStateOf(cachedOriginal == null) }

                        LaunchedEffect(tweet.originalTweetId) {
                            if (tweet.originalTweetId != null && tweet.originalAuthorId != null) {
                                val originalTweetId = tweet.originalTweetId ?: ""
                                val originalAuthorId = tweet.originalAuthorId ?: ""
                                // If no memory-cached version, fall back to DB cache before hitting network
                                if (originalTweet == null) {
                                    val dbCached = withContext(Dispatchers.IO) {
                                        TweetCacheManager.getCachedTweet(originalTweetId)
                                    }
                                    if (dbCached != null) {
                                        originalTweet = dbCached
                                        isLoadingOriginal = false
                                    }
                                }
                                // Refresh from network in the background, update silently
                                val refreshed = withContext(Dispatchers.IO) {
                                    HproseInstance.refreshTweet(originalTweetId, originalAuthorId)
                                }
                                if (refreshed != null) {
                                    originalTweet = refreshed
                                }
                                isLoadingOriginal = false
                            }
                        }
                        
                        if (isLoadingOriginal) {
                            // Show loading state for quoted tweet with spinner
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                tonalElevation = 2.dp,
                                modifier = Modifier.padding(start = 40.dp, top = 4.dp, end = 0.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
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
                                modifier = Modifier.padding(start = 40.dp, top = 4.dp, end = 0.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
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
    viewModel: TweetViewModel,
    onVideoVisibilityChanged: ((Boolean) -> Unit)? = null
) {
    val rootView = LocalView.current
    // Stabilize attachments to prevent recomposition issues (like iOS implementation)
    val stableAttachments = remember(mediaItems.map { it.mid }) {
        mediaItems
    }
    
    // Calculate fixed aspect ratio to prevent height jumping (like iOS calculateFixedAspectRatio)
    val fixedAspectRatio = remember(stableAttachments.map { it.mid }) {
        calculateFixedAspectRatio(stableAttachments)
    }
    
    val pagerState = rememberPagerState(pageCount = { stableAttachments.size })

    // Hysteresis + only-notify-on-change to prevent shake when portrait video is ~50% visible during scroll
    var lastReportedVisible by remember { mutableStateOf<Boolean?>(null) }
    val visibilityCallback = rememberUpdatedState(onVideoVisibilityChanged)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .then(
                if (onVideoVisibilityChanged != null) {
                    Modifier.onGloballyPositioned { layoutCoordinates ->
                        val totalHeight = layoutCoordinates.size.height.toFloat()
                        if (totalHeight > 0) {
                            val windowPos = layoutCoordinates.positionInWindow()
                            val top = windowPos.y
                            val bottom = top + totalHeight
                            val displayFrame = android.graphics.Rect()
                            rootView.getWindowVisibleDisplayFrame(displayFrame)
                            val visibleTop = kotlin.math.max(displayFrame.top.toFloat(), top)
                            val visibleBottom = kotlin.math.min(displayFrame.bottom.toFloat(), bottom)
                            val visibleHeight = kotlin.math.max(0f, visibleBottom - visibleTop)
                            val ratio = (visibleHeight / totalHeight).coerceIn(0f, 1f)
                            // Hysteresis: visible >= 0.6, not visible <= 0.4, else keep previous (avoids flip at 0.5)
                            val visible = when {
                                ratio >= 0.6f -> true
                                ratio <= 0.4f -> false
                                else -> lastReportedVisible ?: (ratio >= 0.5f)
                            }
                            if (lastReportedVisible != visible) {
                                lastReportedVisible = visible
                                visibilityCallback.value?.invoke(visible)
                            }
                        }
                    }
                } else Modifier
            )
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
                    enableTapToShowControls = true,
                    enableCoordinator = false
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
 * Determines if a media type is visual content (should be in MediaGrid)
 */
private fun isMediaType(type: MediaType): Boolean {
    return when (type) {
        MediaType.Image, MediaType.Video, MediaType.HLS_VIDEO, MediaType.Audio -> true
        else -> false
    }
}

/**
 * Determines if a media type is a document (should be in DocumentAttachmentsView)
 */
private fun isDocumentType(type: MediaType): Boolean {
    return when (type) {
        MediaType.PDF, MediaType.Word, MediaType.Excel, MediaType.PPT,
        MediaType.Zip, MediaType.Txt, MediaType.Html, MediaType.Unknown -> true
        else -> false
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
    context: String = "default",
    viewModel: TweetViewModel? = null
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
                    }, context, viewModel = viewModel)
                }
            } else {
                TweetDropdownMenuItems(tweet, parentEntry, {
                    expanded = false
                }, context, viewModel = viewModel)
            }
        }
    }
}
