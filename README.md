# Smart Agent - Dev

Smart Agent is a Kotlin Multiplatform (KMP) mobile application targeting **Android** and **iOS**.  
Built with Compose Multiplatform for the UI layer and following an **MVI + offline-first** architecture with a single source of truth driven by a local SQLDelight database.

The core feature is a **survey response sync engine** — farmers fill in surveys offline and the app reliably uploads them when connectivity is restored, handling retries, attachment uploads, and storage constraints automatically.

---

## Project structure

| Module | Purpose |
|---|---|
| [`composeApp`](./composeApp/src) | Android & shared UI — Compose Multiplatform screens, ViewModels, MVI state |
| [`shared`](./shared/src) | Pure Kotlin business logic shared across all platforms (domain, data, db, core, platform) |
| [`iosApp`](./iosApp/iosApp) | iOS entry point — SwiftUI host that loads the shared Compose UI |

---

## Build and run

### Android
```shell
./gradlew :composeApp:assembleDebug
```

### iOS
Open [`/iosApp`](./iosApp) in Xcode and run, or use the IDE run configuration.

---

## Module / package structure

All shared business logic lives under:
```
shared/src/commonMain/kotlin/com/jonesmb/pulasmartagent/
```
Matching tests live under:
```
shared/src/commonTest/kotlin/com/jonesmb/pulasmartagent/
```
Platform implementations (`androidMain` / `iosMain`) fulfill `expect` declarations from `commonMain`.

```
com.jonesmb.pulasmartagent
│
├── domain/                        # Pure business logic — zero Android/iOS imports
│   ├── model/
│   │   ├── SurveyResponse         # Root aggregate: farmerId, nodes, attachments, SyncStatus
│   │   ├── ResponseNode           # Sealed: Answer | RepeatingSection (arbitrary nesting)
│   │   ├── Attachment             # Photo/file with upload lifecycle (retryCount, lastError)
│   │   ├── SyncResult             # Outcome of one sync run (succeededIds, failedIds, stoppedReason)
│   │   ├── GpsCoordinate          # Single GPS reading: lat, lng, accuracyMetres, capturedAt
│   │   ├── FieldBoundary          # Validated polygon: ordered corners + meanAccuracyMetres
│   │   └── status/
│   │       ├── SyncStatus         # PENDING | IN_PROGRESS | SYNCED | FAILED
│   │       └── AttachmentUploadStatus  # PENDING | UPLOADING | UPLOADED | FAILED
│   ├── errors/
│   │   └── SyncError              # Sealed: NoInternet | Timeout | ServerError(code) | SerializationError | Unknown
│   │                              # Each variant carries isRetriable: Boolean
│   ├── gps/
│   │   └── GpsBoundaryCapture     # AccuracyGate → StabilityBuffer → PolygonValidator → FieldBoundary
│   └── repository/
│       ├── SurveyRepository       # Interface: save, getPending, markSynced/Failed, retry ops
│       └── AttachmentRepository   # Interface: markUploaded/Failed, incrementRetry, getUploaded
│
├── data/                          # Implementations that satisfy domain contracts
│   ├── repository/
│   │   ├── SurveyRepositoryImpl   # SQLDelight-backed; getPending filters PENDING|FAILED + retryCount < MAX
│   │   └── AttachmentRepositoryImpl
│   ├── sync/
│   │   └── SurveySyncEngine       # Mutex-guarded sync loop; storage pre-flight check;
│   │                              # per-survey: meta upload → attachment upload → markSynced
│   │                              # stop rules: NetworkLost on IOException/Timeout,
│   │                              #             continue on 500+, FatalError on unknown
│   ├── network/
│   │   └── SurveyApi              # Interface: uploadSurvey(SurveyResponse), uploadAttachment(Attachment)
│   └── attachments/
│       └── AttachmentManager      # Deletes local files post-upload when StoragePolicy allows
│
├── db/                            # SQLDelight database layer
│   ├── driver/
│   │   └── DriverFactory          # expect/actual — creates SqlDriver per platform
│   └── adapters/
│       ├── EnumAdapters           # Column adapters: SyncStatus/AttachmentUploadStatus ↔ TEXT
│       └── InstantAdapter         # kotlinx-datetime Instant ↔ INTEGER (epoch ms)
│
├── core/                          # Cross-cutting utilities with no domain dependencies
│   ├── constants/
│   │   ├── Constants              # NODE_TYPE_ANSWER, NODE_TYPE_REPEATING_SECTION
│   │   │                          # Constants.Gps: MAX_ACCURACY_METRES, STABILITY_WINDOW_SIZE, CLUSTER_RADIUS_METRES
│   │   └── StoragePolicy          # MIN_REQUIRED_FREE_SPACE_BYTES, MAX_SURVEY_RETRY,
│   │                              # MAX_ATTACHMENT_RETRY, AUTO_DELETE_AFTER_UPLOAD
│   ├── extensions/
│   │   ├── ExceptionMapper        # Throwable.toSyncError(): maps IOException/Timeout/HttpException → SyncError
│   │   └── SyncErrorExt           # SyncError.toDbString() for persistence
│   ├── util/
│   │   └── GeoUtils               # haversineMetres(): straight-line distance in metres (Haversine, pure Kotlin)
│   └── network/
│       ├── HttpException          # Platform-agnostic HTTP error carrying response code
│       ├── SyncErrorException     # Bridges typed SyncError into Throwable for Result.failure
│       └── IOExceptionCheck       # expect fun Throwable.isIOException() — platform-specific detection
│
└── platform/                      # expect interfaces; actuals in androidMain / iosMain
    ├── network/
    │   └── NetworkMonitor         # isConnected(): Boolean
    └── filesystem/
        └── FileSystem             # delete, exists, getFileSize, getAvailableStorageBytes
```

