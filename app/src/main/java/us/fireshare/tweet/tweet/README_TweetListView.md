# TweetListView Component

A reusable, iOS-like TweetListView component for displaying lists of tweets in your Android app.

## Features

- **Pull-to-refresh functionality** - Swipe down to refresh the tweet list
- **Infinite scrolling** - Automatically load more tweets when reaching the bottom
- **Scroll position preservation** - Maintain scroll position across configuration changes
- **Loading states** - Visual indicators for top and bottom loading states
- **TopAppBar integration** - Seamless integration with Material3 TopAppBar scroll behavior
- **Customizable** - Flexible parameters for different use cases
- **Private tweet filtering** - Option to show/hide private tweets

## Components

### 1. TweetListView (Main Component)

The main component with full functionality:

```kotlin
@Composable
fun TweetListView(
    tweets: List<Tweet>,
    parentEntry: NavBackStackEntry,
    listState: LazyListState,
    isRefreshingAtTop: Boolean = false,
    isRefreshingAtBottom: Boolean = false,
    onRefreshTop: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    onScrollPositionChange: ((Pair<Int, Int>) -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 60.dp),
    showPrivateTweets: Boolean = false,
    modifier: Modifier = Modifier
)
```

### 2. SimpleTweetListView

A simplified version for basic tweet list display:

```kotlin
@Composable
fun SimpleTweetListView(
    tweets: List<Tweet>,
    parentEntry: NavBackStackEntry,
    modifier: Modifier = Modifier
)
```

### 3. UserTweetListView

Specialized for user profile screens with pinned tweets support:

```kotlin
@Composable
fun UserTweetListView(
    tweets: List<Tweet>,
    pinnedTweets: List<Tweet> = emptyList(),
    parentEntry: NavBackStackEntry,
    listState: LazyListState,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    isRefreshingAtTop: Boolean = false,
    isRefreshingAtBottom: Boolean = false,
    onRefreshTop: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    showPrivateTweets: Boolean = false,
    modifier: Modifier = Modifier
)
```

## Usage Examples

### Basic Usage

```kotlin
@Composable
fun MyTweetScreen(
    parentEntry: NavBackStackEntry,
    viewModel: TweetFeedViewModel
) {
    val tweets by viewModel.tweets.collectAsState()
    val listState = rememberLazyListState()
    
    TweetListView(
        tweets = tweets,
        parentEntry = parentEntry,
        listState = listState,
        onRefreshTop = { viewModel.refresh() },
        onLoadMore = { viewModel.loadMore() }
    )
}
```

### With TopAppBar Integration

```kotlin
@Composable
fun TweetFeedWithTopBar(
    parentEntry: NavBackStackEntry,
    viewModel: TweetFeedViewModel
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val listState = rememberLazyListState()
    val tweets by viewModel.tweets.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tweets") },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        TweetListView(
            tweets = tweets,
            parentEntry = parentEntry,
            listState = listState,
            scrollBehavior = scrollBehavior,
            onRefreshTop = { viewModel.refresh() },
            onLoadMore = { viewModel.loadMore() },
            modifier = Modifier.padding(padding)
        )
    }
}
```

### User Profile with Pinned Tweets

```kotlin
@Composable
fun UserProfileScreen(
    parentEntry: NavBackStackEntry,
    viewModel: UserViewModel
) {
    val tweets by viewModel.tweets.collectAsState()
    val pinnedTweets by viewModel.pinnedTweets.collectAsState()
    val listState = rememberLazyListState()
    
    UserTweetListView(
        tweets = tweets,
        pinnedTweets = pinnedTweets,
        parentEntry = parentEntry,
        listState = listState,
        onRefreshTop = { viewModel.refreshTweets() },
        onLoadMore = { viewModel.loadMoreTweets() },
        showPrivateTweets = true // Show private tweets for user's own profile
    )
}
```

### Simple List (No Advanced Features)

```kotlin
@Composable
fun SimpleTweetList(
    tweets: List<Tweet>,
    parentEntry: NavBackStackEntry
) {
    SimpleTweetListView(
        tweets = tweets,
        parentEntry = parentEntry
    )
}
```

## Migration from Existing Components

### Replacing FollowingsTweet

Instead of using the existing `FollowingsTweet` component:

```kotlin
// Old way
FollowingsTweet(
    parentEntry = parentEntry,
    listState = listState,
    scrollBehavior = scrollBehavior,
    viewModel = viewModel
)

// New way
TweetListView(
    tweets = viewModel.tweets.collectAsState().value,
    parentEntry = parentEntry,
    listState = listState,
    scrollBehavior = scrollBehavior,
    isRefreshingAtTop = viewModel.isRefreshingAtTop.collectAsState().value,
    isRefreshingAtBottom = viewModel.isRefreshingAtBottom.collectAsState().value,
    onRefreshTop = { viewModel.loadNewerTweets() },
    onLoadMore = { viewModel.loadOlderTweets() },
    onScrollPositionChange = { viewModel.updateScrollPosition(it) }
)
```

### Replacing Profile Screen Tweet List

```kotlin
// Old way - Manual LazyColumn implementation
LazyColumn(
    modifier = Modifier.fillMaxWidth()
        .nestedScroll(scrollBehavior.nestedScrollConnection),
    state = listState
) {
    items(tweets, key = { it.timestamp }) { tweet ->
        if (!tweet.isPrivate || appUser.mid == tweet.authorId) {
            TweetItem(tweet, parentEntry)
        }
    }
    // ... loading indicators
}

// New way
UserTweetListView(
    tweets = tweets,
    pinnedTweets = pinnedTweets,
    parentEntry = parentEntry,
    listState = listState,
    scrollBehavior = scrollBehavior,
    isRefreshingAtTop = isRefreshingAtTop,
    isRefreshingAtBottom = isRefreshingAtBottom,
    onRefreshTop = { viewModel.loadNewerTweets() },
    onLoadMore = { viewModel.loadOlderTweets() },
    showPrivateTweets = appUser.mid == userId
)
```

## Benefits

1. **Consistency** - Unified tweet list behavior across the app
2. **Reusability** - Single component for multiple use cases
3. **Maintainability** - Centralized tweet list logic
4. **iOS-like UX** - Familiar pull-to-refresh and infinite scroll behavior
5. **Performance** - Optimized with proper state management and lazy loading
6. **Flexibility** - Configurable for different scenarios

## Integration with ViewModels

The component works seamlessly with your existing ViewModels:

- `TweetFeedViewModel` for main feed
- `UserViewModel` for user profiles
- Any custom ViewModel that provides tweet lists

## Customization

You can customize the component by:

- Adjusting `contentPadding` for different layouts
- Setting `showPrivateTweets` for privacy control
- Providing custom `onScrollPositionChange` callbacks
- Using different `scrollBehavior` configurations
- Modifying the background color and styling

## Performance Considerations

- Uses `LazyColumn` for efficient list rendering
- Implements proper key-based item identification
- Leverages `derivedStateOf` for scroll position calculations
- Uses `snapshotFlow` for efficient scroll tracking
- Implements proper coroutine scoping for async operations 