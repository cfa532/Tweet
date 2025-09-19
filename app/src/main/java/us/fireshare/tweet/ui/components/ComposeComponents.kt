package us.fireshare.tweet.ui.components

import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import us.fireshare.tweet.R
import us.fireshare.tweet.widget.UploadFilePreview

/**
 * Composable for the main text input field with mention suggestions
 */
@Composable
fun ComposeTextField(
    text: String,
    onTextChange: (String) -> Unit,
    onMentionSearch: (String) -> Unit,
    suggestions: List<String>,
    onSuggestionSelected: (String) -> Unit,
    onClearSuggestions: () -> Unit,
    maxLines: Int = 10,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            onTextChange(newText)
            
            // Handle mention suggestions
            if (newText.contains("@") && newText.length > text.length) {
                val query = newText.substringAfterLast("@")
                if (query.isNotEmpty()) {
                    onMentionSearch(query)
                }
            } else {
                onClearSuggestions()
            }
        },
        label = { Text("What's happening?") },
        maxLines = maxLines,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp)
            .alpha(0.7f)
            .focusRequester(focusRequester)
            .onFocusChanged {
                if (it.isFocused) {
                    keyboardController?.show()
                }
            },
        trailingIcon = {
            if (suggestions.isNotEmpty()) {
                IconButton(onClick = onClearSuggestions) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.close_suggestions)
                    )
                }
            }
        }
    )

    // Mention suggestions dropdown
    if (suggestions.isNotEmpty()) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = onClearSuggestions,
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                )
        ) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text("@$suggestion") },
                    onClick = {
                        onSuggestionSelected(suggestion)
                        onClearSuggestions()
                        focusManager.clearFocus()
                    }
                )
            }
        }
    }
}

/**
 * Composable for the attachment preview row
 */
@Composable
fun AttachmentPreviewRow(
    attachments: List<Uri>,
    onRemoveAttachment: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    if (attachments.isNotEmpty()) {
        Column(modifier = modifier) {
            Spacer(modifier = Modifier.height(8.dp))
            
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(attachments) { uri ->
                    UploadFilePreview(
                        uri = uri,
                        onCheckedChange = { _, isChecked ->
                            if (!isChecked) {
                                onRemoveAttachment(uri)
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * Composable for the action buttons row (private checkbox, camera, file picker, send)
 */
@Composable
fun ActionButtonsRow(
    isPrivate: Boolean,
    onPrivateChange: (Boolean) -> Unit,
    onCameraClick: () -> Unit,
    onFilePickerClick: () -> Unit,
    onSendClick: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
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
                checked = isPrivate,
                onCheckedChange = onPrivateChange
            )
            Text(
                text = "Private",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(Modifier.weight(1f))

        // Camera button
        IconButton(onClick = onCameraClick) {
            Icon(
                painter = painterResource(R.drawable.ic_camera),
                contentDescription = "Open Camera",
                tint = MaterialTheme.colorScheme.surfaceTint,
                modifier = Modifier.size(32.dp)
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))

        // File picker button
        IconButton(onClick = onFilePickerClick) {
            Icon(
                painter = painterResource(R.drawable.ic_photo_plus),
                contentDescription = "Upload File",
                tint = MaterialTheme.colorScheme.surfaceTint,
                modifier = Modifier.size(44.dp)
            )
        }
    }
}
