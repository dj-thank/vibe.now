import AVFoundation
import CoreMedia
import Foundation

@MainActor
final class SessionVideoExporter {
    private let store: SessionStore

    init(store: SessionStore) {
        self.store = store
    }

    func export(_ session: SetLogSession) async throws -> URL {
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

        let outputURL = store.newOutputURL(sessionID: session.id)
        try? FileManager.default.removeItem(at: outputURL)
        guard let exportSession = AVAssetExportSession(
            asset: composition,
            presetName: AVAssetExportPresetHighestQuality
        ) else {
            throw ExportError.cannotCreateExportSession
        }
        exportSession.shouldOptimizeForNetworkUse = true
        exportSession.metadata = try makeMetadata(for: session)
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
            schema: "app.setlog.session.v1",
            sessionID: session.id,
            title: session.title,
            caption: session.caption,
            createdAt: session.createdAt,
            durationSeconds: session.totalDurationSeconds,
            markers: session.markers
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

private struct EmbeddedSessionMetadata: Encodable {
    let schema: String
    let sessionID: UUID
    let title: String
    let caption: String
    let createdAt: Date
    let durationSeconds: Double
    let markers: [CaptureMarker]
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
