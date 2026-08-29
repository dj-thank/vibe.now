import AVFoundation
import Foundation
import SwiftUI
import UIKit

struct SharePayload: Identifiable {
    let id = UUID()
    let items: [Any]
}

@MainActor
final class CameraViewModel: ObservableObject {
    enum PermissionState: Equatable {
        case checking
        case ready
        case cameraDenied
        case unavailable(String)
    }

    private struct QueuedSegmentStart {
        let pressedAt: Date
        let createsMarker: Bool
    }

    @Published var screen: AppScreen = .camera
    @Published private(set) var permissionState: PermissionState = .checking
    @Published private(set) var capturePhase: CapturePhase = .preparing
    @Published private(set) var sessions: [SetLogSession] = []
    @Published private(set) var activeSession: SetLogSession?
    @Published var selectedSession: SetLogSession?
    @Published var sharePayload: SharePayload?
    @Published var alertMessage: String?
    @Published var guidePresented = false
    @Published private(set) var cameraPosition: AVCaptureDevice.Position = .back
    @Published private(set) var isSessionRunning = false
    @Published private(set) var currentHoldDuration: Double = 0
    @Published private(set) var zoomFactor: CGFloat = 1
    @Published private(set) var focusPoint: CGPoint?
    @Published private(set) var cameraSwitchInProgress = false
    @Published private(set) var importInProgress = false
    @Published private(set) var rebuildingSessionID: UUID?
    @Published private(set) var inputSettings: InputSettings
    @Published private(set) var timestampSettings: TimestampOverlaySettings

    let engine = SegmentCaptureEngine()

    private let store: SessionStore
    private lazy var exporter = SessionVideoExporter(store: store)
    private let orientationMonitor = DeviceOrientationMonitor()
    private var pendingSegment: PendingSegment?
    private var queuedSegmentStart: QueuedSegmentStart?
    private var pressState = HardwarePressState()
    private var shortcutPressDetector = MultiPressDetector()
    private var shortcutResolutionTask: Task<Void, Never>?
    private var elapsedTask: Task<Void, Never>?
    private var focusClearTask: Task<Void, Never>?
    private var pendingFinalize = false
    private var pendingGallery = false
    private var stopSessionAfterClip = false
    private var switchAfterSegmentFinalizes = false
    private var resumeAfterCameraSwitch = false
    private var configured = false
    private var appIsActive = true
    private var pinchStartZoom: CGFloat = 1

    init(store: SessionStore = SessionStore()) {
        self.store = store
        inputSettings = store.loadInputSettings()
        timestampSettings = store.loadTimestampSettings()
        bindEngine()
        orientationMonitor.onChange = { [weak self] orientation in
            self?.engine.updateRotation(for: orientation)
        }
        refreshSessions()
        activeSession = store.activeDraft()
        if let activeSession {
            timestampSettings = activeSession.effectiveTimestampOverlay
        }
        guidePresented = store.shouldShowGuide
    }

    var captureEventsEnabled: Bool {
        permissionState == .ready
            && screen == .camera
            && appIsActive
            && isSessionRunning
            && capturePhase != .exporting
    }

    var displayedDuration: Double {
        (activeSession?.totalDurationSeconds ?? 0) + currentHoldDuration
    }

    var markerCount: Int {
        (activeSession?.markers.count ?? 0) + ((pendingSegment?.createsMarker == true && capturePhase == .recording) ? 1 : 0)
    }

    var hasDraftContent: Bool {
        activeSession?.hasRecordedContent == true
    }

    var recordControl: CaptureControl { inputSettings.recordControl }
    var shortcutControl: CaptureControl { inputSettings.recordControl.opposite }

    func requestPermissionsAndStart() async {
        permissionState = .checking
        let cameraAllowed = await Self.requestAccess(for: .video)
        guard cameraAllowed else {
            permissionState = .cameraDenied
            capturePhase = .failed(String(localized: "error.camera.permission"))
            return
        }
        let audioAllowed = await Self.requestAccess(for: .audio)
        engine.configure(includeAudio: audioAllowed)
    }

