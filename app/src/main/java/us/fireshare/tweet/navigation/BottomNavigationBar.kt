package us.fireshare.tweet.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.ActivityViewModel
import us.fireshare.tweet.BuildConfig
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.service.BadgeStateManager
import us.fireshare.tweet.tweet.guestWarning

data class BottomNavigationItem(
    val title: String,
    val route: NavTweet,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val hasNews: Boolean,
    val badgeCount: Int? = null
)

@Composable
fun BottomNavigationBar(
    modifier: Modifier = Modifier,
    navController: NavController,
    selectedIndex: Int = 100
) {
    val activityViewModel = hiltViewModel<ActivityViewModel>()
    val badgeCount by BadgeStateManager.badgeCount.collectAsState()
    var showUpgradeDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    // Locale for translations
    val locale = androidx.compose.ui.text.intl.Locale.current
    val isJapanese = locale.language == "ja"

    // Stabilize the items list to prevent unnecessary recompositions
    val items = remember {
        listOf(
            BottomNavigationItem(
                title = "Home",
                route = NavTweet.TweetFeed,
                selectedIcon = Icons.Filled.Home,
                unselectedIcon = Icons.Outlined.Home,
                hasNews = false,
            ),
            BottomNavigationItem(
                title = "Chat",
                route = NavTweet.ChatList,
                selectedIcon = Icons.Filled.Email,
                unselectedIcon = Icons.Outlined.Email,
                hasNews = false,
                badgeCount = badgeCount,
            ),
            BottomNavigationItem(
                title = "Post",
                route = NavTweet.ComposeTweet,
                selectedIcon = Icons.Filled.Create,
                unselectedIcon = Icons.Outlined.Create,
                hasNews = true
            ),
            BottomNavigationItem(
                title = "Search",
                route = NavTweet.Search,
                selectedIcon = Icons.Filled.Search,
                unselectedIcon = Icons.Outlined.Search,
                hasNews = false
            )
        )
    }

    // Stabilize the current route to prevent unnecessary recompositions
    val currentRoute by remember {
        derivedStateOf {
            navController.currentBackStackEntry?.destination?.route
        }
    }

    // Stabilize navigation callback to prevent unnecessary recompositions
    val onNavigationClick = remember(navController) {
        { targetRoute: NavTweet ->
            if (appUser.isGuest() && targetRoute != NavTweet.TweetFeed) {
                // Handle guest warning
                return@remember
            }
            
            // Only navigate if we're not already on the target route
            if (currentRoute != targetRoute.toString()) {
                if (targetRoute == NavTweet.ChatList) {
                    BadgeStateManager.clearBadge()
                }
                navController.navigate(targetRoute)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp) // Reduced height from default 80dp
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Top shadow line as divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(1.dp)
                .background(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp) // Reduced padding
                .padding(top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val context = LocalContext.current

            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex
                val baseSize = when (index) {
                    0 -> 28.dp  // Home button - larger
                    2 -> 20.dp  // Compose button - smaller
                    else -> 24.dp  // Default size for others
                }
                val finalSize = if (isSelected) baseSize + 4.dp else baseSize

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .fillMaxHeight() // Full height touchable area
                        .clickable {
                            if (appUser.isGuest() && index > 0) {
                                // Use a coroutine scope for guest warning
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
                                    .launch {
                                        guestWarning(context, navController)
                                    }
                                return@clickable
                            }
                            
                            // Check upgrade requirement before navigating to compose
                            if (item.route == NavTweet.ComposeTweet && BuildConfig.IS_MINI_VERSION) {
                                Timber.tag("UpgradeCheck")
                                    .d("Mini version detected - isGuest: ${appUser.isGuest()}, tweetCount: ${appUser.tweetCount}")
                                if (!appUser.isGuest() && appUser.tweetCount > 5) {
                                    Timber.tag("UpgradeCheck").d("Showing upgrade dialog")
                                    showUpgradeDialog = true
                                    return@clickable
                                } else {
                                    Timber.tag("UpgradeCheck")
                                        .d("Upgrade not required - isGuest: ${appUser.isGuest()}, tweetCount: ${appUser.tweetCount}")
                                }
                            }
                            
                            onNavigationClick(item.route)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    BadgedBox(
                        badge = {
                            if (item.badgeCount != null && item.badgeCount > 0) {
                                Badge {
                                    Text(text = item.badgeCount.toString())
                                }
                            } else if (item.hasNews) {
                                Badge()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.title,
                            modifier = Modifier.size(finalSize),
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(
                                alpha = 0.6f
                            )
                        )
                    }
                }
            }
        }
    }
    
    // Upgrade required dialog
    if (showUpgradeDialog) {
        AlertDialog(
            onDismissRequest = { showUpgradeDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Upgrade,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = if (isJapanese) "アップグレードが必要です" else "需要升級",
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    text = if (isJapanese) {
                        "新しいツイートを投稿するには、完全版へのアップグレードが必要です（5つ以上のツイートがあります）。サーバーから最新版をダウンロードします。"
                    } else {
                        "您已發布超過5條推文，需要升級到完整版才能繼續發布。將從服務器下載最新版本。"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        // Trigger immediate server upgrade check (no delay)
                        activityViewModel.checkForUpgrade(context, immediate = true)
                        showUpgradeDialog = false
                    }
                ) {
                    Text(if (isJapanese) "今すぐアップグレード" else "立即升級")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUpgradeDialog = false }
                ) {
                    Text(if (isJapanese) "キャンセル" else "取消")
                }
            }
        )
    }
}