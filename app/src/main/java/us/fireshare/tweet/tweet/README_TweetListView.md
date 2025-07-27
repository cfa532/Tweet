# TweetListView Component

A self-contained, reusable, iOS-like TweetListView component for displaying lists of tweets in your Android app with advanced infinite scroll, gesture detection, and loading management.

## Features

- **Self-contained state management** - Handles pagination, scroll, pull-to-refresh, and load more internally
- **Simplified API** - Single `fetchTweets(pageNumber)` suspend function for all loading operations
- **Automatic page management** - Internal `lastLoadedPage` state tracks pagination automatically
- **Pull-to-refresh functionality** - Swipe down to refresh the tweet list (page 0)
- **Advanced infinite scrolling** - Automatically load more tweets when reaching the bottom
- **Manual gesture detection** - Scroll up gesture at the bottom triggers loadmore when server is depleted
- **Smart debouncing** - Prevents duplicate loadmore requests with page-based deduplication
- **Minimum spinner display time** - Ensures loading spinner is visible for at least 1 second
- **Server depletion detection** - Tracks when server has no more data and allows manual confirmation
- **Scroll position preservation** - Maintain scroll position across configuration changes
- **Loading states** - Visual indicators for top and bottom loading states with timeouts
- **TopAppBar integration** - Seamless integration with Material3 TopAppBar scroll behavior
- **Customizable** - Flexible parameters for different use cases
- **Private tweet filtering** - Option to show/hide private tweets
- **Header content support** - Custom header content (e.g., profile details, pinned tweets)
- **External gesture detection** - Callbacks for external components to trigger loadmore

## Components

### 1. TweetListView (Main Component)

The main self-contained component with full functionality:

```kotlin
@Composable
fun TweetListView(
    tweets: List<Tweet>,
    fetchTweets: suspend (Int) -> List<Tweet?>, // Changed to suspend function
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = 60.dp),
    showPrivateTweets: Boolean = false,
    parentEntry: NavBackStackEntry? = null,
    onScrollStateChange: ((ScrollState) -> Unit)? = null,
    currentUserId: MimeiId? = null, // Add current user ID to detect user changes
    onTweetUnavailable: ((MimeiId) -> Unit)? = null, // Callback when tweet becomes unavailable
    headerContent: (@Composable () -> Unit)? = null, // Optional header content
    onIsAtLastTweetChange: ((Boolean) -> Unit)? = null, // Callback for external gesture detection
    onTriggerLoadMore: (() -> Unit)? = null, // Callback to trigger manual loadmore
)
```

## Advanced Infinite Scroll Algorithm

### Core Components

1. **Page-based Deduplication** (`pendingLoadMorePage`)
   - Tracks which page is currently being loaded
   - Prevents duplicate requests for the same page
   - More efficient than time-based debouncing

2. **Server Depletion Detection** (`serverDepleted`)
   - Tracks when server has no more data
   - Allows manual confirmation via gestures
   - Prevents infinite loading loops

3. **Precise Last Tweet Detection** (`isAtLastTweet`)
   - Uses `derivedStateOf` to check if the very last item is visible
   - More precise than checking if "near bottom"
   - Triggers infinite scroll only when exactly at the last tweet

4. **Manual Gesture Detection**
   - Transparent overlay appears when at last tweet and server depleted
   - Detects upward scroll gestures using `PointerInput`
   - 500ms debouncing prevents multiple rapid triggers
   - 20px threshold for gesture sensitivity

5. **Minimum Spinner Display Time**
   - Spinner shows for at least 1 second regardless of server response time
   - Ensures users see the loading feedback
   - Prevents flickering for fast responses

### Loading States

- **`isRefreshingAtTop`** - Pull-to-refresh state with 10-second timeout
- **`isRefreshingAtBottom`** - Infinite scroll state with 10-second timeout
- **`pendingLoadMorePage`** - Page-based deduplication
- **`externalLoadMoreRequest`** - External gesture trigger

### Algorithm Flow

1. **Initialization**
   - Load enough tweets (minimum 4) for new users
   - Reset `lastLoadedPage` and `serverDepleted` flags
   - 10-second timeout protection

2. **Infinite Scroll Trigger**
   - When `isAtLastTweet` becomes true
   - Only if `!serverDepleted` and `!isRefreshingAtBottom`
   - Uses page-based deduplication

3. **Manual Gesture Trigger**
   - When at last tweet AND `serverDepleted` is true
   - Transparent overlay captures upward gestures
   - 500ms debouncing prevents rapid triggers
   - Always attempts at least one page load for confirmation

4. **Preload Mechanism**
   - Silently loads next page when approaching bottom
   - Uses same deduplication mechanism
   - Improves perceived performance

