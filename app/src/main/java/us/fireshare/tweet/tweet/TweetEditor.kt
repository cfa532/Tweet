package us.fireshare.tweet.tweet

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.navigation.SharedViewModel
import us.fireshare.tweet.ui.components.ActionButtonsRow
import us.fireshare.tweet.ui.components.AttachmentPreviewRow
import us.fireshare.tweet.ui.components.CameraHandler
import us.fireshare.tweet.ui.components.ComposeTextField
import us.fireshare.tweet.ui.components.ComposeTopAppBar
import us.fireshare.tweet.utils.FileSizeUtils

@Composable
internal fun TweetEditor(
    title: String,
    content: String,
    onContentChange: (String) -> Unit,
    isPrivate: Boolean,
    onPrivacyChange: (Boolean) -> Unit,
    attachments: SnapshotStateList<Uri>,
    showCamera: Boolean,
    onShowCameraChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onSubmit: () -> Unit,
    canSubmit: Boolean,
    isBusy: Boolean,
    error: String?,
    onClearError: () -> Unit,
    showActions: Boolean = true,
    showProgress: Boolean = false,
    maxVisibleLines: Int = 12,
    retainedAttachments: List<MimeiFileType> = emptyList(),
    retainedAttachmentBaseUrl: String? = null,
    onRemoveRetainedAttachment: (MimeiFileType) -> Unit = {},
    submitDescription: String = stringResource(R.string.send)
) {
    val context = LocalContext.current
    val sharedViewModel: SharedViewModel = hiltViewModel()
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var fileSizeWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(attachments.toList()) {
        fileSizeWarnings = attachments.map { uri ->
            FileSizeUtils.getFileSizeWarningMessage(context, FileSizeUtils.getFileSize(context, uri))
        }
    }

    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (uri in uris) {
            if (uri in attachments) continue
            if (FileSizeUtils.isFileSizeValid(FileSizeUtils.getFileSize(context, uri))) {
                attachments.add(uri)
            } else {
                Toast.makeText(context, R.string.files_too_large, Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                ComposeTopAppBar(
                    title = title,
                    showCamera = showCamera,
                    onBackClick = onClose,
                    onSendClick = onSubmit,
                    hasContent = canSubmit && !isBusy,
                    sendDescription = submitDescription
                )
                if (showProgress) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    ) { innerPadding ->
        Surface(modifier = Modifier.padding(innerPadding).imePadding()) {
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                ComposeTextField(
                    text = content,
                    onTextChange = onContentChange,
                    onMentionSearch = { query ->
                        sharedViewModel.appUserViewModel.viewModelScope.launch {
                            suggestions = sharedViewModel.appUserViewModel.getSuggestions(query)
                        }
                    },
                    suggestions = suggestions,
                    onSuggestionSelected = { suggestion ->
                        onContentChange(content.substringBeforeLast("@") + "@$suggestion ")
                    },
                    onClearSuggestions = { suggestions = emptyList() },
                    maxLines = maxVisibleLines,
                    readOnly = isBusy,
                    minHeight = 176.dp
                )
                AttachmentPreviewRow(
                    attachments = attachments,
                    onRemoveAttachment = { attachments.remove(it) },
                    retainedAttachments = retainedAttachments,
                    retainedAttachmentBaseUrl = retainedAttachmentBaseUrl,
                    onRemoveRetainedAttachment = onRemoveRetainedAttachment,
                    enabled = !isBusy
                )
                for (warning in fileSizeWarnings) {
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            warning.contains("120MB") -> MaterialTheme.colorScheme.error
                            warning.contains("Large file") || warning.contains("Very large") ->
                                MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (showActions) {
                    ActionButtonsRow(
                        isChecked = isPrivate,
                        onCheckedChange = onPrivacyChange,
                        checkboxLabel = stringResource(R.string.private_tweet),
                        onCameraClick = { onShowCameraChange(true) },
                        onFilePickerClick = { filesPicker.launch(arrayOf("*/*")) },
                        onSendClick = onSubmit,
                        isLoading = isBusy,
                        enabled = !isBusy
                    )
                }
            }
        }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = {},
            text = { Text(message) },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            confirmButton = {
                TextButton(onClick = onClearError) { Text(stringResource(R.string.close)) }
            }
        )
    }

    CameraHandler(
        showCamera = showCamera,
        onImageCaptured = { attachments.add(it) },
        onVideoRecorded = { attachments.add(it) },
        onDismiss = { onShowCameraChange(false) },
        modifier = Modifier.fillMaxSize()
    )
}
