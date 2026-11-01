# Bundled app resources

`jpackage` copies the directory matching the build platform into the packaged app, where
`FfmpegBinary` finds it through the `compose.application.resources.dir` system property.

Populated in CI by `scripts/build-ffmpeg.sh <target>`; the binaries are not committed.

| Directory | Contents |
|---|---|
| `macos-arm64/`, `macos-x64/` | `ffmpeg` built with `--enable-indev=avfoundation` |
| `windows-x64/` | `ffmpeg.exe` built with `--enable-indev=dshow` |
| `linux-x64/` | `ffmpeg` built with `--enable-indev=v4l2 --enable-indev=alsa` |

Without a binary the desktop app still runs — capture is simply reported as unavailable, which is
what `supportsCapture` returns when the lookup fails.

## macOS capture permissions during development

Unsigned local packages use ad-hoc signing. Their designated requirement is a binary hash, so a
rebuild can invalidate an earlier camera or microphone grant. For permission persistence across
builds, package with the same Developer ID Application certificate and bundle ID each time:

```sh
./gradlew :composeApp:createDistributable -Pcompose.desktop.mac.sign=true \
  '-Pcompose.desktop.mac.signing.identity=Your certificate name (TEAMID)'
```

The certificate must already be installed in the signing keychain. Test the packaged `Mira.app`;
running through Gradle uses a different host identity. The bundled FFmpeg helper also needs camera
and audio-input entitlements; `signBundledFfmpeg` applies them and re-seals the app.
