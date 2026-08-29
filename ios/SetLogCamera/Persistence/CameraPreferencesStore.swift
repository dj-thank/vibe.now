import Foundation

final class SessionStore {
    private let fileManager: FileManager
    private let rootURL: URL
    private let defaults: UserDefaults
    private let activeSessionKey = "setlog.active-session-id"
    private let guideSeenKey = "setlog.guide-seen"

    init(
        fileManager: FileManager = .default,
        defaults: UserDefaults = .standard
    ) {
        self.fileManager = fileManager
        self.defaults = defaults

        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        rootURL = base.appending(path: "SetLogCamera/Sessions", directoryHint: .isDirectory)
        try? fileManager.createDirectory(
            at: rootURL,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        recoverInterruptedWork()
    }

    var shouldShowGuide: Bool {
        !defaults.bool(forKey: guideSeenKey)
    }

    func markGuideSeen() {
        defaults.set(true, forKey: guideSeenKey)
    }

    func loadAll() -> [SetLogSession] {
        recoverInterruptedWork()
        let directories = (try? fileManager.contentsOfDirectory(
            at: rootURL,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )) ?? []

        return directories
            .compactMap(readSession(in:))
            .sorted { lhs, rhs in
                if lhs.isResumable != rhs.isResumable {
                    return lhs.isResumable
                }
                return lhs.updatedAt > rhs.updatedAt
            }
    }

    func activeDraft() -> SetLogSession? {
        guard
            let rawID = defaults.string(forKey: activeSessionKey),
            let id = UUID(uuidString: rawID),
            let session = readSession(id: id),
            session.isResumable
        else {
            defaults.removeObject(forKey: activeSessionKey)
            return nil
        }
        return session
    }

    func getOrCreateDraft(now: Date = Date()) throws -> SetLogSession {
        if let existing = activeDraft() {
            return existing
        }
        let session = SetLogSession.draft(now: now)
        try createSessionDirectory(session.id)
        try write(session)
        defaults.set(session.id.uuidString, forKey: activeSessionKey)
        return session
    }

    func readSession(id: UUID) -> SetLogSession? {
        readSession(in: sessionDirectory(id))
    }

    func reserveSegment(sessionID: UUID, pressedAt: Date) throws -> PendingSegment {
        guard let session = readSession(id: sessionID), session.isResumable else {
            throw StoreError.sessionNotResumable
        }
        try createSessionDirectory(sessionID)
        let segmentID = UUID()
        let ordinal = session.segments.count + 1
        return PendingSegment(
            sessionID: sessionID,
            segmentID: segmentID,
            partialFileName: String(format: "segment-%04d-%@.partial.mov", ordinal, segmentID.uuidString),
            finalFileName: String(format: "segment-%04d-%@.mov", ordinal, segmentID.uuidString),
            startedAt: pressedAt,
            timelineOffsetSeconds: session.totalDurationSeconds,
            ordinal: ordinal
        )
    }

    func partialURL(for pending: PendingSegment) -> URL {
        sessionDirectory(pending.sessionID).appending(path: pending.partialFileName)
    }

    func commitSegment(_ pending: PendingSegment, durationSeconds: Double) throws -> SetLogSession {
        guard var session = readSession(id: pending.sessionID), session.isResumable else {
            throw StoreError.sessionNotResumable
        }

        let partial = partialURL(for: pending)
        let final = sessionDirectory(pending.sessionID).appending(path: pending.finalFileName)
        guard fileManager.fileExists(atPath: partial.path) else {
            throw StoreError.missingRecordedClip
        }
        if fileManager.fileExists(atPath: final.path) {
            try fileManager.removeItem(at: final)
        }
        do {
            try fileManager.moveItem(at: partial, to: final)
        } catch {
            try fileManager.copyItem(at: partial, to: final)
            try? fileManager.removeItem(at: partial)
        }

        let safeDuration = max(durationSeconds, 0.001)
        session.status = .draft
        session.outputFileName = nil
        session.errorMessage = nil
        session.updatedAt = Date()
        session.totalDurationSeconds += safeDuration
        session.segments.append(
            SegmentRecord(
                id: pending.segmentID,
                fileName: pending.finalFileName,
                startedAt: pending.startedAt,
                durationSeconds: safeDuration,
                ordinal: pending.ordinal
            )
        )
        session.markers.append(
            CaptureMarker(
                id: UUID(),
                pressedAt: pending.startedAt,
                timelineOffsetSeconds: pending.timelineOffsetSeconds,
                ordinal: pending.ordinal,
                segmentID: pending.segmentID
            )
        )
        try write(session)
        defaults.set(session.id.uuidString, forKey: activeSessionKey)
        return session
    }

    func discardPending(_ pending: PendingSegment) {
        try? fileManager.removeItem(at: partialURL(for: pending))
    }

    func markExporting(sessionID: UUID) throws -> SetLogSession {
        guard var session = readSession(id: sessionID), !session.segments.isEmpty else {
            throw StoreError.emptySession
        }
        session.status = .exporting
        session.errorMessage = nil
        session.outputFileName = nil
        session.updatedAt = Date()
        try write(session)
        defaults.removeObject(forKey: activeSessionKey)
        return session
    }

    func markReady(sessionID: UUID, outputFileName: String) throws -> SetLogSession {
        guard var session = readSession(id: sessionID) else {
            throw StoreError.sessionMissing
        }
        session.status = .ready
        session.outputFileName = outputFileName
        session.errorMessage = nil
        session.updatedAt = Date()
        try write(session)
        defaults.removeObject(forKey: activeSessionKey)
        return session
    }

    func markExportFailed(sessionID: UUID, message: String) throws -> SetLogSession {
        guard var session = readSession(id: sessionID) else {
            throw StoreError.sessionMissing
        }
        if let outputFileName = session.outputFileName {
            try? fileManager.removeItem(at: sessionDirectory(sessionID).appending(path: outputFileName))
        }
        session.status = .failed
        session.outputFileName = nil
        session.errorMessage = message
        session.updatedAt = Date()
        try write(session)
        defaults.set(session.id.uuidString, forKey: activeSessionKey)
        return session
    }

    @discardableResult
    func updateDetails(sessionID: UUID, title: String, caption: String) throws -> SetLogSession {
        guard var session = readSession(id: sessionID) else {
            throw StoreError.sessionMissing
        }
        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        session.title = trimmedTitle.isEmpty
            ? String(localized: "session.untitled")
            : String(trimmedTitle.prefix(80))
        session.caption = String(caption.prefix(2_000))
        session.updatedAt = Date()
        try write(session)
        return session
    }

    func resume(sessionID: UUID) throws -> SetLogSession {
        guard var session = readSession(id: sessionID), session.isResumable else {
            throw StoreError.sessionNotResumable
        }
        session.status = .draft
        session.errorMessage = nil
        session.updatedAt = Date()
        try write(session)
        defaults.set(session.id.uuidString, forKey: activeSessionKey)
        return session
    }

    func delete(sessionID: UUID) throws {
        let directory = sessionDirectory(sessionID)
        if fileManager.fileExists(atPath: directory.path) {
            try fileManager.removeItem(at: directory)
        }
        if defaults.string(forKey: activeSessionKey) == sessionID.uuidString {
            defaults.removeObject(forKey: activeSessionKey)
        }
    }

    func segmentURL(sessionID: UUID, segment: SegmentRecord) -> URL {
        sessionDirectory(sessionID).appending(path: segment.fileName)
    }

    func outputURL(for session: SetLogSession) -> URL? {
        guard let fileName = session.outputFileName else { return nil }
        let url = sessionDirectory(session.id).appending(path: fileName)
        return fileManager.fileExists(atPath: url.path) ? url : nil
    }

    func previewURL(for session: SetLogSession) -> URL? {
        outputURL(for: session)
            ?? session.segments.first.map { segmentURL(sessionID: session.id, segment: $0) }
    }

    func newOutputURL(sessionID: UUID) -> URL {
        sessionDirectory(sessionID).appending(path: "setlog-\(sessionID.uuidString).mp4")
    }

    func makeShareCopy(for session: SetLogSession) throws -> URL {
        guard let source = outputURL(for: session) else {
            throw StoreError.outputMissing
        }
        let shareDirectory = fileManager.temporaryDirectory
            .appending(path: "SetLogCameraShare", directoryHint: .isDirectory)
        try? fileManager.removeItem(at: shareDirectory)
        try fileManager.createDirectory(at: shareDirectory, withIntermediateDirectories: true)
        let destination = shareDirectory.appending(path: "\(sanitizedFileStem(session.title)).mp4")
        try fileManager.copyItem(at: source, to: destination)

        let sidecar = ShareMetadata(
            schema: "app.setlog.session.v1",
            sessionID: session.id,
            title: session.title,
            caption: session.caption,
            createdAt: session.createdAt,
            durationSeconds: session.totalDurationSeconds,
            markers: session.markers
        )
        let metadataData = try Self.encoder.encode(sidecar)
        try metadataData.write(
            to: shareDirectory.appending(path: "\(sanitizedFileStem(session.title))-setlog.json"),
            options: .atomic
        )
        return destination
    }

    private func createSessionDirectory(_ id: UUID) throws {
        try fileManager.createDirectory(
            at: sessionDirectory(id),
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
    }

    private func sessionDirectory(_ id: UUID) -> URL {
        rootURL.appending(path: id.uuidString, directoryHint: .isDirectory)
    }

    private func manifestURL(in directory: URL) -> URL {
        directory.appending(path: "manifest.json")
    }

    private func readSession(in directory: URL) -> SetLogSession? {
        let url = manifestURL(in: directory)
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? Self.decoder.decode(SetLogSession.self, from: data)
    }

    private func write(_ session: SetLogSession) throws {
        try createSessionDirectory(session.id)
        let data = try Self.encoder.encode(session)
        try data.write(
            to: manifestURL(in: sessionDirectory(session.id)),
            options: [.atomic, .completeFileProtectionUnlessOpen]
        )
    }

    private func recoverInterruptedWork() {
        try? fileManager.createDirectory(at: rootURL, withIntermediateDirectories: true)
        if let enumerator = fileManager.enumerator(at: rootURL, includingPropertiesForKeys: nil) {
            for case let url as URL in enumerator where url.lastPathComponent.contains(".partial.") {
                try? fileManager.removeItem(at: url)
            }
        }

        let directories = (try? fileManager.contentsOfDirectory(
            at: rootURL,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )) ?? []
        for directory in directories {
            guard var session = readSession(in: directory), session.status == .exporting else { continue }
            session.status = .failed
            session.outputFileName = nil
            session.errorMessage = String(localized: "error.export.interrupted")
            session.updatedAt = Date()
            try? write(session)
            defaults.set(session.id.uuidString, forKey: activeSessionKey)
        }
    }

    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }()

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()
}

private struct ShareMetadata: Encodable {
    let schema: String
    let sessionID: UUID
    let title: String
    let caption: String
    let createdAt: Date
    let durationSeconds: Double
    let markers: [CaptureMarker]
}

enum StoreError: LocalizedError {
    case sessionMissing
    case sessionNotResumable
    case missingRecordedClip
    case emptySession
    case outputMissing

    var errorDescription: String? {
        switch self {
        case .sessionMissing:
            String(localized: "error.session.missing")
        case .sessionNotResumable:
            String(localized: "error.session.not-resumable")
        case .missingRecordedClip:
            String(localized: "error.clip.missing")
        case .emptySession:
            String(localized: "error.session.empty")
        case .outputMissing:
            String(localized: "error.output.missing")
        }
    }
}
