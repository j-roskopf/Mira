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

case "${TARGET}" in
  macos)   INDEV=(--enable-indev=avfoundation) ;;
  windows) INDEV=(--enable-indev=dshow) ;;
  linux)   INDEV=(--enable-indev=v4l2 --enable-indev=alsa) ;;
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
  --enable-gpl
  --enable-libx264
  --enable-avdevice

  # Inputs: camera and microphone for this platform.
  "${INDEV[@]}"

  # Encoders: H.264 video, AAC audio, MJPEG for the live preview stream.
  --enable-encoder=libx264
  --enable-encoder=aac
  --enable-encoder=mjpeg

  # Decoders: reading back our own files for posters and waveforms.
  --enable-decoder=h264
  --enable-decoder=aac
  --enable-decoder=mjpeg
  --enable-decoder=rawvideo
  --enable-decoder=pcm_s16le

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

  --enable-protocol=file
  --enable-protocol=pipe

  --enable-filter=scale
  --enable-filter=format
  --enable-filter=aformat
  --enable-filter=aresample
  --enable-filter=anull
  --enable-filter=null

  --enable-parser=h264
  --enable-parser=aac
  --enable-parser=mjpeg

  --enable-bsf=h264_mp4toannexb
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

if [[ ! -f config.h ]]; then
  echo "==> Configuring (${TARGET})"
  ./configure "${CONFIGURE_FLAGS[@]}" --prefix="${WORK}/install"
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
