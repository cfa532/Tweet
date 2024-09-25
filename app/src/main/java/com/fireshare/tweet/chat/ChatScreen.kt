package com.fireshare.tweet.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.ChatMessage
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.viewmodel.ChatViewModel
import com.fireshare.tweet.widget.UserAvatar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.timer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel
) {
    val chatMessages by viewModel.chatMessages.collectAsState()
    val navController = LocalNavController.current
    val receipt by viewModel.receipt.collectAsState()

    val listState = rememberLazyListState()
    val layoutInfo by remember { derivedStateOf { listState.layoutInfo } }     // critical to not read layoutInfo directly
    val coroutineScope = rememberCoroutineScope()

    fun scrollToBottom() {
        if (chatMessages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(chatMessages.size - 1)
            }
        }
    }

    // Scroll to the bottom when the screen is first shown
    LaunchedEffect(Unit) {
        if (chatMessages.isNotEmpty()) {
            delay(100)
            scrollToBottom()
        }
        // fetch new messages every time open chat screen.
        timer(period = 30000, action = {
            viewModel.fetchNewMessage()
        }, initialDelay = 30000)
    }

    // Scroll to the bottom when a new message is added
    LaunchedEffect(chatMessages) {
        if (chatMessages.isNotEmpty()) {
            snapshotFlow {
                layoutInfo.visibleItemsInfo.lastOrNull()?.index
            }
                .collect { lastVisibleItemIndex ->
                    if (lastVisibleItemIndex != null && lastVisibleItemIndex == chatMessages.size - 2) {
                        scrollToBottom()
                    }
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
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
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
            Box(modifier = Modifier
                .fillMaxSize()
                .imePadding()
                // Scroll to the bottom when the keyboard is opened
                .onSizeChanged {
                    scrollToBottom()
                }
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 64.dp),
                    state = listState
                ) {
                    items(chatMessages) { msg ->
                        ChatItem(viewModel, msg)
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
fun ChatItem(viewModel: ChatViewModel, message: ChatMessage) {
    val isSentByCurrentUser = message.authorId == appUser.mid
    val receipt by viewModel.receipt.collectAsState()

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
            shape = ChatBubbleShape(isSentByCurrentUser),
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
            keyboardOptions = KeyboardOptions.Default.copy(
                imeAction = ImeAction.Default, // Allow Enter key to add a new line
                keyboardType = KeyboardType.Text
            ),
            keyboardActions = KeyboardActions(
                onSend = {
                    viewModel.sendMessage()
                    viewModel.textState.value = ""
                }
            ),
            trailingIcon = {
                IconButton(onClick = {
                    if (textState.isNotBlank()) {
                        viewModel.sendMessage()
                        viewModel.textState.value = ""
                    }
                }) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.scale(scaleX = -1f, scaleY = 1f))
                }
            },
            singleLine = false,
            maxLines = 5
        )
    }
}