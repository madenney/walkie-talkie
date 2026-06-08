#!/usr/bin/env bash
#
# Build the Android app and install it on the phone over Tailscale wireless ADB.
#
# One-time setup (already done): the phone was paired via Wireless debugging and
# switched to a fixed adb port with `adb tcpip 5555`. After a phone *reboot* the
# tcpip mode resets — re-run wireless-debugging pairing once, then `adb tcpip 5555`.
#
# Usage: ./scripts/deploy-app.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ANDROID_DIR="$PROJECT_ROOT/android"

PHONE="${PHONE_ADB_ADDR:-100.123.218.27:5555}"   # Tailscale IP : fixed adb port
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

echo "==> Building debug APK..."
( cd "$ANDROID_DIR" && ./gradlew assembleDebug )

echo "==> Connecting to phone at $PHONE..."
"$ADB" connect "$PHONE" >/dev/null 2>&1 || true

if ! "$ADB" -s "$PHONE" get-state >/dev/null 2>&1; then
    echo "ERROR: phone not reachable at $PHONE." >&2
    echo "  - Is the phone awake and on Tailscale?" >&2
    echo "  - After a reboot: re-pair via Wireless debugging, then 'adb tcpip 5555'." >&2
    exit 1
fi

echo "==> Installing $APK ..."
"$ADB" -s "$PHONE" install -r "$APK"

echo "==> Done. App updated on phone."
