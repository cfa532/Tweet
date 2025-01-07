package com.fireshare.tweet.service

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.HproseInstance.getUser
import com.fireshare.tweet.HproseInstance.getUserId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.widget.UserAvatar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    selectedBottomBarItemIndex: Int = 3
) {
    val searchQuery = remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val navController = LocalNavController.current
    val searchUsers by viewModel.searchUsers.collectAsState()
    var lastClickTime by remember { mutableLongStateOf(0L) }
    val debounceTime = 500L

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Column {
                        UserAvatar(appUser, 36)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        // manually prevent fast continuous click of a button
                        val currentTime = SystemClock.elapsedRealtime()
                        if (currentTime - lastClickTime > debounceTime) {
                            navController.popBackStack()
                            lastClickTime = currentTime
                        }
                    } ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        bottomBar = { BottomNavigationBar(navController, selectedBottomBarItemIndex) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery.value,
                    onValueChange = { searchQuery.value = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search @username or #hashtag",
                        modifier = Modifier.alpha(0.5f)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        // Perform search here
                        viewModel.viewModelScope.launch {
                            viewModel.search(searchQuery.value)
                        }
                        focusManager.clearFocus()
                    }),
                    shape = RoundedCornerShape(32.dp),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                // Perform search here
                                viewModel.viewModelScope.launch {
                                    viewModel.search(searchQuery.value)
                                }
                            },
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                    }
                )
            }
            // Display search results here
            LazyColumn {
                items(searchUsers.size, key = { index -> searchUsers[index].mid }) { index ->
                    UserSearchResult(searchUsers[index], navController)
                }
            }
        }
    }
}

@Composable
fun UserSearchResult(user: User, navController: NavController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            // Navigate to user profile
            navController.navigate(NavTweet.UserProfile(user.mid))
        }) {
            UserAvatar(user, 32)
        }
        Column {
            Text(
                text = "${user.name} @${user.username}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
            Text(
                text = "${user.profile}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
fun TweetSearchResult(tweet: Tweet) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(tweet.author, 20)
        Text(
            text = "${tweet.content}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

class SearchViewModel : ViewModel() {
    private val _searchUsers = MutableStateFlow<List<User>>(emptyList())
    val searchUsers: StateFlow<List<User>> get() = _searchUsers.asStateFlow()

    suspend fun search(query: String) {
        // if query starts by @, search for a user by username
        if (query.startsWith("@")) {
            val username = query.substring(1)
            val userId = getUserId(username) ?: return
            getUser(userId)?.let {
                _searchUsers.value = listOf(it)
            }
        } else {
            // search tweet content
        }
    }
}