import AVFoundation
import Foundation
import UIKit

struct RecordedSegmentResult: Sendable {
    let url: URL
    let durationSeconds: Double
}

enum SegmentCaptureError: LocalizedError {
    case noCamera
    case cannotAddInput
    case cannotAddOutput
    case notConfigured
    case sessionUnavailable
    case alreadyRecording
    case outputMissing

    var errorDescription: String? {
        switch self {
        case .noCamera:
            String(localized: "error.camera.unavailable")
        case .cannotAddInput:
            String(localized: "error.camera.input")
        case .cannotAddOutput:
            String(localized: "error.camera.output")
        case .notConfigured:
            String(localized: "error.camera.not-configured")
        case .sessionUnavailable:
            String(localized: "error.camera.session")
        case .alreadyRecording:
            String(localized: "error.camera.already-recording")
        case .outputMissing:
            String(localized: "error.camera.output-missing")
        }
    }
}

/// Records one durable movie clip for each configured physical-control hold. A released clip is
/// finalized before it is appended to the persistent session manifest.
final class SegmentCaptureEngine: NSObject, AVCaptureFileOutputRecordingDelegate {
    let session = AVCaptureSession()

    var onConfigured: ((Result<Void, Error>) -> Void)?
    var onSessionRunningChanged: ((Bool) -> Void)?
    var onRecordingStarted: ((URL) -> Void)?
    var onRecordingFinished: ((Result<RecordedSegmentResult, Error>) -> Void)?
    var onCameraPositionChanged: ((AVCaptureDevice.Position) -> Void)?
    var onCameraSwitchFinished: ((Result<AVCaptureDevice.Position, Error>) -> Void)?
    var onInterrupted: ((String) -> Void)?

    private let sessionQueue = DispatchQueue(label: "app.setlog.capture.session", qos: .userInitiated)
    private let movieOutput = AVCaptureMovieFileOutput()
    private var videoInput: AVCaptureDeviceInput?
    private var audioInput: AVCaptureDeviceInput?
    private var configured = false
    private var includeAudio = false
    private var desiredPosition: AVCaptureDevice.Position = .back
    private var activeURL: URL?
    private var recordingStartedUptime: TimeInterval?
    private var stopRequestedBeforeStart = false
    private var observers: [NSObjectProtocol] = []

    override init() {
        super.init()
        installObservers()
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    var isRecording: Bool {
        movieOutput.isRecording
    }

    var cameraPosition: AVCaptureDevice.Position {
        videoInput?.device.position ?? desiredPosition
    }

    func configure(includeAudio: Bool) {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            do {
                self.includeAudio = includeAudio
                try self.configureLocked(position: self.desiredPosition)
                self.configured = true
                DispatchQueue.main.async { self.onConfigured?(.success(())) }
            } catch {
                self.configured = false
                DispatchQueue.main.async { self.onConfigured?(.failure(error)) }
            }
        }
    }

    func startSession() {
        sessionQueue.async { [weak self] in
            guard let self, self.configured, !self.session.isRunning else { return }
            self.session.startRunning()
            let running = self.session.isRunning
            DispatchQueue.main.async { self.onSessionRunningChanged?(running) }
        }
    }

    func stopSession() {
        sessionQueue.async { [weak self] in
            guard let self, self.session.isRunning, !self.movieOutput.isRecording else { return }
            self.session.stopRunning()
            DispatchQueue.main.async { self.onSessionRunningChanged?(false) }
        }
    }

