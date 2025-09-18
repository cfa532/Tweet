package us.fireshare.tweet.tweet

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.datamodel.TW_CONST
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.ui.components.ActionButtonsRow
import us.fireshare.tweet.ui.components.AttachmentPreviewRow
import us.fireshare.tweet.ui.components.CameraHandler
import us.fireshare.tweet.ui.components.ComposeTextField
import us.fireshare.tweet.ui.components.ComposeTopAppBar
import us.fireshare.tweet.ui.components.ExitConfirmationDialog
import us.fireshare.tweet.viewmodel.TweetFeedViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeTweetScreen(
    navController: NavHostController,
) {
    val context = LocalContext.current
    val sharedViewModel: SharedViewModel = hiltViewModel()
    val tweetFeedViewModel = hiltViewModel<TweetFeedViewModel>()

    // Set context for notifications
    LaunchedEffect(Unit) {
        tweetFeedViewModel.setNotificationContext(context)
    }

    // State management
    var tweetContent by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var showExitConfirmation by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    
    val selectedAttachments = remember { mutableStateListOf<Uri>() }

    // File size validation
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

    // File picker launcher
    val filesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            if (selectedAttachments.find { u -> u == uri } == null) {
                if (isFileSizeValid(uri)) {
                    selectedAttachments.add(uri)
                } else {
                    Toast.makeText(context, "Files must be smaller than 120MB", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Handle image capture from CameraX
    val onImageCaptured = { uri: Uri ->
        Timber.tag("CameraX").d("Image captured: $uri")
        selectedAttachments.add(uri)
        showCamera = false
    }

    // Handle video recording from CameraX
    val onVideoRecorded = { uri: Uri ->
        Timber.tag("CameraX").d("Video recorded: $uri")
        selectedAttachments.add(uri)
        showCamera = false
    }

    // Handle mention search
    val onMentionSearch = { query: String ->
        isSearching = true
        sharedViewModel.appUserViewModel.viewModelScope.launch {
            suggestions = sharedViewModel.appUserViewModel.getSuggestions(query)
            isSearching = false
        }
        Unit
    }

    // Handle suggestion selection
    val onSuggestionSelected = { suggestion: String ->
        tweetContent = tweetContent.substringBeforeLast("@") + "@$suggestion "
        suggestions = emptyList()
    }

    // Handle send action
    val onSendClick = {
        if (tweetContent.trim().isNotEmpty() || selectedAttachments.isNotEmpty()) {
            // Upload logic would go here
            navController.popBackStack()
        }
        Unit
    }

    // Handle back action
    val onBackClick = {
        if (tweetContent.trim().isNotEmpty() || selectedAttachments.isNotEmpty()) {
            showExitConfirmation = true
        } else {
            navController.popBackStack()
        }
        Unit
    }

    // Handle camera click
    val onCameraClick = {
        showCamera = true
    }

    // Handle file picker click
    val onFilePickerClick = {
        filesPickerLauncher.launch(arrayOf("*/*"))
    }

    // Check if there's content to send
    val hasContent = tweetContent.trim().isNotEmpty() || selectedAttachments.isNotEmpty()

    Scaffold(
        topBar = {
            ComposeTopAppBar(
                title = "Edit",
                showCamera = showCamera,
                onBackClick = onBackClick,
                onSendClick = onSendClick,
                hasContent = hasContent
            )
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .padding(innerPadding)
                .imePadding()
        ) {
            Column(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp)
            ) {
                // Text input field with mention suggestions
                ComposeTextField(
                    text = tweetContent,
                    onTextChange = { tweetContent = it },
                    onMentionSearch = onMentionSearch,
                    suggestions = suggestions,
                    onSuggestionSelected = onSuggestionSelected,
                    onClearSuggestions = { suggestions = emptyList() }
                )

                // Attachment preview row
                AttachmentPreviewRow(
                    attachments = selectedAttachments,
                    onRemoveAttachment = { selectedAttachments.remove(it) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Action buttons row
                ActionButtonsRow(
                    isPrivate = isPrivate,
                    onPrivateChange = { isPrivate = it },
                    onCameraClick = onCameraClick,
                    onFilePickerClick = onFilePickerClick,
                    onSendClick = onSendClick,
                    isLoading = false
                )
            }
        }
    }

    // Exit confirmation dialog
    ExitConfirmationDialog(
        showDialog = showExitConfirmation,
        onDismiss = { showExitConfirmation = false },
        onConfirm = { navController.popBackStack() }
    )

    // Camera handler
    CameraHandler(
        showCamera = showCamera,
        onImageCaptured = onImageCaptured,
        onVideoRecorded = onVideoRecorded,
        onDismiss = { showCamera = false },
        modifier = Modifier.fillMaxSize()
    )
}