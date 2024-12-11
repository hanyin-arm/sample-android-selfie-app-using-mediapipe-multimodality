package com.example.holisticselfiedemo

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.holisticselfiedemo.HolisticRecognizerHelper.Companion.FACES_COUNT
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.jvm.optionals.getOrNull

class MainViewModel : ViewModel(), HolisticRecognizerHelper.Listener {

    private val holisticRecognizerHelper = HolisticRecognizerHelper()

    private val _uiEvents = MutableSharedFlow<UiEvent>(1)
    val uiEvents: SharedFlow<UiEvent> = _uiEvents

    private val _faceOk = MutableStateFlow(false)
    val faceOk: StateFlow<Boolean> = _faceOk

    private val _gestureOk = MutableStateFlow(false)
    val gestureOk: StateFlow<Boolean> = _gestureOk

    @OptIn(FlowPreview::class)
    private val _bothOk =
        combine(
            _gestureOk.sample(CONDITION_CHECK_SAMPLING_INTERVAL),
            _faceOk.sample(CONDITION_CHECK_SAMPLING_INTERVAL),
        ) { gestureOk, faceOk -> gestureOk && faceOk }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    private val _isCameraReady = MutableStateFlow(true)

    @OptIn(FlowPreview::class)
    val captureEvents: SharedFlow<Unit> =
        combine(_bothOk, _isCameraReady) { bothOk, cameraReady -> bothOk to cameraReady}
            .debounce(CONDITION_CHECK_STABILITY_THRESHOLD)
            .filter { (bothOK, cameraReady) -> bothOK && cameraReady }
            .onEach { _isCameraReady.emit(false) }
            .map {}
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed())

    fun setupHelper(context: Context) {
        viewModelScope.launch {
            holisticRecognizerHelper.apply {
                listener = this@MainViewModel
                if (isClosed)
                    setup(context)
            }
        }
    }

    fun shutdownHelper() {
        viewModelScope.launch {
            holisticRecognizerHelper.apply {
                listener = null
                if (!isClosed)
                    shutdown()
            }
        }
    }

    fun recognizeLiveStream(imageProxy: ImageProxy) {
        holisticRecognizerHelper.recognizeLiveStream(
            imageProxy = imageProxy,
        )
    }

    override fun onFaceLandmarkerError(error: String, errorCode: Int) {
        Log.e(TAG, "Face landmarker error $errorCode: $error")
    }

    override fun onFaceLandmarkerResults(resultBundle: FaceResultBundle) {
        val faceOk = resultBundle.result.faceBlendshapes().getOrNull()?.let { faceBlendShapes ->
            faceBlendShapes.take(FACES_COUNT).all { shapes ->
                shapes.filter {
                    it.categoryName().contains(FACE_CATEGORY_MOUTH_SMILE)
                }.all {
                    it.score() > HolisticRecognizerHelper.DEFAULT_FACE_SHAPE_SCORE_THRESHOLD
                }
            }
        } ?: false

        _faceOk.tryEmit(faceOk)
        _uiEvents.tryEmit(UiEvent.Face(resultBundle))
    }

    override fun onGestureError(error: String, errorCode: Int) {
        Log.e(TAG, "Gesture recognizer error $errorCode: $error")
    }

    override fun onGestureResults(resultBundle: GestureResultBundle) {
        val gestureOk = resultBundle.results.first().gestures()
            .take(HolisticRecognizerHelper.HANDS_COUNT)
            .let { gestures ->
                gestures.isNotEmpty() && gestures
                    .mapNotNull { it.firstOrNull() }
                    .all { GESTURE_CATEGORY_THUMB_UP == it.categoryName() }
            }

        _gestureOk.tryEmit(gestureOk)
        _uiEvents.tryEmit(UiEvent.Gesture(resultBundle))
    }

    fun onPhotoCaptureComplete() {
        viewModelScope.launch {
            delay(IMAGE_CAPTURE_DEFAULT_COUNTDOWN)
            _isCameraReady.emit(true)
        }
    }

    sealed class UiEvent {
        data class Face(
            val face: FaceResultBundle
        ) : UiEvent()

        data class Gesture(
            val gestures: GestureResultBundle,
        ) : UiEvent()
    }

    companion object {
        private const val TAG = "MainViewModel"

        private const val FACE_CATEGORY_MOUTH_SMILE = "mouthSmile"
        private const val GESTURE_CATEGORY_THUMB_UP = "Thumb_Up"

        private const val CONDITION_CHECK_SAMPLING_INTERVAL = 100L
        private const val CONDITION_CHECK_STABILITY_THRESHOLD = 500L

        private const val IMAGE_CAPTURE_DEFAULT_COUNTDOWN = 3000L
    }
}
