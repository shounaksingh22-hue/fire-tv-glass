#!/bin/sh
# Re-enable everything the debloat disabled and give the Amazon launcher back.
#   ./restore.sh              re-enable every package in debloat/disable-list.txt + launcher
#   ./restore.sh <package>    re-enable just one package
# Runs one pm command per shell call (chained pm commands can silently stop).
DIR="$(cd "$(dirname "$0")" && pwd)"
TV="$DIR/tools/tv"

enable() {
  printf '%-55s ' "$1"
  "$TV" shell "pm enable --user 0 $1" | tr -d '\n'; echo
}

if [ -n "$1" ]; then enable "$1"; exit 0; fi

grep -v '^#' "$DIR/debloat/disable-list.txt" | grep -v '^$' | while read -r pkg; do enable "$pkg"; done

# Launcher: re-enable Amazon's home screen and make it the default again
enable com.amazon.tv.launcher
enable com.amazon.firehomestarter
"$TV" shell "cmd package set-home-activity com.amazon.tv.launcher/.ui.HomeActivity_vNext"

# Turn off Glass's Home-button accessibility service, keeping any others you enabled
SVC=com.ivantrj.glasslauncher/com.ivantrj.glasslauncher.HomeButtonService
CUR=$("$TV" shell "settings get secure enabled_accessibility_services" | tr -d '\r\n')
REST=$(printf '%s' "$CUR" | tr ':' '\n' | grep -v "^$SVC\$" | grep -v '^null$' | paste -sd: -)
if [ -z "$REST" ]; then
  "$TV" shell "settings delete secure enabled_accessibility_services"
  "$TV" shell "settings put secure accessibility_enabled 0"
else
  "$TV" shell "settings put secure enabled_accessibility_services '$REST'"
fi
echo
echo "Done. Optional: uninstall the custom launcher with"
echo "  $TV shell 'pm uninstall com.ivantrj.glasslauncher'"
