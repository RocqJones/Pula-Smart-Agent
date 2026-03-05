# Architecture

## 1. Architecture Overview

Smart Agent is a Kotlin Multiplatform Mobile (KMM) app using a clean, layered design so sync logic is shared across Android and iOS. Platform-specific concerns (connectivity, filesystem, DB driver) are pushed behind `expect/actual` abstractions.

Goal: offline-first on unreliable connectivity and low-end devices (limited RAM/storage).

### High-level diagram

```
              +--------------------+
              |   composeApp (UI)  |
              |  MVI state + UI    |
              +----------+---------+
                         |
                         v
+---------------------------------------------------+
|                 shared (commonMain)               |
|                                                   |
|  domain/  <- models + rules + repo interfaces      |
|     ^                                             |
|     |                                             |
|  data/    <- SurveySyncEngine + repository impls   |
|     |            |                                |
|     |            +--> SurveyApi (network contract) |
|     |            +--> SQLDelight repositories      |
|     |            +--> FileSystem (expect)         |
|     v                                             |
|  db/     <- SQLDelight schema + adapters           |
|                                                   |
|  platform/ <- NetworkMonitor + FileSystem (expect) |
+-------------------+-------------------------------+
                    | actual implementations
        +-----------+------------+     +------------+-----------+
        |   androidMain          |     |    iosMain             |
        | ConnectivityManager    |     | NWPathMonitor          |
        | java.io.File           |     | NSFileManager          |
        | AndroidSqliteDriver    |     | NativeSqliteDriver     |
        +------------------------+     +------------------------+
```

Layers:

- `domain/` — pure models + contracts
- `data/` — sync engine, repository implementations, API contracts
- `db/` — SQLDelight database layer
- `platform/` — KMP abstractions implemented per platform

## 2. Domain Layer

Domain defines the portable business model:

- `SurveyResponse` (farmerId, nodes, attachments, `SyncStatus`, retryCount)
- `ResponseNode` sealed tree: `Answer` and `RepeatingSection` (dynamic instances)
- `Attachment` (localPath, size, upload status, retryCount, lastError)
- `SyncError` sealed error model with `isRetriable`

No database or platform dependencies, keeping the layer testable and reusable.

## 3. Data Layer and Sync Engine

`SurveySyncEngine` orchestrates uploads:

1. Pre-flight storage check via `FileSystem.getAvailableStorageBytes()`
2. Fetch pending work from `SurveyRepository`
3. For each survey: upload metadata → upload attachments sequentially → mark synced

Progress is persisted immediately (no rollback). A `Mutex` ensures only one sync run executes at a time.

Error rules (survey metadata upload):

- `IOException` / `TimeoutCancellationException` → mark FAILED, stop queue: `NetworkLost`
- HTTP 500+ → mark FAILED, increment retry, continue
- HTTP 400–499 → mark FAILED, pin retry count to max, continue
- unknown/fatal → stop queue: `FatalError`

Sync outcomes are reported via `SyncResult` (succeededIds, failedIds, stoppedReason).

## 4. Local Persistence

SQLDelight tables:

- `survey_response`
- `response_node` (flat rows + `parent_node_id` for nested/repeating sections)
- `attachment`

`SurveyRepositoryImpl` reconstructs the `ResponseNode` tree from `parent_node_id`. `getPendingSurveys()` returns only `PENDING` or `FAILED` items with `retryCount < MAX_SURVEY_RETRY` to avoid infinite retries.

## 5. Media Attachment Handling and Storage Management

Attachments are linked by `surveyId`. During sync:

- upload attachments after metadata
- on success: mark UPLOADED
- if `AUTO_DELETE_AFTER_UPLOAD` is enabled: delete local file to prevent storage growth

Sync also stops early with `LowStorage` when free space is below `StoragePolicy.MIN_REQUIRED_FREE_SPACE_BYTES`.

## 6. Network Monitoring Strategy

`NetworkMonitor` is a shared abstraction. Android uses `ConnectivityManager`; iOS uses `NWPathMonitor`. The engine checks connectivity before uploads and maps runtime exceptions to `SyncError` to stop early on degraded networks.

## 7. Testing Strategy

Tests focus on deterministic sync behavior:

- full success
- partial failures (400/500)
- timeout/IO early-stop (queue stops, remaining items not attempted)
- low-storage preflight (no API calls)
- attachments uploaded then deleted
- concurrency (Mutex prevents double execution)

Fakes (`FakeSurveyApi`, `FakeNetworkMonitor`, `FakeFileSystem`) make edge cases repeatable.

## 8. Future Improvements

Photo compression, scheduled background sync (WorkManager/BGTaskScheduler), richer diagnostics, and adaptive policies (battery/network type).
