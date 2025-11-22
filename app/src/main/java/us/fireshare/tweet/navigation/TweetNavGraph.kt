package us.fireshare.tweet.navigation

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.chat.ChatListScreen
import us.fireshare.tweet.chat.ChatScreen
import us.fireshare.tweet.profile.EditProfileScreen
import us.fireshare.tweet.profile.FollowerScreen
import us.fireshare.tweet.profile.FollowingScreen
import us.fireshare.tweet.profile.LoginScreen
import us.fireshare.tweet.profile.ProfileScreen
import us.fireshare.tweet.profile.SystemSettings
import us.fireshare.tweet.profile.UserBookmarks
import us.fireshare.tweet.profile.UserFavorites
import us.fireshare.tweet.service.SearchScreen
import us.fireshare.tweet.service.SearchViewModel
import us.fireshare.tweet.tweet.ComposeCommentScreen
import us.fireshare.tweet.tweet.ComposeTweetScreen
import us.fireshare.tweet.tweet.TweetDetailScreen
import us.fireshare.tweet.tweet.TweetFeedScreen
import us.fireshare.tweet.viewmodel.ChatListViewModel
import us.fireshare.tweet.viewmodel.ChatViewModel
import us.fireshare.tweet.viewmodel.TweetFeedViewModel
import us.fireshare.tweet.viewmodel.TweetListViewModel
import us.fireshare.tweet.viewmodel.TweetViewModel
import us.fireshare.tweet.viewmodel.UserViewModel
import us.fireshare.tweet.widget.MediaBrowser
import javax.inject.Inject
import kotlin.reflect.typeOf

