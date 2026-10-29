#!/bin/bash
# Nightly Fire TV upkeep for one TV:  ./maintain.sh <tv-ip>   (schedule: see PLAYBOOK.md)
#   1. Re-disables the approved bloat list (Fire OS updates can switch packages back on).
#      Never touches anything in protected.txt.
#   2. Re-asserts the Glass home setup: accessibility service, alarm tweak, screensaver.
#   3. Updates the GitHub-sourced apps (TizenTube, Jellyfin, Aerial Views) when a newer
#      release exists - only if the new APK is signed with the same key as the installed one.
# Log: ~/Library/Logs/fire-tv-maintain.log
set -u
KIT="$(cd "$(dirname "$0")" && pwd)"
IP="${1:?usage: maintain.sh <tv-ip>}"
export ANDROID_SERIAL="$IP:5555"
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
APKSIGNER=/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0/apksigner
GLASS=com.ivantrj.glasslauncher
SVC="$GLASS/$GLASS.HomeButtonService"
LOG="$HOME/Library/Logs/fire-tv-maintain-$IP.log"
exec >>"$LOG" 2>&1
echo "=== $(date '+%Y-%m-%d %H:%M:%S') start"

adb connect "$ANDROID_SERIAL" >/dev/null
if [ "$(adb get-state 2>/dev/null)" != "device" ]; then
  echo "TV not reachable (off or asleep); will try again next run"; exit 0
fi
DIR="$KIT/tvs/$(adb shell getprop ro.product.model | tr -d '\r')-$IP"
[ -s "$DIR/protected.txt" ] || { echo "no $DIR/protected.txt - run setup.sh first"; exit 1; }
tvsh() { adb shell "$@" </dev/null | tr -d '\r'; }

# ---- 1. bloat stays disabled
disabled=$(tvsh pm list packages -d)
while read -r pkg; do
  [ -z "$pkg" ] && continue
  grep -qx "package:$pkg" "$DIR/protected.txt" && { echo "skip protected $pkg"; continue; }
  printf '%s\n' "$disabled" | grep -qx "package:$pkg" && continue
  echo "re-disable $pkg: $(tvsh pm disable-user --user 0 "$pkg" 2>&1 | tail -1)"
done < <(grep -v '^[[:space:]]*#' "$DIR/plan.txt" | awk '{print $1}' | grep .)

# ---- 2. Glass stays the home screen; Aerial Views stays the screensaver
cur=$(tvsh settings get secure enabled_accessibility_services)
case "$cur" in
  *"$SVC"*) ;;
  null|"") tvsh settings put secure enabled_accessibility_services "$SVC"; echo "re-enabled Glass service" ;;
  *) tvsh settings put secure enabled_accessibility_services "'$cur:$SVC'"; echo "re-enabled Glass service" ;;
esac
tvsh settings put secure accessibility_enabled 1
tvsh settings put global alarm_manager_constants min_futurity=0
DREAM=com.neilturner.aerialviews/.ui.screensaver.DreamActivity
case "$(tvsh settings get secure screensaver_components)" in
  "$DREAM"|"$GLASS/.PaintingsDream") ;;   # Aerial Views, or Glass Paintings picked in Customize
  *)
  tvsh settings put secure screensaver_components "$DREAM"
  tvsh settings put secure screensaver_default_component "$DREAM"
  echo "re-set screensaver" ;;
esac
# Screensaver starts when the idle timer runs out; Fire OS ships it as "never"
if [ "$(tvsh settings get system screen_off_timeout)" -gt 86400000 ] 2>/dev/null; then
  tvsh settings put system screen_off_timeout 300000
  echo "re-set screensaver delay to 5 min"
fi

# ---- 3. GitHub app updates: package | repo | asset file name
TT_ASSET=cobalt-arm.apk; tvsh getprop ro.product.cpu.abilist | grep -q arm64 && TT_ASSET=cobalt-arm64.apk
signer() { "$APKSIGNER" verify --print-certs "$1" 2>/dev/null | grep -m1 'SHA-256 digest' | awk '{print $NF}'; }
while IFS='|' read -r pkg repo asset; do
  installed=$(tvsh dumpsys package "$pkg" | grep -m1 versionName | sed 's/.*versionName=//')
  [ -z "$installed" ] && { echo "$pkg not installed, skipping"; continue; }
  rel=$(curl -sfL "https://api.github.com/repos/$repo/releases/latest") || { echo "$pkg: GitHub unreachable"; continue; }
  tag=$(printf '%s' "$rel" | jq -r '.tag_name' | sed 's/^v//')
  asset="${asset//\{TAG\}/$tag}"
  url=$(printf '%s' "$rel" | jq -r --arg a "$asset" '.assets[] | select(.name == $a) | .browser_download_url')
  if [ -z "$url" ] || [ "$tag" = "null" ]; then echo "$pkg: no asset $asset in latest release"; continue; fi
  if [ "$tag" = "$installed" ]; then echo "$pkg up to date ($installed)"; continue; fi
  work=$(mktemp -d)
  curl -sfL -o "$work/new.apk" "$url" || { echo "$pkg: download failed"; rm -rf "$work"; continue; }
  adb pull "$(tvsh pm path "$pkg" | head -1 | sed 's/^package://')" "$work/old.apk" >/dev/null
  if [ -n "$(signer "$work/new.apk")" ] && [ "$(signer "$work/new.apk")" = "$(signer "$work/old.apk")" ]; then
    echo "$pkg $installed -> $tag: $(adb install -r "$work/new.apk" 2>&1 | tail -1)"
  else
    echo "$pkg $installed -> $tag: SKIPPED, signing key differs from the installed app"
  fi
  rm -rf "$work"
