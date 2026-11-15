package us.fireshare.tweet.tweet

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.ui.components.AttachmentPreviewRow
import us.fireshare.tweet.utils.FileSizeUtils

@Composable
internal fun AdminTweetEditDialog(tweet: Tweet, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adminId = remember(tweet.mid) { HproseInstance.appUser.mid }
    val originalAttachments = remember(tweet.mid) { tweet.attachments.orEmpty() }
    var content by rememberSaveable(tweet.mid) { mutableStateOf(tweet.content.orEmpty()) }
    val retained = remember(tweet.mid) { mutableStateListOf(*originalAttachments.toTypedArray()) }
    val selected = remember(tweet.mid) { mutableStateListOf<Uri>() }
    val uploaded = remember(tweet.mid) { mutableMapOf<Uri, MimeiFileType>() }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val selectFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (uri in uris) {
            if (uri in selected) continue
            if (FileSizeUtils.isFileSizeValid(FileSizeUtils.getFileSize(context, uri))) {
                selected.add(uri)
            } else {
                error = context.getString(R.string.files_too_large)
            }
        }
    }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.edit_tweet)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8
                )
                for (attachment in retained) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(attachment.fileName ?: attachment.mid, Modifier.weight(1f))
                        TextButton(enabled = !saving, onClick = { retained.remove(attachment) }) {
                            Text(stringResource(R.string.remove_attachment))
                        }
                    }
                }
                AttachmentPreviewRow(selected, onRemoveAttachment = { if (!saving) selected.remove(it) })
                TextButton(enabled = !saving, onClick = { selectFiles.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.add_attachment))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && (content.isNotBlank() || retained.isNotEmpty() || selected.isNotEmpty()),
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            check(HproseInstance.appUser.mid == adminId)
                            val filesToUpload = selected.toList()
                            val attachments = if (filesToUpload.isEmpty() && retained == originalAttachments) {
                                null
                            } else {
                                val newAttachments = if (filesToUpload.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
                                    val author = tweet.author?.takeIf { !it.hostIds.isNullOrEmpty() }
                                        ?: HproseInstance.fetchUser(tweet.authorId)
                                        ?: throw IllegalStateException("Tweet author is unavailable")
                                    filesToUpload.map { uri ->
                                        // Reuse completed uploads if a later upload or save fails.
                                        uploaded[uri] ?: checkNotNull(HproseInstance.uploadToIPFS(uri, uploadUser = author)) {
                                            context.getString(R.string.attachment_upload_failed)
                                        }.also { uploaded[uri] = it }
                                    }
                                }
                                retained.toList() + newAttachments
                            }
                            check(HproseInstance.appUser.mid == adminId)
                            HproseInstance.editTweetAsAdmin(tweet, content, attachments)
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: context.getString(R.string.edit_tweet_failed)
                        } finally {
                            saving = false
                        }
                    }
                }
            ) {
                Text(stringResource(if (saving) R.string.saving else R.string.save))
            }
        }
    )
}
