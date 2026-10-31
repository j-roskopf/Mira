# Release pipeline

Mira ships to no app store. Every artifact is built, signed, and attached to a GitHub release, and
the apps update themselves from those releases. The pipeline is Phoebe's and Basil's, merged:
Basil's structure and Firebase/web deploy, Phoebe's Azure Trusted Signing for Windows and its
macOS notarization retry logic, plus a new FFmpeg build stage.

---

## Workflows

| File | Trigger | Purpose |
|---|---|---|
| `.github/workflows/ci.yml` | PR, push to `main` | Build all targets, unit tests, Roborazzi, lint |
| `.github/workflows/release.yml` | push to `main`, `workflow_dispatch` | Full signed release, all platforms |
| `.github/workflows/record-roborazzi.yml` | `workflow_dispatch` | Re-record screenshot baselines |

## Release jobs

```
version ──┬─> ffmpeg (matrix: macos / windows / linux) ──┬─> macos ──┐
          │                                              ├─> windows ─┤
          │                                              └─> linux ───┤
          ├─> android ───────────────────────────────────────────────┤
          │                                                           ├─> github-release
          ├─> ios ──────────────────────────────────────> attach-ios ─┘
          │
          └─> web ──────────────────> web-pages-deploy
```

### `version`

Reads `mira.versionName` / `mira.versionCode` from `gradle.properties`, bumps both, commits
`[skip ci]` to `main`, and tags `release/<version>`. On `workflow_dispatch` an explicit version can
be supplied; otherwise the name is `date -u +"%Y.%m%d.%H%M"`. The version must match
`^[0-9]+\.[0-9]+\.[0-9]+$`.

Every downstream job checks out `needs.version.outputs.tag_name`, never `main`, so a release is
built from exactly one commit.

> If `main` is protected, GitHub Actions needs bypass permission or the job needs a release PAT.

### `ffmpeg` (new)

Matrix over `macos-latest`, `windows-latest`, `ubuntu-latest`. Runs
`scripts/build-ffmpeg.sh <target>`, which configures a size-trimmed static FFmpeg with only what
Mira needs:

- input devices: `avfoundation` (macOS), `dshow` (Windows), `v4l2` + `pulse` (Linux)
- encoders: `libx264`, `aac`; decoders: `h264`, `aac`, `mjpeg`, `rawvideo`
- muxers: `mp4`, `ipod` (m4a), `mjpeg`; protocols: `file`, `pipe`
- filters: `scale`, `format`

Cached with `actions/cache` keyed on the FFmpeg version plus a hash of the configure line, so this
is a ~20 minute job once and a cache restore thereafter. The binary is uploaded as an artifact and
consumed by the matching desktop job, which bundles it via `jpackage`. Target size is 10–20 MB.

Same pattern as Phoebe's `build-projectm.yml` / `scripts/build-projectm.sh`.

### `android`

Decodes the keystore, builds a signed APK (no AAB — there is no Play Store), uploads it.

### `linux`

Builds `.deb` via `jpackage` and a Flatpak bundle, with the FFmpeg binary bundled into the app image.

### `windows`

Builds the MSI, then signs with **Azure Trusted Signing** (`azure/login@v3` →
`azure/artifact-signing-action@v1`). Unsigned MSIs trip SmartScreen, which for a self-updating app
means every update looks like malware.

### `macos`

Imports the Developer ID certificate into a temporary keychain, builds the DMG, signs, then
notarizes with `xcrun notarytool submit --wait` and staples. **Two attempts**, dumping
`notarytool log` on failure — Apple's notary service fails transiently often enough that a single
attempt makes releases flaky.

### `ios`

Xcode 26, imports the distribution certificate and provisioning profile, builds and exports a signed
IPA. Attached to the release after `github-release` completes. Installed by sideloading; iOS cannot
self-install, so the in-app updater deep-links to the release page.

### `web`

Builds `:composeApp:wasmJsBrowserDistribution`, writes `.nojekyll`, copies `index.html` to
`404.html` for client-side routing, writes a `CNAME` containing `mira.joetr.com`, and uploads a
Pages artifact. `web-pages-deploy` publishes it with one retry — `actions/deploy-pages` is also
flaky.

