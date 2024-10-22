package com.fireshare.tweet.profile

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.UserViewModel
import com.fireshare.tweet.widget.UserAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowingScreen(userId: MimeiId, parentEntry: NavBackStackEntry, appUserViewModel: UserViewModel)
{
    val navController = LocalNavController.current
    val viewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(parentEntry, key = userId) {
            factory -> factory.create(userId)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    val userOfProfile by viewModel.user.collectAsState()
                    Column {
                        UserAvatar(userOfProfile, 36)
                        Text(
                            text = userOfProfile.name ?: "No One",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() })
                    {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        bottomBar = { BottomNavigationBar(navController, 1) }
    ) { innerPadding ->

        val followingsOfProfile by viewModel.followings.collectAsState()

        Surface(modifier = Modifier.padding(innerPadding))
        {
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center // Center align content horizontally
                    ) {
                        Text(
                            text = stringResource(R.string.followings),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
                items(followingsOfProfile, key = {it}) { userId ->
                    FollowingItem(userId, navController, appUserViewModel)
                }
            }
        }
    }
}

@Composable
fun FollowingItem(userId: MimeiId, navController: NavController, appUserViewModel: UserViewModel) {
    val user = remember { mutableStateOf<User?>(null) }

    LaunchedEffect(userId) {
        withContext(Dispatchers.IO) {
            user.value = HproseInstance.getUserBase(userId)
        }
    }

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 1.dp),
        thickness = 1.dp,
        color = Color.LightGray
    )
    Row(modifier = Modifier
        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
        .heightIn(max = 200.dp)
        .fillMaxWidth()
    ) {
        IconButton( onClick = {
            user.value?.let{ navController.navigate(NavTweet.UserProfile(it.mid)) }
        }) {
            UserAvatar(user.value,40)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "${user.value?.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "@${user.value?.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                ToggleFollowingButton(userId, appUserViewModel)
            }
            Text(
                text = "${user.value?.profile}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
fun ToggleFollowingButton(userId: MimeiId, appUserViewModel: UserViewModel) {
    val followings by appUserViewModel.followings.collectAsState()
    val isFollowing = followings.contains(userId)
    val followState = remember { mutableStateOf(isFollowing) }
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()

    LaunchedEffect(followings) {
        followState.value = isFollowing
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        Text(
            text = if (followState.value) stringResource(R.string.unfollow) else stringResource(R.string.follow),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .clickable(onClick = {
                    appUserViewModel.toggleFollow(userId) {
                        tweetFeedViewModel.updateFollowings(userId)
                    }
                })
                .border(
                    width = 1.dp,
                    color = Color.DarkGray,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}