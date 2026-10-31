#!/bin/sh
set -e

if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then
  echo "Skipping Gradle build task invocation due to OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED environment variable set to \"YES\""
  exit 0
fi

resolve_java_home() {
  candidate="$1"
  if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
    JAVA_HOME="$candidate"
    return 0
  fi
  return 1
}

if ! resolve_java_home "$JAVA_HOME"; then
  if [ -x /usr/libexec/java_home ]; then
    resolve_java_home "$(/usr/libexec/java_home 2>/dev/null)" || true
  fi
fi

if ! [ -x "$JAVA_HOME/bin/java" ]; then
  for candidate in \
    "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home" \
    "/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home" \
    "$HOME/.sdkman/candidates/java/current"; do
    if resolve_java_home "$candidate"; then
      break
    fi
  done
fi

if ! [ -x "$JAVA_HOME/bin/java" ]; then
  for java_bin in "$HOME/.gradle/jdks"/*/jdk-*/Contents/Home/bin/java \
    "$HOME/.gradle/jdks"/*/Contents/Home/bin/java; do
    if [ -x "$java_bin" ]; then
      JAVA_HOME="$(cd "$(dirname "$java_bin")/.." && pwd)"
      break
    fi
  done
fi

if ! [ -x "$JAVA_HOME/bin/java" ]; then
  echo "error: Could not find Java for Gradle. Install JDK 21+ (e.g. brew install openjdk) or run ./gradlew once from a terminal." >&2
  exit 1
fi

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

cd "$SRCROOT/.."
./gradlew :composeApp:embedAndSignAppleFrameworkForXcode

SCHEME_FILE="$SRCROOT/../core/platform/build/generated/miraConfig/ios-oauth-url-scheme.txt"
INFO_PLIST="$SRCROOT/iosApp/Info.plist"
if [ -f "$SCHEME_FILE" ] && [ -f "$INFO_PLIST" ]; then
  SCHEME="$(tr -d '[:space:]' < "$SCHEME_FILE")"
  if [ -n "$SCHEME" ]; then
    if ! /usr/libexec/PlistBuddy -c "Print :CFBundleURLTypes" "$INFO_PLIST" 2>/dev/null | grep -Fq "$SCHEME"; then
      INDEX=$(/usr/libexec/PlistBuddy -c "Print :CFBundleURLTypes" "$INFO_PLIST" 2>/dev/null | grep -c "Dict" || echo 0)
      /usr/libexec/PlistBuddy -c "Add :CFBundleURLTypes:$INDEX dict" "$INFO_PLIST"
      /usr/libexec/PlistBuddy -c "Add :CFBundleURLTypes:$INDEX:CFBundleURLSchemes array" "$INFO_PLIST"
      /usr/libexec/PlistBuddy -c "Add :CFBundleURLTypes:$INDEX:CFBundleURLSchemes:0 string $SCHEME" "$INFO_PLIST"
    fi
  fi
fi
