#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
exec bash "$repo_root/platforms/ios/scripts/engine-lifecycle-lock-smoke.sh" "$@"
