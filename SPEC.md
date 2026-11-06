# Mira — Product & Architecture Spec

A journal that is calendar-first, offline-first, and multiplatform: Android, iOS, web, and desktop
(macOS, Windows, Linux). Entries are text, audio, or video. Local SQLite is the working store,
Firestore is the source of truth, and a private GitHub repository is a durable plain-text archive.

Visual language lives in [DESIGN.md](DESIGN.md). Release mechanics live in
[docs/pipeline.md](docs/pipeline.md).

---

## 1. Platform matrix

| | Android | iOS | Desktop | Web (wasm) |
|---|---|---|---|---|
| Text entries | ✅ | ✅ | ✅ | ✅ |
| Playback | MediaPlayer | AVPlayer | FFmpeg → PCM / MJPEG | `<audio>` / `<video>` |
| Audio capture | MediaRecorder | AVAudioRecorder | FFmpeg subprocess | MediaRecorder + ffmpeg.wasm |
| Video capture | CameraX Recorder | AVCaptureMovieFileOutput | FFmpeg subprocess | MediaRecorder + ffmpeg.wasm |
| Local media retention | LRU, capped | LRU, capped | Unlimited | None — stream only |
| Auto-update | Self-install APK | Deep-link to release | Self-install | Service worker reload |
| Distribution | APK (GitHub release) | IPA (GitHub release) | DMG / MSI / deb / flatpak | mira.joetr.com |

No app stores. Every artifact is signed; nothing is reviewed.

---

## 2. Stack

Inherited from Basil, which already solves the hard multiplatform problems:

- Kotlin 2.4.0 / Compose Multiplatform 1.11.1 / AGP 9.2.1
- **Metro** for DI, **SQLDelight 2.2.1** for persistence, **Ktor 3.3.2** for HTTP
- **Navigation 3** (`org.jetbrains.androidx.navigation3`)
- **Roborazzi** for screenshot tests
- **Firebase entirely over REST from `commonMain`** — no native Firebase SDKs on any platform

That last point is the keystone. `core/network` speaks the Firestore, Storage, and Identity Toolkit
REST APIs directly, so there is exactly one implementation of sync for all five targets. Nothing in
this spec should introduce a platform-specific backend path.

### Modules

```
androidApp/ iosApp/ composeApp/      app shells
build-logic/                         Gradle convention plugins
core/network                         Firestore / Storage / Auth REST  (from Basil)
core/database                        SQLDelight drivers per platform  (from Basil)
core/platform                        lifecycle, share, file picker    (from Basil)
core/updates                         GitHub-release auto-updater      (from Basil)
core/media                           NEW — capture + playback abstraction
data/auth                            Google / email / anonymous       (from Basil)
data/entries                         NEW — entry repo, sync, outbox
data/archive                         NEW — GitHub one-way archive
data/settings                        NEW — preferences
domain/                              NEW — models, use cases
navigation/                          (from Basil)
ui/                                  theme, components, gallery       (reskinned from Basil)
feature/calendar  feature/day  feature/compose
feature/timeline  feature/search  feature/settings  feature/auth
```

---

## 3. Data model

### Entry

One entry type. **An entry is text that may carry attachments** — a voice memo is an entry with an
empty body and one audio attachment. There is no separate audio-entry or video-entry type, in the
schema or in the sync protocol. The UI presents three creation affordances; they all produce the
same row.

