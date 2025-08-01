package us.fireshare.tweet.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.ChatMessage
import us.fireshare.tweet.navigation.LocalNavController
import us.fireshare.tweet.profile.UserAvatar
import us.fireshare.tweet.viewmodel.ChatViewModel
import us.fireshare.tweet.widget.Gadget.buildAnnotatedText
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
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun scrollToBottom() {
        if (chatMessages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(chatMessages.size - 1)
            }
        }
    }

    LaunchedEffect(Unit) {
        // Assume user read new message when opening this chat screen.
        // Upon opening ChatBox, set new message flag to false in chatSession list.
        viewModel.chatListViewModel?.updateSession(null,
            hasNews = false, receiptId = viewModel.receiptId)

        // fetch new messages every 15s when on chat screen.
        timer(period = 15000, action = {
            viewModel.viewModelScope.launch(Dispatchers.IO) {
                viewModel.fetchNewMessage()
            }
        }, initialDelay = 100)
    }

    // Scroll to the bottom when the screen is first shown
    LaunchedEffect(key1 = listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && chatMessages.isNotEmpty()) {
            scrollToBottom()
        }
    }

    // Scroll to the bottom when a new message is added
    LaunchedEffect(chatMessages) {
        if (chatMessages.isNotEmpty()) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.offset?.let { offset ->
                    offset + (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.size
                        ?: 0) > listState.layoutInfo.viewportEndOffset
                } ?: false
            }
                .collect { isLastItemVisible ->
                    if (isLastItemVisible) {
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
                            UserAvatar(user = receipt, size = 32)
                            Text(
                                text = receipt.profile ?: " ",
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
                            contentDescription = stringResource(R.string.back),
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
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    // Hide keyboard when tapping outside
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
                // Scroll to the bottom when the keyboard is opened
                .onSizeChanged {
                    scrollToBottom()
                }
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 80.dp), // Increased padding for new input design
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
                        .padding(horizontal = 4.dp, vertical = 8.dp)
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
        if (! isSentByCurrentUser) {
            UserAvatar(user = receipt, size = 32)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = ChatBubbleShape(isSentByCurrentUser),
            modifier = Modifier.padding(4.dp)
        ) {
            SelectionContainer {
                BasicText(
                    text = buildAnnotatedText(message.content ?: ""),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
        if (isSentByCurrentUser) {
            Spacer(modifier = Modifier.width(8.dp))
            UserAvatar(user = appUser, size = 32)
        }
    }
}

@Composable
fun ChatInput(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val textState by viewModel.message
    val hasInput = textState.isNotBlank()
    val isSending = remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // Top shadow as divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
//                    shape = RoundedCornerShape(0.dp)
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Attachment button
            IconButton(
                onClick = {
                    // TODO: Implement attachment functionality
                },
                modifier = Modifier.size(40.dp),
                enabled = !isSending.value
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = stringResource(R.string.attached_file),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Text input field with rounded corners
            OutlinedTextField(
                value = textState,
                onValueChange = { viewModel.message.value = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 120.dp)
                    .focusRequester(focusRequester),
                placeholder = {
                    Text(
                        text = stringResource(R.string.type_message),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                },
                keyboardOptions = KeyboardOptions.Default.copy(
                    imeAction = ImeAction.Default,
                    keyboardType = KeyboardType.Text
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (hasInput && !isSending.value) {
                            isSending.value = true
                            viewModel.viewModelScope.launch(Dispatchers.IO) {
                                viewModel.sendMessage()
                                viewModel.message.value = "" // Clear input after sending
                                isSending.value = false
                                // Keep focus on the input field
                                focusRequester.requestFocus()
                            }
                        }
                    }
                ),
                shape = RoundedCornerShape(20.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                singleLine = false,
                maxLines = 5,
                enabled = !isSending.value
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Send button without background decoration
            IconButton(
                onClick = {
                    if (hasInput && !isSending.value) {
                        isSending.value = true
                        viewModel.viewModelScope.launch(Dispatchers.IO) {
                            viewModel.sendMessage()
                            viewModel.message.value = "" // Clear input after sending
                            isSending.value = false
                            // Keep focus on the input field
                            focusRequester.requestFocus()
                        }
                    }
                },
                modifier = Modifier.size(40.dp),
                enabled = hasInput && !isSending.value
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send),
                    tint = if (hasInput && !isSending.value)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier
                        .size(24.dp)
                        .scale(scaleX = -1f, scaleY = 1f)
                )
            }
        }
    }
}