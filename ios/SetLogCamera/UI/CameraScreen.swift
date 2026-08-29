import AVFoundation
import SwiftUI

struct CameraScreen: View {
    @ObservedObject var viewModel: CameraViewModel
    @State private var settingsPresented = false

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Color.black.ignoresSafeArea()

                CameraPreview(
                    engine: viewModel.engine,
                    mirrored: viewModel.cameraPosition == .front,
                    onFocus: { devicePoint, viewPoint in
                        viewModel.focus(at: devicePoint, screenPoint: viewPoint)
                    },
                    onPinch: viewModel.handlePinch
                )
                .ignoresSafeArea()

                LinearGradient(
                    colors: [.black.opacity(0.48), .clear, .black.opacity(0.78)],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .ignoresSafeArea()
                .allowsHitTesting(false)

                if viewModel.timestampSettings.enabled {
                    TimestampOverlayPreview(
                        settings: .constant(viewModel.timestampSettings),
                        interactive: false
                    )
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                }

                if viewModel.capturePhase == .recording {
                    RecordingEdgeGlow()
                        .transition(.opacity)
                        .allowsHitTesting(false)
                }

                if let point = viewModel.focusPoint {
                    FocusReticle()
                        .position(point)
                        .transition(.opacity.combined(with: .scale))
                        .allowsHitTesting(false)
                }

                VStack(spacing: 0) {
                    statusHeader
                    Spacer()
                    bottomControls
                }
                .padding(.horizontal, 18)
                .padding(.top, max(10, geometry.safeAreaInsets.top + 4))
                .padding(.bottom, max(12, geometry.safeAreaInsets.bottom + 2))
            }
            .animation(.easeInOut(duration: 0.2), value: viewModel.capturePhase)
            .animation(.easeInOut(duration: 0.2), value: viewModel.cameraSwitchInProgress)
        }
        .statusBarHidden()
        .sheet(isPresented: $settingsPresented) {
            CameraControlSettingsSheet(viewModel: viewModel)
        }
    }

    private var statusHeader: some View {
        HStack(alignment: .center) {
            CaptureStatusPill(
                phase: viewModel.capturePhase,
                isRunning: viewModel.isSessionRunning,
                switching: viewModel.cameraSwitchInProgress
            )
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(formatDuration(viewModel.displayedDuration))
                    .font(.system(.headline, design: .monospaced, weight: .semibold))
                    .monospacedDigit()
                Text(
                    String(
                        format: String(localized: "camera.marker-count.format"),
                        viewModel.markerCount
                    )
                )
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.72))
            }
        }
        .foregroundStyle(.white)
    }

    private var bottomControls: some View {
        VStack(spacing: 14) {
            instructionCard

            HStack(alignment: .center) {
                NativeCameraControlButton(
                    symbol: "photo.stack.fill",
                    label: String(localized: "camera.open-gallery"),
                    badge: viewModel.sessions.isEmpty ? nil : viewModel.sessions.count,
                    action: viewModel.openGalleryWithoutFinalizing
                )

                Spacer()

                PhysicalRecordIndicator(
                    control: viewModel.recordControl,
                    isRecording: viewModel.capturePhase == .recording
                )

                Spacer()

                NativeCameraControlButton(
                    symbol: "arrow.triangle.2.circlepath.camera.fill",
                    label: String(localized: "camera.switch"),
                    showsProgress: viewModel.cameraSwitchInProgress,
                    action: viewModel.switchCamera
                )
                .disabled(!canSwitchCamera)
                .opacity(canSwitchCamera ? 1 : 0.42)
            }
            .padding(.horizontal, 2)

            HStack(spacing: 12) {
                Button {
                    settingsPresented = true
                } label: {
                    Label(String(localized: "camera.settings"), systemImage: "slider.horizontal.3")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .tint(.white)
                .disabled(!canOpenSettings)

                if viewModel.hasDraftContent
                    || viewModel.capturePhase == .recording
                    || viewModel.capturePhase == .savingClip
                {
                    Button(action: viewModel.finishFromScreen) {
                        Label(String(localized: "camera.finish"), systemImage: "stop.circle.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                    .disabled(viewModel.capturePhase == .exporting)
                }
            }
            .font(.subheadline.weight(.semibold))

            Text(shortcutSummary)
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.68))
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .minimumScaleFactor(0.78)
        }
        .padding(16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .stroke(.white.opacity(0.17), lineWidth: 1)
        )
        .foregroundStyle(.white)
    }

    private var instructionCard: some View {
        HStack(spacing: 12) {
            Image(systemName: instructionSymbol)
                .font(.system(size: 25, weight: .semibold))
                .foregroundStyle(instructionAccent)
                .frame(width: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(instructionTitle)
                    .font(.headline)
                Text(instructionBody)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.72))
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            if viewModel.zoomFactor > 1.01 {
                Text(String(format: "%.1f×", viewModel.zoomFactor))
                    .font(.caption.monospacedDigit().bold())
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(.white.opacity(0.14), in: Capsule())
            }
        }
    }

    private var canSwitchCamera: Bool {
        viewModel.captureEventsEnabled
            && viewModel.capturePhase != .exporting
            && viewModel.capturePhase != .savingClip
            && !viewModel.cameraSwitchInProgress
    }

    private var canOpenSettings: Bool {
        switch viewModel.capturePhase {
        case .ready, .failed:
            !viewModel.cameraSwitchInProgress
        case .preparing, .recording, .savingClip, .exporting:
            false
        }
    }

    private var shortcutSummary: String {
        let key = controlName(viewModel.shortcutControl)
        let double = actionName(viewModel.inputSettings.doublePressAction)
        let triple = actionName(viewModel.inputSettings.triplePressAction)
        return String(
            format: String(localized: "camera.shortcut.summary.format"),
            key,
            double,
            triple
        )
    }

    private func controlName(_ control: CaptureControl) -> String {
        String(localized: control == .secondary ? "control.secondary" : "control.primary")
    }

    private func actionName(_ action: ShortcutAction) -> String {
        switch action {
        case .finish:
            String(localized: "shortcut.action.finish")
        case .openGallery:
            String(localized: "shortcut.action.gallery")
        case .none:
            String(localized: "shortcut.action.none")
        }
    }

    private var instructionTitle: String {
        switch viewModel.capturePhase {
        case .preparing:
            viewModel.cameraSwitchInProgress
                ? String(localized: "camera.state.switching")
                : String(localized: "camera.state.preparing")
        case .ready:
            viewModel.hasDraftContent
                ? String(localized: "camera.state.resume")
                : String(localized: "camera.state.ready")
        case .recording:
            String(localized: "camera.state.recording")
        case .savingClip:
            String(localized: "camera.state.pausing")
        case .exporting:
            String(localized: "camera.state.exporting")
        case .failed:
            String(localized: "camera.state.problem")
        }
    }

    private var instructionBody: String {
        switch viewModel.capturePhase {
        case .preparing:
            viewModel.cameraSwitchInProgress
                ? String(localized: "camera.state.switching.body")
                : String(localized: "camera.state.preparing.body")
        case .ready:
            String(
                format: String(localized: "camera.state.ready.body.format"),
                controlName(viewModel.recordControl)
            )
        case .recording:
            String(localized: "camera.state.recording.body")
        case .savingClip:
            String(localized: "camera.state.pausing.body")
        case .exporting:
            String(localized: "camera.state.exporting.body")
        case .failed(let message):
            message
        }
    }

    private var instructionSymbol: String {
        switch viewModel.capturePhase {
        case .recording:
            "record.circle.fill"
        case .savingClip:
            "pause.circle.fill"
        case .exporting:
            "arrow.triangle.2.circlepath"
        case .failed:
            "exclamationmark.triangle.fill"
        case .preparing:
            viewModel.cameraSwitchInProgress ? "arrow.triangle.2.circlepath.camera.fill" : "hourglass"
        case .ready:
            viewModel.recordControl == .secondary ? "plus.circle.fill" : "minus.circle.fill"
        }
    }

    private var instructionAccent: Color {
        switch viewModel.capturePhase {
        case .recording, .failed:
            .red
        case .savingClip:
            .yellow
        case .preparing where viewModel.cameraSwitchInProgress:
            .cyan
        default:
            .white
        }
    }
}