    func startSegment(to url: URL) {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            guard self.configured else {
                self.finishWithError(SegmentCaptureError.notConfigured)
                return
            }
            guard self.session.isRunning else {
                self.finishWithError(SegmentCaptureError.sessionUnavailable)
                return
            }
            guard !self.movieOutput.isRecording, self.activeURL == nil else {
                self.finishWithError(SegmentCaptureError.alreadyRecording)
                return
            }

            try? FileManager.default.removeItem(at: url)
            self.activeURL = url
            self.recordingStartedUptime = nil
            self.stopRequestedBeforeStart = false
            if let connection = self.movieOutput.connection(with: .video) {
                if connection.isVideoStabilizationSupported {
                    connection.preferredVideoStabilizationMode = .auto
                }
                self.applyCurrentRotation(to: connection)
            }
            self.movieOutput.startRecording(to: url, recordingDelegate: self)
        }
    }

    func stopSegment() {
        sessionQueue.async { [weak self] in
            guard let self, self.activeURL != nil else { return }
            if self.movieOutput.isRecording {
                self.movieOutput.stopRecording()
            } else {
                // AVCaptureMovieFileOutput may need a short interval before didStartRecording fires.
                // Remember an early release/switch request so the clip is closed immediately on start.
                self.stopRequestedBeforeStart = true
            }
        }
    }

    func switchCamera() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            guard self.configured, !self.movieOutput.isRecording else {
                DispatchQueue.main.async {
                    self.onCameraSwitchFinished?(.failure(SegmentCaptureError.alreadyRecording))
                }
                return
            }
            let next: AVCaptureDevice.Position = self.cameraPosition == .back ? .front : .back
            do {
                try self.replaceVideoInputLocked(position: next)
                self.desiredPosition = next
                DispatchQueue.main.async {
                    self.onCameraPositionChanged?(next)
                    self.onCameraSwitchFinished?(.success(next))
                }
            } catch {
                DispatchQueue.main.async {
                    self.onCameraSwitchFinished?(.failure(error))
                }
            }
        }
    }

    func updateRotation(for orientation: UIDeviceOrientation) {
        sessionQueue.async { [weak self] in
            guard let self, let connection = self.movieOutput.connection(with: .video) else { return }
            let angle: CGFloat
            switch orientation {
            case .portrait:
                angle = 90
            case .portraitUpsideDown:
                angle = 270
            case .landscapeLeft:
                angle = 0
            case .landscapeRight:
                angle = 180
            default:
                return
            }
            if connection.isVideoRotationAngleSupported(angle) {
                connection.videoRotationAngle = angle
            }
        }
    }

    func focus(at devicePoint: CGPoint) {
        sessionQueue.async { [weak self] in
            guard let device = self?.videoInput?.device else { return }
            do {
                try device.lockForConfiguration()
                defer { device.unlockForConfiguration() }
                if device.isFocusPointOfInterestSupported, device.isFocusModeSupported(.autoFocus) {
                    device.focusPointOfInterest = devicePoint
                    device.focusMode = .autoFocus
                }
                if device.isExposurePointOfInterestSupported, device.isExposureModeSupported(.autoExpose) {
                    device.exposurePointOfInterest = devicePoint
                    device.exposureMode = .autoExpose
                }
            } catch {
                DispatchQueue.main.async { self?.onInterrupted?(error.localizedDescription) }
            }
        }
    }

    func setZoomFactor(_ factor: CGFloat) {
        sessionQueue.async { [weak self] in
            guard let device = self?.videoInput?.device else { return }
            do {
                try device.lockForConfiguration()
                defer { device.unlockForConfiguration() }
                let maximum = min(device.activeFormat.videoMaxZoomFactor, 8)
                device.videoZoomFactor = min(max(factor, 1), maximum)
            } catch {
                DispatchQueue.main.async { self?.onInterrupted?(error.localizedDescription) }
            }
        }
    }

    func fileOutput(
        _ output: AVCaptureFileOutput,
        didStartRecordingTo fileURL: URL,
        from connections: [AVCaptureConnection]
    ) {
        recordingStartedUptime = ProcessInfo.processInfo.systemUptime
        let shouldStopImmediately = stopRequestedBeforeStart
        stopRequestedBeforeStart = false
        DispatchQueue.main.async { [weak self] in
            self?.onRecordingStarted?(fileURL)
        }
        if shouldStopImmediately {
            movieOutput.stopRecording()
        }
    }

    func fileOutput(
        _ output: AVCaptureFileOutput,
        didFinishRecordingTo outputFileURL: URL,
        from connections: [AVCaptureConnection],
        error: Error?
    ) {
        let started = recordingStartedUptime
        let measuredDuration = started.map { max(0, ProcessInfo.processInfo.systemUptime - $0) }
            ?? max(0, CMTimeGetSeconds(output.recordedDuration))
        let acceptedError = (error as NSError?)?.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool == true

        activeURL = nil
        recordingStartedUptime = nil
        stopRequestedBeforeStart = false

        if let error, !acceptedError {
            try? FileManager.default.removeItem(at: outputFileURL)
            DispatchQueue.main.async { [weak self] in
                self?.onRecordingFinished?(.failure(error))
            }
            return
        }

        guard
            FileManager.default.fileExists(atPath: outputFileURL.path),
            ((try? outputFileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) > 0
        else {
            finishWithError(SegmentCaptureError.outputMissing)
            return
        }

        DispatchQueue.main.async { [weak self] in
            self?.onRecordingFinished?(
                .success(
                    RecordedSegmentResult(
                        url: outputFileURL,
                        durationSeconds: max(measuredDuration, 0.001)
                    )
                )
            )
        }
    }

    private func configureLocked(position: AVCaptureDevice.Position) throws {
        session.beginConfiguration()
        defer { session.commitConfiguration() }

        session.inputs.forEach(session.removeInput)
        session.outputs.forEach(session.removeOutput)
        if session.canSetSessionPreset(.high) {
            session.sessionPreset = .high
        }

        try addVideoInputLocked(position: position)
        if includeAudio {
            try addAudioInputLocked()
        }
        guard session.canAddOutput(movieOutput) else {
            throw SegmentCaptureError.cannotAddOutput
        }
        session.addOutput(movieOutput)
        movieOutput.movieFragmentInterval = CMTime(seconds: 1, preferredTimescale: 600)
        if let connection = movieOutput.connection(with: .video) {
            if connection.isVideoStabilizationSupported {
                connection.preferredVideoStabilizationMode = .auto
            }
            applyCurrentRotation(to: connection)
        }
    }

    private func addVideoInputLocked(position: AVCaptureDevice.Position) throws {
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position)
            ?? AVCaptureDevice.default(for: .video)
        else {
            throw SegmentCaptureError.noCamera
        }
        let input = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(input) else {
            throw SegmentCaptureError.cannotAddInput
        }
        session.addInput(input)
        videoInput = input
        desiredPosition = device.position
    }

    private func addAudioInputLocked() throws {
        guard let device = AVCaptureDevice.default(for: .audio) else { return }
        let input = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(input) else { return }
        session.addInput(input)
        audioInput = input
    }

    private func replaceVideoInputLocked(position: AVCaptureDevice.Position) throws {
        guard let oldInput = videoInput else {
            throw SegmentCaptureError.notConfigured
        }
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) else {
            throw SegmentCaptureError.noCamera
        }
        let newInput = try AVCaptureDeviceInput(device: device)

        session.beginConfiguration()
        session.removeInput(oldInput)
        if session.canAddInput(newInput) {
            session.addInput(newInput)
            videoInput = newInput
        } else {
            session.addInput(oldInput)
            session.commitConfiguration()
            throw SegmentCaptureError.cannotAddInput
        }
        session.commitConfiguration()

        if let connection = movieOutput.connection(with: .video) {
            if connection.isVideoStabilizationSupported {
                connection.preferredVideoStabilizationMode = .auto
            }
            applyCurrentRotation(to: connection)
        }
    }

    private func applyCurrentRotation(to connection: AVCaptureConnection) {
        let orientation = UIDevice.current.orientation
        let angle: CGFloat
        switch orientation {
        case .landscapeLeft:
            angle = 0
        case .landscapeRight:
            angle = 180
        case .portraitUpsideDown:
            angle = 270
        default:
            angle = 90
        }
        if connection.isVideoRotationAngleSupported(angle) {
            connection.videoRotationAngle = angle
        }
    }

    private func installObservers() {
        let center = NotificationCenter.default
        observers.append(
            center.addObserver(
                forName: .AVCaptureSessionWasInterrupted,
                object: session,
                queue: .main
            ) { [weak self] notification in
                let reason = notification.userInfo?[AVCaptureSessionInterruptionReasonKey]
                    .flatMap { ($0 as? NSNumber)?.intValue }
                    .flatMap(AVCaptureSession.InterruptionReason.init(rawValue:))
                let message: String
                switch reason {
                case .audioDeviceInUseByAnotherClient, .videoDeviceInUseByAnotherClient:
                    message = String(localized: "camera.interrupted.in-use")
                case .videoDeviceNotAvailableWithMultipleForegroundApps:
                    message = String(localized: "camera.interrupted.multitasking")
                default:
                    message = String(localized: "camera.interrupted")
                }
                self?.onInterrupted?(message)
            }
        )
        observers.append(
            center.addObserver(
                forName: .AVCaptureSessionRuntimeError,
                object: session,
                queue: .main
            ) { [weak self] notification in
                let error = notification.userInfo?[AVCaptureSessionErrorKey] as? Error
                self?.onInterrupted?(error?.localizedDescription ?? String(localized: "camera.runtime-error"))
            }
        )
    }

    private func finishWithError(_ error: Error) {
        activeURL.map { try? FileManager.default.removeItem(at: $0) }
        activeURL = nil
        recordingStartedUptime = nil
        stopRequestedBeforeStart = false
        DispatchQueue.main.async { [weak self] in
            self?.onRecordingFinished?(.failure(error))
        }
    }
}
