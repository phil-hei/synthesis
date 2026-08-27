#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/runtime-common.sh"

"$SCRIPT_DIR/preflight.sh" core
mkdir -p "$SYNTHESIS_ROOT/.runtime/logs"

pids=()
ollama_pid=""
backend_url=http://localhost:8090/api/scenarios/defaults
frontend_url=http://localhost:4300/

http_ready() {
  curl --max-time 2 -fsS "$1" >/dev/null 2>&1
}

port_listens() {
  local port=$1
  command -v lsof >/dev/null 2>&1 \
    && lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
}

cleanup() {
  trap - INT TERM EXIT
  for pid in "${pids[@]:-}"; do
    [[ -n $pid ]] && kill "$pid" 2>/dev/null || true
  done
  [[ -n $ollama_pid ]] && kill "$ollama_pid" 2>/dev/null || true
}
trap cleanup INT TERM EXIT

backend_ready=0
frontend_ready=0
http_ready "$backend_url" && backend_ready=1
http_ready "$frontend_url" && frontend_ready=1

if [[ $backend_ready == 1 && $frontend_ready == 1 ]]; then
  echo "SyntheSis läuft bereits vollständig: http://localhost:4300 (Backend http://localhost:8090)"
  exit 0
fi

if [[ $backend_ready == 0 ]] && port_listens 8090; then
  echo "Port 8090 ist belegt, antwortet aber nicht als SyntheSis-Backend." >&2
  exit 1
fi
if [[ $frontend_ready == 0 ]] && port_listens 4300; then
  echo "Port 4300 ist belegt, antwortet aber nicht als SyntheSis-Frontend." >&2
  exit 1
fi

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

if [[ $backend_ready == 0 ]]; then
  (cd "$SYNTHESIS_ROOT/backend" && mvn spring-boot:run \
    -Dspring-boot.run.main-class=de.synthesis.SynthesisApplication) \
    >"$SYNTHESIS_ROOT/.runtime/logs/backend.log" 2>&1 &
  pids+=("$!")
else
  echo "Backend auf Port 8090 läuft bereits und wird wiederverwendet."
fi

if [[ $frontend_ready == 0 ]]; then
  (cd "$SYNTHESIS_ROOT/frontend" && npm start) \
    >"$SYNTHESIS_ROOT/.runtime/logs/frontend.log" 2>&1 &
  pids+=("$!")
else
  echo "Frontend auf Port 4300 läuft bereits und wird wiederverwendet."
fi

echo "SyntheSis startet; warte auf Backend und Frontend …"

ready=0
for _ in {1..90}; do
  if http_ready "$backend_url" && http_ready "$frontend_url"; then
    ready=1
    break
  fi
  for pid in "${pids[@]:-}"; do
    if [[ -n $pid ]] && ! kill -0 "$pid" 2>/dev/null; then
      echo "Ein SyntheSis-Prozess wurde während des Starts beendet; letzte Logs:" >&2
      tail -n 20 "$SYNTHESIS_ROOT/.runtime/logs/backend.log" 2>/dev/null || true
      tail -n 20 "$SYNTHESIS_ROOT/.runtime/logs/frontend.log" 2>/dev/null || true
      exit 1
    fi
  done
  sleep 1
done

if [[ $ready != 1 ]]; then
  echo "SyntheSis war nach 90 Sekunden noch nicht vollständig erreichbar; letzte Logs:" >&2
  tail -n 20 "$SYNTHESIS_ROOT/.runtime/logs/backend.log" 2>/dev/null || true
  tail -n 20 "$SYNTHESIS_ROOT/.runtime/logs/frontend.log" 2>/dev/null || true
  exit 1
fi

echo "SyntheSis ist bereit: http://localhost:4300 (Backend http://localhost:8090)"
echo "Logs: .runtime/logs/{backend,frontend,ollama}.log — eigene Prozesse mit Ctrl+C beenden"

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
