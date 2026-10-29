#!/bin/sh
# Disable every package in debloat/disable-list.txt (e.g. after a Fire OS update re-enabled some).
# One pm command per shell call: chained pm commands can silently stop after the first.
DIR="$(cd "$(dirname "$0")" && pwd)"
grep -v '^#' "$DIR/debloat/disable-list.txt" | grep -v '^$' | while read -r pkg; do
  printf '%-55s ' "$pkg"
  "$DIR/tools/tv" shell "pm disable-user --user 0 $pkg 2>&1" | grep -o 'new state: .*\|protected package' || echo "?"
done
