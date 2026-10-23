# ClearCut

A polished, professional Android media analysis and editing application. Import media
from device storage or a direct URL, scan the **entire decodable duration** using three
database-free analysis methods, review timestamped findings with evidence, then edit
audio/video intervals in the **Copyright Removal Lab** and export a validated MP4.

> **Important product limitation:** ClearCut detects repeated content and similarities
> entirely on-device. It does **not** identify every copyrighted work and does **not**
> reproduce YouTube Content ID or Meta Rights Manager. It never advertises guaranteed
> copyright clearance or guaranteed avoidance of platform detection.

## Features

- **Local import** via the Android Storage Access Framework / media picker — no broad
  storage permission required. MP4, MKV, MOV, MP3, AAC, M4A, WAV, and other formats the
  on-device decoders support.
- **URL import** for direct HTTP(S) media links with bounded, cancellable, resumable-free
  streaming downloads, SSRF-safe destination validation, DNS/TLS/HTTP error mapping,
  atomic temp-file finalization, and safe redirect handling.
- **Full-duration, multi-method scanner**:
  - *Audio fingerprints* (RMS, zero-crossing rate, spectral-centroid proxy, and
    low-order autocorrelation ratios over overlapping chunks) → repeated/similar audio.
  - *Visual perceptual hashes* (downscaled grayscale frames sampled across the whole
    duration, plus near scene-change frames) → repeated/similar visuals.
  - *Structural/signal analysis* (luma jumps, repeated frames, audio discontinuities,
    tempo patterns) → structural anomalies.
  - A `MultiMethodScanner` coordinator merges overlapping findings without discarding
    independent evidence and explicitly reports unscanned sections, dropped tracks,
    partial decode, and limitations.
- **Synchronized scanner UI**: findings list with sort/filter, confidence/evidence,
  coverage display, audio/video/structural timeline lanes, and a preview player that
  seeks when you tap a finding.
- **Copyright Removal Lab**: an experimental editing tool for media you own or are
  authorized to modify.
  - Audio: cut-and-join, replace the interval with authorized audio, add voice-over or
    ambience, short crossfades. (Muting is **not** the removal mechanism.)
  - Video: cut/remove, join preceding/following footage, replace with authorized
    replacement footage. (Blurring/covering is **not** the removal mechanism.)
  - Audio and video intervals are selected separately; a per-operation
    synchronization policy prevents drift, black frames, and unintended silence.
- **EDL-based editing**: every operation (source interval, track, operation type,
  replacement source, target interval, sync policy, validation status) is stored in an
  Edit Decision List. The preview and the exporter consume the **same** EDL.
- **Export & validation**: Media3 Transformer renders MP4 (H.264/AAC when the device
  supports it). The output is reopened and inspected (tracks, duration, decodability)
  before success is reported. Originals are never overwritten.
- **Reports**: shareable TXT and JSON reports covering input metadata, coverage,
  methods, all findings with timestamps/evidence/scores, approved edits, replacement
  metadata, output validation, and known limitations — including the mandated
  disclaimer.
- **Premium Material 3 UI**: deep-neutral dark theme by default, optional light theme,
  custom animated processing states (validating / in progress / completed / with
  warnings / cancelled / failed), reduced-motion support, accessible contrast and
  touch targets, adaptive layouts.
- **Privacy by default**: all analysis runs on-device. Nothing is uploaded unless a
  future user-authorized feature explicitly says so. No login cookies, no social
  passwords, no DRM circumvention. Internet is only used for URL imports and any
  provider calls the user explicitly authorizes.

## Architecture

Single `:app` module, organized by feature/package:

```
com.projecthub.clearcut
├── app                Application + MainActivity + navigation
├── core/designsystem  Theme, reusable components, animations, loaders
├── core/model         Media, findings, EDL, errors, scan reports
├── core/media         Media3 inspection/playback, timestamp mapping, ExportRenderer, validation
├── core/network       URL validation, source adapters, OkHttp downloader
├── core/report        TXT + JSON report generation
├── core/scanner       AudioAnalyzer, VisualAnalyzer, StructuralAnalyzer, MultiMethodScanner
├── core/storage       Room (projects/history), DataStore preferences, StorageHelper
├── di                 Manual DI container
├── feature/home       Dashboard
├── feature/import     Local + URL import
├── feature/scanner    Findings, timeline lanes, preview sync
├── feature/editor     Preview, EDL editing, Copyright Removal Lab
├── feature/export     Render + validation workflow
├── feature/projects   Project/export history
└── feature/settings   Appearance, analysis preferences, privacy & storage, diagnostics
```

