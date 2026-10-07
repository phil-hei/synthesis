#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/runtime-common.sh"
source "$SYNTHESIS_ROOT/runtime/kit-sources.env"

PROFILE=${1:-all}
if [[ $PROFILE != kit && $PROFILE != transit && $PROFILE != all ]]; then
  echo "Aufruf: scripts/bootstrap-runtime.sh [kit|transit|all]" >&2
  exit 2
fi

mkdir -p "$SYNTHESIS_ROOT/validation"

clone_pinned() {
  local name=$1 url=$2 commit=$3 dir="$SYNTHESIS_ROOT/validation/$1"
  if [[ ! -e $dir ]]; then
    git clone "$url" "$dir"
  fi
  [[ -d $dir/.git ]] || { echo "$dir ist kein Git-Clone" >&2; exit 2; }
  local actual
  actual=$(git -C "$dir" rev-parse HEAD)
  if [[ $actual != "$commit" ]]; then
    if [[ -n $(git -C "$dir" status --porcelain) ]]; then
      echo "$name hat lokale Änderungen und steht auf $actual; kein automatisches Checkout." >&2
      exit 2
    fi
    git -C "$dir" fetch origin "$commit"
    git -C "$dir" checkout --detach "$commit"
  fi
  echo "$name @ ${commit:0:12}"
}

publish_kit_dependency() {
  local dir=$1 version=$2
  (cd "$dir" && sh gradlew --no-daemon publishToMavenLocal \
    -PdoPublish=true -PbuildVersion="$version" \
    -PlocalUrl=file:///tmp/synthesis-dummy-repo -PlocalRepoUser=x -PlocalRepoPassword=x)
}

if [[ $PROFILE == kit || $PROFILE == all ]]; then
  kit_home=$(kit_java_home) || {
    echo "JDK $SYNTHESIS_KIT_JAVA fehlt. SYNTHESIS_KIT_JAVA_HOME setzen oder JDK $SYNTHESIS_KIT_JAVA installieren." >&2
    exit 2
  }
  export JAVA_HOME=$kit_home
  export PATH="$JAVA_HOME/bin:$PATH"

  clone_pinned kotlin-units "$SYNTHESIS_KOTLIN_UNITS_URL" "$SYNTHESIS_KOTLIN_UNITS_COMMIT"
  clone_pinned DCML "$SYNTHESIS_DCML_URL" "$SYNTHESIS_DCML_COMMIT"
  clone_pinned actitoppNG "$SYNTHESIS_ACTITOPP_URL" "$SYNTHESIS_ACTITOPP_COMMIT"
  clone_pinned logitopp "$SYNTHESIS_LOGITOPP_URL" "$SYNTHESIS_LOGITOPP_COMMIT"
  clone_pinned mobitopp-rastatt "$SYNTHESIS_MOBITOPP_RASTATT_URL" "$SYNTHESIS_MOBITOPP_RASTATT_COMMIT"

  publish_kit_dependency "$SYNTHESIS_ROOT/validation/kotlin-units" 1.0.0
  publish_kit_dependency "$SYNTHESIS_ROOT/validation/DCML" 1.1.0
  bash "$SYNTHESIS_ROOT/scripts/build-actitopp-adapter.sh"
  bash "$SYNTHESIS_ROOT/scripts/build-logitopp-adapter.sh"
fi

if [[ $PROFILE == transit || $PROFILE == all ]]; then
  python_cmd=${SYNTHESIS_PYTHON:-}
  if [[ -z $python_cmd ]]; then
    python_cmd=$(command -v python3.14 || command -v python3)
  fi
  [[ -n $python_cmd ]] || { echo "Python $SYNTHESIS_PYTHON_VERSION fehlt" >&2; exit 2; }
  "$python_cmd" -c "import sys; assert sys.version_info[:2] == (${SYNTHESIS_PYTHON_VERSION/./, })" || {
    echo "Python $SYNTHESIS_PYTHON_VERSION erwartet: $python_cmd" >&2
    exit 2
  }
  venv="$SYNTHESIS_ROOT/validation/transit-venv"
  [[ -x $venv/bin/python ]] || "$python_cmd" -m venv "$venv"
  "$venv/bin/python" -m pip install --requirement "$SYNTHESIS_ROOT/runtime/transit-requirements.txt"
fi

echo "Runtime-Bootstrap abgeschlossen. Nächster Check: scripts/preflight.sh full"