    func appBecameActive() {
        appIsActive = true
        guard permissionState == .ready, screen == .camera else { return }
        engine.startSession()
        orientationMonitor.start()
    }

    func appMovedToBackground() {
        appIsActive = false
        cancelInputTasks()
        pressState.reset()
        shortcutPressDetector.reset()
        queuedSegmentStart = nil
        resumeAfterCameraSwitch = false
        pendingGallery = false
        if capturePhase == .recording || capturePhase == .preparing, pendingSegment != nil {
            stopSessionAfterClip = true
            stopCurrentSegment()
        } else if capturePhase == .savingClip {
            stopSessionAfterClip = true
        } else {
            engine.stopSession()
        }
        orientationMonitor.stop()
    }

    func dismissGuide() {
        guidePresented = false
        store.markGuideSeen()
    }

    // MARK: - Physical capture controls

    func captureControlBegan(_ control: CaptureControl) {
        guard captureEventsEnabled, !pressState.isDown(control) else { return }
        pressState.setDown(true, for: control)

        if control == inputSettings.recordControl {
            queuedSegmentStart = QueuedSegmentStart(pressedAt: Date(), createsMarker: true)
            pendingGallery = false
            maybeStartQueuedSegment()
        }
    }

    func captureControlEnded(_ control: CaptureControl, cancelled: Bool = false) {
        guard pressState.isDown(control) else { return }
        pressState.setDown(false, for: control)

        if control == inputSettings.recordControl {
            queuedSegmentStart = nil
            resumeAfterCameraSwitch = false
            if capturePhase == .recording || (capturePhase == .preparing && pendingSegment != nil) {
                stopCurrentSegment()
            }
            if cancelled {
                shortcutPressDetector.reset()
            }
            return
        }

        guard !cancelled else {
            cancelShortcutResolution()
            shortcutPressDetector.reset()
            return
        }
        registerShortcutPress()
    }

