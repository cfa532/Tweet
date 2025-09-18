package us.fireshare.tweet.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.fireshare.tweet.R
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.viewmodel.TweetFeedViewModel

/**
 * TopAppBar for compose screens with conditional visibility
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeTopAppBar(
    title: String,
    showCamera: Boolean,
    onBackClick: () -> Unit,
    onSendClick: () -> Unit,
    hasContent: Boolean,
    modifier: Modifier = Modifier
) {
    // Only show TopAppBar when camera is not active
    if (!showCamera) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.primary,
            ),
            title = {
                Text(title, fontSize = 18.sp)
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.back)
                    )
                }
            },
            actions = {
                var isLoading by remember { mutableStateOf(false) }
                val coroutineScope = rememberCoroutineScope()
                val context = LocalContext.current
                val sharedViewModel: SharedViewModel = hiltViewModel()
                val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()

                LaunchedEffect(Unit) {
                    tweetFeedViewModel.startListeningToNotifications(context)
                }

                IconButton(
                    enabled = !isLoading && hasContent,
                    onClick = {
                        if (hasContent) {
                            isLoading = true
                            onSendClick()
                            
                            // Navigate back after a short delay
                            coroutineScope.launch {
                                delay(100)
                                // Navigation will be handled by parent
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.send),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.rotate(180f)
                    )
                }
            }
        )
    }
}
