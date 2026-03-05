import XCTest
import Shared

private enum KMP {
    static var defaultDispatcher: Kotlinx_coroutines_coreCoroutineDispatcher {
        Kotlinx_coroutines_coreDispatchers.shared.Default
    }

    static func instant(_ isoString: String) -> Kotlinx_datetimeInstant {
        Kotlinx_datetimeInstant.companion.fromEpochMilliseconds(epochMilliseconds: 1_741_075_200_000)
    }

    static func httpError(code: Int32) -> NSError {
        NSError(domain: "HttpException", code: Int(code))
    }
    static func noInternetError() -> NSError { NSError(domain: "SyncError", code: 1) }
    static func timeoutError()    -> NSError { NSError(domain: "SyncError", code: 2) }
    static func unknownError()    -> NSError { NSError(domain: "SyncError", code: 3) }
}

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
            dispatcher: KMP.defaultDispatcher
        )
    }

    private func survey(id: String, attachments: [Shared.Attachment] = []) -> Shared.SurveyResponse {
        Shared.SurveyResponse(
            id: id,
            farmerId: "farmer-1",
            createdAt: KMP.instant("2026-03-04T08:00:00Z"),
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
            createdAt: KMP.instant("2026-03-04T08:00:00Z"),
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
            dispatcher: KMP.defaultDispatcher
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
        defer { job.cancel(cause: nil) }

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

    @objc(uploadSurveyResponse:completionHandler:)
    func uploadSurvey(response: SurveyResponse, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        resolve(behavior(surveyCallCount), completionHandler); surveyCallCount += 1
    }

    @objc(uploadAttachmentAttachment:completionHandler:)
    func uploadAttachment(attachment: Attachment, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        resolve(behavior(attachCallCount), completionHandler); attachCallCount += 1
    }

    private func resolve(_ r: FakeApiResponse, _ cb: (KotlinUnit?, Error?) -> Void) {
        switch r {
        case .success:               cb(KotlinUnit(), nil)
        case .serverError(let code): cb(nil, KMP.httpError(code: code))
        case .networkLost:           cb(nil, KMP.noInternetError())
        case .timeout:               cb(nil, KMP.timeoutError())
        case .unknown:               cb(nil, KMP.unknownError())
        }
    }
}

private final class FakeRepository: NSObject, SurveyRepository {
    private var surveys: [SurveyResponse]
    private(set) var syncedIds: [String] = []

    init(surveys: [SurveyResponse]) { self.surveys = surveys }

    @objc(getPendingSurveysWithCompletionHandler:)
    func getPendingSurveys(completionHandler: @escaping ([SurveyResponse]?, Error?) -> Void) {
        let pending = surveys.filter {
            ($0.status == SyncStatus.pending || $0.status == SyncStatus.failed) && $0.retryCount < 5
        }
        completionHandler(pending, nil)
    }

    @objc(saveSurveyResponse:completionHandler:)
    func saveSurvey(response: SurveyResponse, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        surveys.append(response); completionHandler(KotlinUnit(), nil)
    }

    @objc(markAsSyncedId:completionHandler:)
    func markAsSynced(id: String, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        syncedIds.append(id); completionHandler(KotlinUnit(), nil)
    }

    @objc(markAsFailedId:error:completionHandler:)
    func markAsFailed(id: String, error: SyncError, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        completionHandler(KotlinUnit(), nil)
    }

    @objc(incrementRetryId:completionHandler:)
    func incrementRetry(id: String, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        completionHandler(KotlinUnit(), nil)
    }

    @objc(pinRetryToMaxId:completionHandler:)
    func pinRetryToMax(id: String, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        completionHandler(KotlinUnit(), nil)
    }
}

private final class FakeAttachmentRepo: NSObject, AttachmentRepository {
    private(set) var uploadedIds: [String] = []

    @objc(getUploadedAttachmentsWithCompletionHandler:)
    func getUploadedAttachments(completionHandler: @escaping ([Attachment]?, Error?) -> Void) {
        completionHandler([], nil)
    }

    @objc(markAsUploadedId:completionHandler:)
    func markAsUploaded(id: String, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        uploadedIds.append(id); completionHandler(KotlinUnit(), nil)
    }

    @objc(markAsFailedId:error:completionHandler:)
    func markAsFailed(id: String, error: SyncError, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        completionHandler(KotlinUnit(), nil)
    }

    @objc(incrementRetryId:completionHandler:)
    func incrementRetry(id: String, completionHandler: @escaping (KotlinUnit?, Error?) -> Void) {
        completionHandler(KotlinUnit(), nil)
    }
}

private final class StubNetworkMonitor: NSObject, NetworkMonitor {
    private let connected: Bool
    init(connected: Bool) { self.connected = connected }

    @objc(isConnectedWithCompletionHandler:)
    func isConnected(completionHandler: @escaping (KotlinBoolean?, Error?) -> Void) {
        completionHandler(KotlinBoolean(bool: connected), nil)
    }
}

private final class StubFileSystem: NSObject, FileSystem {
    private let availableBytes: Int64
    private(set) var deletedPaths: [String] = []

    init(availableBytes: Int64) { self.availableBytes = availableBytes }

    func delete(path: String) -> Bool { deletedPaths.append(path); return true }
    func exists(path: String) -> Bool { !deletedPaths.contains(path) }
    func getFileSize(path: String) -> Int64 { 0 }
    func getAvailableStorageBytes() -> Int64 { availableBytes }
}
