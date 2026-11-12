#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
revision="${1:?exact Git SHA required}"
output="${2:?output directory required}"
port="${PILOT_PORT:-18787}"
model="${3:-qwen3-1.7b}"
case "$model" in
  qwen3-1.7b)
    model_file='https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/90862c4b9d2787eaed51d12237eafdfe7c5f6077/Qwen3-1.7B-Q8_0.gguf'
    model_hash='061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a'
    ;;
  qwen2.5-coder-3b)
    model_file='https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/f74adce6aa16316c625447af059dbebe4983757c/qwen2.5-coder-3b-instruct-q4_k_m.gguf'
    model_hash='724fb256bec1ff062b2f65e4569e871ad2e95ab2a3989723d1769c54294730b7'
    ;;
  *)
    echo 'UNKNOWN_MODEL' >&2
    exit 2
    ;;
esac
if [[ -f "$output/checkpoint.json" ]] && python3 -c 'import json,sys; sys.exit("expression" not in json.load(open(sys.argv[1])))' "$output/checkpoint.json"; then
  exec python3 "$script_dir/run.py" --revision "$revision" --output "$output"
fi

downloads="$(mktemp -d)"
server_pid=
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -rf -- "$downloads"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fetch() {
  local url="$1" hash="$2" target="$3"
  curl --proto '=https' --proto-redir '=https' --fail --location --silent --show-error \
    --max-time 180 --output "$target" "$url"
  printf '%s  %s\n' "$hash" "$target" | sha256sum --check --status
}

fetch 'https://github.com/ggml-org/llama.cpp/releases/download/b12110/llama-b12110-bin-ubuntu-x64.tar.gz' \
  '368227edcc229be5f9bed16a2223d79b5e41d0ac2e19bcf5825e82291c060e55' "$downloads/llama.tgz"
fetch "$model_file" "$model_hash" "$downloads/model.gguf"
tar -xzf "$downloads/llama.tgz" -C "$downloads"
"$downloads/llama-b12110/llama-server" -m "$downloads/model.gguf" --host 127.0.0.1 \
  --port "$port" --alias "$model" --jinja --reasoning-budget 0 -c 16384 -t 4 -np 1 \
  >"$downloads/llama.log" 2>&1 &
server_pid=$!
for _ in $(seq 1 30); do
  kill -0 "$server_pid"
  if curl --noproxy 127.0.0.1 --fail --silent --max-time 2 \
    "http://127.0.0.1:$port/health" >/dev/null; then
    python3 "$script_dir/run.py" --revision "$revision" --output "$output" --port "$port" \
      --model "$model"
    exit
  fi
  sleep 2
done
echo 'LOCAL_MODEL_START_TIMEOUT' >&2
exit 1
