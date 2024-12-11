package com.example.holisticselfiedemo

/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

class HolisticRecognizerHelper(
    private var currentDelegate: Int = DELEGATE_GPU,
    private var runningMode: RunningMode = RunningMode.LIVE_STREAM
) {
    var listener: Listener? = null

    private var faceLandmarker: FaceLandmarker? = null
    private var gestureRecognizer: GestureRecognizer? = null

    fun setup(context: Context) {
        setupFaceLandmarker(context)
        setupGestureRecognizer(context)
    }

    fun shutdown() {
        faceLandmarker?.close()
        faceLandmarker = null
        gestureRecognizer?.close()
        gestureRecognizer = null
    }

    // Return running status of the recognizer helper
    val isClosed: Boolean
        get() = faceLandmarker == null && gestureRecognizer == null

    // Initialize the Face landmarker using current settings on the
    // thread that is using it. CPU can be used with Landmarker
    // that are created on the main thread and used on a background thread, but
    // the GPU delegate needs to be used on the thread that initialized the Landmarker
    private fun setupFaceLandmarker(context: Context) {
        // Set general face landmarker options
        val baseOptionBuilder = BaseOptions.builder()

        // Use the specified hardware for running the model. Default to CPU
        when (currentDelegate) {
            DELEGATE_CPU -> {
                baseOptionBuilder.setDelegate(Delegate.CPU)
            }

            DELEGATE_GPU -> {
                baseOptionBuilder.setDelegate(Delegate.GPU)
            }
        }

        baseOptionBuilder.setModelAssetPath(MP_FACE_LANDMARKER_TASK)

        try {
            val baseOptions = baseOptionBuilder.build()
            // Create an option builder with base options and specific
            // options only use for Face Landmarker.
            val optionsBuilder =
                FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinFaceDetectionConfidence(DEFAULT_FACE_DETECTION_CONFIDENCE)
                    .setMinTrackingConfidence(DEFAULT_FACE_TRACKING_CONFIDENCE)
                    .setMinFacePresenceConfidence(DEFAULT_FACE_PRESENCE_CONFIDENCE)
                    .setNumFaces(FACES_COUNT)
                    .setOutputFaceBlendshapes(true)
                    .setRunningMode(runningMode)

            // The ResultListener and ErrorListener only use for LIVE_STREAM mode.
            if (runningMode == RunningMode.LIVE_STREAM) {
                optionsBuilder
                    .setResultListener(this::returnFaceLivestreamResult)
                    .setErrorListener(this::returnLivestreamError)
            }

            val options = optionsBuilder.build()
            faceLandmarker =
                FaceLandmarker.createFromOptions(context, options)
        } catch (e: IllegalStateException) {
            listener?.onFaceLandmarkerError(
                "Face Landmarker failed to initialize. See error logs for " +
                        "details"
            )
            Log.e(
                TAG, "MediaPipe failed to load the task with error: " + e
                    .message
            )
        } catch (e: RuntimeException) {
            // This occurs if the model being used does not support GPU
            listener?.onFaceLandmarkerError(
                "Face Landmarker failed to initialize. See error logs for " +
                        "details", GPU_ERROR
            )
            Log.e(
                TAG,
                "Face Landmarker failed to load model with error: " + e.message
            )
        }
    }

    // Initialize the gesture recognizer using current settings on the
    // thread that is using it. CPU can be used with recognizers
    // that are created on the main thread and used on a background thread, but
    // the GPU delegate needs to be used on the thread that initialized the recognizer
    private fun setupGestureRecognizer(context: Context) {
        // Set general recognition options, including number of used threads
        val baseOptionBuilder = BaseOptions.builder()

        // Use the specified hardware for running the model. Default to CPU
        when (currentDelegate) {
            DELEGATE_CPU -> {
                baseOptionBuilder.setDelegate(Delegate.CPU)
            }

            DELEGATE_GPU -> {
                baseOptionBuilder.setDelegate(Delegate.GPU)
            }
        }

        baseOptionBuilder.setModelAssetPath(MP_RECOGNIZER_TASK)

        try {
            val baseOptions = baseOptionBuilder.build()
            val optionsBuilder =
                GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinHandDetectionConfidence(DEFAULT_HAND_DETECTION_CONFIDENCE)
                    .setMinTrackingConfidence(DEFAULT_HAND_TRACKING_CONFIDENCE)
                    .setMinHandPresenceConfidence(DEFAULT_HAND_PRESENCE_CONFIDENCE)
                    .setNumHands(HANDS_COUNT)
                    .setRunningMode(runningMode)

            if (runningMode == RunningMode.LIVE_STREAM) {
                optionsBuilder
                    .setResultListener(this::returnGestureLivestreamResult)
                    .setErrorListener(this::returnLivestreamError)
            }
            val options = optionsBuilder.build()
            gestureRecognizer =
                GestureRecognizer.createFromOptions(context, options)
        } catch (e: IllegalStateException) {
            listener?.onGestureError(
                "Gesture recognizer failed to initialize. See error logs for " + "details"
            )
            Log.e(
                TAG,
                "MP Task Vision failed to load the task with error: " + e.message
            )
        } catch (e: RuntimeException) {
            listener?.onGestureError(
                "Gesture recognizer failed to initialize. See error logs for " + "details",
                GPU_ERROR
            )
            Log.e(
                TAG,
                "MP Task Vision failed to load the task with error: " + e.message
            )
        }
    }

    // Convert the ImageProxy to MP Image and feed it to GestureRecognizer and FaceLandmarker.
    fun recognizeLiveStream(
        imageProxy: ImageProxy,
    ) {
        val frameTime = SystemClock.uptimeMillis()

        // Copy out RGB bits from the frame to a bitmap buffer
        val bitmapBuffer = Bitmap.createBitmap(
            imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
        )
        imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
        imageProxy.close()

        val matrix = Matrix().apply {
            // Rotate the frame received from the camera to be in the same direction as it'll be shown
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

            // flip image since we only support front camera
            postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
        }

        // Rotate bitmap to match what our model expects
        val rotatedBitmap = Bitmap.createBitmap(
            bitmapBuffer,
            0,
            0,
            bitmapBuffer.width,
            bitmapBuffer.height,
            matrix,
            true
        )

        // Convert the input Bitmap object to an MPImage object to run inference
        val mpImage = BitmapImageBuilder(rotatedBitmap).build()

        recognizeAsync(mpImage, frameTime)
    }

    // Run hand gesture recognition and face landmarker using MediaPipe Gesture Recognition API
    @VisibleForTesting
    fun recognizeAsync(mpImage: MPImage, frameTime: Long) {
        // As we're using running mode LIVE_STREAM, the recognition result will
        // be returned in returnLivestreamResult function
        faceLandmarker?.detectAsync(mpImage, frameTime)
        gestureRecognizer?.recognizeAsync(mpImage, frameTime)
    }

    // Return the landmark result to this helper's caller
    private fun returnFaceLivestreamResult(
        result: FaceLandmarkerResult,
        input: MPImage
    ) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()

        listener?.onFaceLandmarkerResults(
            FaceResultBundle(
                result,
                inferenceTime,
                input.height,
                input.width
            )
        )
    }

    // Return the recognition result to the helper's caller
    private fun returnGestureLivestreamResult(
        result: GestureRecognizerResult, input: MPImage
    ) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()

        listener?.onGestureResults(
            GestureResultBundle(
                listOf(result), inferenceTime, input.height, input.width
            )
        )
    }

    // Return errors thrown during recognition to this Helper's caller
    private fun returnLivestreamError(error: RuntimeException) {
        listener?.onGestureError(
            error.message ?: "An unknown error has occurred"
        )
    }

    companion object {
        val TAG = "HolisticRecognizerHelper ${this.hashCode()}"

        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1

        private const val MP_FACE_LANDMARKER_TASK = "face_landmarker.task"
        const val FACES_COUNT = 2
        const val DEFAULT_FACE_DETECTION_CONFIDENCE = 0.5F
        const val DEFAULT_FACE_TRACKING_CONFIDENCE = 0.5F
        const val DEFAULT_FACE_PRESENCE_CONFIDENCE = 0.5F
        const val DEFAULT_FACE_SHAPE_SCORE_THRESHOLD = 0.2F

        private const val MP_RECOGNIZER_TASK = "gesture_recognizer.task"
        const val HANDS_COUNT = FACES_COUNT * 2
        const val DEFAULT_HAND_DETECTION_CONFIDENCE = 0.5F
        const val DEFAULT_HAND_TRACKING_CONFIDENCE = 0.5F
        const val DEFAULT_HAND_PRESENCE_CONFIDENCE = 0.5F


        const val OTHER_ERROR = 0
        const val GPU_ERROR = 1
    }

    interface Listener {
        fun onGestureResults(resultBundle: GestureResultBundle)
        fun onGestureError(error: String, errorCode: Int = OTHER_ERROR)

        fun onFaceLandmarkerResults(resultBundle: FaceResultBundle)
        fun onFaceLandmarkerError(error: String, errorCode: Int = OTHER_ERROR)
    }
}

data class FaceResultBundle(
    val result: FaceLandmarkerResult,
    val inferenceTime: Long,
    val inputImageHeight: Int,
    val inputImageWidth: Int,
)

data class GestureResultBundle(
    val results: List<GestureRecognizerResult>,
    val inferenceTime: Long,
    val inputImageHeight: Int,
    val inputImageWidth: Int,
)
