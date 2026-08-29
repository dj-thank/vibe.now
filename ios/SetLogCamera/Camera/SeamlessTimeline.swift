import Foundation

enum CaptureInputTiming {
    static let multiPressWindowSeconds: TimeInterval = 0.72
}

struct MultiPressDetector: Equatable, Sendable {
    private(set) var pressTimes: [TimeInterval] = []

    mutating func register(at timestamp: TimeInterval) -> Int {
        let lowerBound = timestamp - CaptureInputTiming.multiPressWindowSeconds
        pressTimes.removeAll { $0 < lowerBound }
        pressTimes.append(timestamp)
        if pressTimes.count >= 3 {
            pressTimes.removeAll(keepingCapacity: true)
            return 3
        }
        return pressTimes.count
    }

    mutating func resolve() -> Int {
        let count = pressTimes.count
        pressTimes.removeAll(keepingCapacity: true)
        return count
    }

    mutating func reset() {
        pressTimes.removeAll(keepingCapacity: true)
    }
}

struct HardwarePressState: Equatable, Sendable {
    var primaryIsDown = false
    var secondaryIsDown = false

    func isDown(_ control: CaptureControl) -> Bool {
        switch control {
        case .primary:
            primaryIsDown
        case .secondary:
            secondaryIsDown
        }
    }

    mutating func setDown(_ isDown: Bool, for control: CaptureControl) {
        switch control {
        case .primary:
            primaryIsDown = isDown
        case .secondary:
            secondaryIsDown = isDown
        }
    }

    mutating func reset() {
        primaryIsDown = false
        secondaryIsDown = false
    }
}