> **Dependency rule:**  
> `domain` → no imports from anywhere in this project.  
> `data` → may import `domain`, `db`, `core`.  
> `core` → may import `domain` only.  
> `platform` → `commonMain` holds only `expect` interfaces; actuals live in `androidMain` / `iosMain`.

---

## Platform implementations

| Interface | Android | iOS |
|---|---|---|
| `NetworkMonitor` | `AndroidNetworkMonitor` — `ConnectivityManager` + `NET_CAPABILITY_INTERNET` | `IosNetworkMonitor` — `NWPathMonitor` |
| `FileSystem` | `AndroidFileSystem` — `java.io.File`, `usableSpace` from app files dir | `IosFileSystem` — `NSFileManager` |
| `DriverFactory` | `AndroidSqliteDriver` | `NativeSqliteDriver` |
| `IOExceptionCheck` | checks `java.io.IOException` | checks `NSURLErrorDomain` / POSIX errors |

---

## Sync engine behaviour

```
sync()
 ├── storage pre-flight: availableBytes < 100 MB  → SyncResult(LowStorage)
 ├── for each PENDING/FAILED survey (retryCount < MAX_SURVEY_RETRY):
 │    ├── network check                            → stop NetworkLost
 │    ├── uploadSurvey()
 │    │    ├── success                             → proceed to attachments
 │    │    ├── IOException / Timeout               → markFailed, stop NetworkLost
 │    │    ├── ServerError 400-499                 → markFailed, pinRetryToMax, continue
 │    │    ├── ServerError 500+                    → markFailed, incrementRetry, continue
 │    │    └── Unknown                             → markFailed, stop FatalError
 │    ├── for each attachment:
 │    │    ├── network check                       → stop NetworkLost
 │    │    ├── uploadAttachment()
 │    │    │    ├── success                        → markUploaded, delete local file if policy
 │    │    │    ├── IOException / Timeout          → markFailed, stop NetworkLost
 │    │    │    ├── ServerError 400-499            → markFailed, stop FatalError
 │    │    │    └── ServerError 500+               → markFailed, incrementRetry, continue
 │    └── markAsSynced
 └── SyncResult(succeededIds, failedIds, stoppedReason)
```

---

## Tests

| Test file | Covers |
|---|---|
| `SurveySyncEngineTest` | 16 scenarios: all-succeed, partial 500/400, timeout/IOException early stop, concurrent mutex, LowStorage pre-flight, attachment upload + delete, retriable/fatal attachment errors, partial attachment success |
| `GpsBoundaryCaptureTest` | 16 scenarios: AccuracyGate accept/reject, StabilityBuffer window fill / spread rejection / centroid average, PolygonValidator triangle / collinear / self-intersection, full end-to-end capture flow, noisy-ping isolation, meanAccuracyMetres averaging, haversineMetres distance |
| `SyncErrorTest` | `isRetriable` correctness for every `SyncError` variant |
| `SurveyResponseTest` | Domain model construction and node tree |
| `SyncResultTest` | `SyncStopReason` variants |

Test infrastructure (all in `commonTest`):

| Fake | Purpose |
|---|---|
| `FakeSurveyApi` | Lambda-per-call-count for survey and attachment responses; simulates Success / ServerError / Timeout / NetworkLost / Unknown |
| `FakeSurveyRepository` | In-memory store; mirrors `getPendingSurveys` filter (PENDING\|FAILED + retryCount < MAX) |
| `FakeAttachmentRepository` | Records uploaded/failed/retried attachment ids |
| `FakeNetworkMonitor` | Fixed `isConnected` boolean |
| `FakeFileSystem` | Records `deleted` paths; `exists()` returns false for deleted paths |

---

## Branching strategy

We use an explicit promotion pipeline to keep releases predictable:

- All fixes and feature PRs merge into `dev`
- Promote to staging via a bridge branch: `deploy/dev-to-staging` → merge into `staging`
- Promote to production via a bridge branch: `deploy/staging-to-prod` → merge into `prod`

Illustration:

```text
feature/*   fix/*
   \         /
    \       /
     v     v
      dev
       |
       |  (bridge)
       v
deploy/dev-to-staging  --->  staging
                               |
                               |  (bridge)
                               v
                     deploy/staging-to-prod  --->  prod
```

### Versioning

We use SemVer-style versions: `MAJOR.MINOR.PATCH`.

- `dev` may include a pre-release suffix (e.g. `1.0.1-dev`).
- `staging` uses a staging suffix (e.g. `1.0.1-staging`) when doing release verification.
- `prod` uses the clean release version (e.g. `1.0.1`).

Build numbers must be monotonically increasing per platform:

- Android: increment `versionCode` for every staged/prod build.
- iOS: increment `CURRENT_PROJECT_VERSION` for every staged/prod build; keep `MARKETING_VERSION` in sync with Android’s `versionName`.
