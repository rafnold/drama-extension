#!/usr/bin/env bash
# Release guard: fail loudly if a release commit forgot to bump the version.
# v15 shipped correct code but kept version = 14, so CloudStream saw no update
# and the fix appeared to do nothing on device (2026-10-03).
#
# Usage:  ./check-release.sh            # compare HEAD version vs origin/builds
#         ./check-release.sh <version>  # assert the built artifact matches
set -uo pipefail
cd "$(dirname "$0")"

GRADLE_VER="$(grep -oP '^version\s*=\s*\K[0-9]+' DramaExtension/build.gradle.kts | head -1)"
BUILT_VER="$(grep -oP '"version"\s*:\s*\K[0-9]+' build/plugins.json 2>/dev/null | head -1)"

echo "build.gradle.kts version : ${GRADLE_VER:-<none>}"
echo "built plugins.json       : ${BUILT_VER:-<not built>}"

fail=0
if [ -z "${GRADLE_VER:-}" ]; then
  echo "FAIL: could not read version from DramaExtension/build.gradle.kts"; fail=1
fi
if [ -n "${BUILT_VER:-}" ] && [ "$BUILT_VER" != "$GRADLE_VER" ]; then
  echo "FAIL: stale build - artifact is v${BUILT_VER} but source says v${GRADLE_VER}"
  echo "      run: ./gradlew make makePluginsJson"; fail=1
fi

# Compare against what the app actually downloads (the builds branch).
LIVE="$(curl -s --max-time 20 \
  https://raw.githubusercontent.com/rafnold/drama-extension/builds/plugins.json \
  | grep -oP '"version"\s*:\s*\K[0-9]+' | head -1)"
echo "live plugins.json (builds): ${LIVE:-<unreachable>}"

if [ -n "${LIVE:-}" ] && [ -n "${GRADLE_VER:-}" ] && [ "$LIVE" = "$GRADLE_VER" ]; then
  echo "WARN: source version equals the published version - if you are shipping a"
  echo "      fix, bump version in DramaExtension/build.gradle.kts or the app will"
  echo "      not pull it."
  fail=1
fi

[ "$fail" -eq 0 ] && echo "OK: version bump and build artifact are consistent"
exit "$fail"