package us.fireshare.tweet.widget

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.getMediaUrl
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiFileType
import java.io.File
import java.net.URL
import java.text.DecimalFormat

/**
 * View for displaying document attachments (PDF, Word, Excel, etc.) with improved UI
 * Similar to iOS DocumentAttachmentsView implementation
 */
@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun DocumentAttachmentsView(
    documents: List<MimeiFileType>,
    modifier: Modifier = Modifier,
    baseUrl: String? = null,
    maxDocuments: Int? = 2, // Limit to 2 documents in list view (like iOS)
) {
    if (documents.isEmpty()) return

    val context = LocalContext.current
    val displayedDocuments = if (maxDocuments != null && documents.size > maxDocuments) {
        documents.take(maxDocuments)
    } else {
        documents
    }
    val hasMoreDocuments = maxDocuments != null && documents.size > maxDocuments

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Vertical list of documents
        displayedDocuments.forEach { document ->
            DocumentRowView(
                document = document,
                baseUrl = baseUrl,
                context = context
            )
        }

        // Show ellipsis if there are more documents
        if (hasMoreDocuments) {
            Row(
                modifier = Modifier
                    .padding(start = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "···",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(
                    text = "+${documents.size - displayedDocuments.size} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Individual document row view with improved UI
 */
@Composable
private fun DocumentRowView(
    document: MimeiFileType,
    baseUrl: String?,
    context: Context
) {
    var isDownloading by remember { mutableStateOf(false) }
    var isDownloadingForShare by remember { mutableStateOf(false) }

    val icon = getDocumentIcon(document.type)
    val iconColor = getDocumentIconColor(document.type)
    val displayFileName = truncateFileName(document.fileName ?: "Document", maxLength = 30)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(enabled = !isDownloading && !isDownloadingForShare) {
                if (!isDownloading && !isDownloadingForShare) {
                    openDocument(context, document, baseUrl) {
                        isDownloading = it
                    }
                }
            }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayFileName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    document.size?.let { size ->
                        Text(
                            text = formatFileSize(size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Download button
            IconButton(
                onClick = {
                    if (!isDownloadingForShare) {
                        downloadDocument(context, document, baseUrl) {
                            isDownloadingForShare = it
                        }
                    }
                },
                enabled = !isDownloading && !isDownloadingForShare
            ) {
                if (isDownloadingForShare) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.FileDownload,
                        contentDescription = "Download",
                        tint = iconColor
                    )
                }
            }
        }

        // Spinner overlay when downloading for preview
        if (isDownloading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

/**
 * Get icon for document type
 */
private fun getDocumentIcon(type: MediaType): androidx.compose.ui.graphics.vector.ImageVector {
    return when (type) {
        MediaType.PDF -> Icons.Default.PictureAsPdf
        MediaType.Word -> Icons.Default.Description
        MediaType.Excel -> Icons.Default.TableChart
        MediaType.PPT -> Icons.Default.VideoLibrary
        MediaType.Zip -> Icons.Default.Archive
        MediaType.Txt -> Icons.Default.Description
        MediaType.Html -> Icons.Default.Web
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

/**
 * Get icon color for document type
 */
@Composable
private fun getDocumentIconColor(type: MediaType): Color {
    return when (type) {
        MediaType.PDF -> Color(0xFFE53935) // Red
        MediaType.Word -> Color(0xFF1976D2) // Blue
        MediaType.Excel -> Color(0xFF388E3C) // Green
        MediaType.PPT -> Color(0xFFFF6F00) // Orange
        MediaType.Zip -> Color(0xFF7B1FA2) // Purple
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * Truncate file name if too long
 */
private fun truncateFileName(fileName: String, maxLength: Int = 30): String {
    if (fileName.length <= maxLength) return fileName
    val ellipsis = "..."
    val halfLength = (maxLength - ellipsis.length) / 2
    val start = fileName.take(halfLength)
    val end = fileName.substring(fileName.length - halfLength)
    return "$start$ellipsis$end"
}

/**
 * Format file size
 */
private fun formatFileSize(size: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    val formatter = DecimalFormat("#.##")
    var fileSize = size.toDouble()
    var unitIndex = 0

    while (fileSize >= 1024 && unitIndex < units.size - 1) {
        fileSize /= 1024
        unitIndex++
    }

    return "${formatter.format(fileSize)} ${units[unitIndex]}"
}

/**
 * Open document in corresponding app (downloads if needed, then opens)
 */
private fun openDocument(
    context: Context,
    document: MimeiFileType,
    baseUrl: String?,
    onDownloadingChange: (Boolean) -> Unit
) {
    val mediaUrl = document.url ?: getMediaUrl(document.mid, baseUrl ?: "") ?: ""
    if (mediaUrl.isBlank()) {
        Timber.e("DocumentAttachmentsView: Invalid document URL")
        return
    }

    // Create temp directory and file
    val tempDir = File(context.cacheDir, "documents")
    if (!tempDir.exists()) {
        tempDir.mkdirs()
    }

    val originalFileName = document.fileName ?: "Document.pdf"
    val fileExtension = originalFileName.substringAfterLast('.', "pdf")
    val baseName = originalFileName.substringBeforeLast('.', originalFileName)
    val uniqueFileName = "${baseName}_${document.mid.take(8)}.$fileExtension"
    val cachedFile = File(tempDir, uniqueFileName)

    // Check if file already exists and is valid
    if (cachedFile.exists() && cachedFile.length() > 0 && cachedFile.canRead()) {
        Timber.d("DocumentAttachmentsView: Using cached file: $uniqueFileName (${cachedFile.length()} bytes)")
        onDownloadingChange(true)
        // Present document viewer
        presentDocumentViewer(context, cachedFile) {
            onDownloadingChange(false)
        }
        return
    }

    // Download file directly using HTTP (DownloadManager doesn't support cache directory)
    onDownloadingChange(true)
    Timber.d("DocumentAttachmentsView: Downloading file from server...")

    // Use coroutine scope for async download
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val url = URL(mediaUrl)
            val connection = url.openConnection()
            connection.connect()

            // Download file
            connection.getInputStream().use { input ->
                cachedFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Verify file is valid
            if (cachedFile.exists() && cachedFile.length() > 0 && cachedFile.canRead()) {
                Timber.d("DocumentAttachmentsView: File downloaded successfully: ${cachedFile.length()} bytes")
                // Present document viewer on main thread
                withContext(Dispatchers.Main) {
                    presentDocumentViewer(context, cachedFile) {
                        onDownloadingChange(false)
                    }
                }
            } else {
                Timber.e("DocumentAttachmentsView: Downloaded file is invalid")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to download file", Toast.LENGTH_SHORT).show()
                    onDownloadingChange(false)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "DocumentAttachmentsView: Download failed")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to download file: ${e.message}", Toast.LENGTH_SHORT).show()
                onDownloadingChange(false)
            }
        }
    }
}

/**
 * Download document to Downloads folder
 */
private fun downloadDocument(
    context: Context,
    document: MimeiFileType,
    baseUrl: String?,
    onDownloadingChange: (Boolean) -> Unit
) {
    val mediaUrl = document.url ?: getMediaUrl(document.mid, baseUrl ?: "") ?: ""
    if (mediaUrl.isBlank()) {
        Timber.e("DocumentAttachmentsView: Invalid document URL")
        return
    }

    val originalFileName = document.fileName ?: getDefaultFileName(document.type)

    onDownloadingChange(true)

    val request = DownloadManager.Request(mediaUrl.toUri())
        .setTitle(originalFileName)
        .setDescription("Downloading")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, originalFileName)

    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val downloadId = downloadManager.enqueue(request)

    // Monitor download completion
    Thread {
        var downloading = true
        while (downloading) {
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(
                                context,
                                context.getString(R.string.downloading_file),
                                Toast.LENGTH_SHORT
                            ).show()
                            onDownloadingChange(false)
                        }
                        downloading = false
                    }
                    DownloadManager.STATUS_FAILED -> {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(context, "Failed to download file", Toast.LENGTH_SHORT).show()
                            onDownloadingChange(false)
                        }
                        downloading = false
                    }
                }
            }
            cursor.close()
            if (downloading) {
                Thread.sleep(500)
            }
        }
    }.start()
}

/**
 * Present document viewer
 */
private fun presentDocumentViewer(context: Context, file: File, onDismiss: () -> Unit) {
    try {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
        } else {
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, getMimeType(file.name))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Timber.e(e, "DocumentAttachmentsView: Failed to open document")
        Toast.makeText(context, "Failed to open document", Toast.LENGTH_SHORT).show()
    } finally {
        onDismiss()
    }
}

/**
 * Get default file name for document type
 */
private fun getDefaultFileName(type: MediaType): String {
    return when (type) {
        MediaType.PDF -> "Document.pdf"
        MediaType.Word -> "Document.docx"
        MediaType.Excel -> "Spreadsheet.xlsx"
        MediaType.PPT -> "Presentation.pptx"
        MediaType.Zip -> "Archive.zip"
        MediaType.Txt -> "Text.txt"
        MediaType.Html -> "Page.html"
        else -> "Attachment"
    }
}

/**
 * Get MIME type from file name
 */
private fun getMimeType(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "pdf" -> "application/pdf"
        "doc", "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls", "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt", "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip", "rar", "7z" -> "application/zip"
        "txt" -> "text/plain"
        "html", "htm" -> "text/html"
        else -> "application/octet-stream"
    }
}

