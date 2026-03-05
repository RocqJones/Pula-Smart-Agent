# Architecture

## Overview

Smart Agent uses a clean, layered KMM architecture so core sync logic runs identically on Android and iOS. Platform concerns (connectivity, filesystem, DB driver) sit behind `expect/actual` abstractions.

```
  composeApp (UI / MVI)
         |
  shared (commonMain)
  ├── domain/    ← pure models, no platform imports
  ├── data/      ← SurveySyncEngine, repositories, SurveyApi
  ├── db/        ← SQLDelight schema + adapters
  └── platform/  ← NetworkMonitor, FileSystem (expect)
         |
  androidMain          iosMain
  ConnectivityManager  NWPathMonitor
  java.io.File         NSFileManager
  AndroidSqliteDriver  NativeSqliteDriver
```

## Why this architecture? Alternatives considered

Clean architecture was chosen so the sync engine can be tested without a device, a database, or a real server. The strict dependency rule — domain knows nothing about storage or network — makes error classification and retry policies easy to verify in isolation.

**Alternatives considered:**

- **Room + ViewModel (Android-only):** Locks business logic to Android. KMM avoids rewriting the retry policy and error model twice for an iOS port.
- **WorkManager-first:** WorkManager schedules work well but doesn't own upload logic. Building the engine first keeps it testable; WorkManager can wrap it later.
- **Event sourcing / outbox pattern:** Overkill here. A retry-count cap gives sufficient durability without the operational complexity.

## Photo compression extension

A `compressPhoto` `expect` function is defined in `commonMain` with JPEG implementations in `androidMain` (`Bitmap.compress`) and `iosMain` (`UIImage.jpegData`). Compression runs in `AttachmentManager` before upload, writing to a temp path. The original is preserved until upload succeeds, then both files are deleted. `CompressionPolicy.JPEG_QUALITY` and `MAX_DIMENSION_PX` tune the output without code changes.

## Network detection — where it can go wrong

`ConnectivityManager` reports connected the moment a network interface is active. A device behind a captive portal or a router with no upstream internet appears online — the engine attempts uploads, receives `IOException`s, and stops the queue as `NetworkLost`. This is a false negative. **Mitigation:** perform a lightweight HTTPS HEAD probe to the API base URL before starting a sync batch. One small request converts the binary connected/not-connected signal into confirmed-reachable.

## Remote troubleshooting without device access

The `DiagnosticsReport` domain model captures what support needs:

- per-attempt log: `surveyId`, timestamp, HTTP status, exception type, `retryCount`, stop reason
- device context: Android API level, free storage bytes, network type, battery percent
- `last_error` is already persisted per survey and attachment in SQLDelight

A lightweight JSON payload assembled from the local DB can be sent as a low-priority upload when connectivity is restored — no third-party SDK required.

## GPS and geospatial challenges

Rural Sub-Saharan Africa presents specific problems for field boundary capture:

- Canopy and valley terrain can push horizontal GPS accuracy to 10–50m. Boundaries may not close or may overlap adjacent fields.
- **Accuracy gate:** each vertex should include the OS-reported `accuracy` value; points above a threshold (e.g. 15m) are flagged or rejected. Requiring 5 stable consecutive fixes within 5m of each other before accepting a vertex reduces drift.
- **Polygon validity:** minimum 3 vertices, closure within tolerance, no self-intersection — checked locally before saving.
- **Battery:** continuous GPS drains low-end devices fast. A 2s duty-cycle (poll while capturing, suspend otherwise) cuts drain significantly.

## Testing strategy

Fakes (`FakeSurveyApi`, `FakeNetworkMonitor`, `FakeFileSystem`) make every edge case deterministic. Tests cover: all-succeed, partial 500/400, timeout/IO early stop, empty queue, LowStorage preflight, attachment upload and deletion, fatal attachment error, partial attachment success, concurrent sync (Mutex), and exception mapping (every `Throwable` type → correct `SyncError`). The real SQLDelight driver is used in `SurveyRepositoryImplTest` to validate save, retrieve, status transitions, and retry counts against an in-memory database.

## One thing I'd do differently with more time

The engine processes surveys sequentially. On Wi-Fi with a full battery this leaves performance on the table. I would add a `Semaphore(N)`-bounded parallel upload path, selectable at construction time, while keeping sequential as the default. `SyncResult` already collects IDs independently of order, so no structural change is needed.

## Future improvements

WorkManager/BGTaskScheduler scheduling, and device-aware batch sizing (adapt concurrency based on network type and battery level). Sync progress is already exposed via `SurveySyncEngine.progress: StateFlow<SyncProgress?>` for a UI "Uploading 3 of 8…" indicator.
