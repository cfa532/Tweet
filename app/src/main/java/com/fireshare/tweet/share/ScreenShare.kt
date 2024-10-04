package com.fireshare.tweet.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario.launch
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
fun ShareScreenshot(viewModel: TweetViewModel) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    var showBottomSheet by remember { mutableStateOf(false) }

    if (showBottomSheet) {
        BottomSheet(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheet(onDismiss: () -> Unit, viewModel: TweetViewModel) {
    val scaffoldState = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()
    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 128.dp,
        sheetContent = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(128.dp), contentAlignment = Alignment.Center) {
                    Text("Swipe up to expand sheet")
                }
                Text("Sheet content")
                Button(
                    modifier = Modifier.padding(bottom = 64.dp),
                    onClick = { scope.launch { scaffoldState.bottomSheetState.partialExpand() } }
                ) {
                    Text("Click to collapse sheet")
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxWidth().padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Text("Scaffold Content")
        }
    }
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
            android.graphics.Rect(
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
    val canvas = android.graphics.Canvas(bitmap)
    view.draw(canvas)
    return bitmap
}