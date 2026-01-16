package us.fireshare.tweet.profile

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.tweet.TweetListView
import us.fireshare.tweet.viewmodel.UserViewModel

@RequiresApi(Build.VERSION_CODES.R)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserBookmarks(
    viewModel: UserViewModel,    // appUserViewModel
    parentEntry: NavBackStackEntry
) {
    val navController = LocalNavController.current
    val bookmarks by viewModel.bookmarks.collectAsState()
    val user = appUser
    
    // Start listening to tweet and comment notifications
    LaunchedEffect(Unit) {
        viewModel.startListeningToNotifications()
    }

    LaunchedEffect(Unit) {
        // load bookmarked tweets
        withContext(Dispatchers.IO) {
            viewModel.getBookmarks(0) // Load first page
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Column {
                        UserAvatar(user = user, size = 36)
                        Text(
                            text = stringResource(R.string.your_bookmarks),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 2.dp, bottom = 0.dp)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        // Navigate back to user profile's tweetlist view
                        navController.navigate(NavTweet.UserProfile(appUser.mid)) {
                            // Pop back stack up to and including the current screen
                            popUpTo(NavTweet.Bookmarks(appUser.mid)) { inclusive = true }
                            // Avoid creating duplicate entries if UserProfile is already in stack
                            launchSingleTop = true
                        }
                    })
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(color = Color.LightGray)
                .padding(innerPadding),
        ) {
            TweetListView(
                tweets = bookmarks,
                fetchTweets = { pageNumber ->
                    viewModel.getBookmarks(pageNumber)
                    emptyList() // Return empty list since getBookmarks updates the state
                },
                context = "appUserBookmarks",
                showPrivateTweets = true,
                parentEntry = parentEntry,
                restoreScrollPosition = false // Always start from top for bookmarks
            )
        }
    }
}
