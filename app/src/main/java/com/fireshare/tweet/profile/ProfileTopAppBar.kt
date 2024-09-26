package com.fireshare.tweet.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.fireshare.tweet.HproseInstance.appUser
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
fun ProfileTopAppBar(viewModel: UserViewModel, navController: NavHostController,
                     parentEntry: NavBackStackEntry
) {
    var expanded by remember { mutableStateOf(false) }
    val user by viewModel.user.collectAsState()

    LargeTopAppBar(
        title = {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                UserAvatar(user, 90)
                ProfileTopBarButton(viewModel, navController, parentEntry)
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
                            val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()
                            DropdownMenuItem(onClick = {
                                viewModel.logout()
                                tweetFeedViewModel.refresh()
                                navController.navigate(NavTweet.TweetFeed)
                            },
                                text = { Text("Logout") }
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun ProfileTopBarButton(viewModel: UserViewModel, navController: NavHostController, parentEntry: NavBackStackEntry ) {
    val appUserViewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(parentEntry, key = appUser.mid) {
            factory -> factory.create(appUser.mid)
    }
    val followings by appUserViewModel.followings.collectAsState()
    val user by viewModel.user.collectAsState()
    val buttonText = remember { mutableStateOf("Follow") }

    LaunchedEffect(followings) {
        buttonText.value = if (user.mid == appUser.mid) "Edit"
        else if (followings.contains(user.mid)) "Unfollow"
        else "Follow"
    }

    Row(modifier = Modifier.padding(bottom = 4.dp)) {
        // Follow button
        Button(
            onClick = {
                when (buttonText.value) {
                    "Edit" -> navController.navigate(ProfileEditor)
                    else -> {
                        if (appUser.mid != TW_CONST.GUEST_ID)
                            appUserViewModel.toggleFollow(user.mid) {

                            }
                        else {
                            val event = SnackbarEvent(
                                message = "Login to follow.",
                                action = SnackbarAction(
                                    name = "Go!",
                                    action = { navController.navigate(NavTweet.Login) }
                                )
                            )
                            viewModel.showSnackbar(event)
                        }
                    }
                }
            },
            modifier = Modifier.width(IntrinsicSize.Min)
        ) {
            Text(text = buttonText.value)
        }
    }
}