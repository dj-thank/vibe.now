import AVFoundation
import CoreImage
import CoreMedia
import Foundation
import UIKit

@MainActor
final class SessionVideoExporter {
    private let store: SessionStore

    init(store: SessionStore) {
        self.store = store
    }

    func export(_ session: SetLogSession, to destinationURL: URL? = nil) async throws -> URL {
        guard !session.segments.isEmpty else {
            throw StoreError.emptySession
        }

        let composition = AVMutableComposition()
        guard let destinationVideo = composition.addMutableTrack(
            withMediaType: .video,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ) else {
            throw ExportError.cannotCreateVideoTrack
        }
        let destinationAudio = composition.addMutableTrack(
            withMediaType: .audio,
            preferredTrackID: kCMPersistentTrackID_Invalid
        )

        var cursor = CMTime.zero
        var firstTransform = CGAffineTransform.identity
        var hasTransform = false

        for segment in session.segments.sorted(by: { $0.ordinal < $1.ordinal }) {
            let sourceURL = store.segmentURL(sessionID: session.id, segment: segment)
            guard FileManager.default.fileExists(atPath: sourceURL.path) else {
                throw ExportError.missingSegment(segment.ordinal)
            }

            let asset = AVURLAsset(url: sourceURL)
            let assetDuration = try await asset.load(.duration)
            guard assetDuration.isNumeric, assetDuration > .zero else {
                throw ExportError.invalidSegment(segment.ordinal)
            }
            let timeRange = CMTimeRange(start: .zero, duration: assetDuration)

            guard let sourceVideo = try await asset.loadTracks(withMediaType: .video).first else {
                throw ExportError.missingVideoTrack(segment.ordinal)
            }
            try destinationVideo.insertTimeRange(timeRange, of: sourceVideo, at: cursor)
            if !hasTransform {
                firstTransform = try await sourceVideo.load(.preferredTransform)
                hasTransform = true
            }

            if
                let destinationAudio,
                let sourceAudio = try await asset.loadTracks(withMediaType: .audio).first
            {
                try destinationAudio.insertTimeRange(timeRange, of: sourceAudio, at: cursor)
            }
            cursor = CMTimeAdd(cursor, assetDuration)
        }

        destinationVideo.preferredTransform = firstTransform

        let outputURL = destinationURL ?? store.newOutputURL(sessionID: session.id)
        try? FileManager.default.removeItem(at: outputURL)
        guard let exportSession = AVAssetExportSession(
            asset: composition,
            presetName: AVAssetExportPresetHighestQuality
        ) else {
            throw ExportError.cannotCreateExportSession
        }
        exportSession.shouldOptimizeForNetworkUse = true
        exportSession.metadata = try makeMetadata(for: session)
        if session.effectiveTimestampOverlay.enabled {
            let renderer = TimestampFrameRenderer(
                segments: session.segments,
                settings: session.effectiveTimestampOverlay
            )
            exportSession.videoComposition = AVVideoComposition(
                asset: composition,
                applyingCIFiltersWithHandler: { request in
                    let source = request.sourceImage.clampedToExtent()
                    guard let overlay = renderer.overlayImage(
                        at: request.compositionTime,
                        renderSize: request.renderSize,
                        sourceExtent: source.extent
                    ) else {
                        request.finish(with: source.cropped(to: request.sourceImage.extent), context: nil)
                        return
                    }
                    let result = overlay
                        .composited(over: source)
                        .cropped(to: request.sourceImage.extent)
                    request.finish(with: result, context: nil)
                }
            )
        }
        try await exportSession.export(to: outputURL, as: .mp4)

        guard
            FileManager.default.fileExists(atPath: outputURL.path),
            ((try? outputURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) > 0
        else {
            throw ExportError.outputMissing
        }
        return outputURL
    }

    private func makeMetadata(for session: SetLogSession) throws -> [AVMetadataItem] {
        let payload = EmbeddedSessionMetadata(
            schema: "app.vibenow.session.v2",
            sessionID: session.id,
            title: session.title,
            caption: session.caption,
            createdAt: session.createdAt,
            durationSeconds: session.totalDurationSeconds,
            markers: session.markers,
            timestampOverlay: session.effectiveTimestampOverlay,
            imported: session.isImported
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(payload)
        guard let json = String(data: data, encoding: .utf8) else {
            throw ExportError.metadataEncoding
        }

        let title = AVMutableMetadataItem()
        title.identifier = .commonIdentifierTitle
        title.value = session.title as NSString
        title.dataType = kCMMetadataBaseDataType_UTF8 as String
        title.extendedLanguageTag = Locale.current.language.languageCode?.identifier ?? "und"

        let description = AVMutableMetadataItem()
        description.identifier = .commonIdentifierDescription
        description.value = json as NSString
        description.dataType = kCMMetadataBaseDataType_UTF8 as String
        description.extendedLanguageTag = "und"

        return [title, description]
    }
}

private final class TimestampFrameRenderer: @unchecked Sendable {
    private let segments: [SegmentRecord]
    private let settings: TimestampOverlaySettings
    private let lock = NSLock()
    private var cachedKey: String?
    private var cachedImage: CIImage?
    private let formatter: DateFormatter

    init(segments: [SegmentRecord], settings: TimestampOverlaySettings) {
        self.segments = segments.sorted(by: { $0.ordinal < $1.ordinal })
        self.settings = settings.sanitized
        formatter = DateFormatter()
        formatter.locale = Locale.current
        formatter.dateFormat = "yyyy-MM-dd  HH:mm:ss"
    }

    func overlayImage(
        at compositionTime: CMTime,
        renderSize: CGSize,
        sourceExtent: CGRect
    ) -> CIImage? {
        guard settings.enabled else { return nil }
        let seconds = max(0, CMTimeGetSeconds(compositionTime).isFinite
            ? CMTimeGetSeconds(compositionTime)
            : 0)
        let captureDate = captureDate(at: seconds)
        let wholeSecond = Int(captureDate.timeIntervalSince1970.rounded(.down))
        let key = "\(wholeSecond)-\(Int(renderSize.width))-\(Int(renderSize.height))-\(settings.scale)-\(settings.style.rawValue)"

        let image: CIImage? = lock.withCriticalSection {
            if cachedKey == key { return cachedImage }
            let text = formatter.string(from: captureDate)
            let rendered = render(text: text, renderSize: renderSize)
            cachedKey = key
            cachedImage = rendered
            return rendered
        }
        guard let image else { return nil }

        let x = sourceExtent.minX + sourceExtent.width * settings.x - image.extent.width / 2
        let y = sourceExtent.minY + sourceExtent.height * (1 - settings.y) - image.extent.height / 2
        return image.transformed(by: CGAffineTransform(translationX: x, y: y))
    }

    private func captureDate(at timelineSeconds: Double) -> Date {
        var cursor = 0.0
        for segment in segments {
            let end = cursor + max(segment.durationSeconds, 0)
            if timelineSeconds <= end || segment.id == segments.last?.id {
                return segment.startedAt.addingTimeInterval(max(0, timelineSeconds - cursor))
            }
            cursor = end
        }
        return segments.first?.startedAt ?? Date(timeIntervalSince1970: 0)
    }

    private func render(text: String, renderSize: CGSize) -> CIImage? {
        let base = max(24, min(renderSize.width, renderSize.height) * 0.034)
        let pointSize = base * settings.scale
        let font: UIFont = settings.style == .monospaced
            ? .monospacedSystemFont(ofSize: pointSize, weight: .semibold)
            : .systemFont(ofSize: pointSize, weight: .semibold)
        let attributes: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: UIColor.white,
        ]
        let measured = (text as NSString).size(withAttributes: attributes)
        let horizontalPadding = settings.style == .boxed ? pointSize * 0.42 : pointSize * 0.08
        let verticalPadding = settings.style == .boxed ? pointSize * 0.24 : pointSize * 0.08
        let canvasSize = CGSize(
            width: ceil(measured.width + horizontalPadding * 2),
            height: ceil(measured.height + verticalPadding * 2)
        )
        let format = UIGraphicsImageRendererFormat()
        format.opaque = false
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: canvasSize, format: format).image { context in
            if settings.style == .boxed {
                UIColor.black.withAlphaComponent(0.68).setFill()
                UIBezierPath(
                    roundedRect: CGRect(origin: .zero, size: canvasSize),
                    cornerRadius: pointSize * 0.20
                ).fill()
            }
            (text as NSString).draw(
                at: CGPoint(x: horizontalPadding, y: verticalPadding),
                withAttributes: attributes
            )
            context.cgContext.flush()
        }
        return CIImage(image: image)
    }
}

