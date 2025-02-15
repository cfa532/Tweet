package com.fireshare.tweet.profile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.fireshare.tweet.HproseInstance
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.tweet.guestWarning
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.UserViewModel
import com.fireshare.tweet.widget.UserAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowingScreen(userId: MimeiId, appUserViewModel: UserViewModel)
{
    val navController = LocalNavController.current
    val context = LocalContext.current
    val viewModel = if (userId == appUser.mid) appUserViewModel
    else hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
        context as ComponentActivity, key = userId
    ) { factory ->
        factory.create(userId)
    }
    val followingsOfProfile by viewModel.followings.collectAsState()
    val userOfProfile by viewModel.user.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
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
        bottomBar = { BottomNavigationBar(navController, 0) }
    ) { innerPadding ->
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
        user.value = HproseInstance.getUser(userId)
    }
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 1.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    )
    Row(
        modifier = Modifier
            .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .fillMaxWidth()
    ) {
        IconButton(onClick = {
            user.value?.let { navController.navigate(NavTweet.UserProfile(it.mid)) }
        }) {
            UserAvatar(user.value, 40)
        }
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = user.value?.name ?: "No One",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "@${user.value?.username}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    ToggleFollowingButton(userId, appUserViewModel)
                }
            }
            Text(
                text = user.value?.profile?.trim() ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ToggleFollowingButton(userId: MimeiId, appUserViewModel: UserViewModel) {
    val followings by appUserViewModel.followings.collectAsState()
    val isFollowing = followings.contains(userId)
    val followState = remember { mutableStateOf(isFollowing) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()

    LaunchedEffect(followings) {
        followState.value = isFollowing
    }
    Text(
        text = if (followState.value) stringResource(R.string.unfollow) else stringResource(R.string.follow),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .clickable(onClick = {
                if (appUser.mid == TW_CONST.GUEST_ID) {
                    appUserViewModel.viewModelScope.launch {
                        guestWarning(context, navController)
                    }
                    return@clickable
                }
                appUserViewModel.viewModelScope.launch(Dispatchers.IO) {
                    appUserViewModel.toggleFollow(userId) {
                        tweetFeedViewModel.viewModelScope.launch(Dispatchers.IO) {
                            tweetFeedViewModel.updateFollowingsTweets(userId, it)
                        }
                    }
                }
            })
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 20.dp, vertical = 6.dp)
    )
}