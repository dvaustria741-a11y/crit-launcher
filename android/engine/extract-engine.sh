#!/usr/bin/env bash
# Unpack the engine pieces from the Zalith APKs built by android-engine.yml into the Crit app.
# usage: extract-engine.sh <zalith-arm.apk> <zalith-arm64.apk>
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
app="$here/../app/src/main"
tmp="$(mktemp -d)"
for apk in "$@"; do
  d="$tmp/$(basename "$apk" .apk)"
  unzip -q "$apk" 'lib/*' 'assets/runtimes/*' 'assets/app_runtime/*' 'assets/components/*' -d "$d"
  mkdir -p "$app/jniLibs" "$app/assets/engine"
  cp -r "$d"/lib/* "$app/jniLibs/"          # lib/<abi>/*.so
  cp -r "$d"/assets/* "$app/assets/engine/"  # runtimes, app_runtime, components
done
rm -rf "$tmp"
echo "engine extracted into $app (jniLibs/, assets/engine/)"
