#!/usr/bin/env bash
set -euo pipefail
umask 077
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
revision="${1:?exact checkout SHA required}"
output="${2:?checkpoint directory required}"
package="${3:?reviewed package required}"
case "$package" in smoke|backup-replay) ;; *) exit 2 ;; esac
shift 3
[[ "$revision" == "$(git -C "$script_dir" rev-parse HEAD)" ]]
git -C "$script_dir" diff --quiet "$revision" -- .
mkdir -p "$output"
exec 9>"$output/.bootstrap"
flock -n 9 || { echo 'WRITER_BUSY' >&2; exit 1; }

scratch="$(mktemp -d)"
primary_pid=
reserve_pid=
cleanup() {
  for pid in "$primary_pid" "$reserve_pid"; do
    if [[ -n "$pid" ]]; then
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
  rm -rf -- "$scratch"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

prepare_kotlin() {
  local hash artifact jar remaining deadline=$((SECONDS + 90))
  if [[ -z "${PILOT_KOTLIN_CACHE:-}" ]]; then
    export PILOT_KOTLIN_CACHE="$scratch/kotlin"
    mkdir -p "$PILOT_KOTLIN_CACHE" || return 1
  fi
  while read -r hash artifact; do
    jar="$PILOT_KOTLIN_CACHE/${artifact##*/}"
    if [[ "$PILOT_KOTLIN_CACHE" == "$scratch/kotlin" ]]; then
      remaining=$((deadline - SECONDS))
      (( remaining > 0 )) || return 1
      curl --proto '=https' --proto-redir '=https' --fail --silent --show-error \
        --connect-timeout 10 --max-time "$remaining" --output "$jar" "https://repo.maven.apache.org/maven2/$artifact" || return 1
    fi
    printf '%s  %s\n' "$hash" "$jar" | sha256sum --check --status || return 1
  done < "$script_dir/kotlin.lock"
}

if [[ "$package" == backup-replay ]] && ! prepare_kotlin; then
  unset PILOT_KOTLIN_CACHE
  echo 'KOTLIN_DEPENDENCIES_UNAVAILABLE' >&2
fi

read -r free_primary free_reserve < <(python3 - <<'PYPORT'
import socket
with socket.socket() as first, socket.socket() as second:
    first.bind(("127.0.0.1", 0))
    second.bind(("127.0.0.1", 0))
    print(first.getsockname()[1], second.getsockname()[1])
PYPORT
)
primary_port="${PILOT_PRIMARY_PORT:-$free_primary}"
reserve_port="${PILOT_RESERVE_PORT:-$free_reserve}"
for port in "$primary_port" "$reserve_port"; do
  [[ "$port" =~ ^[1-9][0-9]{0,4}$ ]] && (( port <= 65535 ))
done
[[ "$primary_port" != "$reserve_port" ]]

PILOT_PORT="$primary_port" bash "$script_dir/run-local.sh" "$revision" \
  "$scratch/primary.ready" qwen3-1.7b serve 9>&- &
primary_pid=$!
PILOT_PORT="$reserve_port" bash "$script_dir/run-local.sh" "$revision" \
  "$scratch/reserve.ready" qwen2.5-coder-3b serve 9>&- &
reserve_pid=$!
for _ in $(seq 1 130); do
  if { [[ -f "$scratch/primary.ready" ]] || ! kill -0 "$primary_pid" 2>/dev/null; } &&
     { [[ -f "$scratch/reserve.ready" ]] || ! kill -0 "$reserve_pid" 2>/dev/null; }; then
    break
  fi
  sleep 2
done

# An unavailable server is handled by the queue as a provider failure.
primary_server=0
reserve_server=0
if [[ -f "$scratch/primary.ready" ]]; then read -r primary_server < "$scratch/primary.ready"; fi
if [[ -f "$scratch/reserve.ready" ]]; then read -r reserve_server < "$scratch/reserve.ready"; fi
python3 "$script_dir/queue_run.py" --revision "$revision" --output "$output" \
  --package "$package" --primary-port "$primary_port" --reserve-port "$reserve_port" \
  --primary-pid "$primary_server" --reserve-pid "$reserve_server" "$@"
