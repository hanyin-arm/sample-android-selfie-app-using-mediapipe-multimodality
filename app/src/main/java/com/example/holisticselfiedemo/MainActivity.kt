package com.example.holisticselfiedemo

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.icu.text.SimpleDateFormat
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.holisticselfiedemo.databinding.ActivityMainBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // View binding
    private var _viewBinding: ActivityMainBinding? = null
    private val viewBinding: ActivityMainBinding
        get() = _viewBinding ?: throw IllegalStateException("binding absent")

    // Camera
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null

    private var isHelperReady = false

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        _viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (!hasPermissions(baseContext)) {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CODE_CAMERA_PERMISSION
            )
        } else {
            setupCamera()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    viewModel.uiEvents.collect { uiEvent ->
                        when (uiEvent) {
                            is MainViewModel.UiEvent.Face -> drawFaces(uiEvent.face)
                            is MainViewModel.UiEvent.Gesture -> drawGestures(uiEvent.gestures)
                        }
                    }
                }
                launch {
                    viewModel.faceOk.collect {
                        viewBinding.faceReady.isChecked = it
                    }
                }
                launch {
                    viewModel.gestureOk.collect {
                        viewBinding.gestureReady.isChecked = it
                    }
                }
                launch {
                    viewModel.captureEvents.collect {
                        executeCapturePhoto {
                            viewModel.onPhotoCaptureComplete()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setupHelper(baseContext)
        isHelperReady = true
    }

    override fun onPause() {
        super.onPause()
        isHelperReady = false
        viewModel.shutdownHelper()
    }

    private fun setupCamera() {
        viewBinding.viewFinder.post {
            // Unbind camera use cases if exist
            cameraProvider?.unbindAll()

            ProcessCameraProvider.getInstance(baseContext).let {
                it.addListener(
                    {
                        // Build and bind the camera use cases
                        cameraProvider = it.get()
                        bindCameraUseCases()
                    },
                    Dispatchers.Main.asExecutor()
                )
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_FRONT).build()

        // Only using the 4:3 ratio because this is the closest to MediaPipe models
        val resolutionSelector =
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

        // Preview.
        val targetRotation = viewBinding.viewFinder.display.rotation
        preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(targetRotation)
            .build()

        // ImageAnalysis. Using RGBA 8888 to match how MediaPipe models work
        imageAnalysis =
            ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(viewBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(
                        // Forcing a serial executor without parallelism
                        // to avoid packets sent to MediaPipe out-of-order
                        Dispatchers.Default.limitedParallelism(1).asExecutor()
                    ) { image ->
                        if (isHelperReady)
                            viewModel.recognizeLiveStream(image)
                    }
                }

        // Image Capture
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(targetRotation)
            .build()

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalysis, imageCapture
            )

            // Attach the viewfinder's surface provider to preview use case
            preview?.surfaceProvider = viewBinding.viewFinder.surfaceProvider
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun drawFaces(resultBundle: FaceResultBundle) {
        // Pass necessary information to OverlayView for drawing on the canvas
        viewBinding.overlayFace.setResults(
            resultBundle.result,
            resultBundle.inputImageHeight,
            resultBundle.inputImageWidth,
            RunningMode.LIVE_STREAM
        )
        // Force a redraw
        viewBinding.overlayFace.invalidate()
    }

    private fun drawGestures(resultBundle: GestureResultBundle) {
        // Pass necessary information to OverlayView for drawing on the canvas
        viewBinding.overlayGesture.setResults(
            resultBundle.results.first(),
            resultBundle.inputImageHeight,
            resultBundle.inputImageWidth,
            RunningMode.LIVE_STREAM
        )

        // Force a redraw
        viewBinding.overlayGesture.invalidate()
    }

    private fun executeCapturePhoto(onComplete: () -> Unit) {
        imageCapture?.let { imageCapture ->
            val name = SimpleDateFormat(FILENAME, Locale.US)
                .format(System.currentTimeMillis())
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, PHOTO_TYPE)
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    val appName = resources.getString(R.string.app_name)
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "Pictures/${appName}"
                    )
                }
            }

            val outputOptions = ImageCapture.OutputFileOptions
                .Builder(
                    contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                .build()

            showFlashEffect()

            imageCapture.takePicture(outputOptions, Dispatchers.IO.asExecutor(),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(error: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${error.message}", error)
                    }

                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        val savedUri = outputFileResults.savedUri
                        Log.i(TAG, "Photo capture succeeded: $savedUri")

                        onComplete()
                    }
                })
        }
    }

    private fun showFlashEffect() {
        viewBinding.flashOverlay.apply {
            visibility = View.VISIBLE
            alpha = 0f

            // Fade in and out animation
            animate()
                .alpha(1f)
                .setDuration(IMAGE_CAPTURE_FLASH_DURATION)
                .withEndAction {
                    animate()
                        .alpha(0f)
                        .setDuration(IMAGE_CAPTURE_FLASH_DURATION)
                        .withEndAction {
                            visibility = View.GONE
                        }
                }
        }
    }

    private fun hasPermissions(context: Context) = PERMISSIONS_REQUIRED.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            REQUEST_CODE_CAMERA_PERMISSION -> {
                if (PackageManager.PERMISSION_GRANTED == grantResults.getOrNull(0)) {
                    setupCamera()
                } else {
                    val messageResId =
                        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
                            R.string.permission_request_camera_rationale
                        else
                            R.string.permission_request_camera_message
                    Toast.makeText(baseContext, getString(messageResId), Toast.LENGTH_LONG).show()
                }
            }

            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _viewBinding = null
    }

    companion object {
        private const val TAG = "MainActivity"

        // Permissions
        private val PERMISSIONS_REQUIRED = arrayOf(Manifest.permission.CAMERA)
        private const val REQUEST_CODE_CAMERA_PERMISSION = 233

        // Image capture
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_TYPE = "image/jpeg"
        private const val IMAGE_CAPTURE_FLASH_DURATION = 100L
    }
}