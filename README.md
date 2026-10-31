# Mira

A journal, calendar first. Android, iOS, web and desktop (macOS, Windows, Linux), from one Kotlin
Multiplatform codebase.

- **[mira.joetr.com](https://mira.joetr.com)** — the web build
- **[Releases](https://github.com/j-roskopf/Mira/releases)** — signed installers for every platform

Entries are text that may carry audio or video. The calendar is the home screen, and each day's
colour is how much you wrote that day — so a month you journalled reads as a constellation and a
month you did not reads as empty.

## How it works

**Offline first.** SQLite is the working store; the UI never waits on the network. Local writes go
into an outbox that a background worker drains.

**Firestore is the source of truth**, reached entirely over REST from `commonMain` — no native
Firebase SDKs on any platform, so sync is one implementation for all five targets. Merging is
per-row last-write-wins with tombstones, plus two deliberate asymmetries: remote deletes beat stale
local upserts, and a local pending edit blocks a remote non-delete.

**GitHub is a one-way archive.** Entries are pushed to a private repository as Markdown with YAML
frontmatter, through the Git Data API rather than a git binary (there is none on iOS or in a
browser). Every edit is a commit, so `git log` on an entry file is its revision history. Nothing is
ever read back.

**No Cloud Functions.** Media is normalised to mp4/m4a on the client, the GitHub connection uses
OAuth device flow, which needs no client secret, and search runs locally.

## Capture

| | Android | iOS | Desktop | Web |
|---|---|---|---|---|
| Audio | `MediaRecorder` | `AVAudioRecorder` | FFmpeg subprocess | `MediaRecorder` + ffmpeg.wasm |
| Video | CameraX `Recorder` | `AVCaptureMovieFileOutput` | FFmpeg subprocess | `MediaRecorder` + ffmpeg.wasm |

Everything lands as mp4/H.264+AAC or m4a/AAC from every platform, so media recorded anywhere plays
everywhere — a voice note recorded in Chrome still opens on an iPhone years later.

Desktop capture runs a **bundled, size-trimmed static FFmpeg** (~10–20 MB; a stock build is
120–160 MB). One process writes the file and streams MJPEG on stdout for the live preview via the
`tee` muxer, so the camera is only opened once.

## Media retention

Once Storage confirms an upload, the local copy is a cache and never the only copy — so evicting it
is lossless and anything evicted re-streams on demand.

| Platform | Default |
|---|---|
| Desktop | Keep everything |
| Android / iOS | LRU-capped, 5 GB by default, years can be pinned |
| Web | Stream only — browser quota is evicted without warning |

## Stack

Kotlin 2.4 · Compose Multiplatform 1.11 · Navigation 3 · Metro · SQLDelight · Ktor · Roborazzi

```
androidApp/ iosApp/ composeApp/   app shells
core/{network,database,platform,updates,media}
data/{auth,entries,archive,settings}
feature/{calendar,day,compose,timeline,search,settings,auth}
domain/ navigation/ ui/ build-logic/
```

## Building

```bash
./gradlew :composeApp:run                      # desktop
./gradlew :androidApp:installDebug             # android
./gradlew :composeApp:wasmJsBrowserDevelopmentRun   # web
./scripts/run-ios-simulator.sh                 # ios
```

Copy `local.properties.example` to `local.properties` and fill in the Firebase and OAuth values.
Everything except Firebase works without them; without a Firebase project the app simply stays
local.

Desktop capture needs the FFmpeg binary. CI builds it per platform; locally:

```bash
./scripts/build-ffmpeg.sh macos
```

Without it the desktop app still runs — `supportsCapture` reports capture as unavailable and the
compose sheet hides the record affordances.

## Releasing

Push to `main`. The workflow bumps the version, tags `release/<version>`, builds and signs every
platform, and attaches the artifacts to a GitHub release. The apps update themselves from it
(iOS cannot self-install, so it deep-links to the release page).

See [docs/pipeline.md](docs/pipeline.md) for the job graph and the full list of secrets.

## Documents

- **[DESIGN.md](DESIGN.md)** — the visual system: tokens, accent and heatmap ramp, type scale,
  motion, per-screen anatomy
- **[SPEC.md](SPEC.md)** — data model, sync protocol, archive format, media lifecycle, and what is
  deliberately out of scope
- **[docs/pipeline.md](docs/pipeline.md)** — release mechanics