private struct NativeCameraControlButton: View {
    let symbol: String
    let label: String
    var badge: Int?
    var showsProgress = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack(alignment: .topTrailing) {
                ZStack {
                    Circle()
                        .fill(.ultraThinMaterial)
                    Circle()
                        .stroke(.white.opacity(0.26), lineWidth: 1)
                    if showsProgress {
                        ProgressView()
                            .tint(.white)
                    } else {
                        Image(systemName: symbol)
                            .font(.system(size: 21, weight: .semibold))
                    }
                }
                .frame(width: 54, height: 54)

                if let badge {
                    Text("\(badge)")
                        .font(.caption2.bold())
                        .foregroundStyle(.white)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(.red, in: Capsule())
                        .offset(x: 5, y: -3)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

private struct PhysicalRecordIndicator: View {
    let control: CaptureControl
    let isRecording: Bool

    var body: some View {
        VStack(spacing: 5) {
            ZStack {
                Circle()
                    .stroke(.white, lineWidth: 4)
                    .frame(width: 72, height: 72)
                Circle()
                    .fill(isRecording ? Color.red : Color.white.opacity(0.92))
                    .frame(width: isRecording ? 56 : 60, height: isRecording ? 56 : 60)
                    .animation(.easeInOut(duration: 0.18), value: isRecording)
                Text(control == .secondary ? "+" : "−")
                    .font(.system(size: 30, weight: .bold, design: .rounded))
                    .foregroundStyle(isRecording ? .white : .black)
            }
            Text(String(localized: "camera.hold"))
                .font(.caption2.bold())
                .foregroundStyle(.white.opacity(0.78))
        }
        .accessibilityElement(children: .combine)
    }
}

private struct CaptureStatusPill: View {
    let phase: CapturePhase
    let isRunning: Bool
    let switching: Bool

    var body: some View {
        HStack(spacing: 7) {
            Circle()
                .fill(dotColor)
                .frame(width: 8, height: 8)
            Text(label)
                .font(.caption.bold())
                .lineLimit(1)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.black.opacity(0.52), in: Capsule())
        .overlay(Capsule().stroke(.white.opacity(0.16), lineWidth: 1))
    }

    private var label: String {
        if switching { return String(localized: "status.switching") }
        switch phase {
        case .recording:
            String(localized: "status.recording")
        case .savingClip:
            String(localized: "status.paused")
        case .exporting:
            String(localized: "status.saving")
        case .failed:
            String(localized: "status.problem")
        case .preparing:
            String(localized: "status.preparing")
        case .ready:
            isRunning ? String(localized: "status.ready") : String(localized: "status.camera-wait")
        }
    }

    private var dotColor: Color {
        if switching { return .cyan }
        switch phase {
        case .recording:
            .red
        case .savingClip:
            .yellow
        case .failed:
            .orange
        case .exporting, .preparing:
            .blue
        case .ready:
            isRunning ? .green : .gray
        }
    }
}

private struct RecordingEdgeGlow: View {
    @State private var pulse = false

    var body: some View {
        ZStack {
            Rectangle()
                .stroke(.red.opacity(pulse ? 0.9 : 0.55), lineWidth: 5)
                .blur(radius: 4)
                .ignoresSafeArea()
            RecordingCorners()
                .stroke(
                    .red,
                    style: StrokeStyle(lineWidth: 7, lineCap: .round, lineJoin: .round)
                )
                .padding(8)
                .shadow(color: .red.opacity(0.8), radius: pulse ? 12 : 5)
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 0.75).repeatForever(autoreverses: true)) {
                pulse = true
            }
        }
    }
}

private struct RecordingCorners: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        let length = min(rect.width, rect.height) * 0.12

        path.move(to: CGPoint(x: rect.minX, y: rect.minY + length))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.minX + length, y: rect.minY))

        path.move(to: CGPoint(x: rect.maxX - length, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + length))

        path.move(to: CGPoint(x: rect.maxX, y: rect.maxY - length))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.maxX - length, y: rect.maxY))

        path.move(to: CGPoint(x: rect.minX + length, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - length))
        return path
    }
}

private struct FocusReticle: View {
    var body: some View {
        RoundedRectangle(cornerRadius: 5)
            .stroke(.yellow, lineWidth: 1.5)
            .frame(width: 74, height: 74)
            .shadow(color: .black.opacity(0.4), radius: 2)
    }
}
