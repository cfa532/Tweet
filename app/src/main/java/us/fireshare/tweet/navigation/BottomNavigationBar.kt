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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.ActivityViewModel
import us.fireshare.tweet.BuildConfig
import us.fireshare.tweet.HproseInstance.appUserState
import us.fireshare.tweet.R
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
    // Observe appUser changes via StateFlow
    val appUser by appUserState.collectAsState()
    val activityViewModel = hiltViewModel<ActivityViewModel>()
    val badgeCount by BadgeStateManager.badgeCount.collectAsState()
    var showUpgradeDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val guestReminderText = stringResource(R.string.guest_reminder)

    // Items list - must depend on badgeCount to update when badge changes
    val items = remember(badgeCount) {
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

    // Track current route from NavController state
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Navigation callback uses latest route state
    val onNavigationClick: (NavTweet) -> Unit = onNavigationClick@{ targetRoute ->
        if (appUser.isGuest() && targetRoute != NavTweet.TweetFeed) {
            // Handle guest warning
            return@onNavigationClick
        }
        
        // Only navigate if we're not already on the target route
        val targetRouteName = targetRoute::class.qualifiedName
        if (currentRoute != targetRouteName) {
            if (targetRoute == NavTweet.ChatList) {
                BadgeStateManager.clearBadge()
            }
            // Preserve scroll/state when switching tabs (main feed should keep position)
            navController.navigate(targetRoute) {
                launchSingleTop = true
                restoreState = true
                popUpTo(navController.graph.startDestinationId) {
                    saveState = true
                }
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
                                        guestWarning(context, navController, guestReminderText)
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
            onDismissRequest = { },
            icon = {
                Icon(
                    imageVector = Icons.Default.Upgrade,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.upgrade_required_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.upgrade_required_message)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        // Trigger immediate server upgrade check (no delay)
                        Timber.tag("UpgradeButton").d("Upgrade button clicked")
                        activityViewModel.checkForMiniUpgrade(context)
                    }
                ) {
                    Text(stringResource(R.string.upgrade_now))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}