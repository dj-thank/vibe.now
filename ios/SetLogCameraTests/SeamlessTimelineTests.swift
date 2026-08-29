import XCTest
@testable import SetLogCamera

final class CaptureInputTimingTests: XCTestCase {
    func testThreePressesInsideWindowOpenGallery() {
        var detector = TriplePressDetector()
        XCTAssertFalse(detector.register(at: 10.0))
        XCTAssertFalse(detector.register(at: 10.3))
        XCTAssertTrue(detector.register(at: 10.8))
    }

    func testOldPressesExpire() {
        var detector = TriplePressDetector()
        XCTAssertFalse(detector.register(at: 1.0))
        XCTAssertFalse(detector.register(at: 1.2))
        XCTAssertFalse(detector.register(at: 2.1))
        XCTAssertFalse(detector.register(at: 2.2))
        XCTAssertTrue(detector.register(at: 2.3))
    }

    func testDetectorResetsAfterTrigger() {
        var detector = TriplePressDetector()
        _ = detector.register(at: 1.0)
        _ = detector.register(at: 1.1)
        XCTAssertTrue(detector.register(at: 1.2))
        XCTAssertFalse(detector.register(at: 1.3))
    }
}
