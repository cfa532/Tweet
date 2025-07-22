 package us.fireshare.tweet.tweet

 import androidx.compose.foundation.background
 import androidx.compose.foundation.layout.Box
 import androidx.compose.foundation.layout.PaddingValues
 import androidx.compose.foundation.layout.fillMaxSize
 import androidx.compose.foundation.layout.fillMaxWidth
 import androidx.compose.foundation.layout.padding
 import androidx.compose.foundation.layout.size
 import androidx.compose.foundation.layout.wrapContentWidth
 import androidx.compose.foundation.lazy.LazyColumn
 import androidx.compose.foundation.lazy.items
 import androidx.compose.foundation.lazy.rememberLazyListState
 import androidx.compose.material.ExperimentalMaterialApi
 import androidx.compose.material.pullrefresh.PullRefreshIndicator
 import androidx.compose.material.pullrefresh.pullRefresh
 import androidx.compose.material.pullrefresh.rememberPullRefreshState
 import androidx.compose.material3.CircularProgressIndicator
 import androidx.compose.material3.ExperimentalMaterial3Api
 import androidx.compose.material3.MaterialTheme
 import androidx.compose.material3.TopAppBarScrollBehavior
 import androidx.compose.runtime.Composable
 import androidx.compose.runtime.LaunchedEffect
 import androidx.compose.runtime.derivedStateOf
 import androidx.compose.runtime.getValue
 import androidx.compose.runtime.mutableIntStateOf
 import androidx.compose.runtime.mutableStateOf
 import androidx.compose.runtime.remember
 import androidx.compose.runtime.rememberCoroutineScope
 import androidx.compose.runtime.saveable.rememberSaveable
 import androidx.compose.runtime.setValue
 import androidx.compose.runtime.snapshotFlow
 import androidx.compose.ui.Alignment
 import androidx.compose.ui.Modifier
 import androidx.compose.ui.input.nestedscroll.nestedScroll
 import androidx.compose.ui.unit.dp
 import androidx.navigation.NavBackStackEntry
 import kotlinx.coroutines.Dispatchers
 import kotlinx.coroutines.launch
 import kotlinx.coroutines.withContext
 import us.fireshare.tweet.datamodel.Tweet

 /**
  * CommentListView: Specialized list view for displaying tweet comments with Material3 styling.
  * Self-contained with built-in pagination and refresh functionality.
  *
  * @param comments List of comment tweets to display
  * @param getComments Function to load comments for a specific page number
  * @param scrollBehavior Optional TopAppBar scroll behavior
  * @param contentPadding Padding for the list content
  * @param modifier Modifier for the component
  * @param parentEntry Optional NavBackStackEntry for navigation context
  */
 @Composable
 @OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
 fun CommentListView(
     comments: List<Tweet>,
     getComments: (Int) -> Unit,
     modifier: Modifier = Modifier,
     scrollBehavior: TopAppBarScrollBehavior? = null,
     contentPadding: PaddingValues = PaddingValues(bottom = 60.dp),
     parentEntry: NavBackStackEntry? = null,
 ) {
     // Internal state management
     var isRefreshingAtTop by remember { mutableStateOf(false) }
     var isRefreshingAtBottom by remember { mutableStateOf(false) }
     var currentPage by remember { mutableIntStateOf(0) }

     // Remember scroll position across recompositions and configuration changes
     val savedScrollPosition = rememberSaveable { mutableStateOf(Pair(0, 0)) }
     val listState = rememberLazyListState(
         initialFirstVisibleItemIndex = savedScrollPosition.value.first,
         initialFirstVisibleItemScrollOffset = savedScrollPosition.value.second
     )
     val coroutineScope = rememberCoroutineScope()

     val pullRefreshState = rememberPullRefreshState(
         refreshing = isRefreshingAtTop,
         onRefresh = {
             coroutineScope.launch {
                 isRefreshingAtTop = true
                 try {
                     withContext(Dispatchers.IO) {
                         currentPage = 0 // Reset to page 0 for refresh
                         getComments(0)
                     }
                 } finally {
                     isRefreshingAtTop = false
                 }
             }
         }
     )

     val isAtBottom by remember {
         derivedStateOf {
             val layoutInfo = listState.layoutInfo
             val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
             lastVisibleItem != null && lastVisibleItem.index == layoutInfo.totalItemsCount - 1
         }
     }

     // Track scroll position changes and save them
     LaunchedEffect(listState) {
         snapshotFlow { Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
             .collect { position ->
                 savedScrollPosition.value = position
             }
     }

     // Infinite scroll
     LaunchedEffect(isAtBottom) {
         if (isAtBottom && !isRefreshingAtBottom) {
             coroutineScope.launch {
                 isRefreshingAtBottom = true
                 try {
                     withContext(Dispatchers.IO) {
                         currentPage += 1 // Increment page for load more
                         getComments(currentPage)
                     }
                 } finally {
                     isRefreshingAtBottom = false
                 }
             }
         }
     }

     Box(
         modifier = modifier
             .fillMaxSize()
             .background(MaterialTheme.colorScheme.background)
             .pullRefresh(pullRefreshState)
     ) {
         LazyColumn(
             modifier = Modifier
                 .fillMaxSize()
                 .let { if (scrollBehavior != null) it.nestedScroll(scrollBehavior.nestedScrollConnection) else it },
             state = listState,
             contentPadding = contentPadding
         ) {
             items(
                 items = comments,
                 key = { it.mid }
             ) { comment ->
                 parentEntry?.let { CommentItem(comment, null, it) }
             }
             if (isRefreshingAtTop) {
                 item {
                     CircularProgressIndicator(
                         modifier = Modifier
                             .fillMaxWidth()
                             .padding(top = 60.dp)
                             .wrapContentWidth(Alignment.CenterHorizontally)
                             .size(48.dp),
                         color = MaterialTheme.colorScheme.primary,
                         strokeWidth = 4.dp
                     )
                 }
             }
             if (isRefreshingAtBottom) {
                 item {
                     CircularProgressIndicator(
                         modifier = Modifier
                             .fillMaxWidth()
                             .padding(16.dp)
                             .wrapContentWidth(Alignment.CenterHorizontally),
                         color = MaterialTheme.colorScheme.primary,
                         strokeWidth = 4.dp
                     )
                 }
             }
         }
         PullRefreshIndicator(
             refreshing = isRefreshingAtTop,
             state = pullRefreshState,
             modifier = Modifier.align(Alignment.TopCenter),
             backgroundColor = MaterialTheme.colorScheme.surface,
             contentColor = MaterialTheme.colorScheme.primary
         )
     }
 }