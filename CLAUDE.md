# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Nuvio is a Kotlin Multiplatform + Compose Multiplatform rewrite of a media hub app for Android and iOS (a `desktopMain` source set also exists). Single shared codebase in `composeApp/`, with native entry points in `androidApp/` and `iosApp/`. It's a client for the Stremio addon ecosystem — it does not host or distribute content itself (see README "Legal & DMCA" section before making any change that touches source/addon handling).

## Commands

```bash
# Run on a device/emulator/simulator (builds, installs, launches)
./scripts/run-mobile.sh android [e|p] [full|playstore]   # e = emulator (default), p = physical device
./scripts/run-mobile.sh ios [s|p] [full|appstore]          # s = simulator, p = physical device

# Direct Gradle build tasks
./gradlew :composeApp:assembleDebug
./gradlew :androidApp:assembleFullDebug
./gradlew :androidApp:assemblePlaystoreDebug
./gradlew :composeApp:compileKotlinIosSimulatorArm64

# Tests (commonTest runs on Android host + iOS simulator targets)
./gradlew :composeApp:testAndroidHostTest
./gradlew :composeApp:iosSimulatorArm64Test
./gradlew :composeApp:testAndroidHostTest --tests "com.nuvio.app.features.watchprogress.WatchProgressRulesTest"
```

Aggregate Android tasks (`build`, `assemble*`, `bundle*` without a flavor) fail fast unless a distribution is picked via `-Pnuvio.android.distribution=full|playstore` (or `NUVIO_ANDROID_DISTRIBUTION` in `local.properties`) — this is intentional, see Distribution flavors below.

Versioning is driven from `iosApp/Configuration/Version.xcconfig` (`MARKETING_VERSION`/`CURRENT_PROJECT_VERSION`), the shared source of truth for both platforms' version name/code.

## Architecture

### Source set layout (`composeApp/src/`)

- `commonMain/kotlin/com/nuvio/app/` — shared code: `core/` (auth, networking, storage, sync, theming, shared UI primitives) and `features/` (one directory per feature area: player, streams, debrid, catalog, downloads, trakt, plugins, p2p, etc.). Files within a feature directory are flat (`FooRepository.kt`, `FooStorage.kt`, `FooModels.kt`, `FooScreen.kt`) rather than nested into sub-packages.
- `androidMain` / `iosMain` — actual implementations of platform `expect` declarations, plus platform-only integrations (Media3/ExoPlayer + MPV on Android, AVFoundation on iOS).
- `commonTest` — shared unit tests, mirroring the `features/`/`core/` package structure. This is where most test coverage lives (repositories, parsers, business rules) since it runs against both platforms.
- `nativeInterop` — cinterop defs (e.g. `commoncrypto`) for iOS native bridging.

### Distribution flavors — read before touching `expect`/`actual` gated code

The app ships in two variants per platform, and this is load-bearing for App Store/Play Store policy compliance, not just a build-config toggle:

- Android: `androidFull` vs `androidPlaystore` source sets
- iOS: `iosFull` vs `iosAppStore` source sets
- `fullCommonMain` is shared code included in *both* Android-full and iOS-full builds (but neither store build)

Feature availability across these is centralized in `core/build/AppFeaturePolicy.kt` (an `expect object` with `actual` implementations per flavor: `AppFeaturePolicy.android.kt` in both `androidFull`/`androidPlaystore`, `AppFeaturePolicy.ios.kt` in both `iosFull`/`iosAppStore`, plus a `desktopMain` actual). It gates things like `pluginsEnabled`, `p2pEnabled`, `inAppUpdaterEnabled`, `trailerPlaybackMode` — features that are disallowed or risky in store-distributed builds (e.g. plugin execution via `quickjs-kt`, P2P streaming). When adding a feature that touches store policy, gate it through this object rather than ad-hoc platform checks.

`composeApp/build.gradle.kts` resolves which flavor to compile based on Gradle task name (`Full`/`Playstore` in the task, or `-Pnuvio.ios.distribution`/`-Pnuvio.android.distribution`) and wires the matching source dirs in — read that file before changing how flavors are selected.

### Runtime config generation

