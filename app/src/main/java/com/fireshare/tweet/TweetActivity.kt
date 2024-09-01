package com.fireshare.tweet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.rememberAsyncImagePainter
import com.fireshare.tweet.network.HproseInstance.appUser
import com.fireshare.tweet.network.HproseInstance.getMediaUrl
import com.fireshare.tweet.ui.theme.TweetTheme
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.widget.AppIcon
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject


@AndroidEntryPoint
class TweetActivity : ComponentActivity() {

    @Inject
    lateinit var tweetFeedViewModel: TweetFeedViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TweetTheme {
                TweetNavGraph()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopAppBar(
    navController: NavHostController,
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    CenterAlignedTopAppBar(
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
                        .clickable(onClick = { navController.navigate(TweetFeed) })
                ) {
                    AppIcon()
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = { navController.navigate(UserProfile(appUser.mid)) })
            {
                appUser.baseUrl?.let { getMediaUrl(appUser.avatar, it) }?.let {
                    Image(
                        painter = rememberAsyncImagePainter(appUser.baseUrl?.let {
                            getMediaUrl(
                                appUser.avatar, it
                            )
                        }),
                        contentDescription = "User Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior
    )
}

@Composable
fun BottomNavigationBar(navController: NavHostController) {
    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        actions = {
            IconButton(onClick = { navController.navigate(TweetFeed) }) {
                Icon(
//                    painter = painterResource(id = R.drawable.ic_home),
                    rememberVectorPainter(image = Icons.Filled.Home),
                    contentDescription = "Home",
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = { /* Navigate to Message */ }) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_notice),
                    contentDescription = "Notice",
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = { navController.navigate(ComposeTweet) }) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_compose),
                    contentDescription = "Compose",
                    modifier = Modifier.size(32.dp)
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {},
                containerColor = BottomAppBarDefaults.bottomAppBarFabColor,
                elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation()
                ) {
                Icon(Icons.Filled.Add, "Localized description")
            }
        }
    )
}
