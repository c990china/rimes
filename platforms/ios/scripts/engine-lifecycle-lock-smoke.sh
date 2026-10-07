#!/bin/bash
set -euo pipefail

# Host probe of the actual iOS bridge and bundled librime. It uses fresh exec
# children because POSIX file locks cannot be verified within the owning process.
repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$repo_root"
resources_path="${1:-$repo_root/platforms/ios/Resources/EngineData}"
library_dir="$repo_root/Vendor/ios-build/host/rime/lib"
mkdir -p "$repo_root/platforms/ios/build"
probe_root="$(mktemp -d "$repo_root/platforms/ios/build/engine-lifecycle-lock.XXXXXX")"
DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}" xcrun clang++ \
    -std=c++17 -fobjc-arc -framework Foundation \
    -Iplatforms/ios/Bridge -IVendor/ios-build/librime/src \
    platforms/ios/scripts/engine-lifecycle-lock-probe.mm platforms/ios/Bridge/RimeMobile.mm \
    "$library_dir/librime.dylib" -Wl,-rpath,"$library_dir" -o "$probe_root/probe"
"$probe_root/probe" "$resources_path" "$probe_root/users" > "$probe_root/report.json"
cat "$probe_root/report.json"
printf '\nEvidence: %s\n' "$probe_root/report.json"