```sql
CREATE TABLE entries (
    id            TEXT NOT NULL PRIMARY KEY,   -- UUIDv7, client-generated
    owner_id      TEXT NOT NULL,
    local_date    TEXT NOT NULL,               -- 'YYYY-MM-DD', the calendar key
    created_at    INTEGER NOT NULL,            -- epoch millis
    created_tz    TEXT NOT NULL,               -- IANA zone at creation
    updated_at    INTEGER NOT NULL,
    body          TEXT NOT NULL DEFAULT '',
    attachments   TEXT NOT NULL DEFAULT '[]',  -- JSON array, see below
    tags          TEXT NOT NULL DEFAULT '[]',  -- JSON array of strings
    deleted       INTEGER NOT NULL DEFAULT 0,  -- tombstone
    pending_sync  INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX entries_owner_date_idx    ON entries(owner_id, local_date);
CREATE INDEX entries_owner_updated_idx ON entries(owner_id, updated_at, id);

CREATE TABLE entry_outbox (
    entry_id    TEXT NOT NULL PRIMARY KEY,
    kind        TEXT NOT NULL,                 -- UPSERT | DELETE
    enqueued_at INTEGER NOT NULL
);

-- Local-only. Never synced. Staging before upload + LRU cache after.
CREATE TABLE media_blobs (
    id            TEXT NOT NULL PRIMARY KEY,   -- attachment id
    entry_id      TEXT NOT NULL,
    path          TEXT,                        -- file path (desktop/mobile)
    data          BLOB,                        -- inline bytes (small audio only)
    bytes         INTEGER NOT NULL,
    uploaded      INTEGER NOT NULL DEFAULT 0,
    pinned        INTEGER NOT NULL DEFAULT 0,  -- excluded from LRU eviction
    last_access   INTEGER NOT NULL,
    created_at    INTEGER NOT NULL
);

CREATE TABLE app_settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL);
```

**`local_date` is stored, never derived.** Computed from the device zone at creation and never
recomputed. Deriving the calendar day from `created_at` at read time makes entries silently change
day when you travel or when a different device renders them. `created_at` + `created_tz` are kept
for ordering and for displaying the real wall-clock time.

An entry **can be moved** to another day — a plain edit of `local_date`, so the compose sheet carries
a date field defaulting to today.

### Attachment (JSON, inside `entries.attachments`)

```json
{
  "id": "018f3a7c-...",
  "kind": "audio" | "video",
  "storagePath": "users/<uid>/media/<entryId>/<attachmentId>.m4a",
  "url": "https://firebasestorage.../...?alt=media&token=...",
  "mime": "audio/mp4",
  "bytes": 1048576,
  "durationMs": 94000,
  "posterPath": "users/<uid>/media/<entryId>/<attachmentId>.jpg",
  "posterUrl": "https://firebasestorage.../...?alt=media&token=...",
  "thumbnailPath": "users/<uid>/media/<entryId>/<attachmentId>_thumb.jpg",
  "thumbnailUrl": "https://firebasestorage.../...?alt=media&token=...",
  "caption": "",
  "state": "local" | "uploading" | "uploaded"
}
```

Attachments are a **JSON column, not a child table**, because the sync engine merges whole rows.
A child table would allow an entry to arrive on another device without its attachments. The cost is
that "all entries with video" is a `LIKE`, not an index — acceptable at journal scale.

`storagePath` is the durable identifier; `url` is a convenience that can rot and is always
re-derivable from the path.

---

## 4. Sync — Firestore

Per-row last-write-wins with an outbox and tombstones, ported from Basil's `RecipeSyncService` /
`remoteMergeAction` without semantic change.

```
users/{uid}/entries/{entryId}    → mirrors the entries row, attachments as a map array
users/{uid}/meta/sync            → { lastPulledAt }
```

**Push.** Any local mutation writes the row, sets `pending_sync = 1`, and enqueues to
`entry_outbox`. A worker drains the outbox, writing to Firestore, and clears the flag on success.
Failures stay enqueued and retry with backoff.

**Pull.** Query `users/{uid}/entries` ordered by `updatedAt` greater than `lastPulledAt`. For each
remote row apply `mergeAction(pendingKind, localUpdatedAt, remote)`:

- remote deleted, local pending DELETE → **skip**
- remote deleted, local pending anything → **apply and clear outbox** (remote deletes win)
- remote deleted, no local pending → **apply**
- local pending anything → **skip** (local edit in flight wins until pushed)
- local `updatedAt` > remote → **skip**
- otherwise → **apply**

**Conflict policy.** Whole-row LWW on `updated_at`. Two devices editing the same entry's body while
both offline means the later write wins and the earlier is lost. Accepted: single-user app,
simultaneous offline edits of the same entry are vanishingly rare, and the GitHub archive retains
the overwritten version in git history.

