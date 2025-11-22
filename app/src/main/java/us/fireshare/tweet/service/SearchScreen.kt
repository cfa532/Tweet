package us.fireshare.tweet.service

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.HproseInstance.getUser
import us.fireshare.tweet.HproseInstance.getUserId
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.profile.UserAvatar
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    selectedBottomBarItemIndex: Int = 3
) {
    val focusManager = LocalFocusManager.current
    val navController = LocalNavController.current
    val uiState by viewModel.uiState.collectAsState()
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
                        UserAvatar(user = appUser, size = 36)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        val currentTime = SystemClock.elapsedRealtime()
                        if (currentTime - lastClickTime > debounceTime) {
                            navController.popBackStack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            BottomNavigationBar(
                navController = navController,
                selectedIndex = selectedBottomBarItemIndex
            )
        }
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
                    value = uiState.query,
                    onValueChange = { viewModel.updateQuery(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            stringResource(R.string.search_placeholder),
                            modifier = Modifier.alpha(0.5f)
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        viewModel.submitSearch()
                        focusManager.clearFocus()
                    }),
                    shape = RoundedCornerShape(32.dp),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                viewModel.submitSearch()
                                focusManager.clearFocus()
                            },
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(R.string.search)
                            )
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.hasError -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.search_generic_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                uiState.userResults.isEmpty() &&
                        uiState.tweetResults.isEmpty() &&
                        uiState.lastQuery.isNotEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_search_results, uiState.lastQuery),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                uiState.userResults.isNotEmpty() || uiState.tweetResults.isNotEmpty() -> {
                    val hasUsers = uiState.userResults.isNotEmpty()
                    val hasTweets = uiState.tweetResults.isNotEmpty()

                    LazyColumn {
                        if (hasUsers) {
                            item("users_header") {
                                SearchSectionHeader(
                                    text = stringResource(R.string.search_section_users)
                                )
                            }
                            itemsIndexed(
                                uiState.userResults,
                                key = { _, user -> "user_${user.mid}" }
                            ) { index, user ->
                                UserSearchResult(user, navController)

                                if (index < uiState.userResults.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 1.dp),
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                    )
                                } else if (hasTweets) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                        }

                        if (hasTweets) {
                            item("tweets_header") {
                                SearchSectionHeader(
                                    text = stringResource(R.string.search_section_tweets)
                                )
                            }
                            itemsIndexed(
                                uiState.tweetResults,
                                key = { _, tweet -> "tweet_${tweet.mid}" }
                            ) { index, tweet ->
                                TweetSearchResult(tweet, navController)

                                if (index < uiState.tweetResults.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 1.dp),
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                    )
                                }
                            }
                        }
                    }
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
            .clickable { navController.navigate(NavTweet.UserProfile(user.mid)) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(user = user, size = 32)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            val displayName = user.name?.takeIf { it.isNotBlank() }
                ?: user.username?.takeIf { it.isNotBlank() }?.let { "@$it" }
                ?: user.mid
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium
            )
            user.username
                ?.takeIf { it.isNotBlank() && "@$it" != displayName }
                ?.let { handle ->
                    Text(
                        text = "@$handle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            user.profile?.takeIf { it.isNotBlank() }?.let { bio ->
                Text(
                    text = bio,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun TweetSearchResult(tweet: Tweet, navController: NavController) {
    val author = tweet.author ?: User(mid = tweet.authorId)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                navController.navigate(NavTweet.TweetDetail(tweet.authorId, tweet.mid))
            }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(user = author, size = 32)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                val displayName = author.name?.takeIf { it.isNotBlank() }
                    ?: author.username?.takeIf { it.isNotBlank() }
                    ?: author.mid
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium
                )
                author.username?.takeIf { it.isNotBlank() && it != displayName }?.let { handle ->
                    Text(
                        text = "@$handle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        val preview = when {
            !tweet.content.isNullOrBlank() -> tweet.content
            !tweet.title.isNullOrBlank() -> tweet.title
            else -> null
        }

        preview?.takeIf { it.isNotBlank() }?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 40.dp, top = 8.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SearchSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

data class SearchUiState(
    val query: String = "",
    val userResults: List<User> = emptyList(),
    val tweetResults: List<Tweet> = emptyList(),
    val lastQuery: String = "",
    val isLoading: Boolean = false,
    val hasError: Boolean = false
)

@HiltViewModel
class SearchViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    fun updateQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query, hasError = false)
    }

    fun submitSearch() {
        val rawQuery = _uiState.value.query
        val sanitizedQuery = rawQuery.trim()

        if (sanitizedQuery.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                userResults = emptyList(),
                tweetResults = emptyList(),
                lastQuery = "",
                isLoading = false,
                hasError = false
            )
            return
        }

        viewModelScope.launch(IO) {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                hasError = false,
                userResults = emptyList(),
                tweetResults = emptyList(),
                lastQuery = sanitizedQuery
            )

            try {
                val userQuery = sanitizedQuery.removePrefix("@").trim()
                val completionCounter = AtomicInteger(0)
                val expectedCompletions = if (userQuery.isNotEmpty()) 2 else 1

                fun checkCompletion() {
                    if (completionCounter.incrementAndGet() >= expectedCompletions) {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                    }
                }

                // Launch user search and update UI as soon as it completes
                if (userQuery.isNotEmpty()) {
                    launch {
                        try {
                            val localUsers = TweetCacheManager.searchUsers(userQuery, USER_RESULT_LIMIT)
                            val exactUser = fetchExactUser(userQuery)
                            val mergedUsers = mergeUserResults(exactUser, localUsers)
                            _uiState.value = _uiState.value.copy(
                                userResults = mergedUsers,
                                lastQuery = sanitizedQuery
                            )
                            checkCompletion()
                        } catch (e: Exception) {
                            Timber.tag("SearchViewModel").e(e, "User search failed")
                            checkCompletion()
                        }
                    }
                } else {
                    // If no user query, mark user search as complete immediately
                    _uiState.value = _uiState.value.copy(
                        userResults = emptyList(),
                        lastQuery = sanitizedQuery
                    )
                    checkCompletion()
                }

                // Launch tweet search and update UI as soon as it completes
                launch {
                    try {
                        val tweetResults = TweetCacheManager.searchTweets(sanitizedQuery, TWEET_RESULT_LIMIT)
                        _uiState.value = _uiState.value.copy(
                            tweetResults = tweetResults,
                            lastQuery = sanitizedQuery
                        )
                        checkCompletion()
                    } catch (e: Exception) {
                        Timber.tag("SearchViewModel").e(e, "Tweet search failed")
                        checkCompletion()
                    }
                }
            } catch (e: Exception) {
                Timber.tag("SearchViewModel").e(e)
                _uiState.value = _uiState.value.copy(
                    userResults = emptyList(),
                    tweetResults = emptyList(),
                    lastQuery = sanitizedQuery,
                    isLoading = false,
                    hasError = true
                )
            }
        }
    }

    private suspend fun fetchExactUser(query: String): User? {
        return try {
            val exactId = getUserId(query) ?: getUserId(query.lowercase())
            exactId?.let { getUser(it) }
        } catch (e: Exception) {
            Timber.tag("SearchViewModel").v(e, "Exact user lookup failed for query: $query")
            null
        }
    }

    private fun mergeUserResults(exactUser: User?, localUsers: List<User>): List<User> {
        val merged = LinkedHashMap<String, User>(USER_RESULT_LIMIT)
        if (exactUser != null) {
            merged[exactUser.mid] = exactUser
        }
        for (user in localUsers) {
            if (merged.size >= USER_RESULT_LIMIT) break
            merged.putIfAbsent(user.mid, user)
        }
        return merged.values.take(USER_RESULT_LIMIT)
    }

    companion object {
        private const val USER_RESULT_LIMIT = 25
        private const val TWEET_RESULT_LIMIT = 40
    }
}