### `github-release`

Waits on android + linux + windows + macos, downloads every artifact, and creates the release at
`release/<version>` with generated notes and `*.apk *.deb *.flatpak *.msi *.dmg`. `attach-ios`
appends the IPA afterwards.

---

## Custom domain

`mira.joetr.com` → GitHub Pages:

1. DNS at `joetr.com`: `CNAME mira → j-roskopf.github.io.`
2. The `CNAME` file is written into the Pages artifact by the `web` job, not committed — otherwise
   every deploy must preserve it by hand.
3. Enable **Enforce HTTPS** in repository settings once the certificate provisions.

---

## Secrets

### Android
| Secret | Notes |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -i release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | |
| `ANDROID_KEY_ALIAS` | |
| `ANDROID_KEY_PASSWORD` | |
| `GOOGLE_SERVICES_JSON_B64` | Firebase Android config |

### Apple (iOS + macOS)
| Secret | Notes |
|---|---|
| `IOS_CERTIFICATE_BASE64` / `IOS_CERTIFICATE_PASSWORD` | Distribution `.p12` |
| `IOS_PROVISIONING_PROFILE_BASE64` | Ad-hoc or development profile |
| `MACOS_CERTIFICATE_BASE64` / `MACOS_CERTIFICATE_PASSWORD` | Developer ID Application `.p12` |
| `MACOS_DEVELOPER_IDENTITY` | e.g. `Developer ID Application: Joe R (TEAMID)` |
| `MACOS_KEYCHAIN_PASSWORD` | Ephemeral CI keychain |
| `APPLE_ID` / `APPLE_APP_SPECIFIC_PASSWORD` / `APPLE_TEAM_ID` | Notarization |
| `GOOGLE_SERVICE_INFO_PLIST_B64` | Firebase iOS config |

### Windows
| Secret | Notes |
|---|---|
| `AZURE_CLIENT_ID` / `AZURE_TENANT_ID` | OIDC federated credential — no client secret |
| `AZURE_SIGNING_ENDPOINT` | e.g. `https://eus.codesigning.azure.net` |
| `AZURE_SIGNING_ACCOUNT_NAME` | |
| `AZURE_SIGNING_CERTIFICATE_PROFILE_NAME` | |

### Firebase / app config
| Secret | Notes |
|---|---|
| `FIREBASE_PROJECT_ID` | |
| `FIREBASE_WEB_API_KEY` | |
| `FIREBASE_AUTH_DOMAIN` | |
| `FIREBASE_STORAGE_BUCKET` | |
| `FIREBASE_TOKEN` | Deploying rules only |
| `MIRA_GOOGLE_WEB_CLIENT_ID` / `MIRA_GOOGLE_WEB_CLIENT_SECRET` | Google sign-in, web + desktop |
| `MIRA_GOOGLE_IOS_CLIENT_ID` | |
| `MIRA_GITHUB_OAUTH_CLIENT_ID` | Device flow — **no secret by design** |

Every job validates its own secrets before doing any work and fails with an explicit
`::error::` naming the missing one. A release that gets to notarization before discovering an empty
secret wastes 40 minutes.

`firebase/functions/` is empty — Mira has no Cloud Functions. The `firebase` job deploys Firestore
and Storage rules only.

---

## Auto-update

`core/updates` polls the GitHub releases API, compares against `mira.versionName` with
`SemanticVersion`, and picks the asset matching the running platform via `UpdateAssetSelection`.

| Platform | Behaviour |
|---|---|
| Android | Downloads the APK, launches the package installer |
| Desktop | Downloads the installer for the current OS, launches it, exits |
| iOS | Cannot self-install — deep-links to the release page |
| Web | Service worker detects a new build and prompts to reload |

---

## First release

Before any feature work, run the pipeline end to end on an essentially empty app. Every signing path
— Android keystore, Apple notarization, Azure Trusted Signing, Pages + custom domain — fails in its
own particular way the first time, and each one is far cheaper to diagnose against a hello-world
build than against a half-finished app four months in.
