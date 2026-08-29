package app.setlog.capture

import android.app.Application
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import app.setlog.capture.camera.SegmentCamera
import app.setlog.capture.data.SessionRepository
import app.setlog.capture.export.SessionExporter
import app.setlog.capture.model.CaptureState
import app.setlog.capture.model.InputSettings
import app.setlog.capture.model.MainScreen
import app.setlog.capture.model.PendingSegment
import app.setlog.capture.model.SessionStatus
import app.setlog.capture.model.SetLogUiState
import app.setlog.capture.model.TimestampOverlaySettings
import app.setlog.capture.model.VideoSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class SetLogViewModel(application: Application) : AndroidViewModel(application) {
    private data class QueuedSegmentStart(
        val pressedAtEpochMs: Long,
        val createsMarker: Boolean,
    )

    private val repository = SessionRepository(application)
    private val mutableState = MutableStateFlow(loadInitialState())
    val state: StateFlow<SetLogUiState> = mutableState.asStateFlow()

    private var cameraPermissionGranted = false
    private var microphonePermissionGranted = false
    private var pendingSegment: PendingSegment? = null
    private var queuedSegmentStart: QueuedSegmentStart? = null
    private var finishRequested = false
    private var navigationToGalleryRequested = false
    private var switchAfterSegmentFinalizes = false
    private var resumeAfterCameraSwitch = false

    private val camera = SegmentCamera(
        context = application,
        listener = object : SegmentCamera.Listener {
            override fun onCameraReady(usingFrontCamera: Boolean) {
                val shouldResume = resumeAfterCameraSwitch && mutableState.value.volumeUpHeld
                resumeAfterCameraSwitch = false
                update {
                    it.copy(
                        cameraReady = true,
                        usingFrontCamera = usingFrontCamera,
                        cameraSwitchInProgress = false,
                        captureState = idleCaptureState(it.activeSession, cameraReady = true),
                        errorMessage = null,
                    )
                }
                if (shouldResume && queuedSegmentStart == null) {
                    queuedSegmentStart = QueuedSegmentStart(
                        pressedAtEpochMs = System.currentTimeMillis(),
                        createsMarker = false,
                    )
                }
                maybeStartQueuedSegment()
            }

            override fun onCameraError(message: String, cause: Throwable?) {
                resumeAfterCameraSwitch = false
                switchAfterSegmentFinalizes = false
                update {
                    it.copy(
                        cameraReady = false,
                        cameraSwitchInProgress = false,
                        captureState = CaptureState.ERROR,
                        errorMessage = message,
                    )
                }
            }

            override fun onSegmentStarted() {
                val snapshot = mutableState.value
                if (!snapshot.volumeUpHeld || finishRequested || navigationToGalleryRequested ||
                    snapshot.cameraSwitchInProgress
                ) {
                    update { it.copy(captureState = CaptureState.FINALIZING_SEGMENT) }
                    camera.stopSegment()
                    return
                }
                update {
                    it.copy(
                        captureState = CaptureState.RECORDING,
                        currentSegmentDurationMs = 0L,
                        errorMessage = null,
                    )
                }
            }

            override fun onSegmentStatus(durationMs: Long) {
                update { it.copy(currentSegmentDurationMs = durationMs.coerceAtLeast(0L)) }
            }

            override fun onSegmentFinalized(
                durationMs: Long,
                errorCode: Int?,
                message: String?,
            ) {
                handleSegmentFinalized(durationMs, errorCode, message)
            }
        },
    )

    private val exporter = SessionExporter(application, repository)

    fun setPermissions(cameraGranted: Boolean, microphoneGranted: Boolean) {
        cameraPermissionGranted = cameraGranted
        microphonePermissionGranted = microphoneGranted
        update {
            it.copy(
                cameraPermissionGranted = cameraGranted,
                microphonePermissionGranted = microphoneGranted,
                cameraReady = if (cameraGranted) it.cameraReady else false,
                captureState = if (cameraGranted) it.captureState else CaptureState.PREPARING,
            )
        }
    }

    fun attachPreview(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        if (cameraPermissionGranted) {
            camera.attach(previewView, lifecycleOwner)
        }
    }

    fun detachPreview(previewView: PreviewView) {
        camera.detachPreview(previewView)
    }

    fun inputSettingsSnapshot(): InputSettings = mutableState.value.inputSettings

    fun saveInputSettings(settings: InputSettings) {
        repository.saveInputSettings(settings)
        update { it.copy(inputSettings = settings) }
    }

    fun onRecordHoldStarted(pressedAtEpochMs: Long) {
        if (mutableState.value.captureState == CaptureState.EXPORTING) return
        queuedSegmentStart = QueuedSegmentStart(pressedAtEpochMs, createsMarker = true)
        navigationToGalleryRequested = false
        update {
            it.copy(
                screen = MainScreen.CAMERA,
                volumeUpHeld = true,
                selectedSessionId = null,
                errorMessage = null,
            )
        }
        maybeStartQueuedSegment()
    }

    fun onRecordHoldEnded() {
        queuedSegmentStart = null
        resumeAfterCameraSwitch = false
        update { it.copy(volumeUpHeld = false) }
        if (camera.isRecording()) {
            update { it.copy(captureState = CaptureState.FINALIZING_SEGMENT) }
            camera.stopSegment()
        } else if (pendingSegment == null && !finishRequested && !itIsSwitching()) {
            update { it.copy(captureState = idleCaptureState(it.activeSession)) }
        }
    }

    fun finishCurrentSession() {
        queuedSegmentStart = null
        resumeAfterCameraSwitch = false
        switchAfterSegmentFinalizes = false
        finishRequested = true
        navigationToGalleryRequested = true
        update {
            it.copy(
                volumeUpHeld = false,
                screen = MainScreen.GALLERY,
                selectedSessionId = it.activeSession?.id,
                cameraSwitchInProgress = false,
            )
        }
        if (camera.isRecording()) {
            update { it.copy(captureState = CaptureState.FINALIZING_SEGMENT) }
            camera.stopSegment()
        } else if (pendingSegment == null) {
            exportActiveSession()
        }
    }

    fun openGalleryWithoutFinishing() {
        queuedSegmentStart = null
        resumeAfterCameraSwitch = false
        finishRequested = false
        navigationToGalleryRequested = true
        update {
            it.copy(
                volumeUpHeld = false,
                screen = MainScreen.GALLERY,
                selectedSessionId = null,
            )
        }
        if (camera.isRecording()) {
            update { it.copy(captureState = CaptureState.FINALIZING_SEGMENT) }
            camera.stopSegment()
        }
    }

    fun openGalleryFromUi() = openGalleryWithoutFinishing()

    fun openCamera() {
        navigationToGalleryRequested = false
        update {
            it.copy(
                screen = MainScreen.CAMERA,
                selectedSessionId = null,
                captureState = if (it.captureState == CaptureState.EXPORTING) {
                    it.captureState
                } else {
                    idleCaptureState(it.activeSession)
                },
            )
        }
    }

    fun resumeSession(sessionId: String) {
        val session = repository.readSession(sessionId) ?: return
        val draft = if (session.status == SessionStatus.FAILED) {
            repository.markDraftResumable(sessionId)
        } else {
            session
        }
        if (!draft.isDraft) return
        refresh(activeOverride = draft)
        openCamera()
    }

    fun retryExport(sessionId: String) {
        val session = repository.readSession(sessionId) ?: return
        val draft = if (session.status == SessionStatus.FAILED) {
            repository.markDraftResumable(sessionId)
        } else {
            session
        }
        refresh(activeOverride = draft)
        finishRequested = true
        navigationToGalleryRequested = true
        update {
            it.copy(
                screen = MainScreen.GALLERY,
                selectedSessionId = sessionId,
            )
        }
        exportActiveSession()
    }

    fun selectSession(sessionId: String?) {
        update { it.copy(selectedSessionId = sessionId) }
    }

    fun updateSessionDetails(sessionId: String, title: String, caption: String) {
        repository.updateDetails(sessionId, title, caption)
        refresh()
    }

    fun saveActiveTimestampSettings(settings: TimestampOverlaySettings) {
        val safe = settings.sanitized()
        repository.saveTimestampSettings(safe)
        val active = repository.getActiveDraft()?.let { draft ->
            repository.updateTimestampOverlay(draft.id, safe)
        }
        refresh(activeOverride = active)
        update { it.copy(timestampSettings = safe) }
    }

    fun updateSessionAndRebuild(
        sessionId: String,
        title: String,
        caption: String,
        timestampSettings: TimestampOverlaySettings,
    ) {
        repository.updateDetails(sessionId, title, caption)
        val updated = repository.updateTimestampOverlay(sessionId, timestampSettings)
        repository.saveTimestampSettings(timestampSettings)
        update { it.copy(timestampSettings = timestampSettings.sanitized()) }
        if (updated.status == SessionStatus.READY) {
            rebuildReadySession(updated.id)
        } else {
            refresh()
        }
    }

    fun importVideo(uri: Uri) {
        if (mutableState.value.importInProgress || exporter.isExporting()) return
        viewModelScope.launch {
            update { it.copy(importInProgress = true, errorMessage = null) }
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.importVideo(uri) }
            }
            result.onSuccess { imported ->
                refresh()
                update {
                    it.copy(
                        screen = MainScreen.GALLERY,
                        selectedSessionId = imported.id,
                        importInProgress = false,
                    )
                }
            }.onFailure { error ->
                update {
                    it.copy(
                        importInProgress = false,
                        errorMessage = error.localizedMessage
                            ?: getApplication<Application>().getString(R.string.import_failed),
                    )
                }
            }
        }
    }

    fun deleteSession(sessionId: String) {
        repository.deleteSession(sessionId)
        val active = repository.getActiveDraft()
        refresh(activeOverride = active)
        update {
            it.copy(
                selectedSessionId = if (it.selectedSessionId == sessionId) null else it.selectedSessionId,
                captureState = idleCaptureState(active),
            )
        }
    }

    /**
     * Switching during a hold safely closes the current clip, rebinds the opposite lens and starts
     * a continuation clip while the hardware key remains down. The continuation does not create a
     * second press marker.
     */
    fun switchCamera() {
        val snapshot = mutableState.value
        if (snapshot.captureState == CaptureState.EXPORTING || snapshot.cameraSwitchInProgress) return

        update { it.copy(cameraSwitchInProgress = true, errorMessage = null) }
        if (camera.isRecording() || pendingSegment != null) {
            switchAfterSegmentFinalizes = true
            resumeAfterCameraSwitch = snapshot.volumeUpHeld
            queuedSegmentStart = null
            update { it.copy(captureState = CaptureState.FINALIZING_SEGMENT) }
            camera.stopSegment()
        } else {
            performCameraSwitch()
        }
    }

    fun dismissFirstGuide() {
        repository.markFirstGuideSeen()
        update { it.copy(showFirstGuide = false) }
    }

    fun clearError() {
        update { it.copy(errorMessage = null) }
    }

    fun sessionOutputFile(session: VideoSession): File? = repository.outputFile(session)

    fun sessionPreviewFile(session: VideoSession): File? =
        repository.outputFile(session)
            ?.takeIf { it.exists() }
            ?: session.segments.firstOrNull()?.let { repository.segmentFile(session.id, it) }

    fun onAppBackgrounded() {
        queuedSegmentStart = null
        resumeAfterCameraSwitch = false
        update { it.copy(volumeUpHeld = false) }
        if (camera.isRecording()) {
            update { it.copy(captureState = CaptureState.FINALIZING_SEGMENT) }
            camera.stopSegment()
        }
    }

    private fun performCameraSwitch() {
        update {
            it.copy(
                cameraReady = false,
                cameraSwitchInProgress = true,
                captureState = CaptureState.PREPARING,
            )
        }
        if (!camera.switchCamera()) {
            resumeAfterCameraSwitch = false
            update {
                it.copy(
                    cameraReady = true,
                    cameraSwitchInProgress = false,
                    captureState = idleCaptureState(it.activeSession),
                    errorMessage = getApplication<Application>().getString(R.string.camera_switch_unavailable),
                )
            }
        }
    }

    private fun maybeStartQueuedSegment() {
        val request = queuedSegmentStart ?: return
        val snapshot = mutableState.value
        if (!snapshot.volumeUpHeld || !cameraPermissionGranted || !snapshot.cameraReady ||
            snapshot.cameraSwitchInProgress
        ) {
            return
        }
        if (pendingSegment != null || camera.isRecording() || exporter.isExporting()) return

        val session = repository.getOrCreateDraft(
            nowEpochMs = request.pressedAtEpochMs,
            timestampOverlay = snapshot.timestampSettings,
        )
        val pending = runCatching {
            repository.createPendingSegment(
                sessionId = session.id,
                pressedAtEpochMs = request.pressedAtEpochMs,
                createsMarker = request.createsMarker,
            )
        }.getOrElse { error ->
            queuedSegmentStart = null
            update {
                it.copy(
                    captureState = CaptureState.ERROR,
                    errorMessage = error.localizedMessage ?: getApplication<Application>()
                        .getString(R.string.recording_start_failed),
                )
            }
            return
        }

        pendingSegment = pending
        queuedSegmentStart = null
        update {
            it.copy(
                activeSession = session,
                sessions = repository.loadAll(),
                currentSegmentDurationMs = 0L,
                captureState = CaptureState.PREPARING,
            )
        }
        camera.startSegment(
            outputFile = repository.partialFile(pending),
            enableAudio = microphonePermissionGranted,
        )
    }

    private fun handleSegmentFinalized(
        durationMs: Long,
        errorCode: Int?,
        message: String?,
    ) {
        val pending = pendingSegment
        pendingSegment = null

        var active = mutableState.value.activeSession
        var userFacingError: String? = null
        if (pending != null) {
            if (errorCode == null && durationMs >= MINIMUM_SEGMENT_MS) {
                active = runCatching {
                    repository.commitSegment(pending, durationMs)
                }.getOrElse { error ->
                    repository.discardPending(pending)
                    userFacingError = error.localizedMessage
                        ?: getApplication<Application>().getString(R.string.recording_start_failed)
                    repository.readSession(pending.sessionId)
                }
            } else {
                repository.discardPending(pending)
                if (durationMs >= MINIMUM_SEGMENT_MS || errorCode != null) {
                    userFacingError = message
                        ?: getApplication<Application>().getString(R.string.recording_start_failed)
                }
                active = repository.readSession(pending.sessionId)
            }
        }

        update {
            it.copy(
                activeSession = active,
                sessions = repository.loadAll(),
                currentSegmentDurationMs = 0L,
                captureState = idleCaptureState(active),
                errorMessage = userFacingError,
            )
        }

        when {
            finishRequested -> exportActiveSession()
            switchAfterSegmentFinalizes -> {
                switchAfterSegmentFinalizes = false
                performCameraSwitch()
            }
            mutableState.value.volumeUpHeld -> {
                queuedSegmentStart = QueuedSegmentStart(
                    pressedAtEpochMs = System.currentTimeMillis(),
                    createsMarker = false,
                )
                maybeStartQueuedSegment()
            }
            navigationToGalleryRequested -> update { it.copy(screen = MainScreen.GALLERY) }
        }
    }

    private fun exportActiveSession() {
        if (exporter.isExporting()) return
        val current = mutableState.value.activeSession
            ?: repository.getActiveDraft()
            ?: run {
                finishRequested = false
                update {
                    it.copy(
                        captureState = idleCaptureState(null),
                        errorMessage = getApplication<Application>().getString(R.string.nothing_to_finish),
                    )
                }
                return
            }
        val latest = repository.readSession(current.id) ?: current
        if (latest.segments.isEmpty()) {
            finishRequested = false
            update {
                it.copy(
                    activeSession = latest,
                    captureState = idleCaptureState(latest),
                    errorMessage = getApplication<Application>().getString(R.string.nothing_to_finish),
                )
            }
            return
        }

        val outputName = "vibe-${latest.id}.mp4"
        val exportingSession = repository.markExporting(latest.id, outputName)
        update {
            it.copy(
                screen = MainScreen.GALLERY,
                activeSession = exportingSession,
                sessions = repository.loadAll(),
                selectedSessionId = exportingSession.id,
                captureState = CaptureState.EXPORTING,
                currentSegmentDurationMs = 0L,
                errorMessage = null,
            )
        }

        exporter.export(
            session = exportingSession,
            outputFile = repository.outputFile(exportingSession.id, outputName),
            callback = object : SessionExporter.Callback {
                override fun onExportCompleted(sessionId: String, outputFile: File) {
                    val ready = runCatching { repository.markReady(sessionId) }
                        .getOrElse { error ->
                            repository.markExportFailed(
                                sessionId,
                                error.localizedMessage ?: "Finished file validation failed.",
                            )
                            null
                        }
                    finishRequested = false
                    navigationToGalleryRequested = true
                    val active = repository.getActiveDraft()
                    refresh(activeOverride = active)
                    update {
                        it.copy(
                            screen = MainScreen.GALLERY,
                            activeSession = active,
                            selectedSessionId = ready?.id ?: sessionId,
                            captureState = idleCaptureState(active),
                            errorMessage = if (ready == null) {
                                getApplication<Application>().getString(R.string.export_failed)
                            } else {
                                null
                            },
                        )
                    }
                }

                override fun onExportFailed(sessionId: String, message: String) {
                    repository.markExportFailed(sessionId, message)
                    finishRequested = false
                    navigationToGalleryRequested = true
                    val active = repository.getActiveDraft()
                    refresh(activeOverride = active)
                    update {
                        it.copy(
                            screen = MainScreen.GALLERY,
                            activeSession = active,
                            selectedSessionId = sessionId,
                            captureState = idleCaptureState(active),
                            errorMessage = getApplication<Application>().getString(R.string.export_failed),
                        )
                    }
                }
            },
        )
    }

    private fun rebuildReadySession(sessionId: String) {
        if (exporter.isExporting()) return
        val session = repository.readSession(sessionId) ?: return
        if (session.status != SessionStatus.READY || session.segments.isEmpty()) {
            refresh()
            return
        }
        val temporary = repository.outputFile(sessionId, "vibe-${session.id}.rebuild.mp4")
        temporary.delete()
        update {
            it.copy(
                captureState = CaptureState.EXPORTING,
                sessions = repository.loadAll(),
                selectedSessionId = session.id,
                errorMessage = null,
            )
        }
        exporter.export(
            session = session,
            outputFile = temporary,
            callback = object : SessionExporter.Callback {
                override fun onExportCompleted(sessionId: String, outputFile: File) {
                    val result = runCatching {
                        repository.installRebuiltOutput(
                            sessionId = sessionId,
                            temporaryFile = outputFile,
                            outputFileName = "vibe-$sessionId.mp4",
                        )
                    }
                    refresh()
                    update {
                        it.copy(
                            screen = MainScreen.GALLERY,
                            selectedSessionId = sessionId,
                            captureState = idleCaptureState(it.activeSession),
                            errorMessage = result.exceptionOrNull()?.localizedMessage,
                        )
                    }
                }

                override fun onExportFailed(sessionId: String, message: String) {
                    temporary.delete()
                    refresh()
                    update {
                        it.copy(
                            screen = MainScreen.GALLERY,
                            selectedSessionId = sessionId,
                            captureState = idleCaptureState(it.activeSession),
                            errorMessage = message,
                        )
                    }
                }
            },
        )
    }

    private fun refresh(activeOverride: VideoSession? = repository.getActiveDraft()) {
        update {
            it.copy(
                activeSession = activeOverride,
                sessions = repository.loadAll(),
            )
        }
    }

    private fun loadInitialState(): SetLogUiState {
        val active = repository.getActiveDraft()
        return SetLogUiState(
            screen = MainScreen.CAMERA,
            captureState = CaptureState.PREPARING,
            activeSession = active,
            sessions = repository.loadAll(),
            showFirstGuide = !repository.firstGuideSeen(),
            inputSettings = repository.loadInputSettings(),
            timestampSettings = active?.timestampOverlay ?: repository.loadTimestampSettings(),
        )
    }

    private fun idleCaptureState(
        active: VideoSession?,
        cameraReady: Boolean = mutableState.value.cameraReady,
    ): CaptureState = when {
        active?.status == SessionStatus.EXPORTING -> CaptureState.EXPORTING
        cameraReady -> CaptureState.READY
        else -> CaptureState.PREPARING
    }

    private fun itIsSwitching(): Boolean = mutableState.value.cameraSwitchInProgress

    private inline fun update(transform: (SetLogUiState) -> SetLogUiState) {
        mutableState.value = transform(mutableState.value)
    }

    override fun onCleared() {
        camera.close()
        exporter.cancel()
        super.onCleared()
    }

    companion object {
        private const val MINIMUM_SEGMENT_MS = 120L
    }
}
