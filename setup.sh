#!/bin/bash
# Fire TV clean-home kit. One stage at a time, each prints its proof; STOP stages wait for you.
#
#   ./setup.sh <tv-ip> check      device, Fire OS, model, CPU, RAM, TV-or-stick  (read-only)
#   ./setup.sh <tv-ip> protect    save third-party apps to tvs/<model>-<ip>/protected.txt (never touched)
#   ./setup.sh <tv-ip> plan       build tvs/.../plan.txt from debloat-catalog.txt   -> STOP: review it
#   ./setup.sh <tv-ip> debloat    pm disable-user each line of plan.txt (reversible)
#   ./setup.sh <tv-ip> home       install Glass, make it home, wallpapers, screensaver delay
#   ./setup.sh <tv-ip> update     newer Glass on a TV already set up: keeps wallpapers, captions, settings
#   ./setup.sh <tv-ip> extras     offer Aerial Views, Jellyfin, F-Droid, Proton VPN, TizenTube (signed-key checked)
#   ./setup.sh <tv-ip> paintings  Paintings screensaver (art-mode frame): ~60 MB of 4K art, Customize switch
#   ./setup.sh <tv-ip> dns        network-wide ad/tracker blocking (AdGuard Private DNS), with a check
#   ./setup.sh <tv-ip> verify     Home proof, disabled list, protected diff
#   ./setup.sh <tv-ip> undo       re-enable everything in plan.txt and give Home back to Amazon
#
# Needs: adb (brew install android-platform-tools). Rebuilding Glass also needs openjdk@17 +
# android-commandlinetools (see PLAYBOOK.md); a prebuilt assets/glass.apk is used otherwise.
set -u
KIT="$(cd "$(dirname "$0")" && pwd)"
IP="${1:?usage: ./setup.sh <tv-ip> <stage>}"; STAGE="${2:?missing stage}"
export ANDROID_SERIAL="$IP:5555"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
APKSIGNER=$(ls /opt/homebrew/share/android-commandlinetools/build-tools/*/apksigner 2>/dev/null | tail -1)
GLASS=com.ivantrj.glasslauncher
SVC="$GLASS/$GLASS.HomeButtonService"

adb connect "$ANDROID_SERIAL" >/dev/null 2>&1
[ "$(adb get-state 2>/dev/null)" = device ] || { echo "TV at $IP not reachable or not authorized (accept the prompt on the TV)."; exit 1; }
tv() { adb shell "$@" </dev/null | tr -d '\r'; }

# After an (re)install the Glass service can come back half-connected (Android lists it by package
# name, not its label, and Home stops working): remove it and add it back until it's really up.
reconnect_service() {
  local cur try
  for try in 1 2 3; do
    cur=$(tv settings get secure enabled_accessibility_services | tr ':' '\n' | grep -v "^$SVC\$" | grep -v '^null$' | paste -sd: -)
    tv settings put secure enabled_accessibility_services "${cur:-null}"; sleep $((try * 2))
    tv settings put secure enabled_accessibility_services "'${cur:+$cur:}$SVC'"
    tv settings put secure accessibility_enabled 1; sleep $((try * 3))
    if tv dumpsys accessibility | grep -q 'Glass Home button'; then echo "Glass service: connected"; return 0; fi
  done
  echo "Glass service: NOT connected - restart the TV (Settings > My Fire TV > Restart), then run this again"
}
MODEL=$(tv getprop ro.product.model)
DIR="$KIT/tvs/$MODEL-$IP"; mkdir -p "$DIR"

case "$STAGE" in
check)
  adb devices
  echo "Fire OS:  $(tv getprop ro.build.version.name)"
  echo "Model:    $MODEL"
  echo "Android:  API $(tv getprop ro.build.version.sdk)   (Glass needs 28+)"
  echo "CPU ABIs: $(tv getprop ro.product.cpu.abilist)   (pick arm vs arm64 APKs from this)"
  echo "RAM:      $(tv grep MemTotal /proc/meminfo)"
  n=$(tv dumpsys tv_input | grep -o 'HDMIInputService/HW[0-9]*' | sort -u | wc -l | xargs)
  echo "Inputs:   $n HDMI ports$( [ "$n" -gt 0 ] && echo ' -> Fire TV Edition TV (Glass Inputs menu applies)' || echo ' -> streaming stick')"
  echo "Screen-off timer: $(tv settings get system screen_off_timeout) ms (huge = screensaver never starts)"
  ;;
protect)
  if [ -s "$DIR/protected.txt" ]; then echo "protected.txt already exists, not overwriting:"; else tv pm list packages -3 > "$DIR/protected.txt"; fi
  cat "$DIR/protected.txt"
  ;;
plan)
  [ -s "$DIR/protected.txt" ] || { echo "Run 'protect' first."; exit 1; }
  sys=$(tv pm list packages -s | sed 's/^package://')
  {
    echo "# Debloat plan for $MODEL at $IP — $(date '+%Y-%m-%d'). Delete or '#' any line to keep that package."
    grep -v '^[[:space:]]*#' "$KIT/debloat-catalog.txt" | grep '|' | while IFS='|' read -r pkg cat what; do
      pkg=$(echo "$pkg" | xargs)
      printf '%s\n' "$sys" | grep -qx "$pkg" || continue
      grep -qx "package:$pkg" "$DIR/protected.txt" && continue
      printf '%-48s # %s:%s\n' "$pkg" "$(echo "$cat" | xargs)" "$what"
    done
  } > "$DIR/plan.txt"
  cat "$DIR/plan.txt"
  echo; echo "STOP: review $DIR/plan.txt, then run: ./setup.sh $IP debloat"
  ;;
debloat)
  [ -s "$DIR/plan.txt" ] || { echo "Run 'plan' first."; exit 1; }
  grep -v '^[[:space:]]*#' "$DIR/plan.txt" | awk '{print $1}' | grep . | while read -r pkg; do
    grep -qx "package:$pkg" "$DIR/protected.txt" && { echo "SKIP protected $pkg"; continue; }
    printf '%-48s %s\n' "$pkg" "$(tv pm disable-user --user 0 "$pkg" 2>&1 | grep -o 'new state: .*\|protected package' || echo '?')"
  done
  echo "=== proof: pm list packages -d"; tv pm list packages -d
  ;;
home)
  APK="$KIT/assets/glass.apk"   # a fresh build of glass/ wins, a stale one doesn't
  BUILT="$KIT/glass/launcher/app/build/outputs/apk/debug/app-debug.apk"
  [ -f "$BUILT" ] && [ "$BUILT" -nt "$APK" ] && APK="$BUILT"
  echo "Installing $APK"
  out=$(adb install -r -g "$APK" 2>&1); echo "$out" | tail -1
  echo "$out" | grep -q '^Success' || { echo "Install failed: nothing changed. Free space above; Android wants ~10% free (adb shell pm trim-caches 2G)."; exit 1; }
  tv cmd package set-home-activity "$GLASS/.HomeActivity"
  tv settings put global alarm_manager_constants min_futurity=0   # Android 9: no 5 s delay after Home
  tv dpm set-active-admin "$GLASS/.SleepAdmin" | tail -1          # phone remote Sleep without the key helper
  tv am start -n "$GLASS/.HomeActivity" >/dev/null; sleep 3      # Glass makes its remote secret on first start
  reconnect_service   # last: device admin can restart Glass, leaving it half-connected
  "$KIT/bin/remote-helper" "$IP"                                  # phone remote: fast key helper
  # Wallpapers into Glass's private folder (debug build allows run-as)
  tv "run-as $GLASS mkdir -p files/wallpapers"
  for f in "$KIT"/assets/wallpapers/*.jpg; do
    n=$(basename "$f"); adb push "$f" /data/local/tmp/w.jpg >/dev/null
    tv "run-as $GLASS sh -c 'cat > \"files/wallpapers/$n\"' < /data/local/tmp/w.jpg"
  done; tv rm -f /data/local/tmp/w.jpg
  adb push "$KIT/assets/wallpapers/captions.txt" /data/local/tmp/c.txt >/dev/null
  tv "run-as $GLASS sh -c 'cat > files/wallpapers/captions.txt' < /data/local/tmp/c.txt"; tv rm -f /data/local/tmp/c.txt
  echo "wallpapers: $(tv run-as $GLASS ls files/wallpapers | wc -l)"
  [ "$(tv settings get system screen_off_timeout)" -gt 86400000 ] 2>/dev/null && tv settings put system screen_off_timeout 300000
  # "For you" movie/TV cards: your private TMDB key (~/.tmdb-key, chmod 600) — never stored in the kit
  if [ -s "$HOME/.tmdb-key" ]; then
    adb push "$HOME/.tmdb-key" /data/local/tmp/k >/dev/null
    tv "run-as $GLASS sh -c 'cat > files/tmdb.key && chmod 600 files/tmdb.key' < /data/local/tmp/k"; tv rm -f /data/local/tmp/k
    echo "TMDB key installed (suggestions appear on the next home visit)"
  else
    echo "No ~/.tmdb-key: the For you row stays off (see PLAYBOOK.md)"
  fi
  tv am start -n "$GLASS/.HomeActivity" >/dev/null
  echo "Glass installed. Next: ./setup.sh $IP extras  (optional), then verify"
  ;;
extras)
  [ -n "$APKSIGNER" ] || { echo "apksigner missing (brew install --cask android-commandlinetools; sdkmanager 'build-tools;35.0.0')"; exit 1; }
  arm64=$(tv getprop ro.product.cpu.abilist | grep -c arm64)
  work=$(mktemp -d)
  # name | package | repo | asset (jq regex) | pinned signer SHA-256 (verified Oct 2026)
  while IFS='|' read -r name pkg repo asset sig; do
    read -r -p "Install $name? [y/N] " ans </dev/tty; [ "$ans" = y ] || continue
    [ "$name" = TizenTube ] && asset=$([ "$arm64" = 1 ] && echo '^cobalt-arm64\.apk$' || echo '^cobalt-arm\.apk$')
    if [ "$repo" = fdroid ]; then url=https://f-droid.org/F-Droid.apk
    else url=$(curl -sfL "https://api.github.com/repos/$repo/releases/latest" | jq -r --arg a "$asset" '[.assets[] | select(.name | test($a))][0].browser_download_url'); fi
    [ -n "$url" ] && [ "$url" != null ] || { echo "  no download found"; continue; }
    curl -sfL -o "$work/a.apk" "$url" || { echo "  download failed"; continue; }
    got=$("$APKSIGNER" verify --print-certs "$work/a.apk" 2>/dev/null | grep -m1 'SHA-256 digest' | awk '{print $NF}')
    if [ "$got" != "$sig" ]; then echo "  SIGNING KEY MISMATCH ($got) - not installing"; continue; fi
    echo "  $(adb install -r "$work/a.apk" 2>&1 | tail -1)"
    if [ "$pkg" = com.neilturner.aerialviews ]; then
      D=com.neilturner.aerialviews/.ui.screensaver.DreamActivity
      tv settings put secure screensaver_components $D; tv settings put secure screensaver_default_component $D
      tv settings put secure screensaver_enabled 1
      [ "$(tv getprop ro.build.version.sdk)" -ge 30 ] && tv settings put secure amazon_ambient_enabled 0  # Fire OS 8
    fi
  done <<'EOF'
Aerial Views (screensaver)|com.neilturner.aerialviews|theothernt/AerialViews|^aerial-views-.*\.apk$|383b3b2df6d2b17cec60542c576408b0deae502f37749eb5ac7fef2f206fa51b
Jellyfin|org.jellyfin.androidtv|jellyfin/jellyfin-androidtv|-release\.apk$|d881796ed2a67ff6ef9f676828723c6b1fa18e09388962cba4abc4a594a69131
F-Droid (open-source app store)|org.fdroid.fdroid|fdroid||43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab
Proton VPN (open-source build)|ch.protonvpn.android.reproducible|ProtonVPN/android-app|openSource-release-fdroid\.apk$|dcc9439ec1a6c6a8d0203f3423ee42bcc8b970628e53cb73a0393f398dd5b853
TizenTube (ad-free YouTube)|io.gh.reisxd.tizentube.cobalt|reisxd/TizenTubeCobalt||378ea3935af1b95c32f6ce193f4ff7f420cdc2d7160c8c866d28d6a91042ec9a
EOF
  rm -rf "$work"
  ;;
update)
  APK="$KIT/assets/glass.apk"   # a fresh build of glass/ wins, a stale one doesn't
  BUILT="$KIT/glass/launcher/app/build/outputs/apk/debug/app-debug.apk"
  [ -f "$BUILT" ] && [ "$BUILT" -nt "$APK" ] && APK="$BUILT"
  tv df -h /data | tail -1
  echo "Installing $APK"
  out=$(adb install -r -g "$APK" 2>&1); echo "$out" | tail -1
  echo "$out" | grep -q '^Success' || { echo "Install failed: nothing changed. Free space above; Android wants ~10% free (adb shell pm trim-caches 2G)."; exit 1; }
  tv dpm set-active-admin "$GLASS/.SleepAdmin" 2>&1 | tail -1
  tv "run-as $GLASS ls files/paintings" 2>/dev/null | grep -q jpg && tv pm grant "$GLASS" android.permission.WRITE_SECURE_SETTINGS
  tv am start -n "$GLASS/.HomeActivity" >/dev/null; sleep 4
  reconnect_service   # last: device admin and grants can restart Glass, leaving it half-connected
  "$KIT/bin/remote-helper" "$IP"                                  # restarts it if it was the old version
  ;;
paintings)
  # Glass's Paintings screensaver: art in files/paintings, and the right to switch the screensaver
  # from Customize (Screensaver: Aerial Views | Paintings). Needs ~60 MB; Android refuses installs
  # when /data runs under ~10% free, so check first.
  tv df -h /data | tail -1
  tv pm grant "$GLASS" android.permission.WRITE_SECURE_SETTINGS
  tv "run-as $GLASS mkdir -p files/paintings"
  for f in "$KIT"/assets/paintings/*.jpg; do
    n=$(basename "$f"); adb push "$f" /data/local/tmp/p.jpg >/dev/null
    tv "run-as $GLASS sh -c 'cat > \"files/paintings/$n\"' < /data/local/tmp/p.jpg"
  done; tv rm -f /data/local/tmp/p.jpg
  echo "paintings: $(tv run-as $GLASS ls files/paintings | wc -l)  (pick Paintings in Customize > Screensaver)"
  tv df -h /data | tail -1
  ;;
dns)
  # Encrypted Private DNS (Android 9+) through AdGuard's free ad/tracker-blocking resolver
  tv settings put global private_dns_specifier dns.adguard-dns.com
  tv settings put global private_dns_mode hostname; sleep 5
  tv dumpsys connectivity | grep -o 'PrivateDnsServerName: [^ ]*' | head -1
  for d in doubleclick.net device-metrics-us.amazon.com netflix.com www.youtube.com; do
    printf '%-30s %s\n' "$d" "$(tv ping -c1 -W2 "$d" 2>&1 | head -1 | grep -o '([0-9.]*)')"; done
  echo "(127.0.0.1 / 0.0.0.0 = blocked). Undo: adb shell settings put global private_dns_mode off"
  ;;
verify)
  adb shell input keyevent KEYCODE_HOME; sleep 3
  echo "=== Home proof (must show $GLASS)"; tv "dumpsys activity activities | grep -i resumed"
  echo "=== pm list packages -d"; tv pm list packages -d
  echo "=== protected apps missing (must be empty)"
  tv pm list packages -3 | sort > "$DIR/now.txt"; sort "$DIR/protected.txt" | comm -23 - "$DIR/now.txt"
  echo "=== added since protect"; sort "$DIR/protected.txt" | comm -13 - "$DIR/now.txt"
  ;;
undo)
  [ -s "$DIR/plan.txt" ] && grep -v '^[[:space:]]*#' "$DIR/plan.txt" | awk '{print $1}' | grep . | while read -r pkg; do
    printf '%-48s %s\n' "$pkg" "$(tv pm enable --user 0 "$pkg" | tail -1)"; done
  tv cmd package set-home-activity com.amazon.tv.launcher/.ui.HomeActivity_vNext
  tv settings delete secure enabled_accessibility_services; tv settings put secure accessibility_enabled 0
  tv dpm remove-active-admin "$GLASS/.SleepAdmin" >/dev/null 2>&1 || true
  echo "Done. Optional: adb shell pm uninstall $GLASS"
  ;;
*) sed -n '2,12p' "$0"; exit 1 ;;
esac
