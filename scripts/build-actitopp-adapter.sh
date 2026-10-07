#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/runtime-common.sh"
source "$SYNTHESIS_ROOT/runtime/kit-sources.env"
kit_repo=${1:-"$SYNTHESIS_ROOT/validation/actitoppNG"}
[[ $(git -C "$kit_repo" rev-parse HEAD) == "$SYNTHESIS_ACTITOPP_COMMIT" ]] || { echo "Unbekannter actiTopp-Quellstand" >&2; exit 2; }
export JAVA_HOME
JAVA_HOME=$(kit_java_home) || { echo "JDK $SYNTHESIS_KIT_JAVA fehlt" >&2; exit 2; }
export PATH="$JAVA_HOME/bin:$PATH"
# Export only committed upstream sources; neither patches nor untracked runners enter the build.
source_dir=$(mktemp -d "$SYNTHESIS_ROOT/.runtime-actitopp-source.XXXXXX")
trap 'rm -rf "$source_dir"' EXIT
git -C "$kit_repo" archive "$SYNTHESIS_ACTITOPP_COMMIT" | tar -x -C "$source_dir"
(cd "$source_dir" && sh gradlew -p "$SYNTHESIS_ROOT/adapters/actitopp" --no-daemon   -PkitSourceDir="$source_dir" installDist)
dist="$SYNTHESIS_ROOT/adapters/actitopp/build/install/synthesis-actitopp-adapter"
printf 'contractVersion=synthesis-actitopp-households-v2\nupstreamCommit=%s\n' "$SYNTHESIS_ACTITOPP_COMMIT" > "$dist/synthesis-contract.properties"
echo "Separater actiTopp-Adapter bereit: $dist"
