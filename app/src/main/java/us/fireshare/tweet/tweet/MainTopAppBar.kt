package us.fireshare.tweet.tweet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.navigation.NavHostController
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.profile.AppIcon
import us.fireshare.tweet.profile.SimpleAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopAppBar(
    navController: NavHostController,
    onScrollToTop: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    CenterAlignedTopAppBar(
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.primary,
        ),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = {
                            onScrollToTop?.invoke()
                        })
                ) {
                    AppIcon()
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = {
                if (appUser.isGuest()) {
                    navController.navigate(NavTweet.Login)
                } else {
                    navController.navigate(NavTweet.UserProfile(appUser.mid))
                }
            }) {
                SimpleAvatar(user = appUser, size = 32)
            }
        },
        actions = {
            IconButton(onClick = { navController.navigate(NavTweet.Settings) }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.surfaceTint
                )
            }
        },
    )
}