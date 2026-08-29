import AVFoundation
import SwiftUI

struct CameraScreen: View {
    @ObservedObject var viewModel: CameraViewModel

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
                    colors: [.black.opacity(0.58), .clear, .black.opacity(0.72)],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .ignoresSafeArea()
                .allowsHitTesting(false)

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
                    topBar
                    Spacer()
                    bottomPanel
                }
                .padding(.horizontal, 18)
                .padding(.top, max(10, geometry.safeAreaInsets.top + 4))
                .padding(.bottom, max(12, geometry.safeAreaInsets.bottom + 2))
            }
            .animation(.easeInOut(duration: 0.2), value: viewModel.capturePhase)
        }
        .statusBarHidden()
    }

    private var topBar: some View {
        ZStack {
            HStack {
                Button(action: viewModel.openGalleryWithoutFinalizing) {
                    ZStack(alignment: .topTrailing) {
                        Image(systemName: "rectangle.stack.fill")
                            .font(.system(size: 18, weight: .semibold))
                            .frame(width: 48, height: 48)
                            .background(.black.opacity(0.48), in: Circle())
                            .overlay(Circle().stroke(.white.opacity(0.22), lineWidth: 1))
                        if !viewModel.sessions.isEmpty {
                            Text("\(viewModel.sessions.count)")
                                .font(.caption2.bold())
                                .foregroundStyle(.white)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 2)
                                .background(.red, in: Capsule())
                                .offset(x: 4, y: -2)
                        }
                    }
                }
                .accessibilityLabel(String(localized: "camera.open-gallery"))

                Spacer()

                Button(action: viewModel.switchCamera) {
                    Image(systemName: "arrow.triangle.2.circlepath.camera.fill")
                        .font(.system(size: 20, weight: .semibold))
                        .frame(width: 48, height: 48)
                        .background(.black.opacity(0.48), in: Circle())
                        .overlay(Circle().stroke(.white.opacity(0.22), lineWidth: 1))
                }
                .disabled(viewModel.capturePhase != .ready || !viewModel.isSessionRunning)
                .opacity(viewModel.capturePhase == .ready ? 1 : 0.45)
                .accessibilityLabel(String(localized: "camera.switch"))
            }

            CaptureStatusPill(
                phase: viewModel.capturePhase,
                isRunning: viewModel.isSessionRunning
            )
        }
        .foregroundStyle(.white)
    }

    private var bottomPanel: some View {
        VStack(spacing: 13) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(formatDuration(viewModel.displayedDuration))
                        .font(.system(.title2, design: .monospaced, weight: .semibold))
                    Text(
                        String(
                            format: String(localized: "camera.marker-count.format"),
                            viewModel.markerCount
                        )
                    )
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                Spacer()
                if viewModel.zoomFactor > 1.01 {
                    Text(String(format: "%.1f×", viewModel.zoomFactor))
                        .font(.caption.monospacedDigit().bold())
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(.white.opacity(0.12), in: Capsule())
                }
            }

            HStack(spacing: 13) {
                Image(systemName: instructionSymbol)
                    .font(.system(size: 26, weight: .bold))
                    .foregroundStyle(instructionAccent)
                    .frame(width: 38)
                VStack(alignment: .leading, spacing: 3) {
                    Text(instructionTitle)
                        .font(.headline)
                    Text(instructionBody)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }

            if viewModel.hasDraftContent || viewModel.capturePhase == .recording || viewModel.capturePhase == .savingClip {
                Button(action: viewModel.finishFromScreen) {
                    HStack(spacing: 9) {
                        Image(systemName: "checkmark.circle.fill")
                        Text(String(localized: "camera.finish"))
                            .fontWeight(.semibold)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.white)
                .background(.red, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .disabled(viewModel.capturePhase == .exporting)
                .opacity(viewModel.capturePhase == .exporting ? 0.55 : 1)
            }

            HStack(spacing: 8) {
                Label(String(localized: "camera.shortcut.gallery"), systemImage: "minus.circle")
                Text("•")
                Label(String(localized: "camera.shortcut.finish.ios"), systemImage: "minus.circle.fill")
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.72)
        }
        .padding(17)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(.white.opacity(0.16), lineWidth: 1)
        )
        .foregroundStyle(.white)
    }

    private var instructionTitle: String {
        switch viewModel.capturePhase {
        case .preparing:
            String(localized: "camera.state.preparing")
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
            String(localized: "camera.state.preparing.body")
        case .ready:
            String(localized: "camera.state.ready.body")
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
            "hourglass"
        case .ready:
            "plus.circle.fill"
        }
    }

    private var instructionAccent: Color {
        switch viewModel.capturePhase {
        case .recording, .failed:
            .red
        case .savingClip:
            .yellow
        default:
            .white
        }
    }
}

private struct CaptureStatusPill: View {
    let phase: CapturePhase
    let isRunning: Bool

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
        .background(.black.opacity(0.56), in: Capsule())
        .overlay(Capsule().stroke(.white.opacity(0.16), lineWidth: 1))
    }

    private var label: String {
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