Secrets/config (Supabase URL+key, Sentry DSN, Trakt client id/secret, TMDB/IMDB API bases, debrid client ids, app version) are **not** committed as source — they're generated at build time by the `generateRuntimeConfigs` Gradle task in `composeApp/build.gradle.kts` from `local.properties` / env vars, into `build/generated/runtime-config/kotlin/...`. If a generated config object (e.g. `SupabaseConfig`, `TraktConfig`, `SentryConfig`) appears "missing", it's because it hasn't been generated yet — run any compile task first, don't hand-write it into `commonMain`.

### Sync

Cross-device state sync (watch progress, library, settings) goes through `core/sync/SyncManager.kt` backed by Supabase Postgrest. There is **no realtime/push channel** — realtime sync was removed upstream and arrived in the 0.4.1 sync, taking `RealtimeSyncInvalidationService`, `RealtimeSyncConfig.ENABLED` and `NUVIO_REALTIME_SYNC_ENABLED` with it. Don't reach for those; they no longer exist. State is pulled when the app returns to the foreground (`AppForegroundMonitor` + `SyncManager`'s foreground pull job), not pushed from the server, so "why didn't my other device update instantly" is expected behaviour rather than a bug.

`SyncClientIdentity` mints a stable per-install id passed to Supabase as `p_origin_client_id`, letting a device distinguish its own writes from other devices'. The library has its own incremental delta-sync layer under `features/library/sync/` (`LibrarySyncAdapter` / `SupabaseLibrarySyncAdapter`, `LibrarySyncPaging`, `LibrarySyncReconciler`) instead of re-fetching the whole library — change library sync there, not in `SyncManager`.

### No dependency-injection framework

There's no Koin/Hilt/Dagger — repositories and services are plain objects/classes wired up directly. Follow the existing pattern in whichever feature you're touching rather than introducing a DI container.

## Fork context

This is a personal fork (`origin` = `MichaelCommitsAt3AM/NuvioMobileButBetter`) of upstream `NuvioMedia/NuvioMobile`, tracked via an `upstream` remote and periodically merged. `CONTRIBUTING.md`'s strict PR-scoping policy governs contributions back to the upstream project — it does not apply to work done directly on this fork.

This fork only ships Android releases — don't build, sign, or publish iOS for a release here.

### Upstream sync conflict resolutions

These recur on every `merge upstream/cmp-rewrite` — resolve them the same way each time without asking:

