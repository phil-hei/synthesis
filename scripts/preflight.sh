#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/runtime-common.sh"

PROFILE=${1:-core}
if [[ $PROFILE != core && $PROFILE != full ]]; then
  echo "Aufruf: scripts/preflight.sh [core|full]" >&2
  exit 2
fi

failures=0
warnings=0
ok() { printf 'OK    %s\n' "$1"; }
warn() { printf 'WARN  %s\n' "$1"; warnings=$((warnings + 1)); }
fail() { printf 'FEHLT %s\n' "$1"; failures=$((failures + 1)); }
has() { command -v "$1" >/dev/null 2>&1; }

echo "SyntheSis-Preflight ($PROFILE)"

if has java; then
  backend_java=$(java_major)
  if [[ -n $backend_java && $backend_java -ge $SYNTHESIS_BACKEND_JAVA_MIN ]]; then
    ok "Java $backend_java (Backend mindestens $SYNTHESIS_BACKEND_JAVA_MIN)"
  else
    fail "Java >= $SYNTHESIS_BACKEND_JAVA_MIN"
  fi
else
  fail "Java"
fi

has mvn && ok "Maven $(mvn -version 2>/dev/null | sed -n '1s/Apache Maven //p')" || fail "Maven"

if has node; then
  node_version=$(node --version)
  node_major=$(version_major "$node_version")
  if [[ $node_major -eq 20 || $node_major -eq 22 || $node_major -eq 24 ]]; then
    ok "Node $node_version"
  else
    fail "unterstütztes Node (20/22/24; gefunden $node_version, frontend/.nvmrc verwenden)"
  fi
else
  fail "Node"
fi
has npm && ok "npm $(npm --version)" || fail "npm"
has curl && ok "curl" || fail "curl (für Start- und Healthchecks)"

[[ -f $SYNTHESIS_ROOT/backend/pom.xml ]] && ok "Backend-Projekt" || fail "backend/pom.xml"
[[ -f $SYNTHESIS_ROOT/frontend/package-lock.json ]] && ok "Frontend-Lockfile" || fail "frontend/package-lock.json"

# Die Skills liegen doppelt: .agents/skills/ wird gepflegt, .claude/skills/ wird geladen.
# Läuft das auseinander, arbeiten Agenten still nach veralteten Anweisungen — genau so
# überlebte ein bereits widerlegter Rat mehrere Tage. Der Drift ist sonst unsichtbar,
# weil beide Bäume git-getrackt sind und nichts sie vergleicht.
if [[ -d $SYNTHESIS_ROOT/.agents/skills && -d $SYNTHESIS_ROOT/.claude/skills ]]; then
  # `|| true`: diff meldet Unterschiede per Exit 1, und runtime-common.sh setzt set -e —
  # ohne die Klammer bräche das Skript hier ab, statt den Drift zu melden.
  skill_drift=$(diff -rq "$SYNTHESIS_ROOT/.agents/skills" "$SYNTHESIS_ROOT/.claude/skills" 2>&1 || true)
  if [[ -z $skill_drift ]]; then
    ok "Skill-Registries synchron (.agents/skills == .claude/skills)"
  else
    fail "Skill-Registries auseinandergelaufen — .claude/ wird geladen, .agents/ gepflegt:"
    printf '%s\n' "$skill_drift" | sed 's|'"$SYNTHESIS_ROOT"'/||g; s/^/      /'
    echo "      Abgleich nach Richtungsprüfung: cp .agents/skills/<name>/SKILL.md .claude/skills/<name>/SKILL.md"
  fi
fi

if has lsof; then
  lsof -nP -iTCP:8090 -sTCP:LISTEN >/dev/null 2>&1 && warn "Port 8090 ist bereits belegt" || ok "Port 8090 frei"
  lsof -nP -iTCP:4300 -sTCP:LISTEN >/dev/null 2>&1 && warn "Port 4300 ist bereits belegt" || ok "Port 4300 frei"
fi

