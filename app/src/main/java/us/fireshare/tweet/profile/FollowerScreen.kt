package us.fireshare.tweet.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import us.fireshare.tweet.viewmodel.UserViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                            text = if (userId == appUser.mid) {
                                stringResource(R.string.your_fans)
                            } else {
                                val displayName = userOfProfile.name ?: userOfProfile.username ?: stringResource(R.string.no_one)
                                stringResource(R.string.fans_at_username, displayName)
                            },
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
    var hasTimedOut by remember { mutableStateOf(false) }
    val loadingTimeoutMs = 10000L // 10 seconds total timeout

    // Simple timeout - remove placeholder after 10 seconds if still loading
    LaunchedEffect(userId) {
        if (user.username.isNullOrEmpty()) {
            Timber.tag("FollowerItem").d("Waiting for user load: $userId")
            kotlinx.coroutines.delay(loadingTimeoutMs)
            if (user.username.isNullOrEmpty()) {
                Timber.tag("FollowerItem").w("User load timed out for userId: $userId")
                hasTimedOut = true
            }
        }
    }

    // Show placeholder while loading
    val isLoading = user.username.isNullOrEmpty() && !hasTimedOut

    // Hide item if it timed out
    if (hasTimedOut) {
        return
    }

    // Show loading placeholder
    if (isLoading) {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 1.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        )
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .padding(start = 4.dp, end = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar with loading spinner
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Shimmering placeholder for name
                Card(
                    modifier = Modifier
                        .width(140.dp)
                        .height(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {}
                Spacer(modifier = Modifier.height(6.dp))
                // Shimmering placeholder for username
                Card(
                    modifier = Modifier
                        .width(100.dp)
                        .height(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {}
            }
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
            Box(contentAlignment = Alignment.Center) {
                UserAvatar(user = user, size = 40)
                // Show spinner overlay if user data is still being loaded
                if (user.username.isNullOrEmpty() || user.name.isNullOrEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = user.name ?: "No One",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "@${user.username}",
                            modifier = Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                    Text(
                        text = "Joined ${formatUserCreationDate(user.timestamp)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 2.dp)
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

private fun formatUserCreationDate(timestamp: Long): String {
    val date = Date(timestamp)
    val dateFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())
    return dateFormat.format(date)
}
