#!/usr/bin/env bash
# Regenerates libapk-resources/src/main/resources/com/rk/libapk/resources/android-attributes.txt
# from a platform android.jar, using ARSCLib to read its resources.arsc.
#
#   ANDROID_JAR=$ANDROID_HOME/platforms/android-36/android.jar \
#   ARSCLIB_JAR=~/.gradle/caches/.../ARSCLib-1.4.0.jar \
#   ./generate.sh
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$here/../.." && pwd)"

ANDROID_JAR="${ANDROID_JAR:-${ANDROID_HOME:-$HOME/Android/Sdk}/platforms/android-36/android.jar}"
ARSCLIB_JAR="${ARSCLIB_JAR:-}"

if [[ -z "$ARSCLIB_JAR" ]]; then
  ARSCLIB_JAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1/io.github.reandroid/ARSCLib" -name 'ARSCLib-*.jar' ! -name '*sources*' 2>/dev/null | head -1 || true)"
fi
if [[ -z "$ARSCLIB_JAR" || ! -f "$ARSCLIB_JAR" ]]; then
  echo "ARSCLib jar not found in the Gradle cache; run './gradlew :libapk-resources:classes' first or set ARSCLIB_JAR" >&2
  exit 1
fi
if [[ ! -f "$ANDROID_JAR" ]]; then
  echo "android.jar not found at $ANDROID_JAR; set ANDROID_JAR" >&2
  exit 1
fi

out="$repo_root/libapk-resources/src/main/resources/com/rk/libapk/resources/android-attributes.txt"
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT

javac -cp "$ARSCLIB_JAR" -d "$classes" "$here/GenerateAndroidAttributeIds.java"
java -cp "$ARSCLIB_JAR:$classes" GenerateAndroidAttributeIds "$ANDROID_JAR" "$out"
