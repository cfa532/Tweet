package us.fireshare.tweet.tweet

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.fireshare.tweet.ui.theme.mediaGridBorderColor
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.datamodel.TweetEvent
import us.fireshare.tweet.datamodel.TweetNotificationCenter
import us.fireshare.tweet.datamodel.collectCachedUserAsState
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.profile.UserAvatar
import us.fireshare.tweet.viewmodel.ShareLinkStyle
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.Gadget.isElementVisible
import us.fireshare.tweet.widget.ImageCacheManager
import us.fireshare.tweet.widget.VideoManager
import us.fireshare.tweet.widget.MediaGrid
import us.fireshare.tweet.widget.SelectableText
import us.fireshare.tweet.widget.inferMediaTypeFromAttachment

private fun Tweet.detailRoute(): NavTweet.TweetDetail {
    val parentId = savedParentTweet?.mid ?: parentTweetId.takeIf { originalTweetId == null }
    val parentAuthorId = if (parentId != null) savedParentTweet?.authorId ?: interactionHostAuthor?.mid
    else null
    return NavTweet.TweetDetail(authorId, mid, parentId, parentAuthorId)
}

private class ComposeScopedViewModelStoreOwner(
    private val delegate: HasDefaultViewModelProviderFactory
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    private val store = ViewModelStore()

    override val viewModelStore: ViewModelStore
        get() = store

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = delegate.defaultViewModelProviderFactory

    override val defaultViewModelCreationExtras: CreationExtras
        get() = delegate.defaultViewModelCreationExtras

    fun clear() {
        store.clear()
    }
}

