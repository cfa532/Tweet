package com.fireshare.tweet.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.HproseInstance.getMediaUrl
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.navigation.LocalViewModelProvider
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.navigation.ProfileEditor
import com.fireshare.tweet.navigation.SharedViewModel
import com.fireshare.tweet.service.SnackbarAction
import com.fireshare.tweet.service.SnackbarEvent
import com.fireshare.tweet.tweet.guestWarning
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.UserViewModel
import com.fireshare.tweet.widget.ImageViewer
import com.fireshare.tweet.widget.UserAvatar
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTopAppBar(viewModel: UserViewModel,
                     navController: NavHostController,
                     scrollBehavior: TopAppBarScrollBehavior? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val user by viewModel.user
    val scrollFraction = scrollBehavior?.state?.collapsedFraction ?: 0f
    var showDialog by remember { mutableStateOf(false) }    // show large Avatar view

    LargeTopAppBar(
        title = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showDialog) {
                        ImageModalDialog(getMediaUrl(user.avatar, user.baseUrl) ?: "",
                            onDismiss = { showDialog = false })
                    }
                    UserAvatar(user,
                        size = (80 - (scrollFraction * 20)).toInt(),
                        modifier = Modifier.clickable {
                            showDialog = true
                        }
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)
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
                    }
                }
                ProfileTopBarButton(viewModel, navController, scrollBehavior)
            }
        },
        navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }
        },
        actions = {
            if (appUser.mid != TW_CONST.GUEST_ID) {
                if (appUser.mid != user.mid) {
                    val context = LocalContext.current
                    IconButton(onClick = {
                        if (appUser.mid != TW_CONST.GUEST_ID)
                            navController.navigate(NavTweet.ChatBox(user.mid))
                        else {
                            viewModel.viewModelScope.launch {
                                guestWarning(context, navController)
                            }
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.MailOutline,
                            contentDescription = "Message"
                        )
                    }
                }
                Box {
                    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More"
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier
                            .wrapContentWidth(align = Alignment.End)
                            .height(IntrinsicSize.Min)
                    ) {
                        if (user.mid == appUser.mid) {
                            DropdownMenuItem(onClick = {
                                viewModel.viewModelScope.launch {
                                    viewModel.logout {
                                        tweetFeedViewModel.reset()
                                        navController.navigate(NavTweet.TweetFeed)
                                    }
                                }
                            },
                                text = {
                                    Text(
                                        text = stringResource(id = R.string.logout),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            )
                        }
                    }
                }
            }
        },
        scrollBehavior = scrollBehavior
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTopBarButton(viewModel: UserViewModel,
                        navController: NavHostController,
                        scrollBehavior: TopAppBarScrollBehavior?
) {
    val sharedViewModel = LocalViewModelProvider.current?.get(SharedViewModel::class)
    val appUserViewModel = sharedViewModel?.appUserViewModel ?: return
    val followings by appUserViewModel.followings.collectAsState()
    val user by viewModel.user
    val buttonText = remember { mutableStateOf("Follow") }
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()

    val context = LocalContext.current
    LaunchedEffect(followings) {
        buttonText.value = when {
            user.mid == appUser.mid -> context.getString(R.string.edit)
            followings.contains(user.mid) -> context.getString(R.string.unfollow)
            else -> context.getString(R.string.follow)
        }
    }

    Row(modifier = Modifier.padding(bottom = 4.dp)) {
        // hide the Follow/Unfollow button when header is collapsed.
        if (scrollBehavior?.state?.collapsedFraction == 1f)
            return

        Text(
            text = buttonText.value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = Color.DarkGray,
                    shape = RoundedCornerShape(12.dp)
                )
                .clickable(onClick = {
                    when (buttonText.value) {
                        context.getString(R.string.edit) -> navController.navigate(ProfileEditor)
                        else -> {
                            if (appUser.mid != TW_CONST.GUEST_ID)
                                appUserViewModel.viewModelScope.launch(Dispatchers.IO) {
                                    appUserViewModel.toggleFollow(user.mid) {
                                        tweetFeedViewModel.viewModelScope.launch(Dispatchers.IO) {
                                            tweetFeedViewModel.updateFollowingsTweets(user.mid, it)
                                        }
                                    }
                                }
                            else {
                                val event = SnackbarEvent(
                                    message = context.getString(R.string.login_follow),
                                    action = SnackbarAction(
                                        name = context.getString(R.string.go),
                                        action = { navController.navigate(NavTweet.Login) }
                                    )
                                )
                                viewModel.viewModelScope.launch {
                                    viewModel.showSnackbar(event)
                                }
                            }
                        }
                    }
                })
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

/**
 * Display large image of user avatar in a Modal Dialog.
 * */
@Composable
fun ImageModalDialog(
    imageUrl: String,
    onDismiss: () -> Unit
) {
    val systemUiController = rememberSystemUiController()

    // Hide system bars when dialog is shown
    LaunchedEffect(Unit) {
        systemUiController.isSystemBarsVisible = false
    }

    // Restore system bars when dialog is dismissed
    DisposableEffect(Unit) {
        onDispose {
            systemUiController.isSystemBarsVisible = true
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false // Disable platform default width
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize() // Make the dialog fill the entire screen
                .background(Color.Black) // Set background color to black
        ) {
            ImageViewer(imageUrl, isPreview = false) // Use your ImageViewer composable

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}