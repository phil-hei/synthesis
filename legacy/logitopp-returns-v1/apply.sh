#!/bin/sh
set -eu

PATCH_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$PATCH_DIR/../.." && pwd)
HARNESS_DIR=${1:-"$REPO_ROOT/validation/mobitopp-rastatt"}
LOGITOPP_DIR=${2:-"$REPO_ROOT/validation/logitopp"}
CONTRACT=synthesis-logitopp-returns-v1

hash_of() {
  shasum -a 256 "$1" | awk '{print $1}'
}

require_hash() {
  file=$1
  expected=$2
  actual=$(hash_of "$file")
  if [ "$actual" != "$expected" ]; then
    echo "Vertragsinstallation abgebrochen: unerwarteter Ausgangsstand in $file" >&2
    echo "Erwartet: $expected" >&2
    echo "Gefunden: $actual" >&2
    exit 2
  fi
}

require_commit() {
  repo=$1
  expected=$2
  actual=$(git -C "$repo" rev-parse HEAD)
  if [ "$actual" != "$expected" ]; then
    echo "Vertragsinstallation abgebrochen: unerwarteter Upstream-Commit in $repo" >&2
    echo "Erwartet: $expected" >&2
    echo "Gefunden: $actual" >&2
    exit 2
  fi
}

require_commit "$HARNESS_DIR" \
  8ba241b6d1cd4dbb9e0e961acd9f381a0f454611
require_commit "$LOGITOPP_DIR" \
  70f4f6771034f4d1ec9050bb3744114428f67fa3
require_hash "$PATCH_DIR/mobitopp-rastatt.patch" \
  52a48498cb18ab278ff8a641f5079e9877c3792307d5879bf090f7882e05628c
require_hash "$PATCH_DIR/logitopp.patch" \
  8e0c7930233f849f92e0acfe8d636a1d7fb5549c7a2cfff325496a493c0d648d

MARKER="$HARNESS_DIR/config/synthesis/harness-contract.properties"
if [ -f "$MARKER" ] && grep -q "contractVersion=$CONTRACT" "$MARKER"; then
  require_hash "$HARNESS_DIR/build.gradle" \
    851e072990e230e0fdc17adcf85bd3616822eb4020ad9c9719d18e72d46c3b9c
  require_hash "$HARNESS_DIR/src/main/java/edu/kit/ifv/mobitopp/simulation/SimulatorBuilder.java" \
    69fd52e0e4c7486852f7f24d870f023360f8a71be7136cd9e9801e8015fab62a
  require_hash "$HARNESS_DIR/src/main/java/edu/kit/ifv/mobitopp/simulation/SynthesisParcelDemandConfig.java" \
    959b7b26e8cdc1dd7348b0542a991bed5dd890ac5eb3e16330f65495031f8703
  require_hash "$LOGITOPP_DIR/build.gradle" \
    03d254d8f375f749064331c73e61ba36fc2d24815a2252d93fbd364cb8d0c37e
  require_hash "$LOGITOPP_DIR/src/main/java/edu/kit/ifv/mobitopp/simulation/parcels/ParcelBuilder.java" \
    632a0f0164a1b6b353a3b6ead931fc12c747336c2fb02735bedb2d8e64401dad
  echo "Vertrag $CONTRACT ist bereits installiert; publiziere logiTopp erneut."
  (cd "$LOGITOPP_DIR" && sh gradlew clean publishToMavenLocal -x test --no-daemon)
  exit 0
fi

require_hash "$HARNESS_DIR/build.gradle" \
  fecda2bf7dd39c6663f63f6aea89d7cf51916dddb17100378a1e9765b207c60a
require_hash "$HARNESS_DIR/src/main/java/edu/kit/ifv/mobitopp/simulation/SimulatorBuilder.java" \
  7bc768166a0f81ff02f3973559590810b52dcb13933c6acdcc8380836d167133
require_hash "$LOGITOPP_DIR/build.gradle" \
  84e8499d66dde57bfba9c30989a390e5a2986196fffe3f7e79fba596d4d90a1f
require_hash "$LOGITOPP_DIR/src/main/java/edu/kit/ifv/mobitopp/simulation/parcels/ParcelBuilder.java" \
  91935a40eab89617b0cc7b13d0ce47c8d8ba6257e33efb17ae89710949310ff2

git -C "$HARNESS_DIR" apply --check --ignore-space-change \
  "$PATCH_DIR/mobitopp-rastatt.patch"
git -C "$LOGITOPP_DIR" apply --check --ignore-space-change \
  "$PATCH_DIR/logitopp.patch"

git -C "$HARNESS_DIR" apply --ignore-space-change "$PATCH_DIR/mobitopp-rastatt.patch"
git -C "$LOGITOPP_DIR" apply --ignore-space-change "$PATCH_DIR/logitopp.patch"

require_hash "$HARNESS_DIR/build.gradle" \
  851e072990e230e0fdc17adcf85bd3616822eb4020ad9c9719d18e72d46c3b9c
require_hash "$HARNESS_DIR/src/main/java/edu/kit/ifv/mobitopp/simulation/SimulatorBuilder.java" \
  69fd52e0e4c7486852f7f24d870f023360f8a71be7136cd9e9801e8015fab62a
require_hash "$HARNESS_DIR/src/main/java/edu/kit/ifv/mobitopp/simulation/SynthesisParcelDemandConfig.java" \
  959b7b26e8cdc1dd7348b0542a991bed5dd890ac5eb3e16330f65495031f8703
require_hash "$LOGITOPP_DIR/build.gradle" \
  03d254d8f375f749064331c73e61ba36fc2d24815a2252d93fbd364cb8d0c37e
require_hash "$LOGITOPP_DIR/src/main/java/edu/kit/ifv/mobitopp/simulation/parcels/ParcelBuilder.java" \
  632a0f0164a1b6b353a3b6ead931fc12c747336c2fb02735bedb2d8e64401dad

(cd "$LOGITOPP_DIR" && sh gradlew clean publishToMavenLocal -x test --no-daemon)

echo "Vertrag $CONTRACT installiert und logiTopp lokal publiziert."
