#!/bin/sh
# Build the Glass launcher, install it on the Fire TV and make it the home screen.
#   ./install.sh             build + install + set as default home + disable Amazon launcher
#   ./install.sh --no-home   build + install + open it, leave the default home alone
# Needs: JDK 17+, Android SDK (sdk.dir in launcher/local.properties), Python venv in .venv
# (./setup.sh creates both). TV address: .tv-ip file or TV_IP env var.
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
TV="$DIR/tools/tv"
PKG=com.ivantrj.glasslauncher
APK="$DIR/launcher/app/build/outputs/apk/debug/app-debug.apk"

# Use Android Studio's bundled Java when there's no other one
if ! java -version >/dev/null 2>&1 && [ -z "$JAVA_HOME" ]; then
  JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  [ -d "$JBR" ] && export JAVA_HOME="$JBR"
fi
if [ ! -f "$DIR/launcher/local.properties" ]; then
  echo "Android SDK not set up yet. Run ./setup.sh first."; exit 1
fi

echo "== Building"
(cd "$DIR/launcher" && ./gradlew -q assembleDebug)

echo "== Pushing APK (base64 chunks, md5 verified)"
"$TV" push "$APK" /data/local/tmp/glass.apk

echo "== Installing"
"$TV" shell "pm install -r -g /data/local/tmp/glass.apk"
"$TV" shell "rm /data/local/tmp/glass.apk"
# Android 11 only shows the Wi-Fi name to apps with location permission (-g grants it, this makes sure)
"$TV" shell "pm grant $PKG android.permission.ACCESS_FINE_LOCATION" || true

if [ "$1" != "--no-home" ]; then
  echo "== Setting default home"
  "$TV" shell "cmd package set-home-activity $PKG/.HomeActivity"
  # One pm command per shell call: chained pm commands can silently stop after the first.
  # Fire OS 8 refuses this ("protected package"); the accessibility service below covers it.
  if "$TV" shell "pm disable-user --user 0 com.amazon.tv.launcher 2>&1" | grep -q "new state: disabled"; then
    echo "Amazon launcher disabled"
  else
    echo "Amazon launcher is protected on this Fire OS build (expected); using the Home redirect"
  fi

  echo "== Enabling the Home button redirect (accessibility service)"
  SVC="$PKG/$PKG.HomeButtonService"
  CUR=$("$TV" shell "settings get secure enabled_accessibility_services" | tr -d '\r\n')
  case "$CUR" in
    *"$SVC"*) ;;
    null|"") "$TV" shell "settings put secure enabled_accessibility_services $SVC" ;;
    *) "$TV" shell "settings put secure enabled_accessibility_services '$CUR:$SVC'" ;;
  esac
  "$TV" shell "settings put secure accessibility_enabled 1"
fi

"$TV" shell "am start -n $PKG/.HomeActivity" >/dev/null
echo "Done."