@Composable
private fun rememberTweetRowViewModel(tweet: Tweet, key: String = tweet.mid): TweetViewModel {
    val activity = LocalActivity.current as ComponentActivity
    val owner = remember(key) {
        ComposeScopedViewModelStoreOwner(activity)
    }

    DisposableEffect(owner) {
        onDispose {
            owner.clear()
        }
    }

    val viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
        viewModelStoreOwner = owner,
        key = key
    ) { factory ->
        factory.create(tweet)
    }

    // The owner is intentionally stable for the row's lifetime, so Hilt will
    // not call the factory again when the feed replaces this tweet after an
    // update from detail view. Push the new list snapshot into the existing VM.
    // Deep-copy attachments for the effect key because Tweet and attachment
    // fields are mutable and can otherwise change without changing identity.
    val tweetUpdateKey = tweet.copy(
        favorites = tweet.favorites?.toMutableList(),
        attachments = tweet.attachments?.map { it.copy() }
    )
    LaunchedEffect(tweetUpdateKey) {
        viewModel.updateFromList(tweet)
    }

    return viewModel
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun TweetItem(
    tweet: Tweet,
    parentEntry: NavBackStackEntry, // navGraph scoped
    onTweetUnavailable: ((MimeiId) -> Unit)? = null, // callback when tweet becomes unavailable
    context: String = "default",
    currentUserId: MimeiId? = null, // Current profile userId to prevent duplicate navigation
    onScrollToTop: (suspend () -> Unit)? = null // Callback to scroll to top
) {
    val viewModel = rememberTweetRowViewModel(tweet)

    // Saved-list rows stay actionable when the author profile is unavailable.
    val isSavedList = context == "appUserBookmarks" || context == "appUserFavorites"
    if (tweet.authorId.isEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.saved_tweet_unavailable), modifier = Modifier.weight(1f))
            if (isSavedList && tweet.isBookmarked) BookmarkButton(viewModel)
            if (isSavedList && tweet.isFavorite) LikeButton(viewModel)
        }
        return
    }
    if (tweet.author == null && !isSavedList) {
        LaunchedEffect(tweet) {
            Timber.tag("TweetItem").d("Tweet ${tweet.mid} has null author, removing from list")
            onTweetUnavailable?.invoke(tweet.mid)
        }
        Box(modifier = Modifier.size(0.dp))
        return
    }

    // Optimize: Use remember for visibility state to reduce recomposition
    var isVisible by remember { mutableStateOf(false) }
    var tweetTopY by remember { mutableStateOf(0f) }
    var lastVisibilityUpdate by remember { mutableLongStateOf(0L) }
    val coordinator = us.fireshare.tweet.widget.LocalVideoCoordinator.current
    val density = LocalDensity.current
    // Cached height for scroll-up stability (match iOS TweetHeightCache + willDisplay)
    val cachedHeightPx = remember(tweet.mid) { TweetHeightCache.getHeightPx(tweet.mid) }
    // Only apply cached min height for tweets that may have loading placeholders (media/retweets).
    // Text-only tweets render instantly; a stale large cached height causes blank space below content.
    val hasMediaOrIsRetweet = tweet.originalTweetId != null || !tweet.attachments.isNullOrEmpty()
    val hasVideoAttachment by remember(tweet.attachments) {
        derivedStateOf {
            tweet.attachments?.any { attachment ->
                when (inferMediaTypeFromAttachment(attachment)) {
                    MediaType.Video,
                    MediaType.HLS_VIDEO -> true
                    else -> false
                }
            } == true
        }
    }
    // Track per-row coordinates only when needed for video playback ordering.
    val shouldTrackVideoCell by remember(tweet.originalTweetId, hasVideoAttachment) {
        derivedStateOf { tweet.originalTweetId != null || hasVideoAttachment }
    }
    // Keep measuring row height for media/retweet rows only (text-only rows are stable).
    val shouldMeasureRowHeight by remember(hasMediaOrIsRetweet) {
        derivedStateOf { hasMediaOrIsRetweet }
    }
    val cachedHeightDp: Dp? = if (hasMediaOrIsRetweet) {
        cachedHeightPx?.let { px -> density.run { px.toDp() } }
    } else null
    
    // Optimize: Pre-compute derived values to avoid recalculation
    val isRetweet by remember(tweet.originalTweetId, tweet.content, tweet.attachments) {
        derivedStateOf { 
            tweet.originalTweetId != null && 
            tweet.content.isNullOrEmpty() && 
            tweet.attachments.isNullOrEmpty() 
        }
    }
    
    val isRetweetWithContent by remember(tweet.originalTweetId, tweet.content, tweet.attachments) {
        derivedStateOf { 
            (tweet.originalTweetId != null || tweet.savedParentTweet != null) &&
            (tweet.content?.isNotEmpty() == true || tweet.attachments?.isNotEmpty() == true)
        }
    }

    val navController = LocalNavController.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (cachedHeightDp != null) Modifier.heightIn(min = cachedHeightDp) else Modifier)
            .clickable(onClick = {
                // Navigate to detail view when tapping on non-tappable areas
                navController.navigate(
                    NavTweet.TweetDetail(
                        tweet.authorId,
                        tweet.mid
                    )
                )
            })
            .then(
                if (shouldMeasureRowHeight || shouldTrackVideoCell) {
                    Modifier.onGloballyPositioned { layoutCoordinates ->
                        val measuredHeightPx = layoutCoordinates.size.height.toFloat()

                        // Keep height cache writes off text-only rows. Also skip
                        // while the user has the tweet text "Show more"-expanded:
                        // otherwise the (transient) expanded height becomes the
                        // cached `min` height, and after scroll/nav the row would
                        // come back collapsed but still occupy the expanded space.
                        if (shouldMeasureRowHeight
                            && measuredHeightPx >= 60f
                            && !TweetExpansionCache.isExpanded(tweet.mid)
                        ) {
                            TweetHeightCache.setHeight(tweet.mid, measuredHeightPx)
                        }

                        // Only do expensive visibility/bounds work for rows that may host playable video.
                        if (!shouldTrackVideoCell) return@onGloballyPositioned

                        val now = System.currentTimeMillis()
                        if (now - lastVisibilityUpdate > 500L) {
                            lastVisibilityUpdate = now
                            val newVisible = isElementVisible(layoutCoordinates, 50)
                            if (newVisible != isVisible) {
                                isVisible = newVisible
                            }

                            val bounds = layoutCoordinates.boundsInRoot()
                            tweetTopY = bounds.top

                            coordinator.updateTweetCellPosition(
                                tweetId = tweet.mid,
                                cellTopY = bounds.top,
                                cellHeight = measuredHeightPx,
                                isVisible = newVisible
                            )
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        when {
            isRetweet && tweet.author != null -> {
                // Pure retweet - load original tweet
                RetweetContent(
                    tweet = tweet,
                    isVisible = isVisible,
                    parentEntry = parentEntry,
                    viewModel = viewModel,
                    context = context,
                    currentUserId = currentUserId,
                    onScrollToTop = onScrollToTop,
                    containerTopY = tweetTopY
                )
            }
            isRetweetWithContent -> {
                // Retweet with content
                RetweetWithContent(
                    tweet = tweet,
                    parentEntry = parentEntry,
                    onTweetUnavailable = onTweetUnavailable,
                    context = context,
                    currentUserId = currentUserId,
                    onScrollToTop = onScrollToTop,
                    containerTopY = tweetTopY
                )
            }
            else -> {
                // Original tweet
                TweetItemBody(
                    viewModel,
                    parentEntry = parentEntry,
                    context = context,
                    currentUserId = currentUserId,
                    onScrollToTop = onScrollToTop,
                    containerTopY = tweetTopY,
                    enableMediaPreloading = false
                )
            }
        }
    }
}

@Composable
private fun ForwardedByLabel(authorId: MimeiId, username: String?) {
    val text = if (authorId == appUser.mid) {
        stringResource(R.string.forward_by)
    } else {
        "@${username ?: authorId} ${stringResource(R.string.forwarded)}"
    }
    val color = MaterialTheme.colorScheme.tertiary

    Row(
        modifier = Modifier.padding(start = 40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Repeat,
            contentDescription = stringResource(R.string.forwarded),
            modifier = Modifier.size(12.dp),
            tint = color
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = text, fontSize = 13.sp, color = color)
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
private fun RetweetContent(
    tweet: Tweet,
    isVisible: Boolean,
    parentEntry: NavBackStackEntry,
    viewModel: TweetViewModel,
    context: String = "default",
    currentUserId: MimeiId? = null,
    onScrollToTop: (suspend () -> Unit)? = null,
    containerTopY: Float? = null
) {
    val navController = LocalNavController.current
    val coordinator = us.fireshare.tweet.widget.LocalVideoCoordinator.current
    Surface(
        modifier = Modifier.clickable(onClick = {
            // Navigate to detail view when tapping on non-tappable areas
            navController.navigate(
                NavTweet.TweetDetail(
                    tweet.authorId,
                    tweet.mid
                )
            )
        })
    ) {
        // Use remember with a stable key based on originalTweetId to maintain state across recompositions
        val originalTweetId = tweet.originalTweetId
        val cachedOriginalTweet = remember(originalTweetId) {
            originalTweetId?.let { TweetCacheManager.getCachedTweetMemoryOnly(it) }
        }
        var originalTweet by remember(originalTweetId) {
            mutableStateOf(cachedOriginalTweet)
        }
        var isLoadingOriginal by remember(originalTweetId) {
            mutableStateOf(cachedOriginalTweet == null)
        }
        FollowTweetUpdates(originalTweetId) { originalTweet = it }

        LaunchedEffect(originalTweetId, tweet.originalAuthorId) {
            originalTweet?.let {
                coordinator.addRetweetVideos(tweet.mid, it)
                isLoadingOriginal = false
                return@LaunchedEffect
            }
            if (originalTweetId != null && tweet.originalAuthorId != null) {
                withContext(IO) {
                    try {
                        val originalAuthorId = tweet.originalAuthorId ?: ""

                        val replica = tweet.readNodeUrl?.let { source ->
                            HproseInstance.fetchTweet(originalTweetId, originalAuthorId, source)
                        }
                        originalTweet = replica ?: HproseInstance.fetchTweet(originalTweetId, originalAuthorId)
                        if (originalTweet != null) {
                            // Notify VideoPlaybackCoordinator about the loaded original tweet for retweet
                            coordinator.addRetweetVideos(tweet.mid, originalTweet!!)
                        } else {
                            Timber.tag("TweetItem")
                                .w("Original tweet not found: $originalTweetId")
                        }
                    } catch (e: Exception) {
                        Timber.tag("TweetItem").e(e, "Failed to load original tweet")
                        originalTweet = null
                    } finally {
                        isLoadingOriginal = false
                    }
                }
            } else {
                isLoadingOriginal = false
            }
        }

        val readableOriginal = originalTweet?.takeIf { !it.isPrivate || it.authorId == appUser.mid }
        when {
            isLoadingOriginal -> {
                // Show loading state with minimum height to reduce layout jump when content loads
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            readableOriginal != null -> {
                val originalTweetNonNull = readableOriginal

                val originalTweetViewModel = rememberTweetRowViewModel(
                    tweet = originalTweetNonNull,
                    key = tweet.originalTweetId ?: originalTweetNonNull.mid
                )

                Column {
                    ForwardedByLabel(tweet.authorId, tweet.author?.username)
                    // The quoted/original tweet card
                    TweetItemBody(
                        originalTweetViewModel,
                        parentEntry = parentEntry,
                        parentTweet = tweet,
                        context = context,
                        currentUserId = currentUserId,
                        onScrollToTop = onScrollToTop,
                        containerTopY = containerTopY,
                        enableMediaPreloading = false
                    )
                }
            }
            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.original_tweet_not_found), modifier = Modifier.weight(1f))
                    TweetDropdownMenu(tweet, parentEntry, context = context, viewModel = viewModel)
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
private fun RetweetWithContent(
    tweet: Tweet,
    parentEntry: NavBackStackEntry,
    onTweetUnavailable: ((MimeiId) -> Unit)?,
    context: String = "default",
    currentUserId: MimeiId? = null,
    onScrollToTop: (suspend () -> Unit)? = null,
    containerTopY: Float? = null
) {
    val navController = LocalNavController.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val viewModel = rememberTweetRowViewModel(tweet)
    
    val cachedAuthor by collectCachedUserAsState(tweet.authorId)

    // Prefer the reactive user cache so avatar/profile updates recompose visible feed rows.
    val author = cachedAuthor ?: tweet.author
    
    val hasContent by remember(tweet.content) {
        derivedStateOf { !tweet.content.isNullOrEmpty() }
    }
    
    val hasAttachments by remember(tweet.attachments) {
        derivedStateOf { !tweet.attachments.isNullOrEmpty() }
    }

    Surface {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = {
                        navController.navigate(
                            tweet.detailRoute()
                        )
                    })
            ) {
                // Left column: Avatar
                Column {
                    IconButton(
                        onClick = {
                            // Only navigate if we're not already on this user's profile
                            timber.log.Timber.tag("TweetItem").d("Avatar clicked in RetweetWithContent: authorId=${tweet.authorId}, currentUserId=$currentUserId")
                            if (tweet.authorId != currentUserId) {
                                timber.log.Timber.tag("TweetItem").d("Navigating to profile: ${tweet.authorId}")
                                navController.navigate(NavTweet.UserProfile(tweet.authorId))
                            } else {
                                timber.log.Timber.tag("TweetItem").d("Already on this user's profile, scrolling to top")
                                coroutineScope.launch {
                                    onScrollToTop?.invoke()
                                }
                            }
                        },
                        modifier = Modifier.size(52.dp)
                    ) {
                        UserAvatar(
                            user = author ?: User(
                                mid = TW_CONST.GUEST_ID,
                                baseUrl = appUser.baseUrl
                            ),
                            size = 44
                        )
                    }
                }

                // Right column: User info, content, and actions
                Column(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .fillMaxWidth()
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
                        TweetHeaderText(
                            authorName = author?.name,
                            username = author?.username,
                            timestamp = tweet.timestamp,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 2.dp, end = 4.dp)
                        )

                        // Dropdown menu
                        TweetDropdownMenu(tweet, parentEntry, null, context, viewModel)
                    }

                    // Tweet content
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(top = 4.dp, bottom = 8.dp)
                        ) {
                            // Text content of the tweet
                            if (hasContent) {
                                SelectableText(
                                    text = tweet.content!!,
                                    maxLines = 7,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                                    onTextClick = {
                                        // Navigate to detail view when text (not username) is clicked
                                        navController.navigate(
                                            tweet.detailRoute()
                                        )
                                    },
                                    callback = { _ ->
                                        viewModel.viewModelScope.launch(Dispatchers.IO) {
                                            withContext(Dispatchers.Main) {
                                                navController.navigate(
                                                    NavTweet.UserProfile(
                                                        tweet.authorId
                                                    )
                                                )
                                            }
                                        }
                                    },
                                    expansionKey = tweet.mid,
                                )
                            }

                            // Media files
                            if (hasAttachments) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    tonalElevation = 4.dp,
                                    shape = RoundedCornerShape(size = 8.dp),
                                    border = BorderStroke(Dp.Hairline, mediaGridBorderColor)
                                ) {
                                    MediaGrid(
                                        tweet.attachments!!,
                                        viewModel,
                                        parentTweetId = tweet.mid,
                                        containerTopY = containerTopY,
                                        enableRowPreloading = false
                                    )
                                }
                            }
                        }
                    }

                    // Load and display original tweet
                    QuotedTweetContent(tweet, embeddedTweet = tweet.savedParentTweet)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        // State hoist
                        CommentButton(viewModel)
                        RetweetButton(viewModel)
                        LikeButton(viewModel)
                        BookmarkButton(viewModel)
                        // Feed share uses the dtweet.com deep-link format (DEEPLINKING.md)
                        ShareButton(viewModel, linkStyle = ShareLinkStyle.DEEPLINK)
                    }
                }
            }
        }
    }
}

