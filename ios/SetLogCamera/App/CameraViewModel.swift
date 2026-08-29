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

    let engine = SegmentCaptureEngine()

    private let store: SessionStore
    private lazy var exporter = SessionVideoExporter(store: store)
    private let orientationMonitor = DeviceOrientationMonitor()
    private var pendingSegment: PendingSegment?
    private var pressState = HardwarePressState()
    private var triplePressDetector = TriplePressDetector()
    private var recordStartTask: Task<Void, Never>?
    private var finishHoldTask: Task<Void, Never>?
    private var elapsedTask: Task<Void, Never>?
    private var focusClearTask: Task<Void, Never>?
    private var pendingFinalize = false
    private var pendingGallery = false
    private var stopSessionAfterClip = false
    private var configured = false
    private var appIsActive = true
    private var pinchStartZoom: CGFloat = 1

    init(store: SessionStore = SessionStore()) {
        self.store = store
        bindEngine()
        orientationMonitor.onChange = { [weak self] orientation in
            self?.engine.updateRotation(for: orientation)
        }
        refreshSessions()
        activeSession = store.activeDraft()
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
        (activeSession?.markers.count ?? 0) + (capturePhase == .recording ? 1 : 0)
    }

    var hasDraftContent: Bool {
        activeSession?.hasRecordedContent == true
    }

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
        triplePressDetector.reset()
        pendingGallery = false
        if capturePhase == .recording {
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

    // MARK: Physical capture controls

    func secondaryCaptureBegan() {
        guard captureEventsEnabled, !pressState.secondaryIsDown else { return }
        pressState.secondaryIsDown = true
        recordStartTask?.cancel()
        recordStartTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: CaptureInputTiming.chordGraceNanoseconds)
            guard !Task.isCancelled, let self else { return }
            guard self.pressState.secondaryIsDown, !self.pressState.primaryIsDown else { return }
            self.beginSegmentIfPossible()
        }
    }

    func secondaryCaptureEnded(cancelled: Bool = false) {
        guard pressState.secondaryIsDown else { return }
        pressState.secondaryIsDown = false
        recordStartTask?.cancel()
        recordStartTask = nil

        if pressState.primaryIsDown {
            finishHoldTask?.cancel()
            finishHoldTask = nil
        }
        if capturePhase == .recording {
            stopCurrentSegment()
        }
        if cancelled {
            triplePressDetector.reset()
        }
    }

    func primaryCaptureBegan() {
        guard captureEventsEnabled, !pressState.primaryIsDown else { return }
        pressState.primaryIsDown = true
        pressState.finishHoldTriggered = false

        let chordAtStart = pressState.secondaryIsDown
        if chordAtStart {
            recordStartTask?.cancel()
            recordStartTask = nil
            if capturePhase == .recording {
                stopCurrentSegment()
            }
        }
        scheduleFinishHold(requiresSecondary: chordAtStart)
    }

    func primaryCaptureEnded(cancelled: Bool = false) {
        guard pressState.primaryIsDown else { return }
        pressState.primaryIsDown = false
        finishHoldTask?.cancel()
        finishHoldTask = nil

        if pressState.finishHoldTriggered {
            pressState.finishHoldTriggered = false
            triplePressDetector.reset()
            return
        }
        guard !cancelled else {
            triplePressDetector.reset()
            return
        }

        if triplePressDetector.register(at: ProcessInfo.processInfo.systemUptime) {
            openGalleryWithoutFinalizing()
        }
    }

    func finishFromScreen() {
        finishCurrentSession()
    }

    private func scheduleFinishHold(requiresSecondary: Bool) {
        finishHoldTask?.cancel()
        finishHoldTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: CaptureInputTiming.finishHoldNanoseconds)
            guard !Task.isCancelled, let self, self.pressState.primaryIsDown else { return }
            if requiresSecondary, !self.pressState.secondaryIsDown { return }
            self.pressState.finishHoldTriggered = true
            self.finishCurrentSession()
        }
    }

    // MARK: Camera operations

    func switchCamera() {
        guard capturePhase == .ready, isSessionRunning else { return }
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

    private func beginSegmentIfPossible() {
        guard screen == .camera, appIsActive, isSessionRunning else { return }
        switch capturePhase {
        case .ready, .failed:
            do {
                let session = try store.getOrCreateDraft()
                let pending = try store.reserveSegment(sessionID: session.id, pressedAt: Date())
                activeSession = session
                pendingSegment = pending
                capturePhase = .preparing
                currentHoldDuration = 0
                engine.startSegment(to: store.partialURL(for: pending))
            } catch {
                fail(error)
            }
        case .savingClip:
            // The engine will immediately start the next clip after the previous file closes,
            // provided Volume Up is still physically held.
            break
        case .preparing, .recording, .exporting:
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
        recordStartTask?.cancel()
        recordStartTask = nil
        if capturePhase == .recording || capturePhase == .preparing {
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

    // MARK: Navigation and gallery

    func openGalleryWithoutFinalizing() {
        pendingGallery = true
        if capturePhase == .recording || capturePhase == .preparing {
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
            refreshSessions()
            returnToCamera()
        } catch {
            fail(error)
        }
    }

    func saveDetails(sessionID: UUID, title: String, caption: String) {
        do {
            let updated = try store.updateDetails(sessionID: sessionID, title: title, caption: caption)
            refreshSessions()
            selectedSession = updated
            if activeSession?.id == updated.id {
                activeSession = updated
            }
        } catch {
            fail(error)
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
                .appending(path: "\(sanitizedFileStem(session.title))-setlog.json")
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

    // MARK: Engine callbacks

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
            self?.isSessionRunning = running
        }
        engine.onRecordingStarted = { [weak self] _ in
            guard let self else { return }
            if !self.pressState.secondaryIsDown || self.pendingFinalize || self.pendingGallery {
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
            capturePhase = .ready
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
        } else if pendingGallery {
            completeGalleryNavigation()
        } else if pressState.secondaryIsDown, appIsActive, screen == .camera {
            capturePhase = .ready
            beginSegmentIfPossible()
        } else if case .failed = capturePhase {
            // Preserve the visible failure state.
        } else {
            capturePhase = .ready
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

    private func cancelInputTasks() {
        recordStartTask?.cancel()
        recordStartTask = nil
        finishHoldTask?.cancel()
        finishHoldTask = nil
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
