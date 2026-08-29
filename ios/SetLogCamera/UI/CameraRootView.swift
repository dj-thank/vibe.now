import AVKit
import SwiftUI

struct CameraRootView: View {
    @ObservedObject var viewModel: CameraViewModel
    @Environment(\.openURL) private var openURL

    var body: some View {
        Group {
            switch viewModel.permissionState {
            case .checking:
                PermissionStateView(
                    symbol: "camera.aperture",
                    title: String(localized: "permission.preparing.title"),
                    message: String(localized: "permission.preparing.message"),
                    showsProgress: true
                )
            case .ready:
                switch viewModel.screen {
                case .camera:
                    CameraScreen(viewModel: viewModel)
                case .gallery:
                    GalleryScreen(viewModel: viewModel)
                }
            case .cameraDenied:
                PermissionStateView(
                    symbol: "camera.fill",
                    title: String(localized: "permission.camera.title"),
                    message: String(localized: "permission.camera.message"),
                    buttonTitle: String(localized: "common.open-settings"),
                    action: openSettings
                )
            case .unavailable(let message):
                PermissionStateView(
                    symbol: "exclamationmark.camera.fill",
                    title: String(localized: "permission.unavailable.title"),
                    message: message,
                    buttonTitle: String(localized: "common.retry")
                ) {
                    Task { await viewModel.requestPermissionsAndStart() }
                }
            }
        }
        .onCameraCaptureEvent(
            isEnabled: viewModel.captureEventsEnabled,
            primaryAction: { event in
                switch event.phase {
                case .began:
                    viewModel.captureControlBegan(.primary)
                case .ended:
                    viewModel.captureControlEnded(.primary)
                case .cancelled:
                    viewModel.captureControlEnded(.primary, cancelled: true)
                @unknown default:
                    break
                }
            },
            secondaryAction: { event in
                switch event.phase {
                case .began:
                    viewModel.captureControlBegan(.secondary)
                case .ended:
                    viewModel.captureControlEnded(.secondary)
                case .cancelled:
                    viewModel.captureControlEnded(.secondary, cancelled: true)
                @unknown default:
                    break
                }
            }
        )
        .sheet(item: $viewModel.sharePayload) { payload in
            ActivityView(items: payload.items)
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $viewModel.guidePresented) {
            FirstLaunchGuide(onDone: viewModel.dismissGuide)
                .interactiveDismissDisabled()
        }
        .alert(
            String(localized: "alert.title"),
            isPresented: Binding(
                get: { viewModel.alertMessage != nil },
                set: { if !$0 { viewModel.alertMessage = nil } }
            )
        ) {
            Button(String(localized: "common.ok"), role: .cancel) {
                viewModel.alertMessage = nil
            }
        } message: {
            Text(viewModel.alertMessage ?? "")
        }
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        openURL(url)
    }
}

private struct PermissionStateView: View {
    let symbol: String
    let title: String
    let message: String
    var buttonTitle: String?
    var action: (() -> Void)?
    var showsProgress = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 18) {
                Image(systemName: symbol)
                    .font(.system(size: 48, weight: .semibold))
                    .foregroundStyle(.white)
                Text(title)
                    .font(.title2.bold())
                    .multilineTextAlignment(.center)
                Text(message)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                if showsProgress {
                    ProgressView().tint(.white)
                }
                if let buttonTitle, let action {
                    Button(buttonTitle, action: action)
                        .buttonStyle(.borderedProminent)
                        .tint(.white)
                        .foregroundStyle(.black)
                }
            }
            .padding(32)
        }
    }
}

private struct FirstLaunchGuide: View {
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(String(localized: "guide.title"))
                            .font(.largeTitle.bold())
                        Text(String(localized: "guide.subtitle"))
                            .foregroundStyle(.secondary)
                    }

                    GuideRow(
                        number: "1",
                        title: String(localized: "guide.record.title"),
                        detail: String(localized: "guide.record.body"),
                        symbol: "plus.circle.fill"
                    )
                    GuideRow(
                        number: "2",
                        title: String(localized: "guide.pause.title"),
                        detail: String(localized: "guide.pause.body"),
                        symbol: "pause.circle.fill"
                    )
                    GuideRow(
                        number: "3",
                        title: String(localized: "guide.finish.title"),
                        detail: String(localized: "guide.finish.body.ios"),
                        symbol: "checkmark.circle.fill"
                    )
                    GuideRow(
                        number: "4",
                        title: String(localized: "guide.gallery.title"),
                        detail: String(localized: "guide.gallery.body"),
                        symbol: "rectangle.stack.fill"
                    )

                    Text(String(localized: "guide.ios-limitation"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .padding(14)
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
                }
                .padding(24)
            }
            .safeAreaInset(edge: .bottom) {
                Button(action: onDone) {
                    Text(String(localized: "guide.start"))
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                .buttonStyle(.borderedProminent)
                .tint(.white)
                .foregroundStyle(.black)
                .padding()
                .background(.ultraThinMaterial)
            }
        }
    }
}

private struct GuideRow: View {
    let number: String
    let title: String
    let detail: String
    let symbol: String

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            ZStack {
                Circle().fill(.white)
                Text(number).font(.headline).foregroundStyle(.black)
            }
            .frame(width: 38, height: 38)

            VStack(alignment: .leading, spacing: 5) {
                Label(title, systemImage: symbol)
                    .font(.headline)
                Text(detail)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