**Clock skew** is bounded by stamping `updated_at` from the device but rejecting any remote row more
than 24h in the future on apply.

---

## 5. Media

### Capture

| Platform | Implementation | Output |
|---|---|---|
| Android | `MediaRecorder` (audio), system camera app (photo/video) | m4a / jpg / mp4 |
| iOS | `AVAudioRecorder` (audio), `UIImagePickerController` (photo/video) | m4a / jpg / mp4 |
| Web | `MediaRecorder` → webm → **ffmpeg.wasm** transcode | m4a / jpg / mp4 |
| Desktop | **bundled FFmpeg subprocess** (`avfoundation` / `dshow` / `v4l2`+`pulse`) | m4a / jpg / mp4 |

On Android and iOS, photos and videos are captured by the platform camera UI so flash, zoom,
timers, and camera switching are native; iOS re-containers the picker's QuickTime video to mp4 with
`AVAssetExportSession`.

Everything lands as **mp4/H.264+AAC or m4a/AAC**, from every platform. Nothing is transcoded
server-side, there are no Cloud Functions, and media recorded anywhere plays everywhere.

`ffmpeg.wasm` is lazy-loaded on the web only when recording starts, so it never affects first paint.

**Desktop FFmpeg.** A size-trimmed static binary built by `scripts/build-ffmpeg.sh <target>` in CI,
cached by `actions/cache` keyed on FFmpeg version + configure line, and bundled by `jpackage` — the
same pattern Phoebe uses for projectM. Target ~10–20 MB per installer against ~150 MB for JavaCV.

Framing uses a small MJPEG-only process that opens when the capture page mounts, so the preview is
live before the shutter. Recording replaces it with one `tee` process: the file output plus an MJPEG
stream on stdout, decoded into Compose frames. A photo is a single tap that snapshots the framing
frame; only one process ever holds the camera.

**Capture limits:** video H.264 at three tiers — 360p / 350 kbps, 720p / 1.2 Mbps (default), and
1080p / 2.5 Mbps; audio 64 kbps mono AAC; 10 minutes maximum with a soft warning at 5. The global
Low / Medium / High defaults for video and photo live in Settings and both start at Medium. Native
mobile capture maps them to the closest camera-app control; photos are resized and re-encoded to
the chosen tier. Desktop and web no longer duplicate a quality picker in the capture surface.

### Playback

| Platform | Implementation |
|---|---|
| Android | `MediaPlayer` + `SurfaceView` |
| iOS | `AVPlayer` + `AVPlayerLayer` |
| Web | a real `<audio>`/`<video>` element positioned over the space Compose reserves |
| Desktop | the bundled FFmpeg: PCM into `javax.sound.sampled`, MJPEG frames for video |

The source is resolved per device: a local file where one is cached, a Storage stream otherwise,
and raw bytes only when neither exists — which is the web case and anything not yet uploaded. One
player is alive per entry, so starting a second attachment stops the first.

Desktop playback costs nothing extra because the FFmpeg binary is already bundled for capture. The
JDK can play PCM but has no AAC or H.264 decoder, so the same subprocess that records also decodes.

### Upload

- **Audio uploads immediately** (~1 MB for a 2-minute note).
- **Video defers to an unmetered network** by default, with a manual override.
- The entry row syncs instantly regardless. An attachment can therefore exist on another device
  before its bytes do, so `state` is rendered — pending, never broken.

### Retention

The local copy is **always a cache, never the only copy.** Once Storage confirms the upload, evicting
local bytes is lossless and anything evicted re-streams on demand.

| Platform | Default |
|---|---|
| Desktop | Keep everything. Usage readout + "free up space" in Settings |
| Android / iOS | Keep until uploaded, then LRU-capped (default 5 GB, user-configurable). Years can be pinned |
| Web | No local media. Browser OPFS quota makes it impossible, and eviction would be silent data loss |

The entries database itself is a few MB of text and lives in OPFS on web without issue.

### Deletion

