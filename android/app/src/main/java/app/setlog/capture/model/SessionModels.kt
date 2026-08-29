package app.setlog.capture.model

import java.util.UUID

enum class MainScreen {
    CAMERA,
    GALLERY,
}

enum class SessionStatus {
    DRAFT,
    EXPORTING,
    READY,
    FAILED,
}

enum class CaptureState {
    PREPARING,
    READY,
    RECORDING,
    FINALIZING_SEGMENT,
    EXPORTING,
    ERROR,
}

data class SegmentRecord(
    val id: String,
    val fileName: String,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val ordinal: Int,
)

data class CaptureMarker(
    val id: String,
    val pressedAtEpochMs: Long,
    val timelineOffsetMs: Long,
    val segmentId: String,
    val ordinal: Int,
)

data class VideoSession(
    val id: String,
    val title: String,
    val caption: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val status: SessionStatus,
    val segments: List<SegmentRecord>,
    val markers: List<CaptureMarker>,
    val outputFileName: String?,
    val totalDurationMs: Long,
    val errorMessage: String?,
    val timestampOverlay: TimestampOverlaySettings = TimestampOverlaySettings(),
    val imported: Boolean = false,
) {
    val isDraft: Boolean
        get() = status == SessionStatus.DRAFT || status == SessionStatus.FAILED

    companion object {
        fun newDraft(
            nowEpochMs: Long,
            title: String,
            timestampOverlay: TimestampOverlaySettings,
        ): VideoSession = VideoSession(
            id = UUID.randomUUID().toString(),
            title = title,
            caption = "",
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
            status = SessionStatus.DRAFT,
            segments = emptyList(),
            markers = emptyList(),
            outputFileName = null,
            totalDurationMs = 0L,
            errorMessage = null,
            timestampOverlay = timestampOverlay.sanitized(),
            imported = false,
        )
    }
}

data class PendingSegment(
    val sessionId: String,
    val segmentId: String,
    val partialFileName: String,
    val finalFileName: String,
    val startedAtEpochMs: Long,
    val timelineOffsetMs: Long,
    val ordinal: Int,
    val createsMarker: Boolean = true,
)

data class SetLogUiState(
    val screen: MainScreen = MainScreen.CAMERA,
    val captureState: CaptureState = CaptureState.PREPARING,
    val cameraReady: Boolean = false,
    val cameraPermissionGranted: Boolean = false,
    val microphonePermissionGranted: Boolean = false,
    val activeSession: VideoSession? = null,
    val sessions: List<VideoSession> = emptyList(),
    val currentSegmentDurationMs: Long = 0L,
    val volumeUpHeld: Boolean = false,
    val selectedSessionId: String? = null,
    val showFirstGuide: Boolean = false,
    val errorMessage: String? = null,
    val usingFrontCamera: Boolean = false,
    val cameraSwitchInProgress: Boolean = false,
    val importInProgress: Boolean = false,
    val inputSettings: InputSettings = InputSettings(),
    val timestampSettings: TimestampOverlaySettings = TimestampOverlaySettings(),
) {
    val displayedDurationMs: Long
        get() = (activeSession?.totalDurationMs ?: 0L) + currentSegmentDurationMs

    val isRecording: Boolean
        get() = captureState == CaptureState.RECORDING && volumeUpHeld

    val selectedSession: VideoSession?
        get() = sessions.firstOrNull { it.id == selectedSessionId }
}
