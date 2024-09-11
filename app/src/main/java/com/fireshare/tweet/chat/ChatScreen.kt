package com.fireshare.tweet.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val chatMessages by viewModel.chatMessages.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = viewModel.aite.value) },
                actions = {
                    // Add any additional actions here
                }
            )
        },
        content = {innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                items(chatMessages.size) { index ->
                    ChatItem(chatMessages[index])
                }
            }

            // Input field at the bottom
            ChatInput(onSendClick = viewModel::sendMessage)
        }
    )
}

@Composable
fun ChatItem(message: ChatMessage) {

}

@Composable
fun ChatInput(onSendClick: (ChatMessage) -> Unit) {

}