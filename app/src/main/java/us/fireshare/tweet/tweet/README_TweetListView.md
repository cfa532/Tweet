# TweetListView Component

A self-contained, reusable, iOS-like TweetListView component for displaying lists of tweets in your Android app.

## Features

- **Self-contained state management** - Handles pagination, scroll, pull-to-refresh, and load more internally
- **Simplified API** - Single `getTweets(pageNumber)` function for all loading operations
- **Automatic page management** - Internal `currentPage` state tracks pagination automatically
- **Pull-to-refresh functionality** - Swipe down to refresh the tweet list (page 0)
- **Infinite scrolling** - Automatically load more tweets when reaching the bottom (currentPage+1)
- **Scroll position preservation** - Maintain scroll position across configuration changes
- **Loading states** - Visual indicators for top and bottom loading states
- **TopAppBar integration** - Seamless integration with Material3 TopAppBar scroll behavior
- **Customizable** - Flexible parameters for different use cases
- **Private tweet filtering** - Option to show/hide private tweets

## Components

### 1. TweetListView (Main Component)

The main self-contained component with full functionality:

```kotlin
@Composable
fun TweetListView(
    tweets: List<Tweet>,
    getTweets: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onScrollPositionChange: ((Pair<Int, Int>) -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 60.dp),
    showPrivateTweets: Boolean = false,
    parentEntry: NavBackStackEntry? = null,
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
    getTweets: (Int) -> Unit,
    onScrollPositionChange: ((Pair<Int, Int>) -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
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
    
    TweetListView(
        tweets = tweets,
        getTweets = { pageNumber ->
            if (pageNumber == 0) {
                viewModel.refresh()
            } else {
                viewModel.loadMore()
            }
        },
        parentEntry = parentEntry
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
            getTweets = { pageNumber ->
                if (pageNumber == 0) {
                    viewModel.refresh()
                } else {
                    viewModel.loadMore()
                }
            },
            scrollBehavior = scrollBehavior,
            parentEntry = parentEntry,
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
    
    UserTweetListView(
        tweets = tweets,
        pinnedTweets = pinnedTweets,
        parentEntry = parentEntry,
        getTweets = { pageNumber ->
            if (pageNumber == 0) {
                viewModel.refreshTweets()
            } else {
                viewModel.loadMoreTweets()
            }
        },
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

// New way - Self-contained
TweetListView(
    tweets = viewModel.tweets.collectAsState().value,
    getTweets = { pageNumber ->
        if (pageNumber == 0) {
            viewModel.loadNewerTweets()
        } else {
            viewModel.loadOlderTweets()
        }
    },
    onScrollPositionChange = { viewModel.updateScrollPosition(it) },
    scrollBehavior = scrollBehavior,
    parentEntry = parentEntry
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

// New way - Self-contained
UserTweetListView(
    tweets = tweets,
    pinnedTweets = pinnedTweets,
    parentEntry = parentEntry,
    getTweets = { pageNumber ->
        if (pageNumber == 0) {
            viewModel.loadNewerTweets()
        } else {
            viewModel.loadOlderTweets()
        }
    },
    scrollBehavior = scrollBehavior,
    showPrivateTweets = appUser.mid == userId
)
```

## Benefits

1. **Simplified API** - Single function for all loading operations instead of separate callbacks
2. **Self-contained** - No need to manage loading states or page numbers externally
3. **Automatic pagination** - Internal page state management eliminates manual tracking
4. **Consistency** - Unified tweet list behavior across the app
5. **Reusability** - Single component for multiple use cases
6. **Maintainability** - Centralized tweet list logic
7. **iOS-like UX** - Familiar pull-to-refresh and infinite scroll behavior
8. **Performance** - Optimized with proper state management and lazy loading
9. **Flexibility** - Configurable for different scenarios

## How the New API Works

The TweetListView now uses a single `getTweets(pageNumber)` function that handles both refresh and load more operations:

- **Page 0**: Called when user pulls to refresh (resets the list)
- **Page 1, 2, 3...**: Called when user scrolls to bottom (loads more content)

The component internally manages the `currentPage` state:
- On pull-to-refresh: `currentPage = 0`
- On infinite scroll: `currentPage += 1`

### Example Implementation

```kotlin
TweetListView(
    tweets = tweets,
    getTweets = { pageNumber ->
        if (pageNumber == 0) {
            // Refresh: Load newest tweets
            viewModel.loadNewerTweets()
        } else {
            // Load more: Load older tweets
            viewModel.loadOlderTweets()
        }
    }
)
```

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
- Self-managed loading states prevent duplicate requests 