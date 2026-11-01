#!/usr/bin/env bash
#
# Builds a size-trimmed static FFmpeg for Mira's desktop capture.
#
# Desktop capture is an FFmpeg subprocess because the JDK has no camera API, JavaCV's OpenCV
# dependency adds hundreds of megabytes per platform, and a trimmed static build lands around
# 10-20 MB while also giving poster frames, waveform peaks and local transcoding.
#
# Only what Mira actually uses is enabled. A full FFmpeg build is 120-160 MB; the configure line
# below is what keeps this an order of magnitude smaller.
#
# Usage: scripts/build-ffmpeg.sh <macos|windows|linux>
# Output: build/ffmpeg/ffmpeg[.exe]
#
set -euo pipefail

FFMPEG_VERSION="7.1.1"
TARGET="${1:-}"

if [[ -z "${TARGET}" ]]; then
  echo "usage: $0 <macos|windows|linux>" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${ROOT}/build/ffmpeg-src"
OUT="${ROOT}/build/ffmpeg"
mkdir -p "${WORK}" "${OUT}"

# macOS encodes H.264 with VideoToolbox, a system framework: hardware accelerated, no external
# library to link, and nothing for the binary to depend on at runtime. Linking libx264 there
# produced a binary tied to the build machine's homebrew copy, since macOS ld has no -Bstatic and
# prefers the dylib whenever both it and the archive are present.
case "${TARGET}" in
  macos)
    INDEV=(--enable-indev=avfoundation)
    VIDEO_ENCODER=(--enable-videotoolbox --enable-encoder=h264_videotoolbox)
    ;;
  windows)
    INDEV=(--enable-indev=dshow)
    VIDEO_ENCODER=(--enable-gpl --enable-libx264 --enable-encoder=libx264)
    ;;
  linux)
    INDEV=(--enable-indev=v4l2 --enable-indev=alsa)
    VIDEO_ENCODER=(--enable-gpl --enable-libx264 --enable-encoder=libx264)
    ;;
  *) echo "unknown target: ${TARGET}" >&2; exit 2 ;;
esac

CONFIGURE_FLAGS=(
  --disable-everything
  --disable-doc
  --disable-htmlpages
  --disable-manpages
  --disable-podpages
  --disable-txtpages
  --disable-debug
  --disable-network
  --disable-ffplay
  --disable-ffprobe
  --disable-shared
  --enable-static
  # --enable-static only makes FFmpeg's own libraries static. Without this, libx264 links
  # dynamically against the build machine's homebrew copy and the binary dies on any Mac that
  # does not have it.
  --pkg-config-flags=--static
  # avdevice otherwise pulls in X11 screen grabbing, which drags libX11 in as another external
  # dependency Mira never uses.
  --disable-xlib
  --disable-libxcb
  --enable-avdevice

  # Inputs: camera and microphone for this platform.
  "${INDEV[@]}"

  # Encoders: H.264 video (per-platform, see above), AAC audio, MJPEG for the live preview.
  "${VIDEO_ENCODER[@]}"
  --enable-encoder=aac
  --enable-encoder=mjpeg

  # Decoders: reading back our own files for posters, waveforms and playback.
  #
  # hevc is not optional despite Mira always recording H.264. libavcodec shares h2645_sei.o
  # between H.264 and HEVC, but only pulls in aom_film_grain.o for HEVC — so an H.264-only
  # build fails to link with an undefined ff_aom_uninit_film_grain_params. It also means
  # imported iPhone video, which is usually HEVC, can be played back.
  --enable-decoder=h264
  --enable-decoder=hevc
  --enable-decoder=aac
  --enable-decoder=mjpeg
  --enable-decoder=rawvideo
  # lavfi synthetic inputs deliver wrapped frames, used by the bundled-helper regression tests.
  --enable-decoder=wrapped_avframe
  # Capture devices hand over raw PCM, and the macOS microphone specifically delivers 32-bit
  # float — without pcm_f32le, recording fails with "no decoder found for: pcm_f32le".
  --enable-decoder=pcm_f32le
  --enable-decoder=pcm_s16le
  --enable-decoder=pcm_s24le
  --enable-decoder=pcm_s32le
  --enable-decoder=pcm_u8
  # Desktop playback decodes to signed 16-bit PCM and feeds it to javax.sound.sampled, which
  # needs both the encoder and the raw muxer.
  --enable-encoder=pcm_s16le
  --enable-muxer=pcm_s16le

  # Muxers: mp4 for video, ipod for m4a audio, mjpeg + image2 for the preview and posters,
  # tee so one capture can write the file and the preview stream at once.
  --enable-muxer=mp4
  --enable-muxer=ipod
  --enable-muxer=mjpeg
  --enable-muxer=image2
  --enable-muxer=tee

  --enable-demuxer=mov
  --enable-demuxer=mjpeg
  --enable-demuxer=rawvideo
  --enable-demuxer=wav
  --enable-demuxer=aac
  # Synthetic sources, so the shipped binary is the one the capture tests exercise rather than
  # whatever ffmpeg happens to be on the build machine. lavfi is an input device, not a demuxer.
  --enable-indev=lavfi
  --enable-filter=testsrc
  --enable-filter=sine

  --enable-protocol=file
  --enable-protocol=pipe

  # split feeds the recording and preview branches from one camera feed; without it the whole
  # filter graph fails to parse and capture never starts.
  --enable-filter=split
  --enable-filter=scale
  # Recording format controls fit the selected shape and normalize pixel aspect ratio.
  --enable-filter=crop
  --enable-filter=pad
  --enable-filter=setsar
  --enable-filter=format
  --enable-filter=aformat
  --enable-filter=aresample
  --enable-filter=anull
  --enable-filter=null

  --enable-parser=h264
  --enable-parser=hevc
  --enable-parser=aac
  --enable-parser=mjpeg

  --enable-bsf=h264_mp4toannexb
  --enable-bsf=hevc_mp4toannexb
  --enable-bsf=aac_adtstoasc
)

