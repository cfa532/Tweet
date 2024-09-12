package com.fireshare.tweet.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.viewmodel.ChatListViewModel
import com.fireshare.tweet.viewmodel.ChatViewModel
import com.fireshare.tweet.widget.UserAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel
) {
    val chatMessages by viewModel.chatMessages.collectAsState()
    val navController = LocalNavController.current
    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Column {
                        UserAvatar(viewModel.receipt, 40)
                        Text(
                            text = "Message",
                            style = MaterialTheme.typography.bodySmall
                        )
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
        bottomBar = { BottomNavigationBar(navController, 1) }
    ) { innerPadding ->
        Surface(modifier = Modifier.padding(innerPadding))
        {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                items(chatMessages) { msg ->
                    ChatItem(msg)
                }
            }
            // Input field at the bottom
            ChatInput(viewModel)
        }
    }
}


@Composable
fun ChatItem(message: ChatMessage) {
    val isSentByCurrentUser = message.authorId == appUser.mid
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = if (isSentByCurrentUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isSentByCurrentUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(4.dp)
        ) {
            Text(
                text = message.content ?: "",
                color = if (isSentByCurrentUser) Color.White else Color.Black,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
fun ChatInput(viewModel: ChatViewModel) {
    val textState by viewModel.textState
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        BasicTextField(
            value = TextFieldValue(textState),
            onValueChange = { viewModel.textState.value = it.text },
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp) // Adjust height as needed
                .padding(8.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                if (textState.isNotBlank()) {
                    viewModel.sendMessage()
                    viewModel.textState.value = "" // Clear the input field after sending
                }
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Send")
        }
    }
}