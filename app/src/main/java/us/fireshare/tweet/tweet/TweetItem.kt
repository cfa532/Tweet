package us.fireshare.tweet.tweet

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.profile.UserAvatar
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.Gadget.isElementVisible
import us.fireshare.tweet.widget.MediaGrid
import us.fireshare.tweet.widget.SelectableText

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
    // Use activity scope to ensure same ViewModel instance is shared with TweetDetailScreen
    val activity = LocalActivity.current as ComponentActivity
    val viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
        viewModelStoreOwner = activity, key = tweet.mid
    ) { factory ->
        factory.create(tweet)
    }

    // Optimize: Use derivedStateOf to avoid unnecessary recomposition
    val isTweetValid by remember(tweet, tweet.author) {
        derivedStateOf { tweet.author != null }
    }
    
    // Check if tweet or author is null and remove the item if so
    LaunchedEffect(tweet, tweet.author) {
        if (tweet.author == null) {
            Timber.tag("TweetItem").d("Tweet ${tweet.mid} has null author, removing from list")
            if (tweet.mid != null) onTweetUnavailable?.invoke(tweet.mid)
        }
    }

    // If tweet or author is null, return empty content to effectively hide this item
    if (!isTweetValid) {
        Box(modifier = Modifier.size(0.dp))
        return
    }

    // Optimize: Use remember for visibility state to reduce recomposition
    var isVisible by remember { mutableStateOf(false) }
    var tweetTopY by remember { mutableStateOf(0f) }
    var lastVisibilityUpdate by remember { mutableLongStateOf(0L) }
    val debounceMs = 100L // 100ms debounce for visibility detection
    
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
            tweet.originalTweetId != null && 
            (tweet.content?.isNotEmpty() == true || tweet.attachments?.isNotEmpty() == true)
        }
    }

    val navController = LocalNavController.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 8000.dp)
            .clickable(onClick = {
                // Navigate to detail view when tapping on non-tappable areas
                navController.navigate(
                    NavTweet.TweetDetail(
                        tweet.authorId,
                        tweet.mid
                    )
                )
            })
            .onGloballyPositioned { layoutCoordinates ->
                val now = System.currentTimeMillis()
                if (now - lastVisibilityUpdate > debounceMs) {
                    isVisible = isElementVisible(layoutCoordinates, 50)
                    lastVisibilityUpdate = now
                }
                tweetTopY = layoutCoordinates.boundsInRoot().top
            }
    ) {
        when {
            isRetweet -> {
                // Pure retweet - load original tweet
                RetweetContent(
                    tweet = tweet,
                    isVisible = isVisible,
                    parentEntry = parentEntry,
                    onTweetUnavailable = onTweetUnavailable,
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
                    containerTopY = tweetTopY
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
private fun RetweetContent(
    tweet: Tweet,
    isVisible: Boolean,
    parentEntry: NavBackStackEntry,
    onTweetUnavailable: ((MimeiId) -> Unit)?,
    context: String = "default",
    currentUserId: MimeiId? = null,
    onScrollToTop: (suspend () -> Unit)? = null,
    containerTopY: Float? = null
) {
    val navController = LocalNavController.current
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
        var originalTweet by remember(originalTweetId) { mutableStateOf<Tweet?>(null) }
        var isLoadingOriginal by remember(originalTweetId) { mutableStateOf(true) }

        LaunchedEffect(originalTweetId, tweet.originalAuthorId) {
            if (originalTweetId != null && tweet.originalAuthorId != null) {
                withContext(IO) {
                    try {
                        val originalAuthorId = tweet.originalAuthorId ?: ""

                        Timber.tag("TweetItem")
                            .d("Fetching original tweet: $originalTweetId from author: $originalAuthorId")
                        originalTweet = HproseInstance.fetchTweet(
                            originalTweetId,
                            originalAuthorId
                        )
                        if (originalTweet != null) {
                            Timber.tag("TweetItem")
                                .d("Original tweet loaded successfully: ${originalTweet!!.mid}")
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

        when {
            isLoadingOriginal -> {
                // Show loading state with spinner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            originalTweet != null -> {
                val originalTweetNonNull = originalTweet!!

                // Privacy check at rendering stage
                if (originalTweetNonNull.isPrivate && originalTweetNonNull.authorId != appUser.mid) {
                    LaunchedEffect(Unit) {
                        onTweetUnavailable?.invoke(tweet.mid)
                    }
                    Box(modifier = Modifier.size(0.dp))
                } else {
                    // The tweet area with 'Forwarded by' label above
                    // Use activity scope to ensure same ViewModel instance is shared
                    val activity = LocalActivity.current as ComponentActivity
                    val originalTweetViewModel =
                        hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                            viewModelStoreOwner = activity, key = tweet.originalTweetId
                        ) { factory -> factory.create(originalTweetNonNull) }

                    Column(modifier = Modifier.padding(top = 0.dp)) {
                        // Label: Forward by user, above the quoted tweet
                        val forwardBy = if (tweet.authorId == appUser.mid)
                            stringResource(R.string.forward_by)
                        else "@${tweet.author?.username} " + stringResource(R.string.forwarded)

                        Row(
                            modifier = Modifier
                                .offset(y = 0.dp)
                                .zIndex(1f)
                                .padding(start = 40.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Repeat,
                                contentDescription = stringResource(R.string.forwarded),
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = forwardBy,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        // The quoted/original tweet card
                        TweetItemBody(
                            originalTweetViewModel,
                            parentEntry = parentEntry,
                            parentTweet = tweet,
                            modifier = Modifier.offset(y = (-8).dp),
                            context = context,
                            currentUserId = currentUserId,
                            onScrollToTop = onScrollToTop,
                            containerTopY = containerTopY
                        )
                    }
                }
            }
            else -> {
                // Original tweet not available - this retweet should be removed from the list
                LaunchedEffect(Unit) {
                    onTweetUnavailable?.invoke(tweet.mid)
                }
                Box(modifier = Modifier.size(0.dp))
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
    // Use activity scope to ensure same ViewModel instance is shared with TweetDetailScreen
    val activity = LocalActivity.current as ComponentActivity
    val viewModel = hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
        viewModelStoreOwner = activity, key = tweet.mid
    ) { factory ->
        factory.create(tweet)
    }
    
    // Optimize: Pre-compute derived values
    val author by remember(tweet.author) {
        derivedStateOf { tweet.author }
    }
    
    val hasContent by remember(tweet.content) {
        derivedStateOf { !tweet.content.isNullOrEmpty() }
    }
    
    val hasAttachments by remember(tweet.attachments) {
        derivedStateOf { !tweet.attachments.isNullOrEmpty() }
    }

    Surface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = {
                    navController.navigate(
                        NavTweet.TweetDetail(
                            tweet.authorId,
                            tweet.mid
                        )
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
                    modifier = Modifier.width(44.dp)
                ) {
                    UserAvatar(
                        user = author ?: us.fireshare.tweet.datamodel.User(
                            mid = us.fireshare.tweet.datamodel.TW_CONST.GUEST_ID,
                            baseUrl = appUser.baseUrl
                        ),
                        size = 32
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
                    TweetDropdownMenu(tweet, parentEntry, null, context)
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
                                onTextClick = {
                                    // Navigate to detail view when text (not username) is clicked
                                    navController.navigate(
                                        NavTweet.TweetDetail(
                                            tweet.authorId,
                                            tweet.mid
                                        )
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
                                }
                            )
                        }

                        // Media files
                        if (hasAttachments) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .heightIn(min = 20.dp, max = 400.dp),
                                tonalElevation = 4.dp,
                                shape = RoundedCornerShape(size = 8.dp)
                            ) {
                MediaGrid(
                    tweet.attachments!!,
                    viewModel,
                    parentTweetId = tweet.mid,
                    containerTopY = containerTopY
                )
                            }
                        }
                    }
                }

                // Load and display original tweet
                QuotedTweetContent(
                    tweet = tweet,
                    parentEntry = parentEntry,
                    onTweetUnavailable = onTweetUnavailable,
                    context = context,
                    containerTopY = containerTopY
                )

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
                    Spacer(modifier = Modifier.width(40.dp))
                    ShareButton(viewModel)
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
private fun QuotedTweetContent(
    tweet: Tweet,
    parentEntry: NavBackStackEntry,
    onTweetUnavailable: ((MimeiId) -> Unit)?,
    context: String = "default",
    containerTopY: Float? = null
) {
    // Use remember with a stable key based on originalTweetId to maintain state across recompositions
    val originalTweetId = tweet.originalTweetId
    var originalTweet by remember(originalTweetId) { mutableStateOf<Tweet?>(null) }
    var isLoadingOriginal by remember(originalTweetId) { mutableStateOf(true) }

    LaunchedEffect(originalTweetId, tweet.originalAuthorId) {
        if (originalTweetId != null && tweet.originalAuthorId != null) {
            try {
                withContext(IO) {
                    Timber.tag("TweetItem")
                        .d("Fetching quoted original tweet: $originalTweetId from author: ${tweet.originalAuthorId}")
                    originalTweet = HproseInstance.fetchTweet(
                        originalTweetId,
                        tweet.originalAuthorId!!
                    )
                    if (originalTweet != null) {
                        Timber.tag("TweetItem")
                            .d("Quoted original tweet loaded successfully: ${originalTweet!!.mid}")
                    } else {
                        Timber.tag("TweetItem")
                            .w("Quoted original tweet not found: $originalTweetId")
                    }
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
            // Show loading state for quoted tweet with spinner
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
                        .padding(16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
        originalTweet != null -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 8.dp,
            ) {
                // quoted tweet
                // Use activity scope to ensure same ViewModel instance is shared
                val activity = LocalActivity.current as ComponentActivity
                TweetItemBody(
                    hiltViewModel<TweetViewModel, TweetViewModel.TweetViewModelFactory>(
                        viewModelStoreOwner = activity, key = tweet.originalTweetId
                    ) { factory ->
                        factory.create(originalTweet!!)
                    },
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    isQuoted = true,
                    parentEntry = parentEntry,
                    parentTweet = tweet,
                    context = context,
                    containerTopY = containerTopY
                )
            }
        }
        else -> {
            // Original tweet not available - this quoted tweet should be removed from the list
            LaunchedEffect(Unit) {
                onTweetUnavailable?.invoke(tweet.mid)
            }
            Box(modifier = Modifier.size(0.dp))
        }
    }
}