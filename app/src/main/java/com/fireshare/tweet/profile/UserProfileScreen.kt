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
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import coil.compose.rememberAsyncImagePainter
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.network.HproseInstance
import com.fireshare.tweet.network.HproseInstance.appUser
import com.fireshare.tweet.tweet.TweetItem
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun UserProfileScreen(
    navController: NavHostController,
    userId: MimeiId,
) {
    val tweetFeedViewModel: TweetFeedViewModel = hiltViewModel()
    val user = HproseInstance.getUser(userId)

    Column(
        modifier = Modifier
            .fillMaxSize()
//            .padding(start = 16.dp, end = 16.dp)
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
                painter = rememberAsyncImagePainter(appUser.baseUrl?.let {
                    HproseInstance.getMediaUrl(
                        user?.avatar, it
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
                    onClick = { navController.navigate("preferences") },
                    modifier = Modifier.width(IntrinsicSize.Min)
                ) {
                    Text("Edit")
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
                text = user?.name ?: "No one",
                style = MaterialTheme.typography.titleLarge
            )
            Text(text = "@" + (user?.username ?: "NoOne"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(start = 0.dp))
            Text(text = user?.profile ?: "Profile") // Replace with actual resume
            // Add more user details here, like following/followers count
            }

            val tweets by tweetFeedViewModel.tweets.collectAsState()
            val tweetsByAuthor = tweets.filter { it.authorId == appUser.mid }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 8.dp, bottom = 8.dp)
            )
            {
                items(tweetsByAuthor) { tweet ->
                    if (!tweet.isPrivate) TweetItem(tweet)
                }
            }
        }
    }
}
