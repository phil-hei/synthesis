#!/usr/bin/env bash
set -euo pipefail

SYNTHESIS_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export SYNTHESIS_ROOT

# Homebrew installiert versionierte Formeln absichtlich ohne globalen PATH-Link.
if [[ -x /opt/homebrew/opt/node@20/bin/node ]]; then
  export PATH="/opt/homebrew/opt/node@20/bin:$PATH"
fi

source "$SYNTHESIS_ROOT/runtime/versions.env"

version_major() {
  local value=${1#v}
  printf '%s\n' "${value%%.*}"
}

# Hauptversion eines java-Binaries; nicht an Zeile 1 gebunden, weil JAVA_TOOL_OPTIONS eine
# "Picked up …"-Zeile voranstellt.
java_major_of() {
  "$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1
}

java_major() {
  java_major_of java
}

kit_java_home() {
  if [[ -n ${SYNTHESIS_KIT_JAVA_HOME:-} && -x ${SYNTHESIS_KIT_JAVA_HOME}/bin/java ]]; then
    local explicit_major
    explicit_major=$(java_major_of "$SYNTHESIS_KIT_JAVA_HOME/bin/java")
    if [[ $explicit_major == "$SYNTHESIS_KIT_JAVA" ]]; then
      printf '%s\n' "$SYNTHESIS_KIT_JAVA_HOME"
      return 0
    fi
  fi
  local candidate
  for candidate in \
    "/opt/homebrew/opt/openjdk@$SYNTHESIS_KIT_JAVA/libexec/openjdk.jdk/Contents/Home" \
    "/usr/lib/jvm/java-$SYNTHESIS_KIT_JAVA-openjdk-amd64" \
    "/usr/lib/jvm/java-$SYNTHESIS_KIT_JAVA-openjdk-arm64" \
    "/usr/lib/jvm/java-$SYNTHESIS_KIT_JAVA-openjdk" \
    "/usr/lib/jvm/temurin-$SYNTHESIS_KIT_JAVA-jdk-amd64" \
    "/usr/lib/jvm/temurin-$SYNTHESIS_KIT_JAVA-jdk-arm64" \
    "/opt/java/openjdk-$SYNTHESIS_KIT_JAVA"; do
    if [[ -x $candidate/bin/java ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  if [[ -x /usr/libexec/java_home ]]; then
    local macos_jdk macos_major
    macos_jdk=$(/usr/libexec/java_home -v "$SYNTHESIS_KIT_JAVA" 2>/dev/null || true)
    if [[ -n $macos_jdk && -x $macos_jdk/bin/java ]]; then
      macos_major=$(java_major_of "$macos_jdk/bin/java")
      if [[ $macos_major == "$SYNTHESIS_KIT_JAVA" ]]; then
        printf '%s\n' "$macos_jdk"
        return 0
      fi
    fi
  fi
  return 1
}
