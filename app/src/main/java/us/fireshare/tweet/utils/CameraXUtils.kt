package us.fireshare.tweet.utils

import android.content.Context
import android.net.Uri
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraXManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService

    fun initialize() {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    fun startCamera(previewView: PreviewView, onImageCaptured: (Uri) -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(previewView, onImageCaptured)
            } catch (exc: Exception) {
                android.util.Log.e("CameraX", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(previewView: PreviewView, onImageCaptured: (Uri) -> Unit) {
        val cameraProvider = cameraProvider ?: return

        // Preview
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // ImageCapture
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        // Select back camera as a default
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            // Unbind use cases before rebinding
            cameraProvider.unbindAll()

            // Bind use cases to camera
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )

        } catch (exc: Exception) {
            android.util.Log.e("CameraX", "Use case binding failed", exc)
        }
    }

    fun takePicture(onImageCaptured: (Uri) -> Unit) {
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.P) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    android.util.Log.e("CameraX", "Photo capture failed: ${exception.message}", exception)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri
                    android.util.Log.d("CameraX", "Photo capture succeeded: $savedUri")
                    savedUri?.let { onImageCaptured(it) }
                }
            }
        )
    }

    fun switchCamera(previewView: PreviewView, onImageCaptured: (Uri) -> Unit) {
        val cameraSelector = if (camera?.cameraInfo?.lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        
        cameraProvider?.let { provider ->
            try {
                provider.unbindAll()
                bindCameraUseCases(previewView, onImageCaptured)
            } catch (exc: Exception) {
                android.util.Log.e("CameraX", "Camera switch failed", exc)
            }
        }
    }

    fun cleanup() {
        cameraExecutor.shutdown()
    }
}