if [[ $PROFILE == full ]]; then
  source "$SYNTHESIS_ROOT/runtime/kit-sources.env"
  if kit_home=$(kit_java_home); then
    ok "JDK $SYNTHESIS_KIT_JAVA für KIT-Modelle ($kit_home)"
  else
    fail "JDK $SYNTHESIS_KIT_JAVA für KIT-Modelle (SYNTHESIS_KIT_JAVA_HOME setzen)"
  fi

  check_repo() {
    local label=$1 dir=$2 expected=$3
    if [[ ! -d $dir/.git ]]; then
      fail "$label-Clone"
      return
    fi
    local actual
    actual=$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)
    [[ $actual == "$expected" ]] && ok "$label @ ${expected:0:12}" || fail "$label-Commit ($actual)"
  }
  check_repo "kotlin-units" "$SYNTHESIS_ROOT/validation/kotlin-units" "$SYNTHESIS_KOTLIN_UNITS_COMMIT"
  check_repo "DCML" "$SYNTHESIS_ROOT/validation/DCML" "$SYNTHESIS_DCML_COMMIT"
  check_repo "actitoppNG" "$SYNTHESIS_ROOT/validation/actitoppNG" "$SYNTHESIS_ACTITOPP_COMMIT"
  check_repo "logitopp" "$SYNTHESIS_ROOT/validation/logitopp" "$SYNTHESIS_LOGITOPP_COMMIT"
  check_repo "mobitopp-rastatt" "$SYNTHESIS_ROOT/validation/mobitopp-rastatt" "$SYNTHESIS_MOBITOPP_RASTATT_COMMIT"

  actitopp_dist="$SYNTHESIS_ROOT/adapters/actitopp/build/install/synthesis-actitopp-adapter"
  grep -q '^contractVersion=synthesis-actitopp-households-v2$' \
    "$actitopp_dist/synthesis-contract.properties" 2>/dev/null \
    && ok "Separater actiTopp-Haushaltsadapter" || fail "actiTopp-Adapter (scripts/build-actitopp-adapter.sh ausführen)"
  [[ -f $actitopp_dist/bin/synthesis-actitopp-adapter ]] \
    && ok "actiTopp-Launcher" || fail "actiTopp-Launcher"
  grep -q '^contractVersion=synthesis-logitopp-adapter-v2$' \
    "$SYNTHESIS_ROOT/adapters/logitopp/build/install/synthesis-logitopp-adapter/synthesis-contract.properties" 2>/dev/null \
    && ok "Separater logiTopp-Adapter" || fail "Separater logiTopp-Adapter"
  [[ -f $SYNTHESIS_ROOT/adapters/logitopp/build/install/synthesis-logitopp-adapter/bin/synthesis-logitopp-adapter ]] \
    && ok "logiTopp-Launcher" || fail "logiTopp-Launcher"

  for executable in sumo netconvert duarouter; do
    has "$executable" && ok "$executable" || fail "$executable"
  done

  transit_python="$SYNTHESIS_ROOT/validation/transit-venv/bin/python"
  if [[ -x $transit_python ]] && ! "$transit_python" -c pass >/dev/null 2>&1; then
    fail "Transit-venv startet nicht (Python aktualisiert?; scripts/bootstrap-runtime.sh transit legt sie neu an)"
  elif [[ -x $transit_python ]]; then
    if "$transit_python" -c "import pandas, rtree, pyproj; assert pandas.__version__ == '$SYNTHESIS_TRANSIT_PANDAS'; assert rtree.__version__ == '$SYNTHESIS_TRANSIT_RTREE'; assert pyproj.__version__ == '$SYNTHESIS_TRANSIT_PYPROJ'"; then
      ok "Transit-venv (pandas/rtree/pyproj gepinnt)"
    else
      fail "Transit-venv-Versionen"
    fi
  else
    fail "Transit-venv"
  fi

  if has curl && curl --max-time 2 -fsS http://127.0.0.1:11434/v1/models 2>/dev/null \
      | grep -Fq "\"id\":\"$SYNTHESIS_LLM_MODEL\""; then
    ok "Ollama + $SYNTHESIS_LLM_MODEL"
  else
    fail "laufender Ollama-Server mit $SYNTHESIS_LLM_MODEL"
  fi

  [[ -f $SYNTHESIS_ROOT/validation/kvv-gtfs.zip ]] && ok "KVV-GTFS-Fixture" || fail "validation/kvv-gtfs.zip"
  [[ -f $SYNTHESIS_ROOT/validation/rastatt-test.osm ]] && ok "Rastatt-OSM-Fixture" || fail "validation/rastatt-test.osm"
fi

echo "Ergebnis: $failures Fehler, $warnings Hinweise"
[[ $failures -eq 0 ]]
