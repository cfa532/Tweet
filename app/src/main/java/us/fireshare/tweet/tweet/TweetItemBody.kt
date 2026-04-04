package us.fireshare.tweet.tweet

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.profile.UserAvatar
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.DocumentAttachmentsView
import us.fireshare.tweet.widget.MediaGrid
import us.fireshare.tweet.widget.SelectableText
import us.fireshare.tweet.widget.inferMediaTypeFromAttachment
import java.util.concurrent.TimeUnit

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun TweetItemBody(
    viewModel: TweetViewModel,
    modifier: Modifier = Modifier,
    isQuoted: Boolean = false,     // the block is a quoted tweet or not
    parentEntry: NavBackStackEntry,
    parentTweet: Tweet? = null,    // the parent tweet of the quoted original tweet
    context: String = "default",
    currentUserId: us.fireshare.tweet.datamodel.MimeiId? = null, // Current profile userId to prevent duplicate navigation
    onScrollToTop: (suspend () -> Unit)? = null, // Callback to scroll to top
    containerTopY: Float? = null
) {
    val navController = LocalNavController.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val tweet by viewModel.tweetState.collectAsState()

    // Observe author changes reactively via StateFlow
    // This ensures all tweets from the same author update when user data becomes available
    val authorStateFlow = remember(tweet.authorId) {
        TweetCacheManager.getUserStateFlow(tweet.authorId)
    }
    val author by authorStateFlow.collectAsState()

    val hasContent by remember(tweet.content) {
        derivedStateOf { !tweet.content.isNullOrEmpty() }
    }

    val hasAttachments by remember(tweet.attachments) {
        derivedStateOf { !tweet.attachments.isNullOrEmpty() }
    }

    val canNavigate by remember(tweet.authorId, tweet.mid) {
        derivedStateOf { tweet.authorId != null && tweet.mid != null }
    }

    // fold text content up to 9 lines. Open it upon user click.
    Surface(
        // Apply border to the entire TweetBlock
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .clickable(enabled = canNavigate, onClick = {
                // necessary to deal with corrupted data.
                if (canNavigate) {
                    navController.navigate(NavTweet.TweetDetail(tweet.authorId, tweet.mid))
                }
            })
    ) {
        if (isQuoted) {
            // Quoted tweet: two-row layout
            // Row 1: Avatar + Header
            Column(modifier = Modifier.fillMaxWidth().padding(end = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (tweet.authorId != currentUserId) {
                                navController.navigate(NavTweet.UserProfile(tweet.authorId))
                            } else {
                                coroutineScope.launch { onScrollToTop?.invoke() }
                            }
                        },
                        modifier = Modifier.width(40.dp)
                    ) {
                        UserAvatar(
                            user = author ?: us.fireshare.tweet.datamodel.User(
                                mid = us.fireshare.tweet.datamodel.TW_CONST.GUEST_ID,
                                baseUrl = appUser.baseUrl
                            ),
                            size = 32
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.padding(top = 2.dp),
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
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(text = " • ", fontSize = 12.sp)
                            Text(
                                text = localizedTimeDifference(tweet.timestamp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        TweetDropdownMenu(tweet, parentEntry, parentTweet, context)
                    }
                }

                // Row 2: Tweet body (content + media)
                Column(modifier = Modifier.padding(start = 8.dp)) {
                // Text content of the tweet
                if (hasContent) {
                    SelectableText(
                        text = tweet.content!!,
                        maxLines = 7,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
                        modifier = Modifier.padding(bottom = 4.dp),
                        onTextClick = {
                            // Navigate to detail view when text (not username) is clicked
                            navController.navigate(NavTweet.TweetDetail(tweet.authorId, tweet.mid))
                        },
                        callback = { username ->
                            viewModel.viewModelScope.launch(Dispatchers.IO) {
                                HproseInstance.getUserId(username)?.let {
                                    withContext(Dispatchers.Main) {
                                        navController.navigate(NavTweet.UserProfile(it))
                                    }
                                }
                            }
                        }
                    )
                }

                // Media files and documents
                if (hasAttachments) {
                    // Stabilize attachments to prevent video recomposition
                    val stableAttachments = remember(tweet.attachments?.map { it.mid }) {
                        tweet.attachments!!
                    }
                    
                    // Separate media (visual) from documents (like iOS)
                    val mediaAttachments = remember(stableAttachments) {
                        stableAttachments.filter { attachment ->
                            val type = inferMediaTypeFromAttachment(attachment)
                            isMediaType(type)
                        }
                    }
                    val documentAttachments = remember(stableAttachments) {
                        stableAttachments.filter { attachment ->
                            val type = inferMediaTypeFromAttachment(attachment)
                            isDocumentType(type)
                        }
                    }
                    
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .padding(top = 4.dp, end = 6.dp)
                    ) {
                        // MediaGrid for images, videos, and audio (visual content)
                        if (mediaAttachments.isNotEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = 20.dp, max = 400.dp),
                                tonalElevation = 4.dp,
                                shape = RoundedCornerShape(size = 8.dp)
                            ) {
                                MediaGrid(
                                    mediaAttachments,
                                    viewModel,
                                    // For retweets/quotes, parentTweet.mid is the container tweet ID which must be used for video tracking
                                    // This ensures videos are identified by the parent (retweet/quote) ID and video mid
                                    parentTweetId = parentTweet?.mid?.takeIf { it.isNotEmpty() },
                                    containerTopY = containerTopY
                                )
                            }
                        }
                        
                        // Document attachments vertically (below media) - limit to 2 in list
                        if (documentAttachments.isNotEmpty()) {
                            DocumentAttachmentsView(
                                documents = documentAttachments,
                                baseUrl = tweet.author?.baseUrl,
                                maxDocuments = 2 // Show at most 2 documents in tweet list
                            )
                        }
                    }
                }
                } // end body Column

            }
        } else {
            // Normal tweet: original side-by-side layout
            Row(modifier = Modifier.fillMaxWidth()) {
                // Left column: Avatar
                Column(modifier = Modifier.padding(top = 0.dp)) {
                    IconButton(
                        onClick = {
                            timber.log.Timber.tag("TweetItemBody").d("Avatar clicked: authorId=${tweet.authorId}, currentUserId=$currentUserId")
                            if (tweet.authorId != currentUserId) {
                                timber.log.Timber.tag("TweetItemBody").d("Navigating to profile: ${tweet.authorId}")
                                navController.navigate(NavTweet.UserProfile(tweet.authorId))
                            } else {
                                timber.log.Timber.tag("TweetItemBody").d("Already on this user's profile, scrolling to top")
                                coroutineScope.launch { onScrollToTop?.invoke() }
                            }
                        },
                        modifier = Modifier.width(48.dp)
                    ) {
                        UserAvatar(
                            user = author ?: us.fireshare.tweet.datamodel.User(
                                mid = us.fireshare.tweet.datamodel.TW_CONST.GUEST_ID,
                                baseUrl = appUser.baseUrl
                            ),
                            size = 38
                        )
                    }
                }

                // Right column: User info, content, and actions
                Column(
                    modifier = Modifier.fillMaxWidth().padding(end = 4.dp)
                ) {
                    // Top row: User info and dropdown menu
                    Row(
                        modifier = Modifier.padding(bottom = 4.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Row(
                            modifier = Modifier.padding(top = 2.dp),
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
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(text = " • ", fontSize = 12.sp)
                            Text(
                                text = localizedTimeDifference(tweet.timestamp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        TweetDropdownMenu(tweet, parentEntry, parentTweet, context)
                    }

                    // Text content of the tweet
                    if (hasContent) {
                        SelectableText(
                            text = tweet.content!!,
                            maxLines = 7,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
                            modifier = Modifier.padding(bottom = 4.dp),
                            onTextClick = {
                                navController.navigate(NavTweet.TweetDetail(tweet.authorId, tweet.mid))
                            },
                            callback = { username ->
                                viewModel.viewModelScope.launch(Dispatchers.IO) {
                                    HproseInstance.getUserId(username)?.let {
                                        withContext(Dispatchers.Main) {
                                            navController.navigate(NavTweet.UserProfile(it))
                                        }
                                    }
                                }
                            }
                        )
                    }

                    // Media files and documents
                    if (hasAttachments) {
                        val stableAttachments = remember(tweet.attachments?.map { it.mid }) {
                            tweet.attachments!!
                        }
                        val mediaAttachments = remember(stableAttachments) {
                            stableAttachments.filter { attachment ->
                                val type = inferMediaTypeFromAttachment(attachment)
                                isMediaType(type)
                            }
                        }
                        val documentAttachments = remember(stableAttachments) {
                            stableAttachments.filter { attachment ->
                                val type = inferMediaTypeFromAttachment(attachment)
                                isDocumentType(type)
                            }
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 6.dp)
                        ) {
                            if (mediaAttachments.isNotEmpty()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp, max = 400.dp),
                                    tonalElevation = 4.dp,
                                    shape = RoundedCornerShape(size = 8.dp)
                                ) {
                                    MediaGrid(
                                        mediaAttachments,
                                        viewModel,
                                        parentTweetId = parentTweet?.mid?.takeIf { it.isNotEmpty() },
                                        containerTopY = containerTopY
                                    )
                                }
                            }
                            if (documentAttachments.isNotEmpty()) {
                                DocumentAttachmentsView(
                                    documents = documentAttachments,
                                    baseUrl = tweet.author?.baseUrl,
                                    maxDocuments = 2
                                )
                            }
                        }
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
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
