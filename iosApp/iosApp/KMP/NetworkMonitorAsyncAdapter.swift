import Foundation
import Shared

// Adapts the KMP `NetworkMonitor` (completion handlers) to the `NetworkMonitorAsync` protocol (async/await).
struct NetworkMonitorAsyncAdapter: NetworkMonitorAsync {
    private let monitor: NetworkMonitor

    init(_ monitor: NetworkMonitor) {
        self.monitor = monitor
    }

    func isConnected() async -> Bool {
        await withCheckedContinuation { continuation in
            monitor.isConnected { isConnected, _ in
                continuation.resume(returning: isConnected?.boolValue ?? false)
            }
        }
    }
}

