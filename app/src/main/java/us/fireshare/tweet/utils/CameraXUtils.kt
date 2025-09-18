package us.fireshare.tweet.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import timber.log.Timber
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
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

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
                Timber.tag("CameraX").e(exc, "Use case binding failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(previewView: PreviewView, onImageCaptured: (Uri) -> Unit) {
        val cameraProvider = cameraProvider ?: return

        // Preview
        val preview = Preview.Builder()
            .build()
            .also {
                it.surfaceProvider = previewView.surfaceProvider
            }

        // ImageCapture
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        // VideoCapture
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            // Unbind use cases before rebinding
            cameraProvider.unbindAll()

            // Bind use cases to camera
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                currentCameraSelector,
                preview,
                imageCapture,
                videoCapture
            )

        } catch (exc: Exception) {
            Timber.tag("CameraX").e( "Use case binding failed")
        }
    }

    fun takePicture(onImageCaptured: (Uri) -> Unit) {
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "Tweet_$name.jpg")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.DESCRIPTION, "Photo taken with Tweet app")
            put(android.provider.MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.P) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Tweet")
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
                    Timber.tag("CameraX").e(exception, "Photo capture failed: ${exception.message}")
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = output.savedUri
                    Timber.tag("CameraX").d("Photo capture succeeded: $savedUri")
                    savedUri?.let { onImageCaptured(it) }
                }
            }
        )
    }

    fun switchCamera(previewView: PreviewView, onImageCaptured: (Uri) -> Unit) {
        cameraProvider?.let { provider ->
            try {
                // Unbind current use cases
                provider.unbindAll()
                
                // Switch camera selector
                currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                
                // Rebind with new camera
                bindCameraUseCases(previewView, onImageCaptured)
            } catch (exc: Exception) {
                Timber.tag("CameraX").e(exc, "Camera switch failed")
            }
        }
    }

    fun startVideoRecording(onVideoRecorded: (Uri) -> Unit) {
        val videoCapture = videoCapture ?: return

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "Tweet_$name.mp4")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.Video.Media.DESCRIPTION, "Video recorded with Tweet app")
            put(android.provider.MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.P) {
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/Tweet")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        recording = videoCapture.output
            .prepareRecording(context, mediaStoreOutputOptions)
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(context)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        Timber.tag("CameraX").d("Video recording started")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val savedUri = recordEvent.outputResults.outputUri
                            Timber.tag("CameraX").d("Video saved: $savedUri")
                            onVideoRecorded(savedUri)
                        } else {
                            Timber.tag("CameraX").e("Video recording failed: ${recordEvent.error}")
                        }
                        recording = null
                    }
                }
            }
    }

    fun stopVideoRecording() {
        recording?.stop()
        recording = null
    }

    fun isRecording(): Boolean {
        return recording != null
    }

    fun cleanup() {
        recording?.stop()
        cameraExecutor.shutdown()
    }
}
