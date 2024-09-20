package com.fireshare.tweet.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.viewmodel.ChatViewModel
import com.fireshare.tweet.widget.UserAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel
) {
    val chatMessages by viewModel.chatMessages.collectAsState()
    val navController = LocalNavController.current
    val receipt by viewModel.receipt.collectAsState()

    // fetch new messages every time open chat screen.
    viewModel.fetchNewMessage()

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Row(horizontalArrangement = Arrangement.SpaceBetween) {
                        Spacer(modifier = Modifier.weight(1f))
                        Column {
                            UserAvatar(receipt, 32)
                            Text(
                                text = receipt?.profile ?: " ",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() })
                    {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
//        bottomBar = { BottomNavigationBar(navController, 1) }
    ) { innerPadding ->
        Surface(modifier = Modifier.padding(innerPadding)) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 100.dp) // Adjust padding to make space for the input field
                ) {
                    items(chatMessages) { msg ->
                        ChatSession(viewModel, msg)
                    }
                }
                ChatInput(
                    viewModel = viewModel,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
fun ChatSession(viewModel: ChatViewModel, message: ChatMessage) {
    val isSentByCurrentUser = message.authorId == appUser.mid
    var user by remember { mutableStateOf<User?>(null) }
    val receipt by viewModel.receipt.collectAsState()

    LaunchedEffect(Unit) {
        user = viewModel.getSender(message.authorId)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        horizontalArrangement = if (isSentByCurrentUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isSentByCurrentUser) {
            UserAvatar(user = receipt, size = 32)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(4.dp)
        ) {
            Text(
                text = message.content,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(8.dp)
            )
        }
        if (isSentByCurrentUser) {
            Spacer(modifier = Modifier.width(8.dp))
            UserAvatar(user = appUser, size = 32)
        }
    }
}

@Composable
fun ChatInput(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val textState by viewModel.textState
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(0.dp)
    ) {
        TextField(
            value = textState,
            onValueChange = { viewModel.textState.value = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp, max = 150.dp)
                .padding(0.dp),
            placeholder = { Text("Type a message...") },
            trailingIcon = {
                IconButton(onClick = {
                    if (textState.isNotBlank()) {
                        viewModel.sendMessage()
                        viewModel.textState.value = "" // Clear the input field after sending
                    }
                }) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.scale(scaleX = -1f, scaleY = 1f))
                }
            },
            singleLine = false
        )
    }
}