val LocalNavController = compositionLocalOf<NavController> {
    error("NavController must be provided in a CompositionLocalProvider")
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun TweetNavGraph(
    appLinkIntent: Intent,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    var startDestination: NavTweet = NavTweet.TweetFeed
    val sharedViewModel: SharedViewModel = hiltViewModel()
    sharedViewModel.appUserViewModel =
        hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
            LocalActivity.current as ComponentActivity, key = "${appUser.mid}_${appUser.avatar}_${appUser.name}_${appUser.profile}"
        ) { factory ->
            factory.create(appUser.mid)
        }
    // Use the singleton TweetFeedViewModel from AppModule
    val tweetFeedViewModel: TweetFeedViewModel = hiltViewModel()
    
    // Initialize TweetListViewModel
    sharedViewModel.tweetListViewModel = hiltViewModel<TweetListViewModel>()

    // Handle deeplink
    if (appLinkIntent.action == Intent.ACTION_VIEW) {
        val appLinkData = appLinkIntent.data
        if (appLinkData != null) {
            val pathSegments = appLinkData.pathSegments
            if (pathSegments.size >= 3) { // Check if enough segments are present
                val tweetId = pathSegments[1] // Get the 2nd segment (tweetId)
                val authorId = pathSegments[2] // Get the 3rd segment (authorId)
                tweetId?.let { startDestination = NavTweet.DeepLink(tweetId, authorId) }
            }
        }
    }
    CompositionLocalProvider(LocalNavController provides navController) {
        NavHost(
            modifier = modifier,
            navController = navController,
            startDestination = startDestination
        ) {
            composable<NavTweet.TweetFeed> {
                TweetFeedScreen(navController, it, 0, tweetFeedViewModel)
            }
            composable<NavTweet.TweetDetail> { navBackStackEntry ->
                val args = navBackStackEntry.toRoute<NavTweet.TweetDetail>()
                TweetDetailScreen(args.authorId, args.tweetId, navBackStackEntry)
            }
            composable<NavTweet.ComposeTweet> {
                ComposeTweetScreen(navController)
            }
            composable<ComposeComment> {
                var lastClickTime by remember { mutableLongStateOf(0L) }
                val debounceTime = 500L
                ComposeCommentScreen {
                    // manually prevent fast continuous click of a button
                    // which may produce multiple popBackStack() calls.
                    val currentTime = SystemClock.elapsedRealtime()
                    if (currentTime - lastClickTime > debounceTime) {
                        navController.popBackStack()
                    }
                }
            }
            composable<NavTweet.UserProfile> {
                val parentEntry = remember(it) {
                    it
                }
                val userId = it.toRoute<NavTweet.UserProfile>().userId
                // reassign the appUserViewModel here, in case the user login with
                // a different username.
                sharedViewModel.appUserViewModel =
                    hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(
                        LocalActivity.current as ComponentActivity, key = "${appUser.mid}_${appUser.avatar}_${appUser.name}_${appUser.profile}"
                    ) { factory ->
                        factory.create(appUser.mid)
                    }
                ProfileScreen(navController, userId, parentEntry, sharedViewModel.appUserViewModel)
            }
            composable<ProfileEditor> {
                EditProfileScreen(navController, sharedViewModel.appUserViewModel)
            }
            composable<NavTweet.Favorites> {
                val parentEntry = remember(it) {
                    it
                }
                UserFavorites(sharedViewModel.appUserViewModel, parentEntry)
            }
            composable<NavTweet.Bookmarks> {
                val parentEntry = remember(it) {
                    it
                }
                UserBookmarks(sharedViewModel.appUserViewModel, parentEntry)
            }
            composable<NavTweet.ChatBox> {
                // go to individual chatbox
                val args = it.toRoute<NavTweet.ChatBox>()
                // Use NavBackStackEntry as viewModelStoreOwner for proper lifecycle management
                val viewModel = hiltViewModel<ChatViewModel, ChatViewModel.ChatViewModelFactory>(
                    viewModelStoreOwner = it,
                    key = args.receiptId
                ) { factory ->
                    factory.create(receiptId = args.receiptId)
                }
                // Use activity as ViewModelStoreOwner so ChatListViewModel is shared with ChatList screen
                viewModel.chatListViewModel = hiltViewModel<ChatListViewModel>(
                    LocalActivity.current as ComponentActivity
                )
                ChatScreen(viewModel)
            }
            composable<NavTweet.ChatList> {
                // Use activity as ViewModelStoreOwner so ChatListViewModel is shared with ChatBox screen
                val viewModel = hiltViewModel<ChatListViewModel>(
                    LocalActivity.current as ComponentActivity
                )
                ChatListScreen(viewModel)
            }
            composable<NavTweet.MediaViewer>(
                typeMap = mapOf(typeOf<MediaViewerParams>() to TweetNavType.MediaViewerType)
            ) { navBackStackEntry ->
                val parentEntry = remember(navBackStackEntry) {
                    navBackStackEntry
                }
                val md = navBackStackEntry.toRoute<NavTweet.MediaViewer>()
                MediaBrowser(
                    parentEntry,
                    navController,
                    md.params.index,
                    md.params.tweetId,
                    md.params.authorId
                )
            }

            composable<NavTweet.Login> {
                val register: () -> Unit = {
                    navController.navigate(NavTweet.Registration)
                }
                val scope = rememberCoroutineScope()
                LoginScreen(register) {
                    scope.launch(Dispatchers.Main) {
                        navController.popBackStack()
                    }
                }
            }
            composable<NavTweet.Registration> {
                EditProfileScreen(navController, sharedViewModel.appUserViewModel)
            }
            composable<NavTweet.Settings> {
                SystemSettings(navController, sharedViewModel.appUserViewModel)
            }
            composable<NavTweet.Following> {
                val parentEntry = remember(it) {
                    it
                }
                val user = it.toRoute<NavTweet.Following>()
                FollowingScreen(user.userId, parentEntry, sharedViewModel.appUserViewModel)
            }
            composable<NavTweet.Follower> {
                val parentEntry = remember(it) {
                    it
                }
                val user = it.toRoute<NavTweet.Follower>()
                FollowerScreen(user.userId, parentEntry, sharedViewModel.appUserViewModel)
            }
            composable<NavTweet.Search> {
                val parentEntry = remember(it) {
                    it
                }
                val viewModel: SearchViewModel = hiltViewModel(parentEntry)
                SearchScreen(viewModel)
            }
            /**
             * Deeplink carries the tweetId and authorId only.
             * Need to extract tweet data from Mimei DB.
             * */
            composable<NavTweet.DeepLink> {
                val parentEntry = remember(it) {
                    it
                }
                val tweetId = it.toRoute<NavTweet.DeepLink>().tweetId
                val authorId = it.toRoute<NavTweet.DeepLink>().authorId
                TweetDetailScreen(authorId, tweetId, parentEntry)
            }
        }
    }
}

@HiltViewModel
class SharedViewModel @Inject constructor(
) : ViewModel() {
    lateinit var appUserViewModel: UserViewModel
    lateinit var tweetViewModel: TweetViewModel
    lateinit var tweetListViewModel: TweetListViewModel
}
