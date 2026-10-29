#!/bin/sh
# One-time setup: installs the TV helper, asks for your TV's IP address and checks the connection.
# Safe to run again (e.g. when your TV gets a new IP address).
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR" || exit 1

echo "== Installing the TV helper (one time, takes a minute)"
if ! command -v python3 >/dev/null 2>&1; then
  echo "Python 3 is missing. Install it from https://www.python.org/downloads/ and run ./setup.sh again."
  exit 1
fi
if [ ! -x .venv/bin/python ]; then
  python3 -m venv .venv || exit 1
fi
.venv/bin/pip install -q --disable-pip-version-check adb-shell || exit 1

echo
echo "== Your TV's IP address"
echo "On the TV, open Settings > My Fire TV > About > Network."
echo "The IP address looks like 192.168.1.23."
CUR=""
[ -f .tv-ip ] && CUR=$(head -n 1 .tv-ip)
while :; do
  if [ -n "$CUR" ]; then
    printf "Type it here and press Enter [%s]: " "$CUR"
  else
    printf "Type it here and press Enter: "
  fi
  read -r IP
  IP=$(printf '%s' "${IP:-$CUR}" | tr ',' '.' | tr -d ' ')
  if printf '%s' "$IP" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}$'; then
    break
  fi
  echo "That doesn't look like an IP address (four numbers with dots, like 192.168.1.23). Try again."
done
echo "$IP" > .tv-ip
echo "Saved."

echo
echo "== Android SDK (needed to build the launcher)"
if [ -f launcher/local.properties ]; then
  echo "Already set up."
else
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  if [ -d "$SDK" ]; then
    echo "sdk.dir=$SDK" > launcher/local.properties
    echo "Found it at $SDK"
  else
    echo "Not found. Install Android Studio (https://developer.android.com/studio), open it once,"
    echo "then run ./setup.sh again. You can still run ./debloat.sh without it."
  fi
fi
if ! java -version >/dev/null 2>&1 && [ ! -d "/Applications/Android Studio.app/Contents/jbr" ]; then
  echo "Java is missing (needed by ./install.sh). Installing Android Studio includes it."
fi

echo
echo "== Connecting to the TV"
echo "Look at your TV now: if it asks \"Allow USB debugging?\", tick \"Always allow\" and choose OK."
if MODEL=$(tools/tv shell 'getprop ro.product.model' 2>/dev/null) && [ -n "$MODEL" ]; then
  echo "Connected to: $MODEL"
  echo
  echo "All set. Next: ./debloat.sh, then ./install.sh"
else
  echo "Couldn't reach the TV at $IP. Check that:"
  echo "  - the TV is on and on the same Wi-Fi as this Mac"
  echo "  - ADB debugging is on (Settings > My Fire TV > Developer Options)"
  echo "  - the IP address is right, then run ./setup.sh again"
  exit 1
fi
