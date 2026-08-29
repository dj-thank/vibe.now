import AVFoundation
import SwiftUI
import UIKit

final class CameraPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var onFocus: ((CGPoint, CGPoint) -> Void)?
    var onPinch: ((CGFloat, UIGestureRecognizer.State) -> Void)?

    private var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        previewLayer.videoGravity = .resizeAspectFill

        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        addGestureRecognizer(tap)
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(handlePinch(_:)))
        addGestureRecognizer(pinch)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func connect(session: AVCaptureSession) {
        previewLayer.session = session
    }

    func updateMirroring(_ mirrored: Bool) {
        guard let connection = previewLayer.connection, connection.isVideoMirroringSupported else { return }
        connection.automaticallyAdjustsVideoMirroring = false
        connection.isVideoMirrored = mirrored
    }

    @objc private func handleTap(_ recognizer: UITapGestureRecognizer) {
        let layerPoint = recognizer.location(in: self)
        let devicePoint = previewLayer.captureDevicePointConverted(fromLayerPoint: layerPoint)
        onFocus?(devicePoint, layerPoint)
    }

    @objc private func handlePinch(_ recognizer: UIPinchGestureRecognizer) {
        onPinch?(recognizer.scale, recognizer.state)
    }
}

struct CameraPreview: UIViewRepresentable {
    let engine: SegmentCaptureEngine
    let mirrored: Bool
    let onFocus: (CGPoint, CGPoint) -> Void
    let onPinch: (CGFloat, UIGestureRecognizer.State) -> Void

    func makeUIView(context: Context) -> CameraPreviewView {
        let view = CameraPreviewView()
        view.connect(session: engine.session)
        view.onFocus = onFocus
        view.onPinch = onPinch
        view.updateMirroring(mirrored)
        return view
    }

    func updateUIView(_ uiView: CameraPreviewView, context: Context) {
        uiView.connect(session: engine.session)
        uiView.onFocus = onFocus
        uiView.onPinch = onPinch
        uiView.updateMirroring(mirrored)
    }
}
