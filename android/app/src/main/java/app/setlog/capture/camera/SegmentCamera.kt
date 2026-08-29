package app.setlog.capture.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

class SegmentCamera(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onCameraReady(usingFrontCamera: Boolean)
        fun onCameraError(message: String, cause: Throwable? = null)
        fun onSegmentStarted()
        fun onSegmentStatus(durationMs: Long)
        fun onSegmentFinalized(durationMs: Long, errorCode: Int?, message: String?)
    }

    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var usingFrontCamera = false
    private var lastDurationMs = 0L

    fun attach(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        if (this.previewView === previewView && this.lifecycleOwner === lifecycleOwner && provider != null) {
            return
        }
        this.previewView = previewView
        this.lifecycleOwner = lifecycleOwner
        acquireProvider()
    }

    fun detachPreview(previewView: PreviewView) {
        if (this.previewView === previewView) {
            this.previewView = null
            preview?.setSurfaceProvider(null)
        }
    }

    private fun acquireProvider() {
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess {
                        provider = it
                        createUseCases()
                        bind()
                    }
                    .onFailure { error ->
                        listener.onCameraError("Unable to initialize CameraX.", error)
                    }
            },
            mainExecutor,
        )
    }

    private fun createUseCases() {
        preview = Preview.Builder()
            .build()
            .also { previewUseCase ->
                previewView?.surfaceProvider?.let(previewUseCase::setSurfaceProvider)
            }

        val preferred = listOf(Quality.FHD, Quality.HD, Quality.SD)
        val qualitySelector = QualitySelector.fromOrderedList(
            preferred,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD),
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()

        videoCapture = VideoCapture.Builder(recorder).build()
    }

    private fun bind() {
        val currentProvider = provider ?: return
        val owner = lifecycleOwner ?: return
        val previewUseCase = preview ?: return
        val videoUseCase = videoCapture ?: return

        val selector = if (usingFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        runCatching {
            currentProvider.unbindAll()
            currentProvider.bindToLifecycle(owner, selector, previewUseCase, videoUseCase)
        }.onSuccess {
            listener.onCameraReady(usingFrontCamera)
        }.onFailure { error ->
            if (!usingFrontCamera && currentProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                usingFrontCamera = true
                bind()
            } else {
                listener.onCameraError("Unable to bind a camera on this device.", error)
            }
        }
    }

    fun switchCamera() {
        if (activeRecording != null) {
            return
        }
        val currentProvider = provider ?: return
        val target = if (usingFrontCamera) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        if (!currentProvider.hasCamera(target)) {
            return
        }
        usingFrontCamera = !usingFrontCamera
        bind()
    }

    fun isRecording(): Boolean = activeRecording != null

    @SuppressLint("MissingPermission")
    fun startSegment(outputFile: File, enableAudio: Boolean) {
        if (activeRecording != null) {
            return
        }
        val capture = videoCapture ?: run {
            listener.onCameraError("The camera is not ready.")
            return
        }
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) {
            outputFile.delete()
        }
        lastDurationMs = 0L

        val pending = capture.output
            .prepareRecording(appContext, FileOutputOptions.Builder(outputFile).build())
            .let { recording ->
                val hasAudioPermission = ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (enableAudio && hasAudioPermission) recording.withAudioEnabled() else recording
            }

        runCatching {
            pending.start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> listener.onSegmentStarted()
                    is VideoRecordEvent.Status -> {
                        lastDurationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                        listener.onSegmentStatus(lastDurationMs)
                    }
                    is VideoRecordEvent.Finalize -> {
                        lastDurationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                        activeRecording = null
                        val errorCode = if (event.hasError()) event.error else null
                        val message = event.cause?.localizedMessage
                            ?: if (event.hasError()) "CameraX finalize error ${event.error}" else null
                        listener.onSegmentFinalized(lastDurationMs, errorCode, message)
                    }
                    else -> Unit
                }
            }
        }.onSuccess { activeRecording = it }
            .onFailure { error ->
                activeRecording = null
                listener.onSegmentFinalized(
                    durationMs = 0L,
                    errorCode = -1,
                    message = error.localizedMessage ?: "Recording could not start.",
                )
            }
    }

    fun stopSegment() {
        runCatching { activeRecording?.stop() }
    }

    fun close() {
        runCatching { activeRecording?.stop() }
        activeRecording = null
        provider?.unbindAll()
        provider = null
        lifecycleOwner = null
        previewView = null
    }
}
