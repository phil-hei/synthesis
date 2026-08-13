#!/bin/sh
set -eu

# Installiert den Vertrag synthesis-logitopp-returns-v1 auf frischen, gepinnten
# KIT-Clones. Ausgangspunkt ist ausschliesslich der Upstream-Zustand der beiden
# gepinnten Commits — es gibt keinen unversionierten Zwischenstand mehr.
#
# Zusaetzlich zu den Patches werden die im Harness gitignorierten, aber zum
# Kompilieren zwingend noetigen Artefakte installiert (DCML-generierte Klassen,
# .xmi-Modelle, Config-Varianten). Ohne sie kompiliert mobitopp-rastatt nicht:
# der Vertrags-Build entfernt das Codegen-Plugin, und `**/gen/`, `**/generated/`,
# `*.xmi` sowie `*p.yaml` stehen in der .gitignore des Harness.

PATCH_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$PATCH_DIR/../.." && pwd)
HARNESS_DIR=${1:-"$REPO_ROOT/validation/mobitopp-rastatt"}
LOGITOPP_DIR=${2:-"$REPO_ROOT/validation/logitopp"}
CONTRACT=synthesis-logitopp-returns-v1
ARTIFACTS="$PATCH_DIR/harness-artifacts"

hash_of() {
  shasum -a 256 "$1" | awk '{print $1}'
}

require_hash() {
  file=$1
  expected=$2
  actual=$(hash_of "$file")
  if [ "$actual" != "$expected" ]; then
    echo "Vertragsinstallation abgebrochen: unerwarteter Stand in $file" >&2
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

# Nach-Zustand: unveraendert gegenueber der Vorversion des Vertrags.
HARNESS_BUILD_AFTER=851e072990e230e0fdc17adcf85bd3616822eb4020ad9c9719d18e72d46c3b9c
HARNESS_SIMBUILDER_AFTER=69fd52e0e4c7486852f7f24d870f023360f8a71be7136cd9e9801e8015fab62a
HARNESS_PARCELCFG_AFTER=959b7b26e8cdc1dd7348b0542a991bed5dd890ac5eb3e16330f65495031f8703
LOGITOPP_BUILD_AFTER=03d254d8f375f749064331c73e61ba36fc2d24815a2252d93fbd364cb8d0c37e
LOGITOPP_PARCELBUILDER_AFTER=632a0f0164a1b6b353a3b6ead931fc12c747336c2fb02735bedb2d8e64401dad

verify_after() {
  require_hash "$HARNESS_DIR/build.gradle" "$HARNESS_BUILD_AFTER"
  require_hash "$HARNESS_DIR/src/main/java/edu/kit/ifv/mobitopp/simulation/SimulatorBuilder.java" \
    "$HARNESS_SIMBUILDER_AFTER"
  require_hash "$HARNESS_DIR/src/main/java/edu/kit/ifv/mobitopp/simulation/SynthesisParcelDemandConfig.java" \
    "$HARNESS_PARCELCFG_AFTER"
  require_hash "$LOGITOPP_DIR/build.gradle" "$LOGITOPP_BUILD_AFTER"
  require_hash "$LOGITOPP_DIR/src/main/java/edu/kit/ifv/mobitopp/simulation/parcels/ParcelBuilder.java" \
    "$LOGITOPP_PARCELBUILDER_AFTER"
}

verify_artifacts() {
  missing=0
  while read -r expected file; do
    target="$HARNESS_DIR/${file#./}"
    if [ ! -f "$target" ]; then
      echo "Artefakt fehlt: ${file#./}" >&2
      missing=$((missing + 1))
      continue
    fi
    actual=$(hash_of "$target")
    if [ "$actual" != "$expected" ]; then
      echo "Artefakt weicht ab: ${file#./}" >&2
      missing=$((missing + 1))
    fi
  done < "$ARTIFACTS/manifest.sha256"
  if [ "$missing" -ne 0 ]; then
    echo "Vertragsinstallation abgebrochen: $missing Artefakt(e) fehlerhaft." >&2
    exit 2
  fi
}

publish_logitopp() {
  (cd "$LOGITOPP_DIR" && sh gradlew clean publishToMavenLocal -x test --no-daemon)
}

require_commit "$HARNESS_DIR" \
  8ba241b6d1cd4dbb9e0e961acd9f381a0f454611
require_commit "$LOGITOPP_DIR" \
  70f4f6771034f4d1ec9050bb3744114428f67fa3
require_hash "$PATCH_DIR/mobitopp-rastatt.patch" \
  132d9566ef98b6aa29230328f9c27376151d311e0a444fc8f70f2a60f24d09da
require_hash "$PATCH_DIR/logitopp.patch" \
  21f46b146a56538716c5b5b10d0bbd2c465cb1739213b441ee7543856bb2a8e2
[ -f "$ARTIFACTS/manifest.sha256" ] || {
  echo "Vertragsinstallation abgebrochen: $ARTIFACTS/manifest.sha256 fehlt" >&2
  exit 2
}

MARKER="$HARNESS_DIR/config/synthesis/harness-contract.properties"
if [ -f "$MARKER" ] && grep -q "contractVersion=$CONTRACT" "$MARKER"; then
  verify_after
  verify_artifacts
  echo "Vertrag $CONTRACT ist bereits installiert; publiziere logiTopp erneut."
  publish_logitopp
  exit 0
fi

# Je Repo: entweder schon im Vertragszustand (dann nichts tun) oder exakt im
# Upstream-Stand (dann strikt patchen). Beide Faelle sind hash-verifiziert; ein
# dritter, unbekannter Zustand bricht ab. Das faengt den realen Mischfall ab,
# in dem ein Repo neu geklont wurde und das andere bereits gepatcht ist —
# ohne dafuer einen undokumentierten Handschritt zu verlangen.
ensure_patched() {
  dir=$1
  probe=$2
  after=$3
  before=$4
  patch=$5
  actual=$(hash_of "$dir/$probe")
  if [ "$actual" = "$after" ]; then
    echo "$(basename "$dir"): bereits im Vertragszustand, Patch uebersprungen."
    return 0
  fi
  require_hash "$dir/$probe" "$before"
  # Strikt, ohne --ignore-space-change: die Patches sind gegen genau diesen
  # Upstream-Stand erzeugt und muessen sauber greifen.
  git -C "$dir" apply --check --binary "$patch"
  git -C "$dir" apply --binary "$patch"
}

ensure_patched "$HARNESS_DIR" build.gradle \
  "$HARNESS_BUILD_AFTER" \
  6a23e17a4f0970d7e86e433b4baade185f04f7bdea87a2cb97e8a3346455dc90 \
  "$PATCH_DIR/mobitopp-rastatt.patch"

ensure_patched "$LOGITOPP_DIR" build.gradle \
  "$LOGITOPP_BUILD_AFTER" \
  f363ac01b3510e44c6afcca323985417b0675d183b4d73e0440925db6b71d881 \
  "$PATCH_DIR/logitopp.patch"

# Gitignorierte Pflicht-Artefakte installieren.
(cd "$ARTIFACTS" && find . -type f ! -name manifest.sha256 -print) | while read -r file; do
  target="$HARNESS_DIR/${file#./}"
  mkdir -p "$(dirname "$target")"
  cp "$ARTIFACTS/${file#./}" "$target"
done

verify_after
verify_artifacts

publish_logitopp

echo "Vertrag $CONTRACT installiert und logiTopp lokal publiziert."
