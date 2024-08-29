package com.fireshare.tweet.tweet

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.fireshare.tweet.R
import com.fireshare.tweet.datamodel.MimeiId
import com.fireshare.tweet.datamodel.Tweet
import com.fireshare.tweet.network.Gadget.uploadAttachments
import com.fireshare.tweet.network.HproseInstance.appUser
import com.fireshare.tweet.viewmodel.TweetFeedViewModel
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.fireshare.tweet.widget.UploadFilePreview
import com.fireshare.tweet.widget.UserAvatar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeCommentScreen(
    navController: NavHostController,
    tweetId: MimeiId,
) {
    var tweetContent by remember { mutableStateOf("") }
    val selectedAttachments = remember { mutableStateListOf<Uri>() }
    val context = LocalContext.current // Renamed for clarity

    val tweetFeedViewModel: TweetFeedViewModel = hiltViewModel()
    val tweet = tweetFeedViewModel.getTweetById(tweetId) ?: return
    val author = tweet.author

    val viewModel = hiltViewModel<TweetViewModel>(key = tweetId)
    viewModel.init(tweet, tweetFeedViewModel)

    // Create a launcher for the file picker
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            if (selectedAttachments.find { u -> u == it } == null) {
                selectedAttachments.add(it)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        TopAppBar(
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            viewModel.viewModelScope.launch {
                                val attachments = uploadAttachments(context, selectedAttachments)
                                viewModel.uploadComment(
                                    comment = Tweet(
                                    authorId = appUser.mid,
                                    content = tweetContent,
                                    attachments = attachments,
                                ))
                                // clear and return to previous screen
                                selectedAttachments.clear()
                                tweetContent = ""
                                navController.popBackStack()
                            }
                        }, modifier = Modifier
                            .padding(horizontal = 16.dp) // Add padding for spacing
                            .width(intrinsicSize = IntrinsicSize.Min) // Adjust width to fit content
                            .alpha(0.8f) // Set opacity to 80%
                    ) {
                        Text("Comment")
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_back),
                        contentDescription = "Back",
                        modifier = Modifier
                            .size(40.dp)
                            .padding(8.dp)
                    )
                }
            }
        )
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatar(author, 32)
        }
        OutlinedTextField(
            value = tweetContent,
            onValueChange = { tweetContent = it },
            label = { Text("What's happening?") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp)
                .alpha(0.7f)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                Icon(painter = painterResource(R.drawable.ic_photo_plus),
                    contentDescription = "upload file",
                    modifier = Modifier.size(60.dp),
                    tint = MaterialTheme.colorScheme.surfaceTint)
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Display icons for attached files
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            items(selectedAttachments.chunked(2)) { rowItems ->
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rowItems) { uri ->
                        UploadFilePreview(uri)
                    }
                }
            }
        }
    }
}
