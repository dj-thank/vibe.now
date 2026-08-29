import SwiftUI

@main
struct SetLogCameraApp: App {
    @StateObject private var viewModel = CameraViewModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            CameraRootView(viewModel: viewModel)
                .preferredColorScheme(.dark)
                .task { await viewModel.requestPermissionsAndStart() }
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .active:
                        viewModel.appBecameActive()
                    case .background:
                        viewModel.appMovedToBackground()
                    case .inactive:
                        break
                    @unknown default:
                        break
                    }
                }
        }
    }
}
