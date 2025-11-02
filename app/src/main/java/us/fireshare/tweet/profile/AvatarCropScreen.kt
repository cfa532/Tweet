package us.fireshare.tweet.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.viewModelScope
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.User
import us.fireshare.tweet.viewmodel.UserViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Simple avatar cropping screen with circular crop overlay
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarCropScreen(
    user: User,
    viewModel: UserViewModel,
    onNavigateBack: () -> Unit,
    onCropComplete: () -> Unit,
    initialImageUri: Uri? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    
    // State for image handling
    var selectedImageUri by remember { mutableStateOf<Uri?>(initialImageUri) }
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    
    // State for image transformation
    var imageOffset by remember { mutableStateOf(Offset.Zero) }
    var imageScale by remember { mutableFloatStateOf(1f) }
    var imageView by remember { mutableStateOf<SubsamplingScaleImageView?>(null) }
    
    // Crop overlay state - make it much larger
    val cropSize = 300.dp
    val cropSizePx = with(density) { cropSize.toPx() }
    
    // Photo picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedImageUri = it
            uploadError = null
            // Reset transformations when new image is selected
            imageOffset = Offset.Zero
            imageScale = 1f
        }
    }
    
    // Auto-open photo picker when screen opens
    LaunchedEffect(Unit) {
        if (selectedImageUri == null) {
            photoPickerLauncher.launch("image/*")
        }
    }
    
    // Load and process selected image
    LaunchedEffect(selectedImageUri) {
        selectedImageUri?.let { uri ->
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = inputStream?.let { stream ->
                    loadAndRotateImage(stream)
                }
                originalBitmap = bitmap
            } catch (e: Exception) {
                Timber.e("Error loading image: ${e.message}")
                uploadError = "Failed to load image"
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (originalBitmap != null) {
                        Button(
                            onClick = {
                                // Crop and upload the image
                                originalBitmap?.let { bitmap ->
                                    isUploading = true
                                    uploadError = null
                                    
                                    viewModel.viewModelScope.launch {
                                        try {
                                            val croppedBitmap = cropCircularImageFromView(
                                                bitmap = bitmap,
                                                imageView = imageView,
                                                cropSizePx = cropSizePx
                                            )
                                            
                                            // Convert to URI and upload
                                            val croppedUri = bitmapToUri(context, croppedBitmap)
                                            viewModel.updateAvatar(context, croppedUri)
                                            
                                            // Success - navigate back
                                            onCropComplete()
                                        } catch (e: Exception) {
                                            uploadError = "Upload failed: ${e.message}"
                                        } finally {
                                            isUploading = false
                                        }
                                    }
                                }
                            },
                            enabled = !isUploading,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(stringResource(R.string.choose))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (originalBitmap == null) {
                // Show photo picker interface
                PhotoPickerInterface(
                    onPickPhoto = { photoPickerLauncher.launch("image/*") },
                    modifier = Modifier.weight(1f)
                )
            } else {
                // Show cropping interface
                CroppingInterface(
                    bitmap = originalBitmap!!,
                    imageOffset = imageOffset,
                    imageScale = imageScale,
                    cropSizePx = cropSizePx,
                    onOffsetChange = { imageOffset = it },
                    onScaleChange = { imageScale = it },
                    onImageViewReady = { imageView = it },
                    isUploading = isUploading,
                    modifier = Modifier.weight(1f)
                )
            }
            
            // Show error message if upload failed
            uploadError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
            
            // Action buttons - only show when no image selected
            if (originalBitmap == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = { photoPickerLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                        enabled = !isUploading
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.choose_photo))
                    }
                }
            }
        }
        
        // Show loading spinner overlay when uploading
        if (isUploading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Processing...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoPickerInterface(
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.select_photo_to_crop),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.crop_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun CroppingInterface(
    bitmap: Bitmap,
    imageOffset: Offset,
    imageScale: Float,
    cropSizePx: Float,
    onOffsetChange: (Offset) -> Unit,
    onScaleChange: (Float) -> Unit,
    onImageViewReady: (SubsamplingScaleImageView?) -> Unit,
    isUploading: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Black background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Use SubsamplingScaleImageView for proper gestures
            var imageView by remember { mutableStateOf<SubsamplingScaleImageView?>(null) }
            
            AndroidView(
                factory = { context ->
                    SubsamplingScaleImageView(context).apply {
                        setImage(ImageSource.bitmap(bitmap))
                        setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)
                        setDoubleTapZoomDuration(300)
                        setDoubleTapZoomScale(2f)
                        setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_OUTSIDE)
                        imageView = this
                        
                        // Set scale limits immediately
                        val fittedScale = minOf(
                            context.resources.displayMetrics.widthPixels.toFloat() / bitmap.width,
                            context.resources.displayMetrics.heightPixels.toFloat() / bitmap.height
                        )
                        val minScale = fittedScale * 0.5f
                        val maxScale = fittedScale * 3f
                        setMinScale(minScale)
                        setMaxScale(maxScale)
                        
                        // Set scale limits after image is loaded
                        setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                            override fun onImageLoaded() {
                                super.onImageLoaded()
                                post {
                                    // Set initial scale to fit screen
                                    setScaleAndCenter(fittedScale, null)
                                    
                                    Timber.d("AvatarCrop: Image loaded - fitted scale: $fittedScale, min: $minScale, max: $maxScale (view: ${width}x${height}, bitmap: ${bitmap.width}x${bitmap.height})")
                                }
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // Notify when imageView is ready
            LaunchedEffect(imageView) {
                onImageViewReady(imageView)
            }
        }
        
        // Dark overlay outside circle (shows image in circle)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            val radius = cropSizePx / 2
            
            // Draw dark overlay in 4 rectangles around the circle
            val circleRect = androidx.compose.ui.geometry.Rect(
                centerX - radius,
                centerY - radius,
                centerX + radius,
                centerY + radius
            )
            
            // Top rectangle
            drawRect(
                color = Color.Black.copy(alpha = 0.6f),
                topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                size = androidx.compose.ui.geometry.Size(size.width, circleRect.top)
            )
            
            // Bottom rectangle
            drawRect(
                color = Color.Black.copy(alpha = 0.6f),
                topLeft = androidx.compose.ui.geometry.Offset(0f, circleRect.bottom),
                size = androidx.compose.ui.geometry.Size(size.width, size.height - circleRect.bottom)
            )
            
            // Left rectangle
            drawRect(
                color = Color.Black.copy(alpha = 0.6f),
                topLeft = androidx.compose.ui.geometry.Offset(0f, circleRect.top),
                size = androidx.compose.ui.geometry.Size(circleRect.left, circleRect.height)
            )
            
            // Right rectangle
            drawRect(
                color = Color.Black.copy(alpha = 0.6f),
                topLeft = androidx.compose.ui.geometry.Offset(circleRect.right, circleRect.top),
                size = androidx.compose.ui.geometry.Size(size.width - circleRect.right, circleRect.height)
            )
            
            // Draw crop circle border
            drawCircle(
                center = androidx.compose.ui.geometry.Offset(centerX, centerY),
                radius = radius,
                color = Color.White,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}


/**
 * Load image from input stream and apply EXIF rotation correction
 */
private fun loadAndRotateImage(inputStream: InputStream): Bitmap? {
    return try {
        // Read the entire stream into a byte array to handle mark/reset issues
        val byteArray = inputStream.readBytes()
        val byteArrayInputStream = java.io.ByteArrayInputStream(byteArray)
        
        // Decode bitmap
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeStream(byteArrayInputStream, null, options)
        
        if (bitmap != null) {
            // Apply EXIF rotation
            val exifStream = java.io.ByteArrayInputStream(byteArray)
            val exif = ExifInterface(exifStream)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
                else -> return bitmap
            }
            
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else null
    } catch (e: Exception) {
        Timber.e("Error loading and rotating image: ${e.message}")
        null
    }
}

/**
 * Transform bitmap with scale
 */
private fun transformBitmap(bitmap: Bitmap, scale: Float): Bitmap {
    val matrix = Matrix().apply {
        postScale(scale, scale)
    }
    
    return Bitmap.createBitmap(
        bitmap,
        0, 0, bitmap.width, bitmap.height,
        matrix,
        true
    )
}

/**
 * Crop image to circular area from the actual view state, preserving aspect ratio
 */
private fun cropCircularImageFromView(
    bitmap: Bitmap,
    imageView: SubsamplingScaleImageView?,
    cropSizePx: Float
): Bitmap {
    val cropSize = cropSizePx.toInt()
    
    if (imageView != null) {
        // Get the actual view state from SubsamplingScaleImageView
        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        
        // Get the current scale and pan from the view
        val scale = imageView.scale
        val center = imageView.center
        
        // Calculate the source rectangle in the original bitmap
        val sourceCenterX = center?.x ?: (bitmap.width / 2f)
        val sourceCenterY = center?.y ?: (bitmap.height / 2f)
        val sourceSize = cropSize / scale
        
        val sourceX = (sourceCenterX - sourceSize / 2f).toInt().coerceAtLeast(0)
        val sourceY = (sourceCenterY - sourceSize / 2f).toInt().coerceAtLeast(0)
        val sourceWidth = sourceSize.toInt().coerceAtMost(bitmap.width - sourceX)
        val sourceHeight = sourceSize.toInt().coerceAtMost(bitmap.height - sourceY)
        
        // Calculate output dimensions preserving aspect ratio of the selected area
        val selectedAspectRatio = sourceWidth.toFloat() / sourceHeight.toFloat()
        val outputWidth: Int
        val outputHeight: Int
        
        if (selectedAspectRatio > 1f) {
            // Landscape: width is larger
            outputWidth = cropSize
            outputHeight = (cropSize / selectedAspectRatio).toInt()
        } else {
            // Portrait or square: height is larger or equal
            outputWidth = (cropSize * selectedAspectRatio).toInt()
            outputHeight = cropSize
        }
        
        // Create result bitmap with proper aspect ratio
        val result = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        
        // Fill with black background
        canvas.drawColor(android.graphics.Color.BLACK)
        
        // Draw the cropped portion of the bitmap
        val sourceRect = android.graphics.Rect(sourceX, sourceY, sourceX + sourceWidth, sourceY + sourceHeight)
        val destRect = android.graphics.Rect(0, 0, outputWidth, outputHeight)
        canvas.drawBitmap(bitmap, sourceRect, destRect, null)
        
        return result
    } else {
        // Fallback: return original bitmap if imageView is null
        return bitmap
    }
}

/**
 * Convert bitmap to URI for upload (no temp file)
 */
private fun bitmapToUri(context: Context, bitmap: Bitmap): Uri {
    val bytes = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes)
    val tempFile = File(context.cacheDir, "cropped_avatar_${System.currentTimeMillis()}.jpg")
    tempFile.writeBytes(bytes.toByteArray())
    return Uri.fromFile(tempFile)
}
