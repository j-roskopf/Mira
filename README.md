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
| Photo | System camera | `UIImagePickerController` | FFmpeg snapshot | Camera preview + canvas |
| Video | System camera | `UIImagePickerController` | FFmpeg subprocess | `MediaRecorder` + ffmpeg.wasm |

Photo and video on phones are handed to the platform's own camera UI, so flash, zoom, timers, and
the front/back switch come from the OS rather than being rebuilt in Compose. iOS exports the
picker's QuickTime video to mp4 before it enters the archive. Default video and photo quality
(Low / Medium / High, both defaulting to Medium) is chosen in Settings; photos are resized and
re-encoded to match, and video uses the closest recording preset each platform exposes.

Everything lands as mp4/H.264+AAC or m4a/AAC from every platform, so media recorded anywhere plays
everywhere — a voice note recorded in Chrome still opens on an iPhone years later.

Desktop capture runs a **bundled, size-trimmed static FFmpeg** (~10–20 MB; a stock build is
120–160 MB). A lightweight MJPEG-only process opens the camera for framing as soon as the capture
page appears; pressing the shutter closes it and starts the recording process, which writes the
file and streams MJPEG on stdout via the `tee` muxer. A camera handle is only ever held by one
process at a time.

Playback mirrors it: `MediaPlayer` on Android, `AVPlayer` on iOS, a real `<audio>`/`<video>` element
on web, and on desktop the same bundled FFmpeg decoding to PCM and MJPEG — the JDK plays PCM but has
no AAC or H.264 decoder, so the binary earns its place twice.

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
Without a Firebase project the app stays local. GitHub archive connection also requires a GitHub
OAuth app with **Enable Device Flow** selected. Set `mira.github.oauthClientId` in `local.properties`
(or `MIRA_GITHUB_OAUTH_CLIENT_ID` in the build environment), then rebuild the app. No GitHub client
secret is needed. Release builds read the `MIRA_GITHUB_OAUTH_CLIENT_ID` repository secret.

Desktop capture needs the FFmpeg binary. CI builds it per platform; locally:

```bash
./scripts/build-ffmpeg.sh macos
```

On macOS the packaged app declares camera and microphone usage in its `Info.plist`, so the system
prompts the first time you record. Running from Gradle does not get that prompt — macOS attributes
the request to the JVM, and a denial is silent, so capture simply produces nothing. Mira detects
that and says so rather than appearing to record. Use a packaged build to test capture for real:

```bash
./gradlew :composeApp:createDistributable
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
