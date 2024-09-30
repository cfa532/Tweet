package com.fireshare.tweet.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.ChatSession
import com.fireshare.tweet.navigation.BottomNavigationBar
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.navigation.NavTweet
import com.fireshare.tweet.viewmodel.ChatListViewModel
import com.fireshare.tweet.widget.UserAvatar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(viewModel: ChatListViewModel)
{
    val chatSessions by viewModel.chatSessions.collectAsState()
    val navController = LocalNavController.current
    viewModel.previewMessages()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    UserAvatar(appUser, 40)
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
                items(chatSessions, key = {it.receiptId}) { chatSession ->
                    ChatSession(viewModel, chatSession, navController)
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 0.8.dp).alpha(0.7f),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
fun ChatSession(viewModel: ChatListViewModel, chatSession: ChatSession, navController: NavController) {
    val chatMessage = chatSession.lastMessage
    val userMap by viewModel.userMap.collectAsState()
    val user = userMap[chatSession.receiptId]

    LaunchedEffect(Unit) {
        viewModel.getSender(chatSession.receiptId)
    }

    Row(modifier = Modifier.padding(8.dp)) {
        Box(modifier = Modifier
            .size(40.dp)
//            .clip(CircleShape)
            .clickable(onClick = { user?.mid?.let { navController.navigate(NavTweet.UserProfile(it))
            }})
        ) {
            UserAvatar(user)

            // show badge of new incoming message
            if (chatSession.hasNews) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = (6).dp, y = (-4).dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Email, contentDescription = "Mail",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
        Column(modifier = Modifier
            .clickable( onClick = { user?.mid?.let { navController.navigate(NavTweet.ChatBox(it))}
            })) {
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