package us.fireshare.tweet.service

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.fetchUser
import us.fireshare.tweet.HproseInstance.getUserId
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.datamodel.TweetCacheManager
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.profile.UserAvatar
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
    val listState = rememberLazyListState()
    val searchLabel = stringResource(R.string.search)
    val submitSearch = {
        viewModel.submitSearch()
        focusManager.clearFocus()
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) focusManager.clearFocus()
    }
    LaunchedEffect(uiState.isLoading) {
        if (uiState.isLoading) listState.scrollToItem(0)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(stringResource(R.string.search), style = MaterialTheme.typography.titleMedium)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            BottomNavigationBar(navController = navController, selectedIndex = selectedBottomBarItemIndex)
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BasicTextField(
                    value = uiState.query,
                    onValueChange = viewModel::updateQuery,
                    modifier = Modifier.weight(1f).semantics { contentDescription = searchLabel },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                    decorationBox = { innerTextField ->
                        Row(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                                .defaultMinSize(minHeight = 48.dp)
                                .padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Search, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Box(Modifier.weight(1f)) {
                                innerTextField()
                            }
                            if (uiState.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.updateQuery("") }) {
                                    Icon(
                                        Icons.Default.Cancel,
                                        contentDescription = stringResource(R.string.clear),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                )
                TextButton(
                    onClick = submitSearch,
                    enabled = uiState.query.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(stringResource(R.string.search))
                }
            }

            when {
                uiState.isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
                    ) {
                        CircularProgressIndicator(Modifier.size(40.dp))
                        Text(
                            stringResource(R.string.search_countdown, uiState.countdownSeconds),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                uiState.hasError -> SearchEmptyState(
                    title = stringResource(R.string.search_generic_error),
                    subtitle = stringResource(R.string.try_different_search),
                    onTap = { focusManager.clearFocus() }
                )
                uiState.userResults.isEmpty() && uiState.tweetResults.isEmpty() -> SearchEmptyState(
                    title = stringResource(
                        if (uiState.submittedQuery == uiState.query.trim()) R.string.search_no_results else R.string.search
                    ),
                    subtitle = stringResource(
                        if (uiState.submittedQuery == uiState.query.trim()) R.string.try_different_search else R.string.search_hint
                    ),
                    onTap = { focusManager.clearFocus() }
                )
                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        if (uiState.userResults.isNotEmpty()) {
                            item("users_header") {
                                SearchSectionHeader(stringResource(R.string.search_section_users))
                            }
                            itemsIndexed(uiState.userResults, key = { _, user -> "user_${user.mid}" }) { index, user ->
                                UserSearchResult(user, navController)
                                if (index < uiState.userResults.lastIndex) SearchResultDivider()
                            }
                        }
                        if (uiState.tweetResults.isNotEmpty()) {
                            item("tweets_header") {
                                if (uiState.userResults.isNotEmpty()) Spacer(Modifier.height(16.dp))
                                SearchSectionHeader(stringResource(R.string.search_section_tweets))
                            }
                            itemsIndexed(uiState.visibleTweets, key = { _, tweet -> "tweet_${tweet.mid}" }) { index, tweet ->
                                TweetSearchResult(tweet, navController)
                                if (index < uiState.visibleTweets.lastIndex) SearchResultDivider()
                            }
                            if (uiState.hasMoreTweets) {
                                item("more_tweets") {
                                    TextButton(onClick = viewModel::loadNextTweetPage, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.search_load_more))
                                    }
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
private fun SearchEmptyState(title: String, subtitle: String, onTap: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = onTap
        ).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        Icon(
            Icons.Default.Search, contentDescription = null, modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(title, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun UserSearchResult(user: User, navController: NavController) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable {
            focusManager.clearFocus()
            navController.navigate(NavTweet.UserProfile(user.mid))
        }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        UserAvatar(user = user, size = 40)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                user.name?.takeIf { it.isNotBlank() }?.let { name ->
                    Text(name, modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                user.username?.takeIf { it.isNotBlank() }?.let { username ->
                    Text("@$username", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            user.profile?.takeIf { it.isNotBlank() }?.let { bio ->
                Text(bio, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        SearchResultChevron()
    }
}

@Composable
fun TweetSearchResult(tweet: Tweet, navController: NavController) {
    val author = tweet.author ?: User(mid = tweet.authorId)
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable {
            focusManager.clearFocus()
            navController.navigate(NavTweet.TweetDetail(tweet.authorId, tweet.mid))
        }.padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UserAvatar(user = author, size = 32)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    author.name?.takeIf { it.isNotBlank() }?.let { name ->
                        Text(name, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    author.username?.takeIf { it.isNotBlank() }?.let { handle ->
                        Text("@$handle", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val preview = tweet.content?.takeIf { it.isNotBlank() } ?: tweet.title?.takeIf { it.isNotBlank() }
            preview?.let { text ->
                Text(text, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        SearchResultChevron()
    }
}

@Composable
private fun SearchResultChevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
        modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SearchResultDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}

@Composable
private fun SearchSectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}

data class SearchUiState(
    val query: String = "",
    // Empty results only describe the submitted query, not text still being edited.
    val submittedQuery: String? = null,
    val userResults: List<User> = emptyList(),
    val tweetResults: List<Tweet> = emptyList(),
    val isLoading: Boolean = false,
    val hasError: Boolean = false,
    val countdownSeconds: Int = 30,
    val visibleTweetCount: Int = TWEET_PAGE_SIZE
) {
    val visibleTweets: List<Tweet> get() = tweetResults.take(visibleTweetCount)
    val hasMoreTweets: Boolean get() = visibleTweetCount < tweetResults.size
}

private const val TWEET_PAGE_SIZE = 20

@HiltViewModel
class SearchViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()
    private var searchJob: Job? = null

    fun updateQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query, hasError = false)
    }

    fun loadNextTweetPage() {
        val state = _uiState.value
        if (state.hasMoreTweets) {
            _uiState.value = state.copy(visibleTweetCount = state.visibleTweetCount + TWEET_PAGE_SIZE)
        }
    }

    fun submitSearch() {
        // A newer submission owns the results, including an empty query that clears them.
        searchJob?.cancel()
        val rawQuery = _uiState.value.query
        val sanitizedQuery = rawQuery.trim()

        if (sanitizedQuery.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                submittedQuery = null,
                userResults = emptyList(),
                tweetResults = emptyList(),
                isLoading = false,
                hasError = false
            )
            return
        }

        // Serialize state updates with text edits and other submissions on main.
        searchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                submittedQuery = sanitizedQuery,
                isLoading = true,
                hasError = false,
                userResults = emptyList(),
                tweetResults = emptyList(),
                countdownSeconds = SEARCH_TIMEOUT_SECONDS,
                visibleTweetCount = TWEET_PAGE_SIZE
            )

            try {
                val isUsernameOnly = sanitizedQuery.startsWith("@")
                val userQuery = sanitizedQuery.removePrefix("@").trim()

                val queries = launch {
                    if (userQuery.isNotEmpty()) {
                        launch {
                            try {
                                val mergedUsers = withContext(IO) {
                                    val exactUser = if (isUsernameOnly || !userQuery.contains(" ")) {
                                        fetchExactUser(userQuery)
                                    } else {
                                        null
                                    }
                                    val localUsers = TweetCacheManager.searchUsers(userQuery, USER_RESULT_LIMIT)
                                    mergeUserResults(exactUser, localUsers)
                                }
                                ensureActive()
                                _uiState.value = _uiState.value.copy(userResults = mergedUsers)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.tag("SearchViewModel").e(e, "User search failed")
                            }
                        }
                    }

                    if (!isUsernameOnly) {
                        launch {
                            try {
                                val tweetResults = TweetCacheManager.searchTweets(sanitizedQuery, TWEET_RESULT_LIMIT)
                                ensureActive()
                                _uiState.value = _uiState.value.copy(tweetResults = tweetResults)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.tag("SearchViewModel").e(e, "Tweet search failed")
                            }
                        }
                    }
                }
                val countdown = launch {
                    for (remaining in SEARCH_TIMEOUT_SECONDS - 1 downTo 0) {
                        delay(1_000)
                        _uiState.value = _uiState.value.copy(countdownSeconds = remaining)
                    }
                    // Stop the spinner at the deadline even if an underlying RPC is
                    // still unwinding cancellation; only completed results remain.
                    queries.cancel()
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
                queries.join()
                countdown.cancel()
                ensureActive()
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("SearchViewModel").e(e)
                _uiState.value = _uiState.value.copy(
                    userResults = emptyList(),
                    tweetResults = emptyList(),
                    isLoading = false,
                    hasError = true
                )
            }
        }
    }

    private suspend fun fetchExactUser(query: String): User? {
        return try {
            val exactId = getUserId(query) ?: getUserId(query.lowercase())
            // Use skipRetryAndBlacklist = true for search operations - don't retry or update blacklist
            exactId?.let { fetchUser(it, skipRetryAndBlacklist = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("SearchViewModel").v(e, "Exact user lookup failed for query: $query")
            null
        }
    }

    private fun mergeUserResults(exactUser: User?, localUsers: List<User>): List<User> {
        val merged = LinkedHashMap<String, User>(USER_RESULT_LIMIT)
        // Validate exact user has username before adding (like iOS)
        if (exactUser != null && !exactUser.username.isNullOrBlank()) {
            merged[exactUser.mid] = exactUser
        }
        for (user in localUsers) {
            if (merged.size >= USER_RESULT_LIMIT) break
            merged.putIfAbsent(user.mid, user)
        }
        return merged.values.take(USER_RESULT_LIMIT)
    }

    companion object {
        private const val SEARCH_TIMEOUT_SECONDS = 30
        private const val USER_RESULT_LIMIT = 25
        private const val TWEET_RESULT_LIMIT = 40
    }
}