Deleting an entry writes a Firestore tombstone and the deleting client immediately deletes the
Storage objects. If the client is offline the delete is queued; if it never completes, the objects
are orphaned. Settings carries a **"Clean up orphaned media"** sweep that lists Storage objects with
no referencing entry and reclaims them. This is the known weak point of the zero-backend design and
is accepted deliberately.

---

## 6. Archive — GitHub

**One-way push.** Firestore is the source of truth; git is a durable, human-readable, version-
controlled archive. Nothing is ever read back from GitHub into the app.

**Auth: OAuth device flow.** Client ID only, no secret, so no server. The app shows a code, the user
enters it at `github.com/login/device`. Scope `repo` for private repositories. Works identically on
all five platforms, including wasm, where redirect-based OAuth is painful.

**Shared target, per-device credential.** The repository and branch live at
`users/{uid}/meta/archive` with an `enabled` flag, so connecting on one device makes the target
visible on every device signed into the same account. The `repo`-scoped OAuth token is **never**
written to Firestore; each device authorizes once through device flow and keeps the token in local
`app_settings`. A device that sees a target without a local token offers "Connect this device".
Disabling the archive writes `enabled: false`, which clears the target on all devices.

**Transport: the Git Data API**, not a git binary — there is no git on iOS or in a browser. Create
blobs → create tree → create commit → update ref, from `commonMain` over the existing Ktor stack.
One commit per push covering N entries.

**Layout:**

```
entries/2026/10/2026-10-30--143022--a3f9.md
media/2026/10/<entryId>/<attachmentId>.m4a     (only when media backup is on, via LFS)
```

```markdown
---
id: 018f3a7c-...
date: 2026-10-30
created_at: 2026-10-30T14:30:22-05:00
tz: America/Chicago
tags: [travel, work]
attachments:
  - kind: audio
    storage: users/<uid>/media/018f3a7c/voice.m4a
    url: https://firebasestorage.../voice.m4a?alt=media&token=...
    duration_ms: 94000
---

The journal text.
```

Date-partitioned so directory listings stay usable after a decade; filenames sort chronologically;
frontmatter parses in any language.

**Cadence:** debounced ~5 minutes after the last change, plus a manual "Back up now", plus on close
on desktop. Commit message `Sync N entries (2026-10-30)`.

**Repo setup:** Mira offers to create a private `mira-journal` repo via the API, or accepts an
existing one.

**Media:** off by default — frontmatter carries links only. The **"Include media in GitHub backup"**
toggle commits the actual files via Git LFS, producing a fully self-contained archive for anyone who
wants one.

Every edit is a commit, so `git log` on an entry file is that entry's revision history — which also
recovers anything lost to LWW.

---

## 7. Auth

Providers: **Google**, **email + password**, **anonymous**. All three already work across five
platforms in Basil. No Sign in with Apple — it is only mandated by App Store review, and Mira never
goes through review.

**Anonymous is the first-run default.** Mira opens on a cursor, not a login wall: write immediately,
sync later. Upgrading links the anonymous Firebase account to a real credential, preserving every
entry already written.

No Cloud Functions. `firebase/functions/` ships empty.

---

## 8. Search

Entirely local, over the SQLite database. `LIKE` across `body` and `tags`, combined with structured
filters: date range, has-audio, has-video, tag. No index layer until it measurably matters; a
journal is thousands of rows, not millions.

Audio and video content is **not searchable** — there is no transcription. Media entries are found
by date, tag, and any caption typed alongside them. Accepted deliberately in exchange for zero
backend and zero per-minute cost.

---

## 9. Explicitly out of scope

- Health integrations (Withings, Health Connect, HealthKit). Google Fit is closed to new developers
  as of 2024-05-01 and sunsets end of 2026; Health Connect is Android-on-device only with no server
  API. Cut entirely.
- Transcription / speech-to-text.
- Mood or rating fields.
- Two-way GitHub sync. A third independently-writable replica would need three-way merge against
  git's own semantics; the cost dwarfs the benefit.
- App Store and Play Store distribution.
- Sharing, collaboration, multi-user. Mira is single-user by construction.
- Rich text. Entry bodies are plain text; formatting would have to survive markdown round-tripping
  into the archive.