- **`.github/workflows/android-release.yml`**: always keep this fork's version entirely (`git checkout --ours`), discarding upstream's version wholesale. Upstream has restructured this into a multi-job `prepare`/`android`/`ios`/`release` pipeline that also builds and ships iOS IPAs; this fork doesn't build iOS (see above), and this fork's version is the one that builds, signs, and publishes its Android releases in CI (see "Building and publishing a release" below). Don't try to cherry-pick pieces of upstream's restructure into it.
- **`AddonRepository.kt` — primary-profile addon sharing**: this fork replaced upstream's live guard-based model (`isUsingPrimaryAddonsFromSecondaryProfile()`, which blocked local edits while mirroring a primary profile's addons) with a copy-based model (`copyPrimaryAddonsToProfile()`/`fetchPrimaryAddonsForPicker()`, a one-time copy the user can then edit freely) and deleted the guard function. Upstream keeps extending the old guard into more call sites (e.g. `moveAddon`, `pushToServer`) on every sync. When that conflicts: drop any reintroduced `isUsingPrimaryAddonsFromSecondaryProfile()` call (the function doesn't exist on this fork), but keep whatever *unrelated* structural improvements upstream bundled into the same functions (e.g. debounced pushes via `pushJobsByProfile`, no-op `changed`/`shouldRefresh` tracking to skip redundant persist/push calls) — those aren't part of the addon-sharing model and are worth keeping. Watch for compile breakage: other non-conflicting parts of the file may already assume upstream's newer structure (e.g. a `pushJobsByProfile` map or `finally` block referencing a job variable), so a literal "keep ours" on the conflicting hunk alone can leave dangling references — reconcile rather than dropping the whole hunk blind.

### Versioning and releases

Fork releases use `Major.Minor.Patch.Fork` (e.g. `0.3.1.1`, `0.3.1.2`) — the first three segments mirror upstream's last-synced version, and `Fork` increments by 1 per release since that sync, resetting to `1` only on a release that is itself an upstream sync. Tracked in `MARKETING_VERSION` in `iosApp/Configuration/Version.xcconfig` alongside `CURRENT_PROJECT_VERSION` (the Android `versionCode`). Manage both with `scripts/bump-version.sh` rather than hand-editing the xcconfig:

```bash
./scripts/bump-version.sh fork                       # 0.3.1.1 -> 0.3.1.2 (regular release)
./scripts/bump-version.sh sync-upstream 0.3.2         # after merging upstream -> 0.3.2.1
./scripts/bump-version.sh sync-upstream --ref upstream/cmp-rewrite  # same, auto-read from a ref
```

`CURRENT_PROJECT_VERSION` always increments by exactly 1 on every release and is never reset, even when `sync-upstream` resets the fork number — it's the Android `versionCode` and drives the in-app updater (`AppFeaturePolicy.inAppUpdaterEnabled`), so it must keep increasing monotonically regardless of what the marketing version string does. The script commits the bump but does not tag it — the release workflow creates the tag (tag name == the new version); it doesn't push or build anything.

### Building and publishing a release

**Android releases are built and signed only in CI** by `.github/workflows/android-release.yml` (`Build Android Release`, `workflow_dispatch`) — this fork no longer builds, signs, or uploads release APKs locally. Don't run a local release build, hand-create tags, or `gh release upload` a locally built APK; local builds are for testing on a device only. iOS is never built for a release.

The workflow builds `:androidApp:assembleFullRelease` (ABI splits are off, so it produces the single `androidApp-full-release.apk` the in-app updater downloads), signs it with the release keystore, and creates the tag + GitHub release at the dispatched branch. Modes: `dry-run` (validate + release notes, no build), `build-only` (signed APK as a workflow artifact, no tag/release, skips the bump-order checks — use it to test a build), `draft`, `publish` (published directly as Latest).

Required repository secrets (Settings → Secrets and variables → Actions): `NUVIO_LOCAL_PROPERTIES_BASE64` (base64 of `local.properties`; must contain `NUVIO_RELEASE_STORE_PASSWORD`, `NUVIO_RELEASE_KEY_ALIAS`, `NUVIO_RELEASE_KEY_PASSWORD`, `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET` — its `sdk.dir`/`NUVIO_RELEASE_STORE_FILE` lines are dropped and replaced in CI) and `NUVIO_RELEASE_KEYSTORE_BASE64` (base64 of `keystore/release.keystore`). Re-create them whenever those local files change.

1. Commit the feature/fix work first.
2. `./scripts/bump-version.sh fork` (or `sync-upstream`) — commits the bump alone. The workflow refuses to release if anything other than the release workflow/scripts or Markdown docs (`*.md`) changed after the bump.
3. `git push origin HEAD` (no tag to push — the workflow creates it).
4. Dispatch `Build Android Release` on that branch with `mode=draft`.
5. Rewrite the draft's generated notes (see below), then publish it as Latest: `gh release edit <version> --repo MichaelCommitsAt3AM/NuvioMobileButBetter --draft=false --latest`. (`mode=publish` publishes straight away as Latest, but with the generated notes.)

If a release run fails, fix the workflow or code and dispatch it again — don't fall back to building locally. The tag and release are only created in the final step, after the APK has built.

Release title depends on which kind of release this is: a regular fork release (fork number incremented via `bump-version.sh fork`) is titled `"<Major.Minor.Patch> - Fork update <Fork>"`; a release that is itself an upstream sync (fork number reset to `.1` via `bump-version.sh sync-upstream`) is titled `"<Major.Minor.Patch> - sync with upstream"` instead. The workflow sets the title automatically (`scripts/release-metadata.sh`).

GitHub release notes should be short, feature-level bullet points in plain non-technical language (what changed for a user, not what changed in the code) — not a raw commit list. `scripts/generate-release-notes.sh` produces a commit-list draft; rewrite that into a handful of plain-English bullets before publishing, grouping related commits into one line each.
