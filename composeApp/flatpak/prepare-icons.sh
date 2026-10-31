#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "${root}/../.." && pwd)"

icon_128="${root}/icons/128/com.joetr.mira.png"
icon_256="${root}/icons/256/com.joetr.mira.png"
icon_512="${root}/icons/512/com.joetr.mira.png"

if [[ -f "${icon_128}" && -f "${icon_256}" && -f "${icon_512}" ]]; then
  exit 0
fi

if command -v python3 >/dev/null 2>&1 && python3 -c "import PIL" 2>/dev/null; then
  python3 "${repo_root}/branding/generate_icons.py"
  exit 0
fi

source_icon="${repo_root}/branding/mira.png"
wasm_icon_512="${repo_root}/composeApp/src/wasmJsMain/resources/icons/icon-512.png"
desktop_icon_256="${repo_root}/composeApp/src/desktopMain/resources/icon.png"

if [[ ! -f "${source_icon}" ]]; then
  echo "Missing branding icon: ${source_icon}" >&2
  exit 1
fi

resize_icon() {
  local input="$1"
  local output="$2"
  local size="$3"

  if command -v magick >/dev/null 2>&1; then
    magick "${input}" -resize "${size}x${size}" "${output}"
  elif command -v convert >/dev/null 2>&1; then
    convert "${input}" -resize "${size}x${size}" "${output}"
  elif command -v sips >/dev/null 2>&1; then
    sips -z "${size}" "${size}" "${input}" --out "${output}" >/dev/null
  else
    echo "Install ImageMagick, Pillow, or run on macOS with sips to generate Flatpak icons." >&2
    exit 1
  fi
}

mkdir -p "${root}/icons/128" "${root}/icons/256" "${root}/icons/512"
cp "${wasm_icon_512}" "${icon_512}"
cp "${desktop_icon_256}" "${icon_256}"
resize_icon "${source_icon}" "${icon_128}" 128
