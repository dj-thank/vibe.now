import XCTest
@testable import SetLogCamera

final class CameraModelsTests: XCTestCase {
    func testDurationFormatting() {
        XCTAssertEqual(formatDuration(0), "00:00")
        XCTAssertEqual(formatDuration(65.9), "01:05")
        XCTAssertEqual(formatDuration(3_661), "1:01:01")
    }

    func testDraftStartsResumableAndEmpty() {
        let session = SetLogSession.draft(now: Date(timeIntervalSince1970: 0))
        XCTAssertEqual(session.status, .draft)
        XCTAssertTrue(session.isResumable)
        XCTAssertFalse(session.hasRecordedContent)
        XCTAssertTrue(session.segments.isEmpty)
        XCTAssertTrue(session.markers.isEmpty)
    }

    func testFileStemRemovesUnsafeCharacters() {
        XCTAssertEqual(sanitizedFileStem("  My/Set:Log?  "), "My-Set-Log-")
        XCTAssertEqual(sanitizedFileStem("\n\t"), "Vibe.now")
    }

    func testTimestampOverlaySanitizesInteractiveBounds() {
        let raw = TimestampOverlaySettings(
            enabled: true,
            x: -1,
            y: 4,
            scale: 9,
            style: .monospaced
        )
        let safe = raw.sanitized
        XCTAssertEqual(safe.x, 0.08, accuracy: 0.0001)
        XCTAssertEqual(safe.y, 0.88, accuracy: 0.0001)
        XCTAssertEqual(safe.scale, 1.80, accuracy: 0.0001)
        XCTAssertEqual(safe.style, .monospaced)
    }

    func testTalkBackSafeDefaultsUseOppositeControlMultipress() {
        let settings = InputSettings()
        XCTAssertEqual(settings.recordControl, .secondary)
        XCTAssertEqual(settings.doublePressAction, .finish)
        XCTAssertEqual(settings.triplePressAction, .openGallery)
        XCTAssertEqual(settings.recordControl.opposite, .primary)
    }
}
