#!/usr/bin/env bash
# Build assembleRelease for /workspace/work/fnalbum on aarch64.
# Copies the project to /tmp/build/fnalbum (local disk, avoids JuiceFS slowness/locking),
# builds there, copies the APK to /workspace/work/out/app-release.apk.
# Full log: /workspace/tools/build.log   Exit code != 0 on failure.
# Usage: build.sh [extra gradle args]   (BUILD_IN_PLACE=1 to build directly on /workspace)
set -uo pipefail
T=/workspace/tools
SRC=/workspace/work/fnalbum
WORK=/tmp/build/fnalbum
OUT=/workspace/work/out
LOG=$T/build.log
START=$(date +%s)

# (Re)create environment if missing
if [ ! -x "$T/jdk17/bin/java" ] || [ ! -x "$T/gradle-8.7/bin/gradle" ] || [ ! -f "$T/aarch64-tools/aapt2" ] || [ ! -f "$T/env.sh" ]; then
  echo "Environment incomplete, running setup.sh ..."
  bash "$T/setup.sh" || { echo "setup.sh failed"; exit 2; }
fi
source "$T/env.sh"

# Keep the source project's machine-specific settings pointed at this toolchain
AAPT2_PATH=$T/android-sdk/build-tools/34.0.0/aapt2
echo "sdk.dir=$ANDROID_HOME" > "$SRC/local.properties"
if grep -q '^android.aapt2FromMavenOverride=' "$SRC/gradle.properties" 2>/dev/null; then
  sed -i "s#^android.aapt2FromMavenOverride=.*#android.aapt2FromMavenOverride=$AAPT2_PATH#" "$SRC/gradle.properties"
else
  echo "android.aapt2FromMavenOverride=$AAPT2_PATH" >> "$SRC/gradle.properties"
fi

if [ "${BUILD_IN_PLACE:-0}" = 1 ]; then
  WORK=$SRC
else
  # sync sources to local disk (no rsync on this box: tar with excludes, then prune deleted files)
  mkdir -p "$WORK"
  find "$WORK" -mindepth 1 -maxdepth 1 ! -name build ! -name .gradle ! -name app -exec rm -rf {} +
  [ -d "$WORK/app" ] && find "$WORK/app" -mindepth 1 -maxdepth 1 ! -name build -exec rm -rf {} +
  (cd "$SRC" && tar cf - --exclude='./build' --exclude='./app/build' --exclude='./.gradle' --exclude='./.idea' --exclude='*/build/*' .) | (cd "$WORK" && tar xf -)
fi

echo "== build started $(date '+%F %T') in $WORK" | tee "$LOG"
cd "$WORK"
gradle --console=plain -p "$WORK" assembleRelease "$@" >>"$LOG" 2>&1
RC=$?
DUR=$(( $(date +%s) - START ))

APK=$(ls -t "$WORK"/app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)
if [ $RC -eq 0 ] && [ -n "$APK" ]; then
  mkdir -p "$OUT"
  cp -f "$APK" "$OUT/app-release.apk"
  echo "== BUILD OK in ${DUR}s: $OUT/app-release.apk ($(stat -c %s "$OUT/app-release.apk") bytes)" | tee -a "$LOG"
  exit 0
fi
echo "== BUILD FAILED (rc=$RC) after ${DUR}s. Full log: $LOG" | tee -a "$LOG"
echo "---- errors (last 60) ----"
grep -E '^e: |error|FAILURE|What went wrong' "$LOG" | tail -60
[ $RC -eq 0 ] && RC=1
exit $RC
