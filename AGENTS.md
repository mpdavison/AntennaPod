# General Instructions
The following instructions are vital, always follow them.
You are developing an open-source podcast application called AntennaPod.
STRICTLY FOLLOW THE INSTRUCTIONS IN THIS FILE! NEVER DEVIATE FROM THEM.
If this helps you, consider repeating the relevant instructions before you do anything.
Always prefer tool use over shell commands. This is very important to avoid unnecessary user confirmations.
If you have to use shell commands, prefer dedicated tools (such as `jq` for json) instead of custom (python, etc) code.
Never read from or write to any path outside of the /project folder. Use /project/tmp for any temporary files.

# Architecture
AntennaPod uses a highly modularized Gradle architecture with modules organized by domain.
Each module is stored in a folder of the same name (for example `:net:discovery` in `./net/discovery`)
and contains a `README.md` file with a brief explanation of the module's purpose and internal structure.
Several functional areas follow a service-interface/service split: the interface module is depended on by consumers, and the implementation is registered at app startup via `ClientConfigurator`.
- `:app` - Main application module that integrates all features
- `:event` - EventBus events used for cross-component communication throughout the app
- `:model` - Core data classes such as `Feed`, `FeedItem`, `FeedMedia`, and `Chapter`
- `:system` - System integration utilities such as crash reporting, package utilities, and thread utilities
- `:net:common` - General network-related utilities shared across net modules
- `:net:discovery` - Podcast search and discovery APIs
- `:net:download:service-interface` - Interface for starting the download service, allowing other modules to trigger downloads without depending on the implementation
- `:net:download:service` - Implementation of the download service
- `:net:ssl` - SSL backports and security provider implementations
- `:net:sync:gpoddernet` - Sync backend for the open-source Gpodder.net podcast synchronization service
- `:net:sync:service-interface` - Interface for starting the sync service
- `:net:sync:service` - Implementation of the sync service
- `:parser:feed` - XML feed parser
- `:parser:media` - Tag parser for media files including ID3 and ogg/vorbis
- `:parser:transcript` - Parser for episode transcripts
- `:playback:base` - Basic interfaces for the `PlaybackServiceMediaPlayer`
- `:playback:cast` - Chromecast support for the Google Play version of the app
- `:playback:service` - Main service responsible for media playback
- `:storage:database` - Main database containing subscriptions and playback state
- `:storage:database-maintenance-service` - Periodic background tasks to clean up the database
- `:storage:importexport` - Import and export of the AntennaPod database
- `:storage:preferences` - User settings storage (not including the settings UI)
- `:ui:app-start-intent` - Classes to start main app activities from other modules without a direct UI dependency
- `:ui:chapters` - Chapter loading and merging logic for display
- `:ui:common` - Basic UI functionality shared across multiple modules
- `:ui:discovery` - Screens to discover and search for new podcasts
- `:ui:echo` - The "Echo" yearly rewind screen
- `:ui:episodes` - Common classes for displaying episode information
- `:ui:glide` - Glide image loading library configuration and custom model loaders
- `:ui:i18n` - Translated strings and internationalization resources
- `:ui:notifications` - Generic notification channel IDs and notification icons
- `:ui:preferences` - Settings screen UI
- `:ui:statistics` - Statistics screens
- `:ui:transcript` - Utilities for displaying episode transcripts in the UI
- `:ui:widget` - Home screen widget

# Application Architecture & Control Flow

## Entry Point & Initialization
- **Application class**: `PodcastApp` (`app/src/main/java/de/danoeh/antennapod/PodcastApp.java`)
- **Launcher activity**: `SplashActivity` (declared in manifest as LAUNCHER), forwards to `MainActivity`
- **Main Activity**: `MainActivity` (`activity/MainActivity.java`, extends `CastEnabledActivity`)
- **Initialization**: `PodcastApp.onCreate()` → sets up `CrashReportExceptionHandler`, `RxJavaErrorHandlerSetup`, GreenRobot `EventBus` with annotation index (`ApEventBusIndex`), `DynamicColors`, then calls `ClientConfigurator.initialize(this)`
- **ClientConfigurator** (`ClientConfigurator.java`) wires all service implementations via static setters, initializes `PodDBAdapter`, `UserPreferences`, `AdDetectionPreferences`, notification channels, OkHttp cache, and SSL provider

## Dependency Injection
- **No Dagger/Hilt**. Uses a **manual service locator pattern** with static `setImpl()`/`get()` on abstract classes in `*-interface` modules
- Example: `DownloadServiceInterface.get().downloadNow(context, item, ignoreConstraints)`
- Interface modules have a `Stub` implementation (no-ops) for fallback
- Real implementations are registered in `ClientConfigurator.initialize()`

