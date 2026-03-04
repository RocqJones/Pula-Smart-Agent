# Smart Agent

Smart Agent is a Kotlin Multiplatform (KMP) mobile application targeting **Android** and **iOS**.  
Built with Compose Multiplatform for the UI layer and following an **MVI + offline-first** architecture with a single source of truth driven by a local database.

## Project structure

| Module | Purpose |
|---|---|
| [`composeApp`](./composeApp/src) | Android & shared UI — Compose Multiplatform screens, ViewModels, MVI state |
| [`shared`](./shared/src) | Pure Kotlin business logic shared across all platforms (domain, data, db, platform) |
| [`iosApp`](./iosApp/iosApp) | iOS entry point — SwiftUI host that loads the shared Compose UI |

### Build and Run Android Application

To build and run the development version of the Android app, use the run configuration from the run widget
in your IDE’s toolbar or build it directly from the terminal:
- on macOS/Linux
  ```shell
  ./gradlew :composeApp:assembleDebug
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:assembleDebug
  ```

### Build and Run iOS Application

To build and run the development version of the iOS app, use the run configuration from the run widget
in your IDE’s toolbar or open the [/iosApp](./iosApp) directory in Xcode and run it from there.

---

## Module / package structure

All shared business logic lives under `shared/src/commonMain/kotlin/com/jonesmb/pulasmartagent/`.  
Matching test packages live under `shared/src/commonTest/kotlin/com/jonesmb/pulasmartagent/`.

```
com.jonesmb.pulasmartagent
│
├── domain/                   # Pure business logic — no Android/iOS imports
│   ├── model/                # Core data classes & entities (e.g. Survey, Field, Answer)
│   ├── errors/               # Sealed domain error/exception hierarchy
│   └── time/                 # Platform-agnostic date/time wrappers and utilities
│
├── data/                     # Implementations that satisfy domain contracts
│   ├── repository/           # Repository implementations (coordinate db + network)
│   ├── sync/                 # Offline-first sync logic (conflict resolution, work queue)
│   ├── network/              # API client interfaces & DTOs (Ktor, serialization)
│   ├── attachments/          # Photo / file upload & local caching strategies
│   └── diagnostics/          # Logging, analytics events, crash-report helpers
│
├── db/                       # SQLDelight (or equivalent) database layer
│   ├── driver/               # expect/actual DatabaseDriver factory per platform
│   └── adapters/             # Column adapters (e.g. enum ↔ TEXT, Instant ↔ INTEGER)
│
└── platform/                 # expect declarations fulfilled by androidMain / iosMain
    ├── network/              # Connectivity checks, reachability
    ├── filesystem/           # File paths, caching directories, read/write helpers
    └── devicestate/          # Battery, storage, locale — anything device-specific
```

> **Rule of thumb:**  
> `domain` must never import from `data`, `db`, or `platform`.  
> `data` may import `domain` and `db`.  
> `platform` packages contain only `expect` interfaces in `commonMain`; the actual implementations live in `androidMain` and `iosMain`.

---
