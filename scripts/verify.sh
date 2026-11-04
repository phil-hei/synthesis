#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/runtime-common.sh"

PROFILE=${1:-core}
if [[ $PROFILE != core && $PROFILE != full ]]; then
  echo "Aufruf: scripts/verify.sh [core|full]" >&2
  exit 2
fi

"$SCRIPT_DIR/preflight.sh" "$PROFILE"
if [[ $PROFILE == full ]]; then
  export SYNTHESIS_TRANSIT_VENV="$SYNTHESIS_ROOT/validation/transit-venv/bin/python"
  export SYNTHESIS_RUN_ACTITOPP_TEST=true
  export SYNTHESIS_ACTITOPP_DIR="${SYNTHESIS_ACTITOPP_DIR:-$SYNTHESIS_ROOT/adapters/actitopp/build/install/synthesis-actitopp-adapter}"
  export SYNTHESIS_KIT_JAVA_HOME
  SYNTHESIS_KIT_JAVA_HOME=$(kit_java_home)
fi

(cd "$SYNTHESIS_ROOT/backend" && mvn -o test)
(cd "$SYNTHESIS_ROOT/frontend" && npm run audit:prod && npm run lint && npm test && npm run build)

echo "SyntheSis-Verifikation ($PROFILE) erfolgreich."
