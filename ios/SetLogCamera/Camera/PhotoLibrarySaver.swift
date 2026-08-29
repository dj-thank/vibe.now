import SwiftUI
import UIKit

struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    var applicationActivities: [UIActivity]? = nil
    var completion: (() -> Void)?

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(
            activityItems: items,
            applicationActivities: applicationActivities
        )
        controller.completionWithItemsHandler = { _, _, _, _ in
            completion?()
        }
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
