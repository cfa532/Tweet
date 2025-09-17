package us.fireshare.tweet.utils

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@Throws(IOException::class)
fun createVideoFile(context: Context): File? {
    val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val storageDir: File? = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
    return File.createTempFile(
        "VIDEO_${timeStamp}_",
        ".mp4",
        storageDir
    )
}
