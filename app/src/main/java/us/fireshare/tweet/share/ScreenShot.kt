package us.fireshare.tweet.share

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
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import us.fireshare.tweet.R
import java.io.File
import java.io.FileOutputStream

/**
 * @param activity The activity from which the screenshot is captured.
 * @param qrText will be encoded into QR code.
 * @param callback process the screenshot with QR code at right lower corner.
 * */
fun captureScreenshotWithQRCode(activity: Activity, qrText: String, callback: (Bitmap?) -> Unit) {
    captureScreenshot(activity) { screenshot ->
        if (screenshot != null && screenshot.config != null)  {
            // Generate the QR code
            val qrCodeSize = 300 // Define the size of the QR code
            val qrCodeBitmap = generateQRCode(qrText, qrCodeSize)

            // Create a new bitmap with the same dimensions as the screenshot
            val combinedBitmap =
                createBitmap(screenshot.width, screenshot.height, screenshot.config!!)
            val canvas = Canvas(combinedBitmap)

            // Draw the screenshot onto the canvas
            canvas.drawBitmap(screenshot, 0f, 0f, null)

            // Calculate the position for the QR code at the lower right corner
            val left = (screenshot.width - qrCodeSize).toFloat()
            val top = (screenshot.height - qrCodeSize).toFloat()

            // Draw the QR code onto the canvas
            canvas.drawBitmap(qrCodeBitmap, left, top, null)

            // Return the combined bitmap with the QR code
            callback(combinedBitmap)
        } else {
            callback(null)
        }
    }
}

fun captureScreenshot(activity: Activity, callback: (Bitmap?) -> Unit) {
    val window: Window = activity.window
    val view: View = window.decorView.rootView
    val bitmap = createBitmap(view.width, view.height)
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

fun generateQRCode(text: String, size: Int): Bitmap {
    val bitMatrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val width = bitMatrix.width
    val height = bitMatrix.height
    val bitmap = createBitmap(width, height, Bitmap.Config.RGB_565)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap[x, y] = if (bitMatrix.get(
                    x,
                    y
                )
            ) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return bitmap
}