## Cross-Component Communication
- **GreenRobot EventBus** (not LiveData/Flow) for decoupled messaging between components
- Events are lightweight POJOs in the `:event` module (e.g., `FeedEvent`, `QueueEvent`, `PlayerStatusEvent`, `PlaybackPositionEvent`)
- Subscribers use `@Subscribe(threadMode = ThreadMode.MAIN)` annotations
- EventBus index (`ApEventBusIndex`) is generated at compile time via annotation processor
- Register in `onStart()`/`onResume()`, unregister in `onStop()`/`onPause()`

## Async Processing
- **RxJava3** (`Observable`, `Maybe`, `Completable`, `Disposable`, `Single`) for async operations
- Common pattern: `.subscribeOn(Schedulers.computation())`, `.observeOn(AndroidSchedulers.mainThread())`
- Disposables collected manually and disposed in `onStop()` / lifecycle hooks

## Navigation & UI
- **Fragment-based**: `MainActivity` hosts fragments via `FragmentManager`
- **Bottom navigation** (togglable via user preference) + **DrawerLayout**
- **BottomSheetBehavior** (`LockableBottomSheetBehavior`) for the audio player
- **Navigation via drawer items** -> fragments loaded into the container
- **Navigation shortcuts**: `OnlineFeedViewActivity` handles podcast subscription deep links (itpc://, pcast://, feed://, antennapod-subscribe://)

# Key Coding Patterns & Gotchas

## Service-Interface Pattern
Abstract class + static `impl` holder + `setImpl()`/`get()`:

```java
public abstract class DownloadServiceInterface {
    private static DownloadServiceInterface impl;
    public static DownloadServiceInterface get() { return impl; }
    public static void setImpl(DownloadServiceInterface impl) { ... }
    public abstract void downloadNow(Context context, FeedItem item, boolean ignoreConstraints);
}
```

The interface module (`:net:download:service-interface`) depends on `:model`, `:net:common`, `:storage:preferences` but **not** on the implementation module. The stub class in the same module provides default no-op behavior.

## Database Layer
- **Raw SQLite** via `PodDBAdapter` singleton — **not Room** and no ORM
- `ContentValues` for inserts/updates
- **Reads**: `DBReader` utility class with static methods (synchronous, cursor-to-object mappers)
- **Writes**: `DBWriter` utility class with static methods, all executed on a **single-threaded executor** (`DatabaseExecutor`, `MIN_PRIORITY`)
- `DBWriter` methods return `Future<?>` for optional synchronization
- Events posted via `EventBus` after successful DB operations (e.g., `EventBus.getDefault().post(new FeedListUpdateEvent(feed))`)
- Column name constants: `KEY_xxx`, table name constants: `TABLE_NAME_xxx`
- `PodDBAdapter.open()`/`close()` are effectively no-ops but always called for compatibility
- Version constant: `public static final int VERSION = 3110000;`

## UI Patterns
- All adapters extend `SelectableAdapter<T>` (which extends `RecyclerView.Adapter<T>`), not `RecyclerView.Adapter` directly
- ViewHolders are **separate classes** (not inner classes) in a `ui/.../` package
- `WeakReference<FragmentActivity>` for activity references in adapters to avoid leaks
- `onViewRecycled()` clears listeners to prevent fragment leaks
- Fragments use `static final String TAG` constant
- Fragments use `getActivity()` cast to `MainActivity` for certain operations
- Dummy views pattern for adapter item counts
- **ViewBinding** enabled for all modules

## Testing
- **JUnit4** + **Robolectric** + **Mockito 5**
- Test classes use `@RunWith(RobolectricTestRunner.class)` for tests needing Android context
- Plain `@Test` from JUnit4 for pure Java logic tests
- Common test dependencies: `awaitility`, `espresso-core`, `espresso-contrib`, `espresso-intents`
- Tests spread across module-specific `src/test/` directories, not centralized
- `@VisibleForTesting` annotation used for exposing internals to tests
- Robolectric calls `Application.onCreate()` for every test — EventBus catches the double-init exception (`EventBusException`)

## Build Configuration
- **SDK**: compileSdk 36, minSdk 23, targetSdk 36
- **Java version**: source/target compatibility Java 21
- **Flavors**: `free` and `play` (market dimension)
- **Lint**: `checkDependencies true`, `warningsAsErrors true`, `abortOnError true`
- **Compiler**: `-Werror` flag — warnings treated as errors (with selected exclusions: `-deprecation,-serial,-this-escape,-unchecked,-processing,-classfile`)
- **SpotBugs**: effort=max, reportLevel=medium, ignoreFailures=false (exceptions parsed from XML)
- **Checkstyle**: toolVersion 10.12.0, config at `config/checkstyle/checkstyle.xml`

## User-Facing Strings
- All user-visible strings go into `:ui:i18n` module (`res/values/strings.xml`) — never create string resources elsewhere
- Only English strings are edited directly; translations handled via Weblate

# Commands

## Build
```bash
./gradlew :app:assembleDebug
```

## Install & Run
```bash
./gradlew --console=plain :app:installPlayDebug && adb shell monkey -p de.danoeh.antennapod.debug 1
```

## Unit Tests (specific module)
```bash
./gradlew --console=plain :module:name:test
```

## Unit Tests (all modules, CI-style)
```bash
./gradlew testPlayDebugUnitTest testDebugUnitTest
```

## Full Code Style Check
```bash
./gradlew checkstyle lint spotbugsPlayDebug spotbugsDebug
```

## XML Formatting Verification
```bash
# Uses android-xml-formatter to check layout XML formatting
find . -wholename "*/res/layout/*.xml" | xargs java -jar android-xml-formatter.jar
```

## Crash Debugging
```bash
adb logcat -d | grep "de.danoeh.antennapod" | tail -20
```

# Important Gotchas

1. **CD command is forbidden** — always assume you're at the project root (`/workspace`)
2. **Never filter/truncate compiler output** — use `./gradlew :app:assembleDebug` raw, no piping through grep/head/tail
3. **Never reference full package names inline** — always use imports
4. **Never add comments to code** — only if explicitly asked; never remove existing comments
5. **Keep diffs minimal** — don't rename, reformat, or optimize existing code
6. **No Kotlin** — project is entirely Java
7. **Database is raw SQLite** — no Room; `PodDBAdapter` is the sole database access layer
8. **Writes are single-threaded** via `DBWriter`'s `DatabaseExecutor` — don't create concurrent DB write paths
9. **EventBus for inter-component communication** — not LiveData, not custom listeners
10. **App package**: `de.danoeh.antennapod`, debug suffix: `.debug`
11. **Manifest has many external URL scheme handlers** (`itpc://`, `pcast://`, `feed://`, `antennapod-subscribe://`) — be careful not to break deep linking
12. **Robolectric calls `Application.onCreate()` per test** — EventBus double-init is caught silently, but other singletons may need guards
13. **`-Werror` is on** — warnings become compilation errors; check lint/checkstyle before building
14. **Lint `abortOnError true`** — any lint error fails the build
15. **PlaybackService** uses `MediaSessionCompat` and `androidx.media3`, and the manifest has `USE_MEDIA3_PLAYBACK_SERVICE` build config flag
16. **Version codes follow a schema**: `1.2.3-beta4` → `1020304`, `1.2.3` → `1020395`
17. **`commons-io` must stay at 2.5** — newer versions cause `ClassNotFoundException` on Android 6
18. **Never reinvent wheels** — use existing well-vetted libraries for cryptography, encoding, and protocol implementation. Implementing ciphers, hash algorithms, signature schemes, or protocol parsers from scratch is forbidden unless you obtain explicit permission.

# PR Conventions
When creating a PR, always read the PR template at .github/pull_request_template.md before starting and strictly follow it.
The description goes above the checklist.
Always mention the corresponding issue using "Closes: #<number>" in the description.
Never change the PR title unless explicitly asked to do so; the original title from the prompt is usually the most appropriate one.
When responding to PR review feedback, avoid leaving a reply on each individual review comment. Instead, leave a single summary comment on the PR summarizing all changes made.
Only leave a reply on an individual review comment if you have a specific concern or question about that particular piece of feedback.
Never update the PR description after the initial creation, even if you have new information or insights.
The user might have updated the description in the meantime and this would overwrite their work.
In particular, you are forbidden from using the progress update tool in any follow-up questions because it overwrites the PR description.
This holds even if the global agent instructions tell you to do this.

# Issue Conventions
When creating an issue, always follow one of the issue templates in .github/ISSUE_TEMPLATE/.
Apply the corresponding labels and always mention in the technical info box that the issue was AI generated.

# Ad Skip Feature (Active Development)
This branch (`feature/adskip`) implements ad detection and automatic skipping during playback. Key components:
- **`AdSkipController`** (`:playback:service`) — core logic: loads ad timestamps from JSON files, checks playback position against ad segments, seeks past them. Provides undo capability via `MessageEvent` with callback.
- **`AdDetectionManager`** (`:net:download:service-interface`) — abstract service interface for enqueuing ad detection
- **`AdDetectionWorker`** (`:net:download:service`) — WorkManager-based background worker that runs ad detection on downloaded media
- **`AdDetectionPreferences`** (`:storage:preferences`) — user preferences for ad detection (enabled/disabled)
- **`AdDetectionPreferencesFragment`** (`:app`) — settings UI for ad detection
- **`AdSkipPreferencesTransporter`** (`:storage:importexport`) — import/export backup support for ad skip preferences
- Ad timestamp files are JSON with `{"status": "complete|processing", "ads": [{"startMs": ..., "endMs": ...}, ...]}`
