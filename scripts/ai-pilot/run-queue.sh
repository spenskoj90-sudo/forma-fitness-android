#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
revision="${1:?exact checkout SHA required}"
output="${2:?checkpoint directory required}"
package="${3:?reviewed package required}"
case "$package" in smoke|backup-replay) ;; *) exit 2 ;; esac
shift 3
[[ "$revision" == "$(git -C "$script_dir" rev-parse HEAD)" ]]

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

if [[ "$package" == backup-replay && -z "${PILOT_KOTLIN_CACHE:-}" ]]; then
  export PILOT_KOTLIN_CACHE="$scratch/kotlin"
  mkdir -p "$PILOT_KOTLIN_CACHE"
  while read -r hash artifact; do
    jar="$PILOT_KOTLIN_CACHE/${artifact##*/}"
    curl --proto '=https' --proto-redir '=https' --fail --silent --show-error \
      --max-time 90 --output "$jar" "https://repo.maven.apache.org/maven2/$artifact"
    printf '%s  %s\n' "$hash" "$jar" | sha256sum --check --status
  done < "$script_dir/kotlin.lock"
fi

primary_port="${PILOT_PRIMARY_PORT:-18787}"
reserve_port="${PILOT_RESERVE_PORT:-18788}"
for port in "$primary_port" "$reserve_port"; do
  [[ "$port" =~ ^[1-9][0-9]{0,4}$ ]] && (( port <= 65535 ))
done
[[ "$primary_port" != "$reserve_port" ]]

PILOT_PORT="$primary_port" bash "$script_dir/run-local.sh" "$revision" \
  "$scratch/primary.ready" qwen3-1.7b serve &
primary_pid=$!
PILOT_PORT="$reserve_port" bash "$script_dir/run-local.sh" "$revision" \
  "$scratch/reserve.ready" qwen2.5-coder-3b serve &
reserve_pid=$!
for _ in $(seq 1 130); do
  if { [[ -f "$scratch/primary.ready" ]] || ! kill -0 "$primary_pid" 2>/dev/null; } &&
     { [[ -f "$scratch/reserve.ready" ]] || ! kill -0 "$reserve_pid" 2>/dev/null; }; then
    break
  fi
  sleep 2
done

# An unavailable server is handled by the queue as a provider failure.
python3 "$script_dir/queue_run.py" --revision "$revision" --output "$output" \
  --package "$package" --primary-port "$primary_port" --reserve-port "$reserve_port" "$@"