done <<EOF
io.gh.reisxd.tizentube.cobalt|reisxd/TizenTubeCobalt|$TT_ASSET
org.jellyfin.androidtv|jellyfin/jellyfin-androidtv|jellyfin-androidtv-v{TAG}-release.apk
com.neilturner.aerialviews|theothernt/AerialViews|aerial-views-{TAG}.apk
EOF
# ---- 4. Storage: Fire TVs ship with ~4 GB; below 600 MB free Android refuses installs/updates
free_kb=$(tvsh df /data | tail -1 | awk '{print $4}')
case "$free_kb" in *G) ;; *M) [ "${free_kb%M}" -lt 600 ] && { tvsh pm trim-caches 4G; echo "cleared app caches (was $free_kb free)"; } ;; esac

# ---- 5. New wallpaper: Wikimedia Commons Picture of the Day (free licence, wide landscapes only)
G=com.ivantrj.glasslauncher
POTD="$DIR/potd.txt"; touch "$POTD"
day="${POTD_DAY:-$(date -u +%Y/%m/%d)}"  # POTD_DAY=YYYY/MM/DD to fetch a past day
if ! grep -q "^$day|" "$POTD"; then
  feed=$(curl -sfL -A 'fire-tv-glass/1.0 (github.com/shounaksingh22-hue/fire-tv-glass)' "https://api.wikimedia.org/feed/v1/wikipedia/en/featured/$day")
  src=$(printf '%s' "$feed" | jq -r '.image.image.source // empty')
  w=$(printf '%s' "$feed" | jq -r '.image.image.width // 0'); h=$(printf '%s' "$feed" | jq -r '.image.image.height // 1')
  if [ -n "$src" ] && [ "$w" -ge 1920 ] && [ $((w * 100)) -ge $((h * 145)) ]; then
    # Name from the file title, caption from the description's first sentence; both kept to plain characters
    cap=$(printf '%s' "$feed" | jq -r '.image.description.text // empty' | tr '\n|' '  ' | sed -e 's/\. .*/./' -e 's/ *$//' | cut -c1-90)
    [ -z "$cap" ] && cap="Wikimedia Commons Picture of the Day"
    # Short name for Customize: the caption's first words (no parentheses), whole words up to 28 chars, no dangling 'at/in/of…'
    name=$(printf '%s' "$cap" | sed -e 's/([^)]*)//g' -e 's/[^A-Za-z0-9 ,-]//g' \
      | awk '{n=""; for (i=1;i<=NF;i++) { t=(n==""?$i:n" "$i); if (length(t)>28) break; n=t } print n}' \
      | sed -E -e 's/[ ,-]*$//' -e 's/( (at|in|of|on|by|the|and|with|from|near|a|an|to|for))+$//')
    [ -z "$name" ] && name="Picture $(date -u +%m-%d)"
    work=$(mktemp -d)
    if [ -n "$name" ] && curl -sfL -A 'fire-tv-glass/1.0 (github.com/shounaksingh22-hue/fire-tv-glass)' -o "$work/src" "$src" \
       && sips -Z 1920 -s format jpeg -s formatOptions 85 "$work/src" --out "$work/w.jpg" >/dev/null 2>&1; then
      adb push "$work/w.jpg" /data/local/tmp/w.jpg >/dev/null
      tvsh "run-as $G sh -c 'cat > \"files/wallpapers/$name.jpg\"' < /data/local/tmp/w.jpg"
      tvsh "run-as $G sh -c 'echo \"$name|$cap\" >> files/wallpapers/captions.txt'"
      tvsh rm -f /data/local/tmp/w.jpg
      echo "$day|$name" >> "$POTD"
      # Not switched now: Glass shows the newest unseen photo at its nightly 12 AM change
      echo "new wallpaper (shows at 12 AM): $name — $cap"
      # keep the newest 20 Pictures of the Day
      while [ "$(wc -l < "$POTD")" -gt 20 ]; do
        old=$(head -1 "$POTD" | cut -d'|' -f2-)
        tvsh "run-as $G rm -f \"files/wallpapers/$old.jpg\""
        tvsh "run-as $G sh -c 'grep -v \"^$old|\" files/wallpapers/captions.txt > files/c.tmp; mv files/c.tmp files/wallpapers/captions.txt'"
        tail -n +2 "$POTD" > "$POTD.tmp" && mv "$POTD.tmp" "$POTD"
      done
    else
      echo "picture of the day: download failed"
    fi
    rm -rf "$work"
  else
    echo "$day|" >> "$POTD.skipped"; echo "picture of the day not a wide landscape (${w}x${h}), skipped"
  fi
fi
# ---- 6. Phone remote: its fast key helper dies with a TV reboot and only a computer can restart it
"$KIT/bin/remote-helper" "$IP"
echo "=== done"