private extension NSLock {
    func withCriticalSection<T>(_ body: () -> T) -> T {
        lock()
        defer { unlock() }
        return body()
    }
}

private struct EmbeddedSessionMetadata: Encodable {
    let schema: String
    let sessionID: UUID
    let title: String
    let caption: String
    let createdAt: Date
    let durationSeconds: Double
    let markers: [CaptureMarker]
    let timestampOverlay: TimestampOverlaySettings
    let imported: Bool
}

enum ExportError: LocalizedError {
    case cannotCreateVideoTrack
    case cannotCreateExportSession
    case missingSegment(Int)
    case invalidSegment(Int)
    case missingVideoTrack(Int)
    case metadataEncoding
    case outputMissing

    var errorDescription: String? {
        switch self {
        case .cannotCreateVideoTrack:
            String(localized: "error.export.video-track")
        case .cannotCreateExportSession:
            String(localized: "error.export.session")
        case .missingSegment(let number):
            String(format: String(localized: "error.export.segment-missing.format"), number)
        case .invalidSegment(let number):
            String(format: String(localized: "error.export.segment-invalid.format"), number)
        case .missingVideoTrack(let number):
            String(format: String(localized: "error.export.segment-video.format"), number)
        case .metadataEncoding:
            String(localized: "error.export.metadata")
        case .outputMissing:
            String(localized: "error.export.output")
        }
    }
}
