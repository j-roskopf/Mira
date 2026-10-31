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
