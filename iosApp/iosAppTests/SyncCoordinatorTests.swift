import XCTest
import Shared

final class SyncCoordinatorTests: XCTestCase {

    private func makeEngine(
        surveys: [Shared.SurveyResponse] = [],
        api: SurveyApi,
        connected: Bool = true,
        availableBytes: Int64 = Int64.max
    ) -> SurveySyncEngine {
        let repo    = FakeRepository(surveys: surveys)
        let attRepo = FakeAttachmentRepo()
        let monitor = StubNetworkMonitor(connected: connected)
        let fs      = StubFileSystem(availableBytes: availableBytes)
        return SurveySyncEngine(
            repository: repo,
            attachmentRepository: attRepo,
            api: api,
            networkMonitor: monitor,
            fileSystem: fs,
            dispatcher: Kotlinx_coroutines_coreDispatchers.shared.Default
        )
    }

    private func survey(id: String, attachments: [Shared.Attachment] = []) -> Shared.SurveyResponse {
        Shared.SurveyResponse(
            id: id,
            farmerId: "farmer-1",
            createdAt: Kotlinx_datetimeInstant.companion.parse(isoString: "2026-03-04T08:00:00Z"),
            status: SyncStatus.pending,
            retryCount: 0,
            nodes: [],
            attachments: attachments
        )
    }

    private func attachment(id: String, surveyId: String = "s1") -> Shared.Attachment {
        Shared.Attachment(
            id: id,
            surveyId: surveyId,
            localPath: "/tmp/\(id).jpg",
            sizeBytes: 1024,
            createdAt: Kotlinx_datetimeInstant.companion.parse(isoString: "2026-03-04T08:00:00Z"),
            uploadStatus: AttachmentUploadStatus.pending,
            retryCount: 0,
            lastError: nil
        )
    }

    private func runSync(engine: SurveySyncEngine) async -> SyncResult {
        await withCheckedContinuation { continuation in
            engine.sync { result, _ in continuation.resume(returning: result!) }
        }
    }

    func testAllSurveysSucceed() async throws {
        let api    = FakeSurveyApi(behavior: { _ in .success })
        let engine = makeEngine(surveys: (1...3).map { survey(id: "s\($0)") }, api: api)

        let result = await runSync(engine: engine)

        XCTAssertEqual(result.succeededIds.sorted(), ["s1", "s2", "s3"])
        XCTAssertTrue(result.failedIds.isEmpty)
        XCTAssertNil(result.stoppedReason)
    }

    func testLowStorageStopsBeforeAnyUpload() async throws {
        let api    = FakeSurveyApi(behavior: { _ in .success })
        let engine = makeEngine(surveys: [survey(id: "s1")], api: api, availableBytes: 10 * 1024 * 1024)

        let result = await runSync(engine: engine)

        XCTAssertTrue(result.stoppedReason is SyncStopReasonLowStorage)
        XCTAssertTrue(result.succeededIds.isEmpty)
    }

    func testNetworkLostBeforeFirstSurvey() async throws {
        let api    = FakeSurveyApi(behavior: { _ in .success })
        let engine = makeEngine(surveys: [survey(id: "s1")], api: api, connected: false)

        let result = await runSync(engine: engine)

        XCTAssertTrue(result.stoppedReason is SyncStopReasonNetworkLost)
        XCTAssertTrue(result.failedIds.contains("s1"))
    }

    func testServerError500ContinuesToNextSurvey() async throws {
        var call = 0
        let api = FakeSurveyApi(behavior: { _ in defer { call += 1 }; return call == 0 ? .serverError(500) : .success })
        let engine = makeEngine(surveys: [survey(id: "s1"), survey(id: "s2")], api: api)

        let result = await runSync(engine: engine)

        XCTAssertTrue(result.failedIds.contains("s1"))
        XCTAssertTrue(result.succeededIds.contains("s2"))
        XCTAssertNil(result.stoppedReason)
    }

    func testServerError400PinsRetryAndContinues() async throws {
        var call = 0
        let api = FakeSurveyApi(behavior: { _ in defer { call += 1 }; return call == 0 ? .serverError(422) : .success })
        let engine = makeEngine(surveys: [survey(id: "s1"), survey(id: "s2")], api: api)

        let result = await runSync(engine: engine)

        XCTAssertTrue(result.failedIds.contains("s1"))
        XCTAssertTrue(result.succeededIds.contains("s2"))
        XCTAssertNil(result.stoppedReason)
    }

    func testUnknownErrorStopsFatal() async throws {
        let api    = FakeSurveyApi(behavior: { _ in .unknown })
        let engine = makeEngine(surveys: [survey(id: "s1"), survey(id: "s2")], api: api)

        let result = await runSync(engine: engine)

        XCTAssertTrue(result.stoppedReason is SyncStopReasonFatalError)
        XCTAssertTrue(result.failedIds.contains("s1"))
    }

