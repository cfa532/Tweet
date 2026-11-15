package us.fireshare.tweet.tweet

import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.fireshare.tweet.HproseInstance
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.Tweet
import us.fireshare.tweet.ui.components.ExitConfirmationDialog
import us.fireshare.tweet.utils.FileSizeUtils
import us.fireshare.tweet.widget.ImageViewer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdminTweetEditDialog(tweet: Tweet, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val originalContent = remember(tweet.mid) { tweet.content.orEmpty() }
    val adminId = remember(tweet.mid) { HproseInstance.appUser.mid }
    val originalAttachments = remember(tweet.mid) { tweet.attachments.orEmpty() }
    var content by rememberSaveable(tweet.mid) { mutableStateOf(originalContent) }
    val retained = remember(tweet.mid) { mutableStateListOf(*originalAttachments.toTypedArray()) }
    val selected = remember(tweet.mid) { mutableStateListOf<Uri>() }
    val uploaded = remember(tweet.mid) { mutableMapOf<Uri, MimeiFileType>() }
    var saving by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
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

    val hasChanges = content != originalContent || retained != originalAttachments || selected.isNotEmpty()
    val hasContent = content.isNotBlank() || retained.isNotEmpty() || selected.isNotEmpty()
    val closeEditor = {
        if (!saving) {
            if (hasChanges) showDiscard = true else onDismiss()
        }
    }

    fun saveEdit() {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            try {
                check(HproseInstance.appUser.mid == adminId)
                val filesToUpload = selected.toList()
                val attachments =
                    if (filesToUpload.isEmpty() && retained == originalAttachments) {
                        null
                    } else {
                        val newAttachments = if (filesToUpload.isEmpty()) emptyList()
                        else withContext(Dispatchers.IO) {
                            val author = tweet.author?.takeIf { !it.hostIds.isNullOrEmpty() }
                                ?: HproseInstance.fetchUser(tweet.authorId)
                                ?: throw IllegalStateException("Tweet author is unavailable")
                            filesToUpload.map { uri ->
                                // Reuse files already uploaded before a failed save.
                                uploaded[uri] ?: checkNotNull(
                                    HproseInstance.uploadToIPFS(uri, uploadUser = author)
                                ) {
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

    Dialog(
        onDismissRequest = closeEditor,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        val focusManager = LocalFocusManager.current
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(stringResource(R.string.edit)) },
                        navigationIcon = {
                            IconButton(onClick = closeEditor, enabled = !saving) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                            }
                        },
                        actions = {
                            TextButton(
                                enabled = !saving && hasChanges && hasContent,
                                onClick = {
                                    focusManager.clearFocus()
                                    saveEdit()
                                },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(stringResource(if (saving) R.string.saving else R.string.save))
                            }
                        }
                    )
                    if (saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else HorizontalDivider()
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    )
                }
                TextField(
                    value = content,
                    onValueChange = { content = it },
                    readOnly = saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 144.dp),
                    minLines = 4,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    placeholder = { Text(stringResource(R.string.whats_happening)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
                if (retained.isNotEmpty() || selected.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(retained) { attachment ->
                            EditAttachmentTile(
                                name = attachment.fileName,
                                removable = !saving,
                                onRemove = { retained.remove(attachment) }
                            ) {
                                if (attachment.type == MediaType.Image) {
                                    ImageViewer(
                                        imageUrl = HproseInstance.getMediaUrl(
                                            attachment.mid, tweet.readNodeUrl ?: tweet.author?.baseUrl
                                        ),
                                        imageMid = attachment.mid,
                                        modifier = Modifier.fillMaxSize(),
                                        enableLongPress = false,
                                        useFillMode = true
                                    )
                                }
                            }
                        }
                        items(selected, key = { it.toString() }) { uri ->
                            val thumbnail by produceState<ImageBitmap?>(null, uri) {
                                value = withContext(Dispatchers.IO) {
                                    runCatching {
                                        context.contentResolver.loadThumbnail(uri, Size(256, 256), null).asImageBitmap()
                                    }.getOrNull()
                                }
                            }
                            EditAttachmentTile(
                                name = null,
                                removable = !saving,
                                onRemove = { selected.remove(uri) }
                            ) {
                                thumbnail?.let {
                                    Image(
                                        bitmap = it,
                                        contentDescription = stringResource(R.string.attached_file),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(
                    enabled = !saving,
                    onClick = { selectFiles.launch(arrayOf("*/*")) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text(stringResource(R.string.add_attachment))
                }
            }
        }
        ExitConfirmationDialog(
            showDialog = showDiscard,
            onDismiss = { showDiscard = false },
            onConfirm = onDismiss
        )
    }
}

@Composable
private fun EditAttachmentTile(
    name: String?,
    removable: Boolean,
    onRemove: () -> Unit,
    preview: @Composable () -> Unit
) {
    Column(modifier = Modifier.size(width = 108.dp, height = 128.dp)) {
        Box(
            modifier = Modifier.size(108.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_photo_plus),
                contentDescription = stringResource(R.string.attached_file),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            preview()
            IconButton(
                onClick = onRemove,
                enabled = removable,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Box(
                    modifier = Modifier.size(24.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.remove_attachment),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        name?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
