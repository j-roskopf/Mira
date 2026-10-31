#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PROJECT="${IOS_PROJECT:-iosApp/iosApp.xcodeproj}"
SCHEME="${IOS_SCHEME:-iosApp}"
CONFIGURATION="${IOS_CONFIGURATION:-Debug}"
SIMULATOR_NAME="${IOS_SIMULATOR_NAME:-}"
LOCK_DIR="$ROOT_DIR/build/ios-simulator-run.lock"

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "xcodebuild was not found. Install Xcode and select it with xcode-select."
  exit 1
fi

if ! command -v xcrun >/dev/null 2>&1; then
  echo "xcrun was not found. Install Xcode command line tools."
  exit 1
fi

extract_udids() {
  sed -nE 's/.*\(([A-F0-9-]{36})\).*/\1/p'
}

find_booted_simulator() {
  xcrun simctl list devices booted | extract_udids | head -n 1
}

find_named_simulator() {
  local name="$1"
  xcrun simctl list devices available |
    grep -F "$name" |
    extract_udids |
    tail -n 1
}

find_latest_iphone_simulator() {
  xcrun simctl list devices available |
    grep -F "iPhone" |
    extract_udids |
    tail -n 1
}

SIMULATOR_UDID="${IOS_SIMULATOR_UDID:-}"

if [ -z "$SIMULATOR_UDID" ]; then
  SIMULATOR_UDID="$(find_booted_simulator)"
fi

if [ -z "$SIMULATOR_UDID" ] && [ -n "$SIMULATOR_NAME" ]; then
  SIMULATOR_UDID="$(find_named_simulator "$SIMULATOR_NAME")"
fi

if [ -z "$SIMULATOR_UDID" ]; then
  SIMULATOR_UDID="$(find_latest_iphone_simulator)"
fi

if [ -z "$SIMULATOR_UDID" ]; then
  echo "No available iPhone simulator found."
  exit 1
fi

if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  LOCK_PID="$(cat "$LOCK_DIR/pid" 2>/dev/null || true)"
  if [ -n "$LOCK_PID" ] && kill -0 "$LOCK_PID" 2>/dev/null; then
    echo "An iOS simulator run is already active with PID $LOCK_PID."
    exit 1
  fi

  rm -rf "$LOCK_DIR"
  mkdir "$LOCK_DIR"
fi

echo "$$" > "$LOCK_DIR/pid"
trap 'rm -rf "$LOCK_DIR"' EXIT

DERIVED_DATA_PATH="${IOS_DERIVED_DATA_PATH:-$ROOT_DIR/build/ios-derived-data/$CONFIGURATION-$SIMULATOR_UDID}"

open -a Simulator >/dev/null 2>&1 || true
xcrun simctl boot "$SIMULATOR_UDID" >/dev/null 2>&1 || true
xcrun simctl bootstatus "$SIMULATOR_UDID" -b

xcodebuild \
  -project "$PROJECT" \
  -scheme "$SCHEME" \
  -configuration "$CONFIGURATION" \
  -destination "platform=iOS Simulator,id=$SIMULATOR_UDID" \
  -derivedDataPath "$DERIVED_DATA_PATH" \
  build

APP_PATH="$(find "$DERIVED_DATA_PATH/Build/Products/${CONFIGURATION}-iphonesimulator" -maxdepth 1 -name "*.app" -type d | head -n 1)"

if [ -z "$APP_PATH" ]; then
  echo "Built app was not found in $DERIVED_DATA_PATH/Build/Products/${CONFIGURATION}-iphonesimulator."
  exit 1
fi

BUNDLE_ID="$(/usr/libexec/PlistBuddy -c "Print CFBundleIdentifier" "$APP_PATH/Info.plist")"

xcrun simctl install "$SIMULATOR_UDID" "$APP_PATH"
xcrun simctl launch "$SIMULATOR_UDID" "$BUNDLE_ID"
