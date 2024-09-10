package com.fireshare.tweet.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.navigation.ProfileEditor
import com.fireshare.tweet.service.SnackbarAction
import com.fireshare.tweet.service.SnackbarEvent
import com.fireshare.tweet.viewmodel.UserViewModel
import com.fireshare.tweet.widget.AppIcon
import com.fireshare.tweet.widget.UserAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTopAppBar(viewModel: UserViewModel, navController: NavHostController, user: User) {
    LargeTopAppBar(
        title = {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                UserAvatar(user, 90)
                ButtonFollow(viewModel, navController, user)
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
            IconButton(onClick = {}) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More"
                )
            }
        }
    )
}

@Composable
fun ButtonFollow(viewModel: UserViewModel, navController: NavHostController, user: User) {
    Row(modifier = Modifier.padding(bottom = 4.dp)) {
        // Follow button
        Button(
            onClick = {
                when (viewModel.followButtonText) {
                    "Edit" -> navController.navigate(ProfileEditor)
                    "Login" -> navController.navigate(NavTweet.Login)
                    else -> {
                        if (appUser.mid != TW_CONST.GUEST_ID)
                            viewModel.toggleFollow(user.mid)
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
            Text(text = viewModel.followButtonText)
        }
    }
}