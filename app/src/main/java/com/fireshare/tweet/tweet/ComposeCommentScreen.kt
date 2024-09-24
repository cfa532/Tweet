package com.fireshare.tweet.tweet

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.fireshare.tweet.R
import com.fireshare.tweet.navigation.LocalViewModelProvider
import com.fireshare.tweet.navigation.SharedTweetViewModel
import com.fireshare.tweet.service.SnackbarAction
import com.fireshare.tweet.service.SnackbarController
import com.fireshare.tweet.service.SnackbarEvent
import com.fireshare.tweet.widget.UploadFilePreview
import com.fireshare.tweet.widget.UserAvatar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeCommentScreen(
    navController: NavHostController,
) {
    var tweetContent by remember { mutableStateOf("") }
    val selectedAttachments = remember { mutableStateListOf<Uri>() }
    val localContext = LocalContext.current

    val viewModelProvider = LocalViewModelProvider.current
    val sharedViewModel = viewModelProvider?.get(SharedTweetViewModel::class)
    val viewModel = sharedViewModel?.sharedTVMInstance ?: return
    val tweet by viewModel.tweetState.collectAsState()
    val author = tweet.author

    val isCheckedToTweet by viewModel.isCheckedToTweet

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

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text("Comment", fontSize = 18.sp)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (tweetContent.isNotEmpty() || selectedAttachments.isNotEmpty()) {
                            val event = SnackbarEvent(
                                message = "Are you sure to quit?",
                                action = SnackbarAction(name = "Quit",
                                    action = { navController.popBackStack() })
                            )
                            viewModel.viewModelScope.launch {
                                SnackbarController.sendEvent(event)
                            }
                        } else
                            navController.popBackStack()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton( onClick = {
                        viewModel.uploadComment(localContext, tweetContent, selectedAttachments )

                        // clear and return to previous screen
                        selectedAttachments.clear()
                        tweetContent = ""
                        navController.popBackStack()}
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send"
                        )
                    }
                }
            )
        }) { innerPadding ->
        Surface(
            modifier = Modifier.padding(innerPadding)
        ) {
            Column(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp)
            ) {
                Spacer(modifier = Modifier.padding(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row (
                        horizontalArrangement = Arrangement.Start,
                        modifier = Modifier.weight(1f)
                    ) {
                        UserAvatar(author, 32)
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text(
                            text = "Reply to @${author?.username}",
                            modifier = Modifier.alpha(0.6f)
                        )
                    }
                    Row (
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Checkbox(
                            checked = isCheckedToTweet,
                            onCheckedChange = { viewModel.onCheckedChange(it) },
                            modifier = Modifier
                                .size(18.dp)
                                .alpha(0.8f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Post as Tweet",
                            modifier = Modifier.alpha(0.6f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
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
                        Icon(
                            painter = painterResource(R.drawable.ic_photo_plus),
                            contentDescription = "upload file",
                            modifier = Modifier.size(60.dp),
                            tint = MaterialTheme.colorScheme.surfaceTint
                        )
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
    }
}