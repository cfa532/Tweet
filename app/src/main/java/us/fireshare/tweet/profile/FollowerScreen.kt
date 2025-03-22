package us.fireshare.tweet.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.datamodel.isGuest
import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
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
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        bottomBar = { BottomNavigationBar(navController = navController, selectedIndex = 0) }
    ) { innerPadding ->
        val followersOfProfile by viewModel.followers.collectAsState()

        Surface(modifier = Modifier.padding(innerPadding))
        {
            LazyColumn( modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center // Center align content horizontally
                    ) {
                        Text(
                            text = stringResource(R.string.fans),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
                items(followersOfProfile, key = { it }) { userId ->
                    FollowerItem(
                        userId,
                        if (userId == appUser.mid) appUserViewModel
                        else hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
                            parentEntry, key = userId
                        ) { factory ->
                            factory.create(userId)
                        },
                        appUserViewModel
                    )
                }
            }
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

    // Skip rendering if user is a guest (not properly loaded)
    if (user.isGuest()) {
        // Try to reload the user data when this item would be visible
        LaunchedEffect(userId) {
            viewModel.refreshUser()
        }
        return
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
            navController.navigate(NavTweet.UserProfile(user.mid))
        }) {
            UserAvatar(user = user, size = 40)
        }
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = user.name ?: "No One",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "@${user.username}",
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
