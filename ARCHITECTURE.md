# Architecture


```
  composeApp (UI / MVI)
         |
  shared (commonMain)
  ├── domain/    ← pure models, no platform imports
  ├── data/      ← SurveySyncEngine, repositories, SurveyApi
  ├── db/        ← SQLDelight schema + adapters
  └── platform/  ← NetworkMonitor, FileSystem (expect)
         |
  androidMain               iosMain
  ConnectivityManager       NWPathMonitor
  AndroidSqliteDriver       NativeSqliteDriver
  java.io.File              NSFileManager
```

## Architecture choice and alternatives considered

- KMM with clean architecture — the sync engine is tested with plain Kotlin fakes, no device, emulator, or real server needed
- Every retry rule, error classification, and storage policy lives in `commonMain` and runs identically on both platforms
- **SQLDelight** over Room: Room is Android-only; SQLDelight generates type-safe multiplatform Kotlin from a single schema; `InstantAdapter` and `EnumAdapters` map non-trivial types at the DB boundary — no TypeConverter workarounds
- **Alternative considered:** Android-only coroutine work queue — simpler, but the sync state machine would be untestable without Android instrumentation and unshareable with iOS

## Scenario solutions

**Scenario 1 — Offline storage:**
- `SurveyResponse` is persisted immediately on submit
- `response_node` uses `parent_node_id` to represent dynamic repeating sections — a farmer with 3 farms produces one `RepeatingSection` row and one child per instance, reconstructed as a sealed `ResponseNode` tree on read
- `getPendingSurveys()` filters `PENDING | FAILED` with `retryCount < MAX_SURVEY_RETRY` to exclude exhausted surveys
- `StoragePolicy.AUTO_DELETE_AFTER_UPLOAD` removes local files after a confirmed upload, preventing storage growth on 16–32 GB devices

**Scenario 2 — Partial failure:**
- Each survey is persisted immediately after its attempt — no batch transaction
- `SyncResult` returns explicit `succeededIds` and `failedIds`
- On the next sync, only `PENDING` or `FAILED` surveys below the retry cap are returned; `SYNCED` surveys are never re-uploaded

**Scenario 3 — Network degradation:**
- `IOException` and `TimeoutCancellationException` map to `SyncError.NoInternet` / `SyncError.Timeout` and immediately stop the queue with `SyncStopReason.NetworkLost` — preserving battery and data quota
- `ServerError` 500+ increments retry and continues; 400–499 pins retry count to max so the request is permanently excluded

**Scenario 4 — Concurrent sync prevention:**
- A `Mutex` in `SurveySyncEngine` ensures the second `sync()` call suspends until the first completes
- The second run operates on an already-updated database — no duplicate uploads or state corruption

**Scenario 5 — Error model:**
- `Throwable.toSyncError()` maps every failure to a sealed `SyncError` with `isRetriable`
- `HttpException` 4xx → non-retriable `ServerError`; 5xx → retriable `ServerError`
- `TimeoutCancellationException` → `Timeout`; `IOException` → `NoInternet`; unknown → `Unknown`
- Serialization failures are wrapped as `SyncErrorException(SyncError.SerializationError)` at the network boundary

## Photo compression extension

- `compressPhoto` is an `expect` function in `commonMain` with `androidMain` (`Bitmap.compress` + `Matrix` scaling) and `iosMain` (`UIImage` + `UIImageJPEGRepresentation`) actuals
- `CompressionPolicy.JPEG_QUALITY` and `MAX_DIMENSION_PX` tune output without code changes
- The original file is deleted only after a confirmed successful upload

## Network detection — where it can go wrong

- `ConnectivityManager` reports connected the moment a network interface is active — a device behind a captive portal appears online
- The engine attempts uploads, receives `IOException`s, and stops the queue as `NetworkLost` (false negative)
- **Mitigation:** an HTTPS HEAD probe to the API base URL before each sync batch confirms actual server reachability, not just interface availability

## Remote troubleshooting without device access

- `DiagnosticsReport` captures per-attempt logs: `surveyId`, HTTP status, exception type, `retryCount`, stop reason
- Device context logged: API level, free storage, network type, battery
- `last_error` is already persisted per survey and attachment in SQLDelight
- A JSON payload from the local DB is sent as a low-priority background upload — no third-party SDK required

## GPS and geospatial challenges

**Challenges:**
- Tree cover and valleys degrade GPS accuracy to 10–50 m — boundaries may not close or overlap adjacent fields
- Budget devices use basic GPS chips with no atmospheric error correction
- Continuous GPS polling drains battery; agents may have no charging access for the rest of the day

**Workable solution:**

```
GpsBoundaryCapture
├── AccuracyGate      — drops any reading where accuracyMetres > 15 m (Constants.Gps.MAX_ACCURACY_METRES)
├── StabilityBuffer   — collects 5 consecutive pings; accepts corner only when all cluster within 5 m
│                       centroid computed via haversineMetres() in core/util/GeoUtils
├── PolygonValidator  — rejects < 3 corners, collinear points (Shoelace area = 0), self-intersections
└── FieldBoundary     — saved as (lat, lng, accuracyMetres, capturedAt) per corner + meanAccuracyMetres
```

- **AccuracyGate** — drops any fix with error radius > 15 m before it can become a corner
- **StabilityBuffer** — waits for 5 pings clustering within 5 m; saves the centroid as the corner, not the first available reading
- **PolygonValidator** — validates on-device before saving; rejects unclosed or self-crossing boundaries with a prompt to re-walk
- **2-second ping interval** — halves battery drain vs. continuous polling with no accuracy loss at walking pace
- Each corner stores its accuracy value so the backend can flag or re-request low-quality boundaries

## One thing I'd do differently with more time

- Add `Semaphore(N)`-bounded parallel uploads, selectable at construction, for Wi-Fi conditions
- `SyncResult` already collects IDs independently of order — only the loop strategy changes, no structural rewrite needed
- Expose a `Flow<SyncProgress>` from `SurveySyncEngine` so the UI layer can show live upload progress (e.g. "Uploading 3 of 8") without polling the database

## Blocked: Swift sync engine (async/await + actors)

- Attempted as a bonus; PR open at **https://github.com/RocqJones/Pula-Smart-Agent/pull/10**
- Blocked by KMM-to-Swift bridging issues: sealed subclasses not visible as Swift types, `KotlinThrowable` not conforming to Swift `Error`, and coroutine dispatchers not accessible from Swift
- The Kotlin sync engine remains fully covered by `commonMain` unit tests on both platforms
- With more time: add a thin Kotlin wrapper that re-exposes sealed subclasses via `@ObjCName` and a Swift-native `Error` enum mapping to `SyncError` to unblock the test suite