All expensive work runs off the main thread; long-running user-initiated exports run in
a foreground service so they survive leaving the screen.

## Prerequisites

- JDK 17
- Android SDK (compileSdk 35, minSdk 26, targetSdk 35)
- Android Gradle Plugin 8.5+ with Gradle 8.9

## Build & test

```bash
# Install any Android SDK components the machine needs, then:
./gradlew :app:assembleDebug            # debug APK
./gradlew :app:assembleRelease          # release APK (unsigned unless signing configured)
./gradlew :app:testDebugUnitTest        # JVM unit tests (90+ tests)
./gradlew :app:lintDebug                # lint (0 errors)
```

APK outputs land in `app/build/outputs/apk/debug/` and
`app/build/outputs/apk/release/`.

### CI

`.github/workflows/android-build.yml` runs on push to `main`/`master`, pull requests,
manual dispatch, and published releases. It:

1. Checks out the repository, sets up JDK 17, configures Gradle caching.
2. Builds the debug APK.
3. Runs unit tests.
4. Runs lint.
5. Builds the release APK.
6. Uploads both APKs as workflow artifacts, and test/lint reports on failure.
7. Attaches APK assets to the triggering GitHub Release when the event is a release.

Release signing is intentionally **not** wired into the workflow. To publish signed
release builds, configure the standard Android signing in `app/build.gradle.kts` and
supply `SIGNING_KEYSTORE` and related secrets through GitHub Secrets. **Never commit
keystores, passwords, or signing keys** — the repository `.gitignore` excludes
`*.jks`, `*.keystore`, and `keystore.properties`.

## Supported import sources

- **Local files** — anything the system picker provides that the on-device decoders
  can read.
- **Direct HTTP(S) media links** — MP4, MKV, MOV, MP3, AAC, M4A, WAV, etc., where the
  server returns the media bytes. Downloads are validated by inspecting the actual
  content independently of the server's claimed content type (HTML instead of media is
  rejected; format/codec detection happens after download).

### Not supported (by design)

YouTube and Facebook regular video pages are **not** treated as downloadable media
URLs. Their public pages may require authorized provider functionality and their
terms may not permit direct media extraction. ClearCut only uses official provider
APIs or authorized integrations; the source-adapter interface exists so more
providers can be added later under compliant terms. Attempting login-cookie
extraction, token theft, DRM circumvention, paywall bypass, scraping to evade
provider restrictions, or anti-bot defeat is **never** implemented or suggested.

When a provider URL cannot be accessed lawfully, the app explains why and offers
alternatives — import a local file or the direct media link you are authorized to use.

## Privacy behavior

- **Works offline**: local import, all three analysis methods, the editor, and
  export/reports run fully on-device. No online copyright database, mandatory
  backend, paid fingerprint API, or API key is required.
- **Needs internet**: URL import (direct media links), and anything a future adapter
  explicitly requires with user authorization.
- **Permissions requested**: `INTERNET`, `ACCESS_NETWORK_STATE`,
  `FOREGROUND_SERVICE(_DATA_SYNC)` for exports, and `POST_NOTIFICATIONS`. No broad
  storage read, no contacts, no location.
- **Cleartext HTTP**: disabled at the manifest level; HTTP is rejected unless an
  explicit, disclosed justification exists.
- **Cleanup**: Settings → Privacy & storage lets users clear scan history, cached
  media, and local indexes. Temporary downloads are removed after cancellation or
  failure.
- **No auto-upload** of imported media; no social-account passwords; no auth headers
  or signed URLs ever logged (errors redact them).

## Known limitations

- Database-free analysis detects repeated/similar content but cannot identify every
  copyrighted work or match an unknown song to an owner without an actual identified
  reference source. A "no match" result never means "copyright-free".
- Audio *self*-similarity cannot distinguish "the same person's song repeated" from
  "two different songs that are similar" — it only reports technical similarity.
- The VisualAnalyzer uses a lightweight perceptual hash of downscaled frames; very
  compressed, heavily trimmed, or resolution-shifted footage may lower hit rate.
- Export quality depends on the device's software encoder; codec combinations not
  supported by the device encoder fail with an explicit error rather than silent
  fallback.
- Media with DRM or unsupported container/codecs cannot be decoded and is reported
  honestly.

## Disclaimer

ClearCut provides technical similarity analysis and media editing tools. Similarity
findings do not establish copyright ownership or infringement. No match found does not
mean the content is copyright-free. The app does not guarantee that YouTube, Facebook,
or another platform will or will not flag the media. Only edit and analyze media you
own or are authorized to modify.

## License

See the repository for the license governing this source. Imported media remains the
property of its respective owners; users are responsible for having rights to the
media they analyze and edit.