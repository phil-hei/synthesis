#!/bin/sh
set -eu

PATCH_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$PATCH_DIR/../.." && pwd)
ACTITOPP_DIR=${1:-"$REPO_ROOT/validation/actitoppNG"}
CONTRACT=synthesis-actitopp-v1
EXPECTED_COMMIT=69729de78326dca5e4853c94bac1161d51592513
BUILD_BEFORE=507b5d77da6afecd6d5b3f59d77417f12e2596d7d480f41ec6c244ce164b0c83
BUILD_AFTER=1fb1108f5329707eb292218c369b241505991d2c3c0c45a207ea2e718f18c316
PATCH_SHA=2c44695b719cb9aa713e77e72261ed3ad3f0a580884751dbdbee56182d2eec4f
RUNNER_SHA=3d84b980475e0c90a139fed1e51512e2f1d33793543c8c8e645eca74554199d4

# Keg-only Homebrew-JDK ohne globale Symlinks verwenden; andere Systeme können
# JAVA_HOME vor dem Aufruf explizit setzen.
if test -x /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home/bin/java; then
  JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
  export JAVA_HOME
  PATH="$JAVA_HOME/bin:$PATH"
  export PATH
fi

hash_of() {
  shasum -a 256 "$1" | awk '{print $1}'
}

test -d "$ACTITOPP_DIR/.git" || {
  echo "actiTopp-Clone fehlt: $ACTITOPP_DIR" >&2
  exit 2
}

actual_commit=$(git -C "$ACTITOPP_DIR" rev-parse HEAD)
test "$actual_commit" = "$EXPECTED_COMMIT" || {
  echo "Unerwarteter actiTopp-Commit: $actual_commit (erwartet $EXPECTED_COMMIT)" >&2
  exit 2
}

test "$(hash_of "$PATCH_DIR/actitoppNG.patch")" = "$PATCH_SHA" || {
  echo "Prüfsumme des actiTopp-Patches stimmt nicht" >&2
  exit 2
}
test "$(hash_of "$PATCH_DIR/SynthesisRunner.kt")" = "$RUNNER_SHA" || {
  echo "Prüfsumme des actiTopp-Runners stimmt nicht" >&2
  exit 2
}

build_file="$ACTITOPP_DIR/build.gradle.kts"
build_hash=$(hash_of "$build_file")
if test "$build_hash" = "$BUILD_BEFORE"; then
  git -C "$ACTITOPP_DIR" apply --unidiff-zero --check "$PATCH_DIR/actitoppNG.patch"
  git -C "$ACTITOPP_DIR" apply --unidiff-zero "$PATCH_DIR/actitoppNG.patch"
elif test "$build_hash" != "$BUILD_AFTER"; then
  echo "Unbekannter actiTopp-Buildstand: $build_hash" >&2
  exit 2
fi

runner_dir="$ACTITOPP_DIR/src/main/kotlin"
runner_file="$runner_dir/SynthesisRunner.kt"
mkdir -p "$runner_dir"
if test -f "$runner_file" && test "$(hash_of "$runner_file")" != "$(hash_of "$PATCH_DIR/SynthesisRunner.kt")"; then
  echo "Abweichender actiTopp-Runner vorhanden: $runner_file" >&2
  exit 2
fi
cp "$PATCH_DIR/SynthesisRunner.kt" "$runner_file"
printf 'contractVersion=%s\nupstreamCommit=%s\n' "$CONTRACT" "$EXPECTED_COMMIT" \
  > "$ACTITOPP_DIR/synthesis-contract.properties"

test "$(hash_of "$build_file")" = "$BUILD_AFTER" || {
  echo "actiTopp-Builddatei stimmt nach Installation nicht" >&2
  exit 2
}

(cd "$ACTITOPP_DIR" && sh gradlew --no-daemon classes)
echo "Vertrag $CONTRACT installiert und kompiliert."
