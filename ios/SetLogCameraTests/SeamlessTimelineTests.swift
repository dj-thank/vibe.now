import XCTest
@testable import SetLogCamera

final class CaptureInputTimingTests: XCTestCase {
    func testThreePressesInsideWindowResolveImmediately() {
        var detector = MultiPressDetector()
        XCTAssertEqual(detector.register(at: 10.0), 1)
        XCTAssertEqual(detector.register(at: 10.3), 2)
        XCTAssertEqual(detector.register(at: 10.6), 3)
        XCTAssertEqual(detector.resolve(), 0)
    }

    func testOldPressesExpire() {
        var detector = MultiPressDetector()
        XCTAssertEqual(detector.register(at: 1.0), 1)
        XCTAssertEqual(detector.register(at: 1.2), 2)
        XCTAssertEqual(detector.register(at: 2.1), 1)
        XCTAssertEqual(detector.resolve(), 1)
    }

    func testDoublePressResolvesAfterWindowTask() {
        var detector = MultiPressDetector()
        XCTAssertEqual(detector.register(at: 1.0), 1)
        XCTAssertEqual(detector.register(at: 1.1), 2)
        XCTAssertEqual(detector.resolve(), 2)
        XCTAssertEqual(detector.resolve(), 0)
    }
}