    private func registerShortcutPress() {
        let count = shortcutPressDetector.register(at: ProcessInfo.processInfo.systemUptime)
        shortcutResolutionTask?.cancel()

        if count >= 3 {
            let resolved = shortcutPressDetector.resolve()
            performShortcut(inputSettings.triplePressAction, count: resolved)
            return
        }

        shortcutResolutionTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(CaptureInputTiming.multiPressWindowSeconds))
            guard !Task.isCancelled, let self else { return }
            let resolved = self.shortcutPressDetector.resolve()
            if resolved == 2 {
                self.performShortcut(self.inputSettings.doublePressAction, count: resolved)
            }
        }
    }

    private func performShortcut(_ action: ShortcutAction, count: Int) {
        guard count >= 2 else { return }
        switch action {
        case .finish:
            SetLogHaptics.finished()
            finishCurrentSession()
        case .openGallery:
            SetLogHaptics.selection()
            openGalleryWithoutFinalizing()
        case .none:
            break
        }
    }

    func finishFromScreen() {
        finishCurrentSession()
    }

    func saveInputSettings(_ settings: InputSettings) {
        guard capturePhase != .recording, capturePhase != .preparing, capturePhase != .savingClip else {
            alertMessage = String(localized: "settings.input.busy")
            return
        }
        cancelShortcutResolution()
        pressState.reset()
        inputSettings = settings
        store.saveInputSettings(settings)
    }

    func saveTimestampDefaults(_ settings: TimestampOverlaySettings) {
        let sanitized = settings.sanitized
        timestampSettings = sanitized
        store.saveTimestampSettings(sanitized)
        guard let activeSession else { return }
        do {
            self.activeSession = try store.updateTimestampOverlay(
                sessionID: activeSession.id,
                settings: sanitized
            )
            refreshSessions()
        } catch {
            fail(error)
        }
    }

    // MARK: - Camera operations

    func switchCamera() {
        guard captureEventsEnabled, !cameraSwitchInProgress else { return }
        cameraSwitchInProgress = true
        resumeAfterCameraSwitch = pressState.isDown(inputSettings.recordControl)

        if capturePhase == .recording || (capturePhase == .preparing && pendingSegment != nil) {
            switchAfterSegmentFinalizes = true
            stopCurrentSegment()
        } else if capturePhase == .savingClip {
            switchAfterSegmentFinalizes = true
        } else {
            performCameraSwitch()
        }
    }

    private func performCameraSwitch() {
        capturePhase = .preparing
        engine.switchCamera()
    }

    func focus(at devicePoint: CGPoint, screenPoint: CGPoint) {
        focusPoint = screenPoint
        engine.focus(at: devicePoint)
        focusClearTask?.cancel()
        focusClearTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1.2))
            guard !Task.isCancelled else { return }
            self?.focusPoint = nil
        }
    }

    func handlePinch(scale: CGFloat, state: UIGestureRecognizer.State) {
        switch state {
        case .began:
            pinchStartZoom = zoomFactor
        case .changed:
            let next = min(max(pinchStartZoom * scale, 1), 8)
            zoomFactor = next
            engine.setZoomFactor(next)
        default:
            pinchStartZoom = zoomFactor
        }
    }

    private func maybeStartQueuedSegment() {
        guard let queuedSegmentStart else { return }
        guard pressState.isDown(inputSettings.recordControl) else {
            self.queuedSegmentStart = nil
            return
        }
        guard !cameraSwitchInProgress, pendingSegment == nil else { return }
        guard capturePhase == .ready || isFailure(capturePhase) else { return }
        self.queuedSegmentStart = nil
        beginSegmentIfPossible(
            pressedAt: queuedSegmentStart.pressedAt,
            createsMarker: queuedSegmentStart.createsMarker
        )
    }

    private func beginSegmentIfPossible(pressedAt: Date, createsMarker: Bool) {
        guard screen == .camera, appIsActive, isSessionRunning, !cameraSwitchInProgress else { return }
        switch capturePhase {
        case .ready, .failed:
            do {
                let session = try store.getOrCreateDraft(timestampOverlay: timestampSettings)
                let pending = try store.reserveSegment(
                    sessionID: session.id,
                    pressedAt: pressedAt,
                    createsMarker: createsMarker
                )
                activeSession = session
                pendingSegment = pending
                capturePhase = .preparing
                currentHoldDuration = 0
                engine.startSegment(to: store.partialURL(for: pending))
            } catch {
                fail(error)
            }
        case .savingClip, .preparing, .recording, .exporting:
            break
        }
    }

    private func stopCurrentSegment() {
        guard capturePhase == .recording || capturePhase == .preparing else { return }
        capturePhase = .savingClip
        stopElapsedTimer()
        engine.stopSegment()
    }

    private func finishCurrentSession() {
        guard capturePhase != .exporting else { return }
        guard activeSession?.hasRecordedContent == true || pendingSegment != nil else {
            alertMessage = String(localized: "finish.empty")
            SetLogHaptics.warning()
            return
        }

        pendingFinalize = true
        pendingGallery = false
        queuedSegmentStart = nil
        resumeAfterCameraSwitch = false
        switchAfterSegmentFinalizes = false
        cameraSwitchInProgress = false
        cancelShortcutResolution()
        if capturePhase == .recording || (capturePhase == .preparing && pendingSegment != nil) {
            stopCurrentSegment()
        } else if capturePhase != .savingClip {
            exportActiveSession()
        }
    }

    private func exportActiveSession() {
        guard let sessionID = activeSession?.id else {
            pendingFinalize = false
            return
        }
        do {
            let session = try store.markExporting(sessionID: sessionID)
            activeSession = session
            capturePhase = .exporting
            engine.stopSession()
            isSessionRunning = false
            Task { [weak self] in
                guard let self else { return }
                do {
                    let output = try await self.exporter.export(session)
                    let ready = try self.store.markReady(
                        sessionID: session.id,
                        outputFileName: output.lastPathComponent
                    )
                    self.pendingFinalize = false
                    self.activeSession = nil
                    self.capturePhase = .ready
                    self.refreshSessions()
                    self.selectedSession = ready
                    self.screen = .gallery
                    self.pressState.reset()
                    SetLogHaptics.finished()
                } catch {
                    let failed = try? self.store.markExportFailed(
                        sessionID: session.id,
                        message: error.localizedDescription
                    )
                    self.pendingFinalize = false
                    self.activeSession = failed
                    self.capturePhase = .failed(error.localizedDescription)
                    self.refreshSessions()
                    self.selectedSession = failed
                    self.screen = .gallery
                    self.alertMessage = error.localizedDescription
                    SetLogHaptics.warning()
                }
            }
        } catch {
            pendingFinalize = false
            fail(error)
        }
    }

    // MARK: - Navigation and gallery

    func openGalleryWithoutFinalizing() {
        pendingGallery = true
        pendingFinalize = false
        queuedSegmentStart = nil
        resumeAfterCameraSwitch = false
        switchAfterSegmentFinalizes = false
        cameraSwitchInProgress = false
        if capturePhase == .recording || (capturePhase == .preparing && pendingSegment != nil) {
            stopCurrentSegment()
            return
        }
        if capturePhase == .savingClip {
            return
        }
        completeGalleryNavigation()
    }

    func returnToCamera() {
        selectedSession = nil
        sharePayload = nil
        screen = .camera
        activeSession = store.activeDraft()
        if let activeSession {
            timestampSettings = activeSession.effectiveTimestampOverlay
        } else {
            timestampSettings = store.loadTimestampSettings()
        }
        capturePhase = .ready
        if appIsActive {
            engine.startSession()
            orientationMonitor.start()
        }
    }

    func selectSession(_ session: SetLogSession) {
        selectedSession = session
    }

    func closeSessionDetails() {
        selectedSession = nil
    }

    func resume(_ session: SetLogSession) {
        do {
            activeSession = try store.resume(sessionID: session.id)
            timestampSettings = activeSession?.effectiveTimestampOverlay ?? store.loadTimestampSettings()
            refreshSessions()
            returnToCamera()
        } catch {
            fail(error)
        }
    }

    func saveDetailsAndOverlay(
        sessionID: UUID,
        title: String,
        caption: String,
        timestampOverlay: TimestampOverlaySettings
    ) {
        guard rebuildingSessionID == nil else { return }
        do {
            var updated = try store.updateDetails(sessionID: sessionID, title: title, caption: caption)
            updated = try store.updateTimestampOverlay(
                sessionID: sessionID,
                settings: timestampOverlay
            )
            refreshSessions()
            selectedSession = updated
            if activeSession?.id == updated.id {
                activeSession = updated
                timestampSettings = updated.effectiveTimestampOverlay
            }

            guard updated.status == .ready else { return }
            rebuildReadySession(updated)
        } catch {
            fail(error)
        }
    }

    private func rebuildReadySession(_ session: SetLogSession) {
        rebuildingSessionID = session.id
        Task { [weak self] in
            guard let self else { return }
            let temporaryURL = self.store.temporaryRebuildURL(sessionID: session.id)
            do {
                let output = try await self.exporter.export(session, to: temporaryURL)
                let ready = try self.store.installRebuiltOutput(
                    sessionID: session.id,
                    temporaryURL: output,
                    outputFileName: "vibe-\(session.id.uuidString).mp4"
                )
                self.rebuildingSessionID = nil
                self.refreshSessions()
                self.selectedSession = ready
                SetLogHaptics.selection()
            } catch {
                try? FileManager.default.removeItem(at: temporaryURL)
                self.rebuildingSessionID = nil
                self.alertMessage = error.localizedDescription
                self.refreshSessions()
                SetLogHaptics.warning()
            }
        }
    }

    func importVideo(_ sourceURL: URL) {
        guard !importInProgress else { return }
        importInProgress = true
        Task { [weak self] in
            guard let self else { return }
            let didAccess = sourceURL.startAccessingSecurityScopedResource()
            defer {
                if didAccess {
                    sourceURL.stopAccessingSecurityScopedResource()
                }
            }
            do {
                let asset = AVURLAsset(url: sourceURL)
                let duration = try await asset.load(.duration)
                let seconds = duration.isNumeric ? max(0.001, CMTimeGetSeconds(duration)) : 0.001
                let imported = try self.store.importVideo(
                    from: sourceURL,
                    durationSeconds: seconds
                )
                self.importInProgress = false
                self.refreshSessions()
                self.selectedSession = imported
                SetLogHaptics.selection()
            } catch {
                self.importInProgress = false
                self.alertMessage = error.localizedDescription
                SetLogHaptics.warning()
            }
        }
    }

    func finalize(_ session: SetLogSession) {
        do {
            activeSession = try store.resume(sessionID: session.id)
            pendingFinalize = true
            exportActiveSession()
        } catch {
            fail(error)
        }
    }

    func share(_ session: SetLogSession) {
        do {
            let videoURL = try store.makeShareCopy(for: session)
            let metadataURL = videoURL.deletingLastPathComponent()
                .appending(path: "\(sanitizedFileStem(session.title))-vibenow.json")
            let items: [Any] = FileManager.default.fileExists(atPath: metadataURL.path)
                ? [videoURL, metadataURL]
                : [videoURL]
            sharePayload = SharePayload(items: items)
        } catch {
            fail(error)
        }
    }

    func delete(_ session: SetLogSession) {
        do {
            try store.delete(sessionID: session.id)
            if activeSession?.id == session.id {
                activeSession = nil
            }
            if selectedSession?.id == session.id {
                selectedSession = nil
            }
            refreshSessions()
        } catch {
            fail(error)
        }
    }

    func previewURL(for session: SetLogSession) -> URL? {
        store.previewURL(for: session)
    }

    // MARK: - Engine callbacks

    private func bindEngine() {
        engine.onConfigured = { [weak self] result in
            guard let self else { return }
            switch result {
            case .success:
                self.configured = true
                self.permissionState = .ready
                self.capturePhase = .ready
                self.engine.startSession()
                self.orientationMonitor.start()
            case .failure(let error):
                self.configured = false
                self.permissionState = .unavailable(error.localizedDescription)
                self.capturePhase = .failed(error.localizedDescription)
            }
        }
        engine.onSessionRunningChanged = { [weak self] running in
            guard let self else { return }
            self.isSessionRunning = running
            if running {
                self.maybeStartQueuedSegment()
            }
        }
        engine.onRecordingStarted = { [weak self] _ in
            guard let self else { return }
            if !self.pressState.isDown(self.inputSettings.recordControl)
                || self.pendingFinalize
                || self.pendingGallery
                || self.cameraSwitchInProgress
            {
                self.capturePhase = .savingClip
                self.engine.stopSegment()
                return
            }
            self.capturePhase = .recording
            self.startElapsedTimer()
            SetLogHaptics.recordingStarted()
        }
        engine.onRecordingFinished = { [weak self] result in
            self?.handleRecordingFinished(result)
        }
        engine.onCameraPositionChanged = { [weak self] position in
            self?.cameraPosition = position
            self?.zoomFactor = 1
        }
        engine.onCameraSwitchFinished = { [weak self] result in
            guard let self else { return }
            self.cameraSwitchInProgress = false
            switch result {
            case .success:
                self.capturePhase = self.idleCapturePhase()
            case .failure(let error):
                self.capturePhase = self.idleCapturePhase()
                self.alertMessage = error.localizedDescription
                SetLogHaptics.warning()
            }

            let shouldResumeContinuation = self.resumeAfterCameraSwitch
                && self.pressState.isDown(self.inputSettings.recordControl)
                && !self.pendingFinalize
                && !self.pendingGallery
            self.resumeAfterCameraSwitch = false
            if shouldResumeContinuation, self.queuedSegmentStart == nil {
                self.queuedSegmentStart = QueuedSegmentStart(
                    pressedAt: Date(),
                    createsMarker: false
                )
            }
            self.maybeStartQueuedSegment()
        }
        engine.onInterrupted = { [weak self] message in
            guard let self else { return }
            self.alertMessage = message
            if self.capturePhase == .recording {
                self.stopCurrentSegment()
            }
        }
    }

    private func handleRecordingFinished(_ result: Result<RecordedSegmentResult, Error>) {
        stopElapsedTimer()
        guard let pending = pendingSegment else {
            capturePhase = idleCapturePhase()
            return
        }
        pendingSegment = nil

        switch result {
        case .success(let recorded):
            do {
                activeSession = try store.commitSegment(
                    pending,
                    durationSeconds: recorded.durationSeconds
                )
                refreshSessions()
                SetLogHaptics.recordingPaused()
            } catch {
                store.discardPending(pending)
                fail(error)
            }
        case .failure(let error):
            store.discardPending(pending)
            fail(error)
        }

        if stopSessionAfterClip {
            stopSessionAfterClip = false
            engine.stopSession()
        }
        if pendingFinalize {
            exportActiveSession()
            return
        }
        if pendingGallery {
            completeGalleryNavigation()
            return
        }
        if switchAfterSegmentFinalizes {
            switchAfterSegmentFinalizes = false
            performCameraSwitch()
            return
        }

        capturePhase = idleCapturePhase()
        if pressState.isDown(inputSettings.recordControl), appIsActive, screen == .camera {
            if queuedSegmentStart == nil {
                queuedSegmentStart = QueuedSegmentStart(pressedAt: Date(), createsMarker: false)
            }
            maybeStartQueuedSegment()
        }
    }

    private func completeGalleryNavigation() {
        pendingGallery = false
        cancelInputTasks()
        pressState.reset()
        engine.stopSession()
        isSessionRunning = false
        refreshSessions()
        screen = .gallery
    }

    private func refreshSessions() {
        sessions = store.loadAll()
        if let selectedID = selectedSession?.id {
            selectedSession = sessions.first(where: { $0.id == selectedID })
        }
    }

    private func startElapsedTimer() {
        stopElapsedTimer()
        let started = ProcessInfo.processInfo.systemUptime
        elapsedTask = Task { [weak self] in
            while !Task.isCancelled {
                self?.currentHoldDuration = max(0, ProcessInfo.processInfo.systemUptime - started)
                try? await Task.sleep(for: .milliseconds(100))
            }
        }
    }

    private func stopElapsedTimer() {
        elapsedTask?.cancel()
        elapsedTask = nil
        currentHoldDuration = 0
    }

    private func cancelShortcutResolution() {
        shortcutResolutionTask?.cancel()
        shortcutResolutionTask = nil
    }

    private func cancelInputTasks() {
        cancelShortcutResolution()
        shortcutPressDetector.reset()
        queuedSegmentStart = nil
    }

    private func idleCapturePhase() -> CapturePhase {
        if case .failed = capturePhase {
            return capturePhase
        }
        return .ready
    }

    private func isFailure(_ phase: CapturePhase) -> Bool {
        if case .failed = phase { return true }
        return false
    }

    private func fail(_ error: Error) {
        capturePhase = .failed(error.localizedDescription)
        alertMessage = error.localizedDescription
        SetLogHaptics.warning()
    }

    private static func requestAccess(for mediaType: AVMediaType) async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: mediaType) {
        case .authorized:
            true
        case .notDetermined:
            await AVCaptureDevice.requestAccess(for: mediaType)
        case .denied, .restricted:
            false
        @unknown default:
            false
        }
    }
}
