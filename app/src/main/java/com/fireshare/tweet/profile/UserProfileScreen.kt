package com.fireshare.tweet.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import coil.compose.rememberAsyncImagePainter
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.network.HproseInstance
import com.fireshare.tweet.network.HproseInstance.appUser
import com.fireshare.tweet.tweet.TweetItem
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.UserViewModel
import com.fireshare.tweet.widget.ProfileEditor
import dagger.hilt.android.lifecycle.HiltViewModel

@Composable
fun UserProfileScreen(
    navController: NavHostController,
    userId: MimeiId,
    parentEntry: NavBackStackEntry,
) {
    val userViewModel = hiltViewModel<UserViewModel, UserViewModel.UserViewModelFactory>(parentEntry, key = userId) {
        factory -> factory.create(userId)
    }
    val user by userViewModel.user.collectAsState()
    val tweets by userViewModel.tweets.collectAsState()
    val fans by userViewModel.fans.collectAsState()
    val followings by userViewModel.followings.collectAsState()

    // current user's detail has been loaded by default. Load other users' data only when homepage opened.
    if (user.mid != appUser.mid)
        userViewModel.getFollows(user)

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // User header
        ProfileTopAppBar(navController)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.LightGray)
                .padding(start = 16.dp, end = 16.dp, top = 3.dp, bottom = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Image(
                painter = rememberAsyncImagePainter(user.baseUrl?.let {
                    HproseInstance.getMediaUrl(
                        user.avatar, it
                    )
                }),
                contentDescription = "User Avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
            )
            Row(modifier = Modifier.padding(bottom = 4.dp)) {
                Button(
                    onClick = {
                        when (userViewModel.followButtonText) {
                            "Edit" -> navController.navigate(ProfileEditor)
                            else -> userViewModel.toggleFollow(userId)
                        }
                    },
                    modifier = Modifier.width(IntrinsicSize.Min)
                ) {
                    Text(text = userViewModel.followButtonText)
                }
            }
        }
        Spacer(modifier = Modifier.padding(top = 8.dp))
        Column(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.Start
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
                Text(
                    text = user.name ?: "No one",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "@" + (user.username ?: "NoOne"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 0.dp)
                )
                Text(text = user.profile ?: "Profile")
                Row {
                    Text(text = "${fans.count()} Followers")
                    Spacer(modifier = Modifier.padding(horizontal = 20.dp))
                    Text(text = "${followings.count()} Following")
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 1.dp),
                thickness = 1.dp,
                color = Color.Gray
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 8.dp, bottom = 8.dp)
            )
            {
                items(tweets) { tweet ->
                    if (!tweet.isPrivate) TweetItem(tweet, parentEntry)
                }
            }
        }
    }
}
