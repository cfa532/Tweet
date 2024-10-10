package com.fireshare.tweet.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.navigation.ProfileEditor
import com.fireshare.tweet.service.SnackbarAction
import com.fireshare.tweet.service.SnackbarEvent
import com.fireshare.tweet.tweet.guestWarning
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.UserViewModel
import com.fireshare.tweet.widget.UserAvatar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTopAppBar(viewModel: UserViewModel,
                     navController: NavHostController,
                     parentEntry: NavBackStackEntry,
                     scrollBehavior: TopAppBarScrollBehavior? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val user by viewModel.user.collectAsState()
    val scrollFraction = scrollBehavior?.state?.collapsedFraction ?: 0f

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

                    UserAvatar(user, size = (80 - (scrollFraction * 20)).toInt() )

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
                ProfileTopBarButton(viewModel, navController, parentEntry, scrollBehavior)
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
                    val tweetFeedviewModel = hiltViewModel<TweetFeedViewModel>()
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
                                viewModel.logout()
                                tweetFeedviewModel.clearTweets()
                                navController.navigate(NavTweet.TweetFeed)
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
                        parentEntry: NavBackStackEntry,
                        scrollBehavior: TopAppBarScrollBehavior?
) {
    val appUserViewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
        parentEntry,
        key = appUser.mid
    ) { factory ->
        factory.create(appUser.mid)
    }
    val followings by appUserViewModel.followings.collectAsState()
    val user by viewModel.user.collectAsState()
    val buttonText = remember { mutableStateOf("Follow") }
    val context = LocalContext.current

    LaunchedEffect(followings) {
        buttonText.value = when {
            user.mid == appUser.mid -> context.getString(R.string.edit)
            followings.contains(user.mid) -> context.getString(R.string.unfollow)
            else -> context.getString(R.string.follow)
        }
    }

    Row(modifier = Modifier.padding(bottom = 4.dp)) {
        // Follow button
        if (scrollBehavior?.state?.collapsedFraction == 1f) return  // hide follow button when collapsed.
        Button(
            onClick = {
                when (buttonText.value) {
                    context.getString(R.string.edit) -> navController.navigate(ProfileEditor)
                    else -> {
                        if (appUser.mid != TW_CONST.GUEST_ID)
                            appUserViewModel.toggleFollow(user.mid)
                        else {
                            val event = SnackbarEvent(
                                message = context.getString(R.string.login_follow),
                                action = SnackbarAction(
                                    name = context.getString(R.string.go),
                                    action = { navController.navigate(NavTweet.Login) }
                                )
                            )
                            viewModel.showSnackbar(event)
                        }
                    }
                }
            },
            colors = ButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContentColor = Color.Gray, disabledContainerColor = Color.White
            ),
            modifier = Modifier.width(intrinsicSize = IntrinsicSize.Max)
                .height(32.dp)
        ) {
            Text(
                text = buttonText.value,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}