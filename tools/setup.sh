#!/usr/bin/env bash
# Re-creates the aarch64 Android build environment under /workspace/tools (idempotent).
# Layout:
#   /workspace/tools/jdk17            Temurin JDK 17 (aarch64)
#   /workspace/tools/android-sdk      cmdline-tools/latest, platforms;android-34, build-tools;34.0.0, platform-tools
#   /workspace/tools/gradle-8.7       Gradle 8.7 distribution
#   /workspace/tools/gradle-home      GRADLE_USER_HOME (dependency caches)
#   /workspace/tools/aarch64-tools    lzhiyong static aarch64 build tools (aapt2, aapt, zipalign, aidl, ...)
#   /workspace/tools/env.sh           env exports (sourced by build.sh)
set -euo pipefail
T=/workspace/tools
DL=/tmp/tools-dl
mkdir -p "$T" "$DL"
cd "$T"
log(){ echo "[setup $(date +%H:%M:%S)] $*"; }

ARCH=$(uname -m)
[ "$ARCH" = aarch64 ] || log "WARNING: host is $ARCH, this script targets aarch64"

# ---- JDK 17 (aarch64) ----
if ! "$T/jdk17/bin/java" -version >/dev/null 2>&1; then
  log "Installing Temurin JDK 17 aarch64"
  rm -rf "$T/jdk17"; mkdir -p "$T/jdk17"
  curl -fsSL -o "$DL/jdk.tgz" "https://api.adoptium.net/v3/binary/latest/17/ga/linux/aarch64/jdk/hotspot/normal/eclipse"
  tar xzf "$DL/jdk.tgz" -C "$T/jdk17" --strip-components=1
  rm -f "$DL/jdk.tgz"
fi
export JAVA_HOME=$T/jdk17
export PATH=$JAVA_HOME/bin:$PATH

# ---- Android cmdline-tools ----
SDK=$T/android-sdk
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  log "Installing Android cmdline-tools"
  rm -rf "$SDK/cmdline-tools"; mkdir -p "$SDK/cmdline-tools"
  curl -fsSL -o "$DL/cmd.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q -o "$DL/cmd.zip" -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -f "$DL/cmd.zip"
fi
export ANDROID_HOME=$SDK ANDROID_SDK_ROOT=$SDK

# ---- SDK packages ----
# sdkmanager's own downloader fails in this sandbox ("Failed to download any source lists"),
# so packages are fetched directly with curl from Google's repository and unpacked in place.
sdk_pkg(){ # $1=zip name  $2=target dir  $3=sha1
  local zip=$1 dest=$2 sha=$3
  [ -f "$dest/source.properties" ] && return 0
  log "Installing $zip -> $dest"
  curl -fsSL -o "$DL/$zip" "https://dl.google.com/android/repository/$zip"
  echo "$sha  $DL/$zip" | sha1sum -c - >/dev/null
  rm -rf "$DL/x" "$dest"; mkdir -p "$DL/x" "$(dirname "$dest")"
  unzip -q -o "$DL/$zip" -d "$DL/x"
  mv "$DL/x"/* "$dest"   # zip has a single top-level dir
  rm -rf "$DL/x" "$DL/$zip"
}
sdk_pkg platform-34-ext7_r03.zip      "$SDK/platforms/android-34"  1f2e9478d6a7601425ceaa553311dc43191f103d
sdk_pkg build-tools_r34-linux.zip     "$SDK/build-tools/34.0.0"    d6d58e0c6925a9e4d9a541e84cd1f405c2f9d2a9
sdk_pkg platform-tools_r37.0.1-linux.zip "$SDK/platform-tools"     477254aa5f903c15cf51001717bdf347fb6b53e0
mkdir -p "$SDK/licenses"
# standard SDK license hashes (accepting = same as `sdkmanager --licenses`)
printf '\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e' > "$SDK/licenses/android-sdk-license"

# ---- aarch64 static build tools (Google ships x86_64 only) ----
AT=$T/aarch64-tools
if [ ! -f "$AT/aapt2" ] || ! file -b "$AT/aapt2" | grep -q "ELF.*aarch64"; then
  log "Installing lzhiyong aarch64 static build tools 34.0.3"
  rm -rf "$AT" "$DL/at"; mkdir -p "$DL/at"
  curl -fsSL -o "$DL/at.zip" https://github.com/lzhiyong/android-sdk-tools/releases/download/34.0.3/android-sdk-tools-static-aarch64.zip
  unzip -q -o "$DL/at.zip" -d "$DL/at"
  # flatten: collect all ELF executables into $AT
  mkdir -p "$AT"
  find "$DL/at" -type f | while read -r f; do
    if file -b "$f" | grep -q 'ELF.*aarch64'; then cp -f "$f" "$AT/"; fi
  done
  chmod +x "$AT"/*
  rm -rf "$DL/at" "$DL/at.zip"
fi
# replace x86_64 binaries in build-tools/34.0.0 and platform-tools with aarch64 ones (keep .x86 backups)
for dir in "$SDK/build-tools/34.0.0" "$SDK/platform-tools"; do
  [ -d "$dir" ] || continue
  for b in "$AT"/*; do
    n=$(basename "$b")
    if [ -f "$dir/$n" ] && ! file -bL "$dir/$n" | grep -q aarch64; then
      [ -f "$dir/$n.x86" ] || mv "$dir/$n" "$dir/$n.x86"
      cp -f "$b" "$dir/$n"; chmod +x "$dir/$n"
    fi
  done
done

# ---- Gradle 8.7 ----
if [ ! -x "$T/gradle-8.7/bin/gradle" ]; then
  log "Installing Gradle 8.7"
  curl -fsSL -o "$DL/gradle.zip" https://services.gradle.org/distributions/gradle-8.7-bin.zip
  rm -rf "$T/gradle-8.7"
  unzip -q -o "$DL/gradle.zip" -d "$T"
  rm -f "$DL/gradle.zip"
fi
mkdir -p "$T/gradle-home"
cat > "$T/gradle-home/gradle.properties" <<'EOF'
org.gradle.java.home=/workspace/tools/jdk17
EOF

# ---- env file ----
cat > "$T/env.sh" <<'EOF'
export JAVA_HOME=/workspace/tools/jdk17
export ANDROID_HOME=/workspace/tools/android-sdk
export ANDROID_SDK_ROOT=/workspace/tools/android-sdk
export GRADLE_USER_HOME=/workspace/tools/gradle-home
export AAPT2=/workspace/tools/aarch64-tools/aapt2
export PATH=/workspace/tools/jdk17/bin:/workspace/tools/gradle-8.7/bin:/workspace/tools/android-sdk/platform-tools:/workspace/tools/android-sdk/build-tools/34.0.0:$PATH
EOF

log "Verify:"
java -version 2>&1 | head -1
"$T/gradle-8.7/bin/gradle" --version 2>/dev/null | grep -E '^Gradle' || true
file "$AT/aapt2" | cut -c1-120
"$AT/aapt2" version 2>&1 | head -1 || true
log "DONE"