    func testAttachmentUploadedAndLocalFileDeleted() async throws {
        let att     = attachment(id: "a1", surveyId: "s1")
        let api     = FakeSurveyApi(behavior: { _ in .success })
        let repo    = FakeRepository(surveys: [survey(id: "s1", attachments: [att])])
        let attRepo = FakeAttachmentRepo()
        let fs      = StubFileSystem(availableBytes: Int64.max)
        let engine  = SurveySyncEngine(
            repository: repo,
            attachmentRepository: attRepo,
            api: api,
            networkMonitor: StubNetworkMonitor(connected: true),
            fileSystem: fs,
            dispatcher: Kotlinx_coroutines_coreDispatchers.shared.Default
        )

        let result = await runSync(engine: engine)

        XCTAssertTrue(result.succeededIds.contains("s1"))
        XCTAssertTrue(attRepo.uploadedIds.contains("a1"))
        XCTAssertTrue(fs.deletedPaths.contains(att.localPath))
    }

    func testProgressStreamEmitsOneEventPerSurvey() async throws {
        let api    = FakeSurveyApi(behavior: { _ in .success })
        let engine = makeEngine(surveys: (1...3).map { survey(id: "s\($0)") }, api: api)

        var events: [SyncProgress?] = []
        let job = FlowCollectorHelper.shared.collect(flow: engine.progress) { events.append($0) }
        defer { job.cancel(cause: KotlinCancellationException()) }

        _ = await runSync(engine: engine)

        let nonNil = events.compactMap { $0 }
        XCTAssertEqual(nonNil.count, 3)
        XCTAssertEqual(nonNil.map { Int($0.current) }, [1, 2, 3])
        XCTAssertNil(events.last as? SyncProgress)
    }
}

private enum FakeApiResponse { case success, serverError(Int32), networkLost, timeout, unknown }

private final class FakeSurveyApi: NSObject, SurveyApi {
    private let behavior: (Int) -> FakeApiResponse
    private var surveyCallCount = 0
    private var attachCallCount = 0

    init(behavior: @escaping (Int) -> FakeApiResponse) { self.behavior = behavior }

    func uploadSurvey(response: Shared.SurveyResponse, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        resolve(behavior(surveyCallCount), completionHandler); surveyCallCount += 1
    }

    func uploadAttachment(attachment: Shared.Attachment, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        resolve(behavior(attachCallCount), completionHandler); attachCallCount += 1
    }

    private func resolve(_ r: FakeApiResponse, _ cb: (KotlinUnit?, Error?) -> Void) {
        switch r {
        case .success:               cb(KotlinUnit(), nil)
        case .serverError(let code): cb(nil, HttpException(code: code))
        case .networkLost:           cb(nil, SyncErrorException(error: SyncErrorNoInternet()))
        case .timeout:               cb(nil, SyncErrorException(error: SyncErrorTimeout()))
        case .unknown:               cb(nil, SyncErrorException(error: SyncErrorUnknown()))
        }
    }
}

private final class FakeRepository: NSObject, SurveyRepository {
    private var surveys: [Shared.SurveyResponse]
    private(set) var syncedIds: [String] = []

    init(surveys: [Shared.SurveyResponse]) { self.surveys = surveys }

    func getPendingSurveys(completionHandler: @escaping ([Shared.SurveyResponse]?, Error?) -> Void) {
        let pending = surveys.filter {
            ($0.status == SyncStatus.pending || $0.status == SyncStatus.failed) && $0.retryCount < 5
        }
        completionHandler(pending, nil)
    }

    func saveSurvey(response: Shared.SurveyResponse, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        surveys.append(response); completionHandler(KotlinUnit(), nil)
    }

    func markAsSynced(id: String, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        syncedIds.append(id); completionHandler(KotlinUnit(), nil)
    }

    func markAsFailed(id: String, error: SyncError, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        completionHandler(KotlinUnit(), nil)
    }

    func incrementRetry(id: String, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        completionHandler(KotlinUnit(), nil)
    }

    func pinRetryToMax(id: String, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        completionHandler(KotlinUnit(), nil)
    }
}

private final class FakeAttachmentRepo: NSObject, AttachmentRepository {
    private(set) var uploadedIds: [String] = []

    func getUploadedAttachments(completionHandler: @escaping ([Shared.Attachment]?, Error?) -> Void) {
        completionHandler([], nil)
    }

    func markAsUploaded(id: String, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        uploadedIds.append(id); completionHandler(KotlinUnit(), nil)
    }

    func markAsFailed(id: String, error: SyncError, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        completionHandler(KotlinUnit(), nil)
    }

    func incrementRetry(id: String, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        completionHandler(KotlinUnit(), nil)
    }
}

private final class StubNetworkMonitor: NSObject, NetworkMonitor {
    private let connected: Bool
    init(connected: Bool) { self.connected = connected }

    func isConnected(completionHandler: @escaping (KotlinBoolean?, Error?) -> Void) {
        completionHandler(KotlinBoolean(bool: connected), nil)
    }
}

private final class StubFileSystem: NSObject, FileSystem {
    private let availableBytes: Int64
    private(set) var deletedPaths: [String] = []

    init(availableBytes: Int64) { self.availableBytes = availableBytes }

    func delete(path: String) -> KotlinBoolean {
        deletedPaths.append(path); return KotlinBoolean(bool: true)
    }

    func exists(path: String) -> KotlinBoolean {
        KotlinBoolean(bool: !deletedPaths.contains(path))
    }

    func getFileSize(path: String) -> KotlinLong { KotlinLong(value: 0) }

    func getAvailableStorageBytes() -> KotlinLong { KotlinLong(value: availableBytes) }
}
