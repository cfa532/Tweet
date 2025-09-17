package us.fireshare.tweet.tweet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import us.fireshare.tweet.HproseInstance.appUser
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.profile.UserAvatar
import us.fireshare.tweet.viewmodel.TweetFeedViewModel
import us.fireshare.tweet.widget.UploadFilePreview
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.service.FileTypeDetector
import us.fireshare.tweet.utils.createVideoFile
import us.fireshare.tweet.ui.CameraXPreview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeCommentScreen(
    popBack: () -> Unit,
) {
    var tweetContent by remember { mutableStateOf("") }
    var showExitConfirmation by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val sharedViewModel: SharedViewModel = hiltViewModel()
    val tweetViewModel = sharedViewModel.tweetViewModel
    val tweet by tweetViewModel.tweetState.collectAsState()
    val author by remember { derivedStateOf { tweet.author } }
    val isCheckedToTweet by tweetViewModel.isCheckedToTweet

    // Get TweetFeedViewModel to set notification context for Toast messages
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()

    // Start listening to tweet and comment notifications
    LaunchedEffect(Unit) {
        tweetViewModel.startListeningToNotifications(context)
        tweetFeedViewModel.setNotificationContext(context)
    }

    // Create a launcher for the file picker
    val selectedAttachments = remember { mutableStateListOf<Uri>() }
    
    // Function to check if file is within size limits
    fun isFileSizeValid(uri: Uri): Boolean {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val fileSize = inputStream?.available() ?: 0
            inputStream?.close()
            fileSize <= TW_CONST.MAX_FILE_SIZE
        } catch (e: Exception) {
            false
        }
    }
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let {
            if (selectedAttachments.find { u -> u == it } == null) {
                if (isFileSizeValid(it)) {
                    selectedAttachments.add(it)
                } else {
                    Toast.makeText(context, "Files must be smaller than 120MB", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    // CameraX state
    var showCamera by remember { mutableStateOf(false) }

    // Handle image capture from CameraX
    val onImageCaptured = { uri: Uri ->
        android.util.Log.d("CameraX", "Image captured: $uri")
        selectedAttachments.add(uri)
        showCamera = false
    }

    // Open camera with CameraX
    val openCamera = {
        showCamera = true
    }

    // Request camera permission
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openCamera()
        } else {
            Toast.makeText(context, "Camera permission required", Toast.LENGTH_SHORT).show()
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
                            showExitConfirmation = true
                        } else {
                            popBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    var isLoading by remember { mutableStateOf(false) }
                    val coroutineScope = rememberCoroutineScope()

                    IconButton(
                        enabled = !isLoading,
                        onClick = {
                            if (tweetContent.isNotEmpty() || selectedAttachments.isNotEmpty()) {
                                isLoading = true
                                // Store content before clearing
                                val contentToUpload = tweetContent.trim()
                                val attachmentsToUpload = selectedAttachments.toList()

                                // Clear UI immediately for better UX
                                selectedAttachments.clear()
                                tweetContent = ""

                                // Upload comment
                                tweetViewModel.uploadComment(
                                    context,
                                    contentToUpload,
                                    attachmentsToUpload,
                                )

                                // Navigate back after a short delay to ensure upload is initiated
                                coroutineScope.launch {
                                    delay(100) // Small delay to ensure upload is started
                                    popBack()
                                }
                            }
                        })
                    {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.send),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.rotate(180f)

                        )
                    }
                }
            )
        }) { innerPadding ->
        Surface(
            modifier = Modifier
                .padding(innerPadding)
                .imePadding()
        ) {
            Column(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        UserAvatar(
                            user = author ?: User(
                                mid = TW_CONST.GUEST_ID,
                                baseUrl = appUser.baseUrl
                            ), size = 36
                        )
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text(
                            text = "Reply to @${author?.username}",
                            modifier = Modifier.alpha(0.7f)
                        )
                    }
                }

                val focusRequester = remember { FocusRequester() }
                val keyboardController = LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus() // Request focus on composable launch
                }
                OutlinedTextField(
                    value = tweetContent,
                    onValueChange = {
                        // Limit comment content to 280 characters (same as tweets)
                        if (it.length <= 280) {
                            tweetContent = it
                        }
                    },
                    label = { Text("What's happening?") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 600.dp)
                        .alpha(0.7f)
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            if (it.isFocused) {
                                // Optionally show the keyboard programmatically
                                keyboardController?.show()
                            }
                        },
                )

                // Character counter
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "${tweetContent.length}/280",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tweetContent.length > 260) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // row of icons at bottom of text field.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .alpha(0.9f)
                            .size(40.dp)
                    ) {
                        Checkbox(
                            checked = isCheckedToTweet,
                            onCheckedChange = { tweetViewModel.onCheckedChange(it) },
                            modifier = Modifier
                        )
                        Text(
                            text = "Post as Tweet",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    IconButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                openCamera()
                            } else {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .padding(top = 10.dp, end = 8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_camera), // Replace with your camera icon
                            contentDescription = stringResource(R.string.open_camera),
                            tint = MaterialTheme.colorScheme.surfaceTint,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            filePickerLauncher.launch(
                                arrayOf(
                                    "image/*",
                                    "video/*",
                                    "audio/*"
                                )
                            )
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_photo_plus),
                            contentDescription = stringResource(R.string.upload_file),
                            tint = MaterialTheme.colorScheme.surfaceTint,
                        )
                    }
                }
                // Display icons for attached files
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    items(selectedAttachments.chunked(4)) { rowItems ->
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(rowItems) { uri ->
                                UploadFilePreview(uri, onCheckedChange = { updatedUri, checked ->
                                    if (!checked) {
                                        selectedAttachments.remove(updatedUri)
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }

        // Exit confirmation dialog
        if (showExitConfirmation) {
            AlertDialog(
                onDismissRequest = { showExitConfirmation = false },
                title = { Text(stringResource(R.string.discard_comment)) },
                text = { Text(stringResource(R.string.unsaved_content_warning)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showExitConfirmation = false
                            popBack()
                        }
                    ) {
                        Text(stringResource(R.string.discard))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showExitConfirmation = false }
                    ) {
                        Text(stringResource(R.string.keep_editing))
                    }
                }
            )
        }

        // CameraX Preview
        if (showCamera) {
            CameraXPreview(
                onImageCaptured = onImageCaptured,
                onDismiss = { showCamera = false },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
