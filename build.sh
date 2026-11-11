#!/usr/bin/env bash
# Gradle-free Android build: validate -> aapt2 -> javac -> d8 -> zipalign -> apksigner
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

has_javac() {
  [ -n "${1:-}" ] && { [ -x "$1/bin/javac.exe" ] || [ -x "$1/bin/javac" ]; }
}

mixed_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}

resolve_java() {
  local candidate
  local candidates=()
  if [ -n "${JAVA_HOME:-}" ]; then candidates+=("$JAVA_HOME"); fi
  candidates+=("C:/Program Files/Android/openjdk/jdk-21.0.8")
  # Last resort on this workspace, only if the two locations above have no javac.
  if [ -d "$ROOT/../pixel/toolchain/jdk-21" ]; then
    candidates+=("$ROOT/../pixel/toolchain/jdk-21")
  fi
  for candidate in "${candidates[@]}"; do
    if has_javac "$candidate"; then
      export JAVA_HOME="$(mixed_path "$candidate")"
      return 0
    fi
  done
  echo "BUILD FAILED: set JAVA_HOME to a JDK. javac was not found." >&2
  exit 1
}

resolve_sdk() {
  local candidate
  local candidates=()
  if [ -n "${ANDROID_HOME:-}" ]; then candidates+=("$ANDROID_HOME"); fi
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then candidates+=("$ANDROID_SDK_ROOT"); fi
  candidates+=("C:/Users/hopel/AppData/Local/Android/Sdk")
  candidates+=("$ROOT/../.sdk")
  for candidate in "${candidates[@]}"; do
    if [ -d "$candidate/build-tools" ] && [ -d "$candidate/platforms" ]; then
      export ANDROID_HOME="$(mixed_path "$candidate")"
      return 0
    fi
  done
  echo "BUILD FAILED: set ANDROID_HOME to an Android SDK." >&2
  exit 1
}

pick_build_tools() {
  local best="" d
  while IFS= read -r d; do
    if [ -f "$d/aapt2.exe" ] || [ -f "$d/aapt2" ]; then best="$d"; fi
  done < <(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V)
  printf '%s' "$best"
}

pick_platform() {
  # Only android-<integer> folders. A name like android-37.0 is not a valid targetSdkVersion.
  local best="" d name level
  while IFS= read -r d; do
    [ -f "$d/android.jar" ] || continue
    name="${d##*/}"
    level="${name#android-}"
    case "$level" in
      ''|*[!0-9]*) continue ;;
    esac
    best="$d"
  done < <(find "$ANDROID_HOME/platforms" -mindepth 1 -maxdepth 1 -type d -name 'android-*' | sort -V)
  printf '%s' "$best"
}

tool() {
  local base="$BT/$1"
  if [ -f "$base.exe" ]; then printf '%s\n' "$base.exe"
  elif [ -f "$base.bat" ]; then printf '%s\n' "$base.bat"
  elif [ -f "$base" ]; then printf '%s\n' "$base"
  else printf '\n'
  fi
}

resolve_java
resolve_sdk

BT="$(pick_build_tools)"
if [ -z "$BT" ] || { [ ! -f "$BT/aapt2.exe" ] && [ ! -f "$BT/aapt2" ]; }; then
  echo "BUILD FAILED: no build-tools with aapt2 under $ANDROID_HOME" >&2
  exit 1
fi

PLATFORM="$(pick_platform)"
AJ="$PLATFORM/android.jar"
if [ ! -f "$AJ" ]; then
  echo "BUILD FAILED: no platforms/android-*/android.jar under $ANDROID_HOME" >&2
  exit 1
fi
TARGET="${PLATFORM##*-}"

if [ -n "${TRAVERSE_KEYSTORE:-}" ] && [ -f "$TRAVERSE_KEYSTORE" ]; then
  KS="$(mixed_path "$TRAVERSE_KEYSTORE")"
elif [ -n "${HOME:-}" ] && [ -f "$HOME/.android/debug.keystore" ]; then
  KS="$(mixed_path "$HOME/.android/debug.keystore")"
elif [ -f "C:/Users/hopel/.android/debug.keystore" ]; then
  KS="C:/Users/hopel/.android/debug.keystore"
else
  echo "BUILD FAILED: debug keystore not found. Set TRAVERSE_KEYSTORE." >&2
  exit 1
fi

if [ -x "$JAVA_HOME/bin/javac.exe" ]; then JAVAC="$JAVA_HOME/bin/javac.exe"; else JAVAC="$JAVA_HOME/bin/javac"; fi
if [ -x "$JAVA_HOME/bin/jar.exe" ]; then JAR="$JAVA_HOME/bin/jar.exe"; else JAR="$JAVA_HOME/bin/jar"; fi
AAPT2="$(tool aapt2)"
AAPT="$(tool aapt)"
D8="$(tool d8)"
ZIPALIGN="$(tool zipalign)"
APKSIGNER="$(tool apksigner)"
if [ -z "$AAPT2" ] || [ -z "$AAPT" ] || [ -z "$D8" ] || [ -z "$ZIPALIGN" ] || [ -z "$APKSIGNER" ]; then
  echo "BUILD FAILED: build-tools in $BT are incomplete (need aapt2, aapt, d8, zipalign, apksigner)." >&2
  exit 1
fi

OUT=build
APK=Traverse.apk
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

if ! command -v node >/dev/null 2>&1; then
  echo "BUILD FAILED: node is required to validate the curriculum." >&2
  exit 1
fi

echo "[0/7] validate content"
node tools/validate.js assets/curriculum.json

echo "[1/7] aapt2 compile"
"$AAPT2" compile --dir res -o "$OUT/res.zip"

echo "[2/7] aapt2 link  (platform $TARGET, $BT)"
"$AAPT2" link -o "$OUT/base.apk" -I "$AJ" --manifest AndroidManifest.xml \
  -R "$OUT/res.zip" --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version "$TARGET" \
  --version-code 1 --version-name 1.0 \
  --auto-add-overlay -A assets

echo "[3/7] javac  ($JAVA_HOME)"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
if ! "$JAVAC" -encoding UTF-8 -nowarn -Xlint:none -source 8 -target 8 \
    -bootclasspath "$AJ" -classpath "$AJ" -d "$OUT/classes" "@$OUT/sources.txt" \
    2> "$OUT/javac.log"; then
  grep -vE "bootstrap class path|^warning|source value 8|target value 8|deprecat" "$OUT/javac.log" >&2 || true
  echo "BUILD FAILED: javac" >&2
  exit 1
fi

echo "[4/7] jar + d8"
"$JAR" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$D8" --min-api 24 --output "$OUT/dex" "$OUT/classes.jar"

echo "[5/7] add dex"
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"
( cd "$OUT" && "$AAPT" add -f base.apk classes.dex >/dev/null )

echo "[6/7] zipalign"
"$ZIPALIGN" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "[7/7] sign"
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android \
  --out "$APK" "$OUT/aligned.apk"

bytes="$(stat -c%s "$APK" 2>/dev/null || wc -c < "$APK")"
echo "OK -> $APK  (${bytes} bytes)"
