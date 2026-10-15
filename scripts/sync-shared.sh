#!/usr/bin/env bash
# Copies the shared detection data into the iOS Swift package, which cannot
# reference files outside its own directory.
#
#   scripts/sync-shared.sh          copy shared/ data into ios/SpamShieldCore
#   scripts/sync-shared.sh --check  exit non-zero if the copies are out of date
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="$root/ios/SpamShieldCore/Sources/SpamShieldCore/Resources"

# source:destination-file-name
files=(
  "shared/rules.json:rules.json"
  "shared/model/sms_classifier.json:sms_classifier.json"
)

mode="${1:-sync}"
case "$mode" in
  sync|--check) ;;
  *) echo "usage: $0 [--check]" >&2; exit 2 ;;
esac

status=0
mkdir -p "$dest"
for pair in "${files[@]}"; do
  src="$root/${pair%%:*}"
  out="$dest/${pair##*:}"
  if [[ "$mode" == "--check" ]]; then
    if ! diff -q "$src" "$out" >/dev/null 2>&1; then
      echo "out of date: ${out#"$root"/} (run scripts/sync-shared.sh)" >&2
      diff -u "$src" "$out" >&2 || true
      status=1
    fi
  else
    cp "$src" "$out"
    echo "copied ${pair%%:*} -> ${out#"$root"/}"
  fi
done

if [[ "$mode" == "--check" && $status -eq 0 ]]; then
  echo "shared data in sync"
fi
exit $status
