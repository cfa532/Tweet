package us.fireshare.tweet.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiId

import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.tweet.localizedTimeDifference
import us.fireshare.tweet.viewmodel.UserViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowerScreen(
    userId: MimeiId,
    parentEntry: NavBackStackEntry,
    appUserViewModel: UserViewModel
) {
    val navController = LocalNavController.current
    val viewModel = if (userId == appUser.mid) appUserViewModel
    else hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
        parentEntry, key = userId
    ) { factory ->
        factory.create(userId)
    }
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
                        UserAvatar(user = userOfProfile, size = 36)
                        Text(
                            text = userOfProfile.name ?: "No One",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 2.dp, bottom = 0.dp)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() })
                    {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        bottomBar = { BottomNavigationBar(navController = navController, selectedIndex = 0) }
    ) { innerPadding ->
        Surface(modifier = Modifier.padding(innerPadding)) {
            UserListView(
                fetchUserIds = { batchNumber ->
                    viewModel.fetchFollowers(batchNumber)
                },
                contentPadding = PaddingValues(bottom = 60.dp),
                userItem = { followerUserId ->
                    FollowerItem(
                        userId = followerUserId,
                        viewModel = if (followerUserId == appUser.mid) appUserViewModel
                        else hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
                            parentEntry, key = followerUserId
                        ) { factory ->
                            factory.create(followerUserId)
                        },
                        appUserViewModel = appUserViewModel
                    )
                },
                currentUserId = userId
            )
        }
    }
}

@Composable
fun FollowerItem(
    userId: MimeiId,
    viewModel: UserViewModel,
    appUserViewModel: UserViewModel
) {
    val user by viewModel.user.collectAsState()
    val navController = LocalNavController.current

    // Proactively load user data for every user ID
    LaunchedEffect(userId) {
        Timber.tag("FollowerItem").d("Loading user data for userId: $userId")
        withContext(IO) {
            viewModel.refreshUser()
        }
    }

    // Retry loading if the user data failed to load (user is guest)
    LaunchedEffect(user) {
        if (user.isGuest()) {
            withContext(IO) {
                viewModel.refreshUser()
            }
        }
    }

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 1.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    )
    Row(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .padding(start = 4.dp, end = 8.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .fillMaxWidth()
    ) {
        IconButton(onClick = {
            if (!user.isGuest()) {
                navController.navigate(NavTweet.UserProfile(user.mid))
            }
        }) {
            UserAvatar(user = user, size = 40)
        }
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = user.name ?: "No One",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "@${user.username} - ${localizedTimeDifference(user.timestamp)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
                ToggleFollowingButton(userId, viewModel, appUserViewModel)
            }
            Text(
                text = user.profile?.trim() ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
