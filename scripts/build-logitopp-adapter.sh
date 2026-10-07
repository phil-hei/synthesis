#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/runtime-common.sh"
source "$SYNTHESIS_ROOT/runtime/kit-sources.env"
(cd "$SYNTHESIS_ROOT/legacy/logitopp-returns-v1/harness-artifacts" && shasum -a 256 -c manifest.sha256 >/dev/null)
export JAVA_HOME
JAVA_HOME=$(kit_java_home)
export PATH="$JAVA_HOME/bin:$PATH"
source_dir=$(mktemp -d "$SYNTHESIS_ROOT/.runtime-logitopp-source.XXXXXX")
trap 'rm -rf "$source_dir"' EXIT
mkdir "$source_dir/logitopp" "$source_dir/rastatt" "$source_dir/gradle-runner"
git -C "$SYNTHESIS_ROOT/validation/actitoppNG" archive "$SYNTHESIS_ACTITOPP_COMMIT" gradlew gradle | tar -x -C "$source_dir/gradle-runner"
git -C "$SYNTHESIS_ROOT/validation/logitopp" archive "$SYNTHESIS_LOGITOPP_COMMIT" | tar -x -C "$source_dir/logitopp"
git -C "$SYNTHESIS_ROOT/validation/mobitopp-rastatt" archive "$SYNTHESIS_MOBITOPP_RASTATT_COMMIT" | tar -x -C "$source_dir/rastatt"
(cd "$source_dir/gradle-runner" && sh gradlew -p "$SYNTHESIS_ROOT/adapters/logitopp" --no-daemon -PlogitoppSourceDir="$source_dir/logitopp" -PrastattSourceDir="$source_dir/rastatt" test installDist)
dist="$SYNTHESIS_ROOT/adapters/logitopp/build/install/synthesis-logitopp-adapter"
printf 'contractVersion=synthesis-logitopp-adapter-v2\nlogitoppCommit=%s\nrastattCommit=%s\n' "$SYNTHESIS_LOGITOPP_COMMIT" "$SYNTHESIS_MOBITOPP_RASTATT_COMMIT" > "$dist/synthesis-contract.properties"
echo "Separater logiTopp-Adapter bereit: $dist"
