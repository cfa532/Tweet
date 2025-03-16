package us.fireshare.tweet.tweet

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.widget.MediaPreviewGrid
import us.fireshare.tweet.widget.SelectableText

@Composable
fun TweetItemBody(
    viewModel: TweetViewModel,
    isQuoted: Boolean = false,     // the block is a quoted tweet or not
    parentEntry: NavBackStackEntry,
    parentTweet: Tweet? = null,    // the parent tweet of the quoted original tweet
) {
    val navController = LocalNavController.current
    val tweet by viewModel.tweetState.collectAsState()

    // fold text content up to 9 lines. Open it upon user click.
    Surface(
        // Apply border to the entire TweetBlock
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
        modifier = Modifier
            .padding(top = 8.dp)
            .clickable(onClick = {
                navController.navigate(NavTweet.TweetDetail(tweet.authorId, tweet.mid))
            })
    ) {
        Column(
            modifier = Modifier
                .padding(start = 4.dp, end = 4.dp, top = 0.dp, bottom = 0.dp)
        ) {
            // Tweet Header. Icon, name, timestamp, more actions
            TweetItemHeader(viewModel, parentEntry, parentTweet)

            Surface(
                shape = MaterialTheme.shapes.small, // Inner border
                modifier = Modifier
                    .padding(start = 32.dp, top = 2.dp, bottom = 4.dp, end = 4.dp)
            ) {
                Column {
                    // Text content of the tweet
                    tweet.content?.let {
                        SelectableText(text = it, maxLines = 10) { username ->
                            viewModel.viewModelScope.launch(Dispatchers.IO) {
                                HproseInstance.getUserId(username)?.let {
                                    withContext(Dispatchers.Main) {
                                        navController.navigate(NavTweet.UserProfile(it))
                                    }
                                }
                            }
                        }
                    }
                    // there are attached media files
                    if (! tweet.attachments.isNullOrEmpty()) {
                        Surface (
                            modifier = Modifier.fillMaxWidth()
                                .padding(top = 8.dp)
                                .heightIn(min = 32.dp, max = 400.dp),
                            tonalElevation = 4.dp,
                            shape = RoundedCornerShape(size = 8.dp)
                        ) {
                            MediaPreviewGrid(tweet.attachments!!, viewModel)
                        }
                    }
                    /**
                     * If the tweet being displayed is quoted by other tweet, do not show buttons
                     * */
                    if (!isQuoted) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            LikeButton(viewModel)
                            BookmarkButton(viewModel)
                            CommentButton(viewModel)
                            RetweetButton(viewModel)
                            Spacer(modifier = Modifier.width(40.dp))
                            ShareButton(viewModel)
//                            ShareScreenshotButton(viewModel)
                        }
                    }
                }
            }
        }
    }
}
