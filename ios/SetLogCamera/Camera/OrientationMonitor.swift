import UIKit

final class DeviceOrientationMonitor {
    var onChange: ((UIDeviceOrientation) -> Void)?
    private var token: NSObjectProtocol?

    func start() {
        guard token == nil else { return }
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        token = NotificationCenter.default.addObserver(
            forName: UIDevice.orientationDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            let orientation = UIDevice.current.orientation
            guard orientation.isPortrait || orientation.isLandscape else { return }
            self?.onChange?(orientation)
        }
        let orientation = UIDevice.current.orientation
        if orientation.isPortrait || orientation.isLandscape {
            onChange?(orientation)
        }
    }

    func stop() {
        if let token {
            NotificationCenter.default.removeObserver(token)
        }
        token = nil
        UIDevice.current.endGeneratingDeviceOrientationNotifications()
    }
}

enum SetLogHaptics {
    static func recordingStarted() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred(intensity: 0.7)
    }

    static func recordingPaused() {
        UIImpactFeedbackGenerator(style: .soft).impactOccurred(intensity: 0.6)
    }

    static func selection() {
        UISelectionFeedbackGenerator().selectionChanged()
    }

    static func finished() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    static func warning() {
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
    }
}
