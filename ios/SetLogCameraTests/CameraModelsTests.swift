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
        XCTAssertEqual(sanitizedFileStem("\n\t"), "SetLog")
    }
}
