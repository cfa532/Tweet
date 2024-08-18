package com.fireshare.tweet

import PreferencesHelper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.rememberAsyncImagePainter
import com.fireshare.tweet.network.HproseInstance.appUser
import com.fireshare.tweet.network.HproseInstance.getMediaUrl
import com.fireshare.tweet.profile.PreferencesScreen
import com.fireshare.tweet.profile.UserProfileScreen
import com.fireshare.tweet.tweet.ComposeTweetScreen
import com.fireshare.tweet.tweet.TweetFeedScreen
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
                MainScreen(tweetFeedViewModel)
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: TweetFeedViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val preferencesHelper = remember { PreferencesHelper(context) }

    Column {
        NavHost(
            navController = navController,
            startDestination = "tweetFeed",
        ) {
            composable("tweetFeed") {
                TweetFeedScreen(navController)
            }
            composable("composeTweet") {
                ComposeTweetScreen(
                    navController = navController,
                    viewModel = viewModel,
                )
            }
//            composable("userProfile") {
//                UserProfileScreen(appUser, navController)
//            }
            composable("preferences") {
                PreferencesScreen(navController, preferencesHelper)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopAppBar(navController: NavHostController) {
    TopAppBar(
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                AppIcon()
            }
        },
        navigationIcon = {
            IconButton(onClick = { navController.navigate("userProfile") }) {
                appUser.baseUrl?.let { getMediaUrl(appUser.avatar, it) }?.let {
                    Image(
                        painter = rememberAsyncImagePainter(appUser.baseUrl?.let { getMediaUrl(
                            appUser.avatar, it) }),
                        contentDescription = "User Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                    )
                }
            }
        }
    )
}

@Composable
fun BottomNavigationBar(navController: NavHostController) {
    BottomAppBar {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = { navController.navigate("tweetFeed") }) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_home),
                    contentDescription = "Home",
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = { /* Navigate to Notice */ }) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_notice),
                    contentDescription = "Notice",
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = { navController.navigate("composeTweet") }) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_compose),
                    contentDescription = "Compose",
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}