5. **Loading Completion**
   - Updates `lastLoadedPage` only if valid tweets found
   - Resets `serverDepleted` flag if new data found
   - Ensures minimum 1-second spinner display

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
        fetchTweets = { pageNumber ->
            viewModel.fetchTweets(pageNumber) // Suspend function
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
            fetchTweets = { pageNumber ->
                viewModel.fetchTweets(pageNumber)
            },
            scrollBehavior = scrollBehavior,
            parentEntry = parentEntry,
            modifier = Modifier.padding(padding)
        )
    }
}
```

### Profile Screen with Header Content

```kotlin
@Composable
fun ProfileScreen(
    parentEntry: NavBackStackEntry,
    viewModel: UserViewModel
) {
    val tweets by viewModel.tweets.collectAsState()
    val pinnedTweets by viewModel.pinnedTweets.collectAsState()
    
    // Create header content with profile details and pinned tweets
    val headerContent: @Composable () -> Unit = {
        Column {
            ProfileDetail(viewModel, navController)
            
            if (pinnedTweets.isNotEmpty()) {
                // Pinned tweets section
                pinnedTweets.forEach { tweet ->
                    TweetItem(tweet, parentEntry)
                }
            }
        }
    }
    
    TweetListView(
        tweets = tweets,
        fetchTweets = { pageNumber ->
            viewModel.fetchTweets(pageNumber)
        },
        showPrivateTweets = true, // Show private tweets in profile
        parentEntry = parentEntry,
        headerContent = headerContent,
        currentUserId = user.mid,
        onTweetUnavailable = { tweetId ->
            viewModel.removeTweetFromAllLists(tweetId)
        }
    )
}
```

### External Gesture Detection

```kotlin
@Composable
fun FollowingsTweet(
    parentEntry: NavBackStackEntry,
    viewModel: TweetFeedViewModel
) {
    val tweets by viewModel.tweets.collectAsState()
    
    // State for external gesture detection
    var isAtLastTweet by remember { mutableStateOf(false) }
    
    TweetListView(
        tweets = tweets,
        fetchTweets = { pageNumber ->
            viewModel.fetchTweets(pageNumber)
        },
        parentEntry = parentEntry,
        onIsAtLastTweetChange = { isAtLast ->
            isAtLastTweet = isAtLast
        },
        onTriggerLoadMore = {
            // External trigger for loadmore
        }
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
    scrollBehavior = scrollBehavior,
    viewModel = viewModel
)

// New way - Self-contained with gesture detection
TweetListView(
    tweets = viewModel.tweets.collectAsState().value,
    fetchTweets = { pageNumber ->
        viewModel.fetchTweets(pageNumber)
    },
    scrollBehavior = scrollBehavior,
    parentEntry = parentEntry,
    onScrollStateChange = { viewModel.updateScrollPosition(it) }
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

// New way - Self-contained with header content
TweetListView(
    tweets = tweets,
    fetchTweets = { pageNumber ->
        viewModel.fetchTweets(pageNumber)
    },
    scrollBehavior = scrollBehavior,
    showPrivateTweets = appUser.mid == userId,
    headerContent = { /* Profile details and pinned tweets */ }
)
```

## Benefits

1. **Simplified API** - Single suspend function for all loading operations
2. **Self-contained** - No need to manage loading states or page numbers externally
3. **Automatic pagination** - Internal page state management eliminates manual tracking
4. **Advanced gesture detection** - Manual loadmore triggers when server is depleted
5. **Smart debouncing** - Page-based deduplication prevents redundant requests
6. **Consistent UX** - Unified tweet list behavior across the app
7. **Performance optimized** - Preload mechanism and efficient state management
8. **iOS-like behavior** - Familiar pull-to-refresh and infinite scroll patterns
9. **Flexible** - Header content support for profile screens
10. **Robust** - Timeout protection and error handling

## How the New API Works

The TweetListView now uses a single `fetchTweets(pageNumber)` suspend function that handles all loading operations:

- **Page 0**: Called when user pulls to refresh (resets the list)
- **Page 1, 2, 3...**: Called when user scrolls to bottom or makes gesture (loads more content)

The component internally manages:
- `lastLoadedPage` - Tracks the last successfully loaded page
- `serverDepleted` - Indicates when server has no more data
- `pendingLoadMorePage` - Prevents duplicate requests
- `isRefreshingAtBottom` - Shows loading spinner

### Example Implementation

```kotlin
TweetListView(
    tweets = tweets,
    fetchTweets = { pageNumber ->
        // This is a suspend function that returns List<Tweet?>
        viewModel.fetchTweets(pageNumber)
    }
)
```

## Integration with ViewModels

The component works seamlessly with your existing ViewModels:

- `TweetFeedViewModel` for main feed
- `UserViewModel` for user profiles (already integrated in ProfileScreen)
- Any custom ViewModel that provides tweet lists

## Customization

You can customize the component by:

- Adjusting `contentPadding` for different layouts (default: 60dp bottom)
- Setting `showPrivateTweets` for privacy control
- Providing custom `onScrollStateChange` callbacks
- Using different `scrollBehavior` configurations
- Adding `headerContent` for profile screens
- Modifying the background color and styling
- Adjusting gesture sensitivity (currently 20px threshold)
- Changing debounce timing (currently 500ms)

## Performance Considerations

- Uses `LazyColumn` for efficient list rendering
- Implements proper key-based item identification
- Leverages `derivedStateOf` for scroll position calculations
- Uses `snapshotFlow` for efficient scroll tracking
- Implements proper coroutine scoping for async operations
- Self-managed loading states prevent duplicate requests
- Page-based deduplication is more efficient than time-based
- Preload mechanism improves perceived performance
- Minimum spinner display time prevents UI flickering 