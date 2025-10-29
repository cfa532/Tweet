package us.fireshare.tweet.profile

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.getMediaUrl
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.viewmodel.TweetFeedViewModel
import us.fireshare.tweet.viewmodel.UserViewModel
import us.fireshare.tweet.widget.AdvancedImageViewer
import us.fireshare.tweet.widget.SelectableText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTopAppBar(viewModel: UserViewModel,
                     navController: NavHostController,
                     scrollBehavior: TopAppBarScrollBehavior? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val user by viewModel.user.collectAsState()
    val scrollFraction = scrollBehavior?.state?.collapsedFraction ?: 0f
    var showDialog by remember { mutableStateOf(false) }    // show full Avatar image
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()

    // manually prevent fast continuous click of a button
    var lastClickTime by remember { mutableLongStateOf(0L) }
    val debounceTime = 500L

    LargeTopAppBar(
        title = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showDialog) {
                        ImageModalDialog(user,
                            onDismiss = { showDialog = false })
                    }
                    UserAvatar(
                        user = user,
                        size = (80 - (scrollFraction * 20)).toInt(),
                        onClick = { showDialog = true }
                    )
                    Column(
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                    ) {
                        Text(
                            text = user.name ?: "No one",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "@" + (user.username ?: "NoOne"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(start = 0.dp)
                        )
                        // Show registration date
                        val date = Date(user.timestamp)
                        val dateFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())
                        Text(
                            text = "${stringResource(R.string.joined)} ${dateFormat.format(date)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 0.dp, top = 2.dp)
                        )
                    }
                }
                ProfileTopBarButton(viewModel, navController, scrollBehavior)
            }
        },
        navigationIcon = {
            IconButton(onClick = {
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - lastClickTime > debounceTime) {
                    // Navigate back to tweet feed to prevent navigation issues
                    try {
                        // First try to pop back stack
                        if (!navController.popBackStack()) {
                            // If popBackStack returns false, navigate to tweet feed
                            navController.navigate(NavTweet.TweetFeed) {
                                // Clear the back stack to prevent multiple back navigation
                                popUpTo(NavTweet.TweetFeed) { inclusive = true }
                            }
                        }
                    } catch (_: Exception) {
                        // Fallback: navigate to tweet feed
                        navController.navigate(NavTweet.TweetFeed) {
                            popUpTo(NavTweet.TweetFeed) { inclusive = true }
                        }
                    }
                    lastClickTime = currentTime
                }
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        actions = {
            if (! appUser.isGuest()) {
                if (appUser.mid != user.mid) {
                    IconButton(onClick = {
                        // Navigate to chat screen with the user
                        navController.navigate(NavTweet.ChatBox(user.mid))
                    }) {
                        Icon(
                            imageVector = Icons.Default.MailOutline,
                            contentDescription = stringResource(R.string.message)
                        )
                    }
                }
                Box {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more)
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier
                            .wrapContentWidth(align = Alignment.End)
                            .border(
                                width = 1.dp, // Border width
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ),
                    ) {
                        if (user.mid == appUser.mid) {
                            DropdownMenuItem(
                                onClick = {
                                    viewModel.viewModelScope.launch(IO) {
                                        viewModel.logout {
                                            tweetFeedViewModel.viewModelScope.launch(IO) {
                                                tweetFeedViewModel.reset()
                                            }
                                            viewModel.viewModelScope.launch(Main) {
                                                navController.navigate(NavTweet.TweetFeed)
                                            }
                                        }
                                    }
                                },
                                text = {
                                    Text(
                                        text = stringResource(id = R.string.logout),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
                                modifier = Modifier.heightIn(max = 32.dp)
                            )
                        }
                    }
                }
            }
        },
        scrollBehavior = scrollBehavior
    )
}

/**
 * Display large image of user avatar in a Modal Dialog.
 * */
@Composable
fun ImageModalDialog(
    user: User,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize() // Make the dialog fill the entire screen
                .background(Color.Black) // Set background color to black
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .verticalScroll(scrollState)
            ) {
                getMediaUrl(user.avatar, user.baseUrl)?.let {
                    AdvancedImageViewer(it)
                }
                SelectableText(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                    text = user.mid + "\n" + user.hostIds?.first() + "\n" + user.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White
                )
            }
        }
    }
}