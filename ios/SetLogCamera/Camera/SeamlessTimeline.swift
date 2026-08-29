import Foundation

enum CaptureInputTiming {
    static let chordGraceNanoseconds: UInt64 = 90_000_000
    static let finishHoldNanoseconds: UInt64 = 2_000_000_000
    static let triplePressWindowSeconds: TimeInterval = 0.9
}

struct TriplePressDetector: Equatable, Sendable {
    private(set) var pressTimes: [TimeInterval] = []

    mutating func register(at timestamp: TimeInterval) -> Bool {
        let lowerBound = timestamp - CaptureInputTiming.triplePressWindowSeconds
        pressTimes.removeAll { $0 < lowerBound }
        pressTimes.append(timestamp)
        if pressTimes.count >= 3 {
            pressTimes.removeAll(keepingCapacity: true)
            return true
        }
        return false
    }

    mutating func reset() {
        pressTimes.removeAll(keepingCapacity: true)
    }
}

struct HardwarePressState: Equatable, Sendable {
    var primaryIsDown = false
    var secondaryIsDown = false
    var finishHoldTriggered = false

    mutating func reset() {
        primaryIsDown = false
        secondaryIsDown = false
        finishHoldTriggered = false
    }
}
