package com.fireshare.tweet.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import com.fireshare.tweet.HproseInstance.appUser
import com.fireshare.tweet.datamodel.TW_CONST
import com.fireshare.tweet.navigation.LocalNavController
import com.fireshare.tweet.tweet.guestWarning
import com.fireshare.tweet.viewmodel.TweetViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreenshotButton(viewModel: TweetViewModel) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    var showBottomSheet by remember { mutableStateOf(false) }

    if (showBottomSheet) {
        ShareBottomSheet(
            onDismiss = { showBottomSheet = false },
            viewModel = viewModel
        )
    }
    IconButton(onClick = {
        if (appUser.mid == TW_CONST.GUEST_ID) {
            viewModel.viewModelScope.launch {
                guestWarning(context, navController)
            }
        } else {
            showBottomSheet = true
        }
    }) {
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Share",
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun ShareBottomSheet(onDismiss: () -> Unit, viewModel: TweetViewModel) {
    val scope = rememberCoroutineScope()
    val bottomSheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        sheetState = bottomSheetState,
        onDismissRequest = onDismiss,
        content = {
            // Custom options
            ListItem(
                headlineContent = { Text("Save Image") },
                modifier = Modifier.clickable {
                    // Handle save image action
                    scope.launch { onDismiss() }
                }
            )
            ListItem(
                headlineContent = { Text("Copy Link") },
                modifier = Modifier.clickable {
                    // Handle copy link action
                    scope.launch { bottomSheetState.hide() }
                }
            )
            HorizontalDivider()
            // Regular Android sharing options
            ListItem(
                headlineContent = { Text("Share via...") },
                modifier = Modifier.clickable {
                    // Handle share action
                    // Implement your sharing logic here
                    scope.launch { bottomSheetState.hide() }
                }
            )
        }
    )
}

fun captureScreenshot(activity: Activity, callback: (Bitmap?) -> Unit) {
    val window: Window = activity.window
    val view: View = window.decorView.rootView
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val location = IntArray(2)
    view.getLocationInWindow(location)
    try {
        PixelCopy.request(
            window,
            Rect(
                location[0],
                location[1],
                location[0] + view.width,
                location[1] + view.height
            ),
            bitmap,
            { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    callback(bitmap)
                } else {
                    callback(null)
                }
            },
            Handler(Looper.getMainLooper())
        )
    } catch (e: IllegalArgumentException) {
        e.printStackTrace()
        callback(null)
    }
}

fun saveBitmapToFile(context: Context, bitmap: Bitmap, filename: String): File {
    val file = File(context.cacheDir, filename)
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    return file
}

fun shareImage(context: Context, file: File) {
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val shareIntent: Intent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_STREAM, uri)
        type = "image/png"
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share Image"))
}

fun generateQRCode(text: String, size: Int): Bitmap {
    val bitMatrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val width = bitMatrix.width
    val height = bitMatrix.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}

fun getScreenshotFromView(view: View): Bitmap? {
    if (view.width == 0 || view.height == 0) {
        return null
    }
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    view.draw(canvas)
    return bitmap
}