cd "${WORK}"

if [[ ! -d "ffmpeg-${FFMPEG_VERSION}" ]]; then
  echo "==> Fetching FFmpeg ${FFMPEG_VERSION}"
  curl -fsSL -o "ffmpeg-${FFMPEG_VERSION}.tar.xz" \
    "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
  tar xf "ffmpeg-${FFMPEG_VERSION}.tar.xz"
fi

cd "ffmpeg-${FFMPEG_VERSION}"

if [[ "${TARGET}" == "macos" ]]; then
  AUDIO_PATCH="${ROOT}/scripts/ffmpeg-patches/avfoundation-audio-queue.patch"
  if patch -p1 --dry-run --forward < "${AUDIO_PATCH}" >/dev/null 2>&1; then
    patch -p1 --forward < "${AUDIO_PATCH}"
  elif ! patch -p1 --dry-run --reverse < "${AUDIO_PATCH}" >/dev/null 2>&1; then
    echo "::error::AVFoundation audio patch does not match this FFmpeg source" >&2
    exit 1
  fi
fi

# Reconfigure whenever the flag set changes; a stale config.h silently builds the old feature
# set and the mismatch only shows up as a link error or a missing codec at runtime.
FLAGS_FINGERPRINT="$(printf '%s\n' "${CONFIGURE_FLAGS[@]}" | shasum | cut -d' ' -f1)"
if [[ ! -f config.h || "$(cat .mira-flags 2>/dev/null)" != "${FLAGS_FINGERPRINT}" ]]; then
  echo "==> Configuring (${TARGET})"
  make distclean >/dev/null 2>&1 || true
  ./configure "${CONFIGURE_FLAGS[@]}" --prefix="${WORK}/install" 2>&1 | tee .mira-configure.log

  # configure only warns when an option matches nothing, so a renamed or mistyped component
  # silently produces a binary missing exactly the codec it was asked for. The muxer is called
  # pcm_s16le, not s16le, and that typo shipped a build that could record but never play back.
  if grep -q "did not match anything" .mira-configure.log; then
    echo "::error::FFmpeg configure ignored an option:" >&2
    grep "did not match anything" .mira-configure.log >&2
    exit 1
  fi

  echo "${FLAGS_FINGERPRINT}" > .mira-flags
fi

echo "==> Building"
make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

BINARY="ffmpeg"
[[ "${TARGET}" == "windows" ]] && BINARY="ffmpeg.exe"

cp "${BINARY}" "${OUT}/${BINARY}"
chmod +x "${OUT}/${BINARY}"

if command -v strip >/dev/null 2>&1; then
  strip "${OUT}/${BINARY}" || true
fi

SIZE="$(du -h "${OUT}/${BINARY}" | cut -f1)"
echo "==> ${OUT}/${BINARY} (${SIZE})"
