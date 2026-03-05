import Foundation
import Shared

// Swift actor wrapping the shared KMP SurveySyncEngine.
actor SyncCoordinator {

    private let engine: SurveySyncEngine

    init(baseURL: URL) {
        let driver  = DriverFactoryKt.createSqlDriver()
        let repo    = SurveyRepositoryImpl(driver: driver)
        let attRepo = AttachmentRepositoryImpl(driver: driver)
        let api     = SurveyApiImpl(baseURL: baseURL)
        let monitor = IosNetworkMonitor()
        let fs      = IosFileSystem()

        engine = SurveySyncEngine(
            repository: repo,
            attachmentRepository: attRepo,
            api: api,
            networkMonitor: monitor,
            fileSystem: fs,
            dispatcher: Kotlinx_coroutines_coreDispatchers.shared.Default
        )
    }

    // Bridges the KMP suspend fun into Swift async/await.
    func sync() async -> SyncResult {
        await withCheckedContinuation { continuation in
            engine.sync { result, _ in continuation.resume(returning: result!) }
        }
    }

    // Bridges StateFlow<SyncProgress?> into an AsyncStream via FlowCollectorHelper.
    func progressStream() -> AsyncStream<SyncProgress?> {
        AsyncStream { continuation in
            let job = FlowCollectorHelper.shared.collect(
                flow: engine.progress,
                onEach: { continuation.yield($0) }
            )
            continuation.onTermination = { _ in job.cancel(cause: nil) }
        }
    }
}