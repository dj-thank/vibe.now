import AVFoundation
import Foundation

// MARK: - Navigation and capture lifecycle

enum AppScreen: String, Codable, Sendable {
    case camera
    case gallery
}

enum SessionStatus: String, Codable, Sendable {
    case draft
    case exporting
    case ready
    case failed
}

enum CapturePhase: Equatable, Sendable {
    case preparing
    case ready
    case recording
    case savingClip
    case exporting
    case failed(String)

    var isBusy: Bool {
        switch self {
        case .preparing, .savingClip, .exporting:
            true
        case .ready, .recording, .failed:
            false
        }
    }
}

// MARK: - Configurable hardware controls

/// iOS Capture Controls exposes a primary and a secondary action. On iPhone, the secondary action
/// is normally Volume Up, while the primary action may be Volume Down, Action Button, or Camera
/// Control depending on the device and system configuration.
enum CaptureControl: String, Codable, CaseIterable, Identifiable, Sendable {
    case secondary
    case primary

    var id: String { rawValue }
    var opposite: CaptureControl { self == .secondary ? .primary : .secondary }
}

enum ShortcutAction: String, Codable, CaseIterable, Identifiable, Sendable {
    case finish
    case openGallery
    case none

    var id: String { rawValue }
}

struct InputSettings: Codable, Equatable, Sendable {
    var recordControl: CaptureControl = .secondary
    var doublePressAction: ShortcutAction = .finish
    var triplePressAction: ShortcutAction = .openGallery
}

// MARK: - Timestamp overlay

enum TimestampStyle: String, Codable, CaseIterable, Identifiable, Sendable {
    case clean
    case boxed
    case monospaced

    var id: String { rawValue }
}

struct TimestampOverlaySettings: Codable, Equatable, Sendable {
    var enabled = true
    /// Normalized horizontal center, from 0 (left) to 1 (right).
    var x: Double = 0.5
    /// Normalized vertical center, from 0 (top) to 1 (bottom).
    var y: Double = 0.14
    var scale: Double = 1
    var style: TimestampStyle = .boxed

    var sanitized: TimestampOverlaySettings {
        var result = self
        result.x = min(max(result.x, 0.08), 0.92)
        result.y = min(max(result.y, 0.08), 0.88)
        result.scale = min(max(result.scale, 0.60), 1.80)
        return result
    }
}

// MARK: - Session data

struct SegmentRecord: Identifiable, Codable, Equatable, Sendable {
    let id: UUID
    let fileName: String
    let startedAt: Date
    let durationSeconds: Double
    let ordinal: Int
}

struct CaptureMarker: Identifiable, Codable, Equatable, Sendable {
    let id: UUID
    let pressedAt: Date
    let timelineOffsetSeconds: Double
    let ordinal: Int
    let segmentID: UUID
}

struct PendingSegment: Equatable, Sendable {
    let sessionID: UUID
    let segmentID: UUID
    let partialFileName: String
    let finalFileName: String
    let startedAt: Date
    let timelineOffsetSeconds: Double
    let ordinal: Int
    let createsMarker: Bool
}

struct SetLogSession: Identifiable, Codable, Equatable, Sendable {
    let id: UUID
    var title: String
    var caption: String
    let createdAt: Date
    var updatedAt: Date
    var status: SessionStatus
    var outputFileName: String?
    var segments: [SegmentRecord]
    var markers: [CaptureMarker]
    var totalDurationSeconds: Double
    var errorMessage: String?

    // Optional storage preserves decoding compatibility with v0.1 manifests.
    var timestampOverlay: TimestampOverlaySettings?
    var imported: Bool?

    var isResumable: Bool {
        status == .draft || status == .failed
    }

    var hasRecordedContent: Bool {
        !segments.isEmpty
    }

    var effectiveTimestampOverlay: TimestampOverlaySettings {
        get { (timestampOverlay ?? TimestampOverlaySettings()).sanitized }
        set { timestampOverlay = newValue.sanitized }
    }

    var isImported: Bool { imported ?? false }

    static func draft(
        now: Date,
        timestampOverlay: TimestampOverlaySettings = TimestampOverlaySettings()
    ) -> SetLogSession {
        let dateText = DateFormatters.fileTitle.string(from: now)
        return SetLogSession(
            id: UUID(),
            title: String(format: String(localized: "session.default.title.format"), dateText),
            caption: "",
            createdAt: now,
            updatedAt: now,
            status: .draft,
            outputFileName: nil,
            segments: [],
            markers: [],
            totalDurationSeconds: 0,
            errorMessage: nil,
            timestampOverlay: timestampOverlay.sanitized,
            imported: false
        )
    }
}

// MARK: - Formatting

enum DateFormatters {
    static let display: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .medium
        return formatter
    }()

    static let overlay: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale.current
        formatter.dateFormat = "yyyy-MM-dd  HH:mm:ss"
        return formatter
    }()

    static let timeOnly: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .medium
        return formatter
    }()

    static let fileTitle: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH-mm-ss"
        return formatter
    }()

    static let iso8601 = ISO8601DateFormatter()
}

func formatDuration(_ seconds: Double) -> String {
    let whole = max(0, Int(seconds.rounded(.down)))
    let hours = whole / 3_600
    let minutes = (whole % 3_600) / 60
    let remaining = whole % 60
    if hours > 0 {
        return String(format: "%d:%02d:%02d", hours, minutes, remaining)
    }
    return String(format: "%02d:%02d", minutes, remaining)
}

func sanitizedFileStem(_ raw: String) -> String {
    let invalid = CharacterSet(charactersIn: "/\\:*?\"<>|")
        .union(.newlines)
        .union(.controlCharacters)
    let cleaned = raw
        .components(separatedBy: invalid)
        .joined(separator: "-")
        .trimmingCharacters(in: .whitespacesAndNewlines)
    let compact = cleaned.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
    return String((compact.isEmpty ? "Vibe.now" : compact).prefix(80))
}
