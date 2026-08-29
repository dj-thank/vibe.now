import Foundation

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

    var isResumable: Bool {
        status == .draft || status == .failed
    }

    var hasRecordedContent: Bool {
        !segments.isEmpty
    }

    static func draft(now: Date) -> SetLogSession {
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
            errorMessage: nil
        )
    }
}

enum DateFormatters {
    static let display: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .medium
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
    return String((compact.isEmpty ? "SetLog" : compact).prefix(80))
}
