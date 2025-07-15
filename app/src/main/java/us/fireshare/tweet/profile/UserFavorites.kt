package us.fireshare.tweet.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.tweet.TweetListView
import us.fireshare.tweet.viewmodel.UserViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserFavorites(
    viewModel: UserViewModel,    // appUserViewModel
    parentEntry: NavBackStackEntry
) {
    val navController = LocalNavController.current
    val start = remember { mutableIntStateOf(0) }
    val favorites by viewModel.favorites.collectAsState()
    val user = appUser
    val isLoading by viewModel.isLoading.collectAsState()
    
    // Start listening to tweet and comment notifications
    LaunchedEffect(Unit) {
        viewModel.startListeningToNotifications()
    }

    LaunchedEffect(Unit) {
        viewModel.isLoading.value = true
        withContext(Dispatchers.IO) {
            viewModel.getFavorites(start.intValue)
        }
        viewModel.isLoading.value = false
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
                            text = stringResource(R.string.user_favorites),
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(color = Color.LightGray)
                .padding(innerPadding),
        ) {
            if (isLoading) {
                // Show a large loading indicator when data is being loaded
                CircularProgressIndicator(
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentSize(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 8.dp
                )
            } else {
                TweetListView(
                    tweets = favorites,
                    fetchTweets = { pageNumber ->
                        viewModel.viewModelScope.launch(Dispatchers.IO) {
                            if (pageNumber == 0) {
                                start.intValue = 0
                            } else {
                                start.intValue += 10
                            }
                            viewModel.getFavorites(start.intValue)
                        }
                    },
                    showPrivateTweets = false,
                    parentEntry = parentEntry
                )
            }
        }
    }
}
