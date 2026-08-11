#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/runtime-common.sh"

"$SCRIPT_DIR/preflight.sh" core
mkdir -p "$SYNTHESIS_ROOT/.runtime/logs"

pids=()
ollama_pid=""
cleanup() {
  trap - INT TERM EXIT
  for pid in "${pids[@]:-}"; do
    [[ -n $pid ]] && kill "$pid" 2>/dev/null || true
  done
  [[ -n $ollama_pid ]] && kill "$ollama_pid" 2>/dev/null || true
}
trap cleanup INT TERM EXIT

if [[ ${SYNTHESIS_WITH_LLM:-1} == 1 ]] && command -v ollama >/dev/null 2>&1; then
  if ! curl --max-time 2 -fsS http://127.0.0.1:11434/v1/models >/dev/null 2>&1; then
    ollama serve >"$SYNTHESIS_ROOT/.runtime/logs/ollama.log" 2>&1 &
    ollama_pid=$!
    for _ in {1..20}; do
      curl --max-time 1 -fsS http://127.0.0.1:11434/v1/models >/dev/null 2>&1 && break
      sleep 1
    done
  fi
fi

transit_python="$SYNTHESIS_ROOT/validation/transit-venv/bin/python"
[[ -x $transit_python ]] && export SYNTHESIS_TRANSIT_VENV_PYTHON="$transit_python"
if kit_home=$(kit_java_home); then
  export SYNTHESIS_KIT_JAVA_HOME="$kit_home"
fi

(cd "$SYNTHESIS_ROOT/backend" && mvn spring-boot:run \
  -Dspring-boot.run.main-class=de.synthesis.SynthesisApplication) \
  >"$SYNTHESIS_ROOT/.runtime/logs/backend.log" 2>&1 &
pids+=("$!")
(cd "$SYNTHESIS_ROOT/frontend" && npm start) \
  >"$SYNTHESIS_ROOT/.runtime/logs/frontend.log" 2>&1 &
pids+=("$!")

echo "SyntheSis startet: http://localhost:4300 (Backend http://localhost:8090)"
echo "Logs: .runtime/logs/{backend,frontend,ollama}.log — Beenden mit Ctrl+C"

while true; do
  for pid in "${pids[@]}"; do
    if ! kill -0 "$pid" 2>/dev/null; then
      wait "$pid" || true
      echo "Ein SyntheSis-Prozess wurde beendet; letzte Logs:" >&2
      tail -n 20 "$SYNTHESIS_ROOT/.runtime/logs/backend.log" 2>/dev/null || true
      tail -n 20 "$SYNTHESIS_ROOT/.runtime/logs/frontend.log" 2>/dev/null || true
      exit 1
    fi
  done
  sleep 1
done
