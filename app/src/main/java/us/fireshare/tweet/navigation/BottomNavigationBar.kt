package us.fireshare.tweet.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.tweet.guestWarning
import us.fireshare.tweet.viewmodel.BottomBarViewModel

@Composable
fun BottomNavigationBar(
    modifier: Modifier = Modifier,
    navController: NavController,
    selectedIndex: Int = 100,
    bottomBarViewModel: BottomBarViewModel = hiltViewModel()
) {
    var selectedItemIndex by rememberSaveable { mutableIntStateOf(0) }
    val badgeCount by bottomBarViewModel.badgeCount.collectAsState()

    val items = listOf(
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
    NavigationBar(
        modifier = modifier
    ) {
        val context = LocalContext.current

        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = index == selectedIndex,
//                label = { Text(text = item.title) },
                onClick = {
                    selectedItemIndex = index
                    if (appUser.isGuest() && index > 0) {
                        bottomBarViewModel.viewModelScope.launch {
                            guestWarning(context, navController)
                        }
                        return@NavigationBarItem
                    }
                    val currentRoute = navController.currentBackStackEntry?.destination?.route
                    if (currentRoute != null) {
                        // if in the same route as the destination, do nothing
                        if (!currentRoute.contains(item.route.toString()) ) {
                            if (item.route == NavTweet.ChatList) {
                                bottomBarViewModel.updateBadgeCount(0)
                            }
                            navController.navigate(item.route)
                        }
                    }
                },
                icon = {
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
                            imageVector = if (index == selectedIndex) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.title
                        )
                    }
                }
            )
        }
    }
}

data class BottomNavigationItem(
    val title: String,
    val route: NavTweet,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val hasNews: Boolean,
    val badgeCount: Int? = null
)
