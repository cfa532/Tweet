package com.fireshare.tweet.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.datamodel.User
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.viewmodel.ChatListViewModel
import com.fireshare.tweet.widget.UserAvatar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel)
{
    val chatMessages by viewModel.chatSessions.collectAsState()
    val navController = LocalNavController.current
    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text(
                        text = "Chat Messages",
                        style = MaterialTheme.typography.bodyLarge
                    )
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
        Surface(
            modifier = Modifier
                .fillMaxSize() // Ensure Surface fills the available space
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.Top
            ) {
                items(chatMessages) { chatMessage ->
                    ChatSession(viewModel, chatMessage)
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 1.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
fun ChatSession(viewModel: ChatListViewModel, chatMessage: ChatMessage) {
    var user by remember { mutableStateOf<User?>(null) }

    LaunchedEffect(chatMessage.authorId) {
        val id = if (chatMessage.authorId != appUser.mid) chatMessage.authorId else chatMessage.receiptId
        user = viewModel.getSender(id)
    }
    Row(modifier = Modifier.padding(8.dp)) {
        UserAvatar(user)
        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${user?.name}@${user?.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(chatMessage.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            if (chatMessage.authorId == appUser.mid) {
                Text(
                    text = chatMessage.content ?: "You have sent a media.",
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                Text(
                    text = chatMessage.content ?: "You have received a media.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}