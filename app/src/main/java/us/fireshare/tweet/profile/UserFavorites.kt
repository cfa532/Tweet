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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.Tweet

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.fireshare.tweet.navigation.BottomBarState
import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.tweet.ScrollDirection
import us.fireshare.tweet.tweet.ScrollState
import us.fireshare.tweet.tweet.TweetListView
import us.fireshare.tweet.viewmodel.UserViewModel
import us.fireshare.tweet.widget.LocalVideoCoordinator
import us.fireshare.tweet.widget.VideoPlaybackCoordinator

@RequiresApi(Build.VERSION_CODES.R)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserFavorites(
    viewModel: UserViewModel,    // appUserViewModel
    parentEntry: NavBackStackEntry
) {
    val navController = LocalNavController.current
    val favorites by viewModel.favorites.collectAsState()
    val favoritesInitialLoadComplete by viewModel.favoritesInitialLoadComplete.collectAsState()
    val user = appUser

    val favoritesCoordinator = remember { VideoPlaybackCoordinator() }

    // Track scroll-to-top trigger
    var scrollToTopTrigger by remember { mutableIntStateOf(0) }

    // State to track scroll state for bottom bar opacity
    var scrollState by remember { mutableStateOf(ScrollState(false, ScrollDirection.NONE)) }
    val coroutineScope = rememberCoroutineScope()

    // Start listening to tweet and comment notifications
    LaunchedEffect(Unit) {
        viewModel.startListeningToNotifications()
    }

    // Only load favorites if the list is empty (initial load)
    // TweetListView handles pagination, so we don't reload on navigation back
    LaunchedEffect(Unit) {
        if (favorites.isEmpty()) {
            withContext(Dispatchers.IO) {
                viewModel.getFavorites(0) // Load first page only if empty
            }
        }
    }

    // Only scroll to top on first-ever load (list was empty on entry).
    // On navigation back, ViewModel still holds data so list won't be empty.
    val wasEmptyOnEntry = remember { favorites.isEmpty() }
    LaunchedEffect(favoritesInitialLoadComplete) {
        if (favoritesInitialLoadComplete && wasEmptyOnEntry) {
            scrollToTopTrigger++
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                                text = stringResource(R.string.your_favorites),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 2.dp, bottom = 0.dp)
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            navController.popBackStack()
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
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = Color.LightGray)
                    .padding(innerPadding),
            ) {
                CompositionLocalProvider(LocalVideoCoordinator provides favoritesCoordinator) {
                    TweetListView(
                        tweets = favorites,
                        fetchTweets = { pageNumber ->
                            viewModel.getFavorites(pageNumber)
                        },
                        showPrivateTweets = true,
                        context = "appUserFavorites",
                        parentEntry = parentEntry,
                        isInitialLoading = favorites.isEmpty() && !favoritesInitialLoadComplete,
                        scrollToTopTrigger = scrollToTopTrigger,
                        onScrollStateChange = { newScrollState ->
                            scrollState = newScrollState
                            when (newScrollState.direction) {
                                ScrollDirection.UP -> {
                                    BottomBarState.opacity = 0.98f
                                }
                                ScrollDirection.DOWN -> {
                                    coroutineScope.launch {
                                        delay(100)
                                        if (scrollState.direction == ScrollDirection.DOWN) {
                                            BottomBarState.opacity = 0.2f
                                        }
                                    }
                                }
                                ScrollDirection.NONE -> {}
                            }
                        }
                    )
                }
            }
        }

        BottomNavigationBar(
            Modifier
                .alpha(BottomBarState.opacity)
                .align(Alignment.BottomCenter),
            navController,
            0
        )
    }
}
