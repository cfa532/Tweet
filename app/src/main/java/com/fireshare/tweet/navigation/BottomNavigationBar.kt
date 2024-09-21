package com.fireshare.tweet.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Home
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.tweet.guestWarning
import com.fireshare.tweet.viewmodel.BadgeViewModel
import kotlinx.coroutines.launch

@Composable
fun BottomNavigationBar(
    navController: NavController,
    selectedIndex: Int = 100,
    badgeViewModel: BadgeViewModel = hiltViewModel()
) {
    var selectedItemIndex by rememberSaveable { mutableIntStateOf(0) }
    val badgeCount by badgeViewModel.badgeCount.collectAsState()

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
        )
    )
    NavigationBar {
        val scope = rememberCoroutineScope()

        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = index == selectedIndex,
                label = { Text(text = item.title) },
                onClick = {
                    selectedItemIndex = index
                    if (appUser.mid == TW_CONST.GUEST_ID && index>0) {
                        scope.launch {
                            guestWarning(navController)
                        }
                        return@NavigationBarItem
                    }
                    navController.navigate(item.route) },
                icon = {
                    BadgedBox(
                        badge = {
                            if (item.badgeCount != null) {
                                Badge {
                                    Text(text = item.badgeCount.toString())
                                }
                            } else if(item.hasNews) {
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