/**
 * An embedded card (a retweet's or a quote's original) keeps its own copy of the
 * original. Apply the edit, like and bookmark messages the lists already apply,
 * so a change made elsewhere reaches the card while it is on screen.
 */
@Composable
private fun FollowTweetUpdates(tweetId: MimeiId?, onUpdate: (Tweet) -> Unit) {
    val latestOnUpdate by rememberUpdatedState(onUpdate)
    LaunchedEffect(tweetId) {
        if (tweetId == null) return@LaunchedEffect
        TweetNotificationCenter.events.collect { event ->
            val updated = when (event) {
                is TweetEvent.TweetUpdated -> event.tweet
                is TweetEvent.TweetLiked -> event.tweet
                is TweetEvent.TweetBookmarked -> event.tweet
                else -> null
            }
            if (updated?.mid == tweetId) latestOnUpdate(updated)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
internal fun QuotedTweetContent(
    tweet: Tweet,
    embeddedTweet: Tweet? = null
) {
    // Use remember with a stable key based on originalTweetId to maintain state across recompositions
    val originalTweetId = embeddedTweet?.mid ?: tweet.originalTweetId
    val cachedOriginalTweet = remember(originalTweetId) {
        embeddedTweet ?: originalTweetId?.let { TweetCacheManager.getCachedTweetMemoryOnly(it) }
    }
    var originalTweet by remember(originalTweetId) {
        mutableStateOf(cachedOriginalTweet)
    }
    var isLoadingOriginal by remember(originalTweetId) {
        mutableStateOf(cachedOriginalTweet == null)
    }
    FollowTweetUpdates(originalTweetId) { originalTweet = it }

    LaunchedEffect(originalTweetId, embeddedTweet?.authorId, tweet.originalAuthorId) {
        originalTweet?.let {
            isLoadingOriginal = false
            return@LaunchedEffect
        }
        val embeddedAuthorId = embeddedTweet?.authorId ?: tweet.originalAuthorId
        if (originalTweetId != null && embeddedAuthorId != null) {
            try {
                withContext(IO) {
                    originalTweet = HproseInstance.fetchTweet(
                        originalTweetId,
                        embeddedAuthorId
                    )
                }
            } catch (e: Exception) {
                Timber.tag("TweetItem").e(
                    e,
                    "Error loading original tweet: $originalTweetId"
                )
            } finally {
                isLoadingOriginal = false
            }
        } else {
            isLoadingOriginal = false
        }
    }

    when {
        isLoadingOriginal -> {
            // Show loading state with minimum height to reduce layout jump when content loads
            Surface(
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 8.dp,
                modifier = Modifier.padding(
                    start = 4.dp,
                    top = 8.dp,
                    end = 8.dp
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
        originalTweet != null -> QuotePreviewCard(originalTweet!!)
        else -> {
            // Embedded tweet could not be found/loaded (deleted, network error, etc).
            // Render the quote tweet card anyway with a placeholder in place of the
            // missing embedded tweet, instead of dropping the whole tweet from the list.
            Surface(
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 8.dp,
                modifier = Modifier.padding(
                    start = 4.dp,
                    top = 8.dp,
                    end = 8.dp
                )
            ) {
                Text(
                    text = stringResource(R.string.original_tweet_not_found),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                )
            }
        }
    }
}

private const val QUOTE_PREVIEW_MAX_CHARS = 4_096

@Composable
private fun QuotePreviewCard(original: Tweet) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val cachedAuthor by collectCachedUserAsState(original.authorId)
    val author = cachedAuthor ?: original.author
    val attachment = original.attachments?.firstOrNull()
    val mediaType = attachment?.let { inferMediaTypeFromAttachment(it) }
    val mediaUrl = HproseInstance.getMediaUrl(attachment?.mid, original.readNodeUrl ?: author?.baseUrl)
    var image by remember(attachment?.mid) { mutableStateOf<Bitmap?>(null) }
    val isVideo = mediaType == MediaType.Video || mediaType == MediaType.HLS_VIDEO

    LaunchedEffect(attachment?.mid, attachment?.previewMid, mediaUrl, mediaType) {
        if (attachment == null) return@LaunchedEffect
        if (mediaType == MediaType.Image) {
            image = ImageCacheManager.downloadAndCacheImage(
                context, mediaUrl, attachment.mid,
                ownerId = original.authorId, previewMid = attachment.previewMid
            )
        } else if (isVideo) {
            // The quoted video is never played in the feed, so its poster cannot
            // come from a rendered player snapshot; opt into bounded remote
            // extraction or the card stays a gray box.
            VideoManager.ensureVideoPoster(
                context, attachment.mid, mediaUrl, mediaType, null,
                allowRemoteExtraction = true
            )
        }
    }

    Surface(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .aspectRatio(2f)
            .clickable {
                navController.navigate(original.detailRoute())
            },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(Dp.Hairline, mediaGridBorderColor)
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (author != null) UserAvatar(author, size = 22)
                TweetHeaderText(
                    authorName = author?.name,
                    username = author?.username,
                    timestamp = original.timestamp,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (attachment != null) {
                    Surface(
                        modifier = Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val bitmap = if (isVideo) VideoManager.posterBitmaps[attachment.mid] else image
                            if (bitmap != null) {
                                Image(bitmap.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                            } else if (!isVideo) {
                                Text(
                                    attachment.fileName ?: attachment.type.name,
                                    modifier = Modifier.padding(4.dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isVideo) {
                                // Same play badge style as AttachmentPreview video thumbnails
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(32.dp)
                                        .background(Color.LightGray.copy(alpha = 0.5f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = stringResource(R.string.play_video),
                                        tint = Color.White,
                                        modifier = Modifier.align(Alignment.Center).size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                // The fixed 2:1 card clips its text, but Compose still measures and lays
                // out every character it is given, so a long quoted document stalled the
                // feed. Hand it a bounded prefix, far more than the card can show
                // (matches the iOS quote card's bound).
                val previewText = remember(original.title, original.content) {
                    listOfNotNull(original.title, original.content)
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                        .take(QUOTE_PREVIEW_MAX_CHARS)
                }
                Text(
                    text = previewText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
