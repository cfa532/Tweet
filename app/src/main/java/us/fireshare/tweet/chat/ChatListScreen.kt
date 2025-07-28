package us.fireshare.tweet.chat

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.ChatSession
import us.fireshare.tweet.navigation.BottomNavigationBar
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.navigation.NavTweet
import us.fireshare.tweet.profile.UserAvatar
import us.fireshare.tweet.viewmodel.ChatListViewModel
import us.fireshare.tweet.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(viewModel: ChatListViewModel)
{
    val chatSessions by viewModel.chatSessions.collectAsState()
    val navController = LocalNavController.current
    var lastClickTime by remember { mutableLongStateOf(0L) }
    val debounceTime = 500L

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            viewModel.previewMessages()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    UserAvatar(user = appUser, size = 40)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        // manually prevent fast continuous click of a button
                        val currentTime = SystemClock.elapsedRealtime()
                        if (currentTime - lastClickTime > debounceTime) {
                            navController.popBackStack()
                            lastClickTime = currentTime
                        }
                    } ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        bottomBar = { BottomNavigationBar(navController = navController, selectedIndex = 1) }
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
                    ChatSession(chatSession, navController)
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 0.8.dp).alpha(0.7f),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                item {
                    if (chatSessions.isEmpty()) {
                        Text(stringResource(R.string.no_chat),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ChatSession(
    chatSession: ChatSession,
    navController: NavController,
    viewModel: ChatViewModel = hiltViewModel<ChatViewModel, ChatViewModel.ChatViewModelFactory>(
        key = chatSession.receiptId
    ) {factory -> factory.create(chatSession.receiptId)}
) {
    val chatMessage = chatSession.lastMessage
    val user by viewModel.receipt.collectAsState()

    Row(modifier = Modifier.padding(8.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(onClick = {
                    user?.mid?.let {
                        navController.navigate(NavTweet.UserProfile(it))
                    }
                })
        ) {
            UserAvatar(user = user)

            // show badge of new incoming message
            if (chatSession.hasNews) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = (6).dp, y = (-4).dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Email, contentDescription = stringResource(R.string.mail),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(start = 4.dp)
            .clickable(onClick = {
                user?.mid?.let { navController.navigate(NavTweet.ChatBox(it)) }
            })
        ) {
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
                    text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(
                        Date(
                            chatMessage.timestamp
                        )
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Text(
                text = chatMessage.content,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}