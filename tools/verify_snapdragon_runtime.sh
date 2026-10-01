#!/usr/bin/env bash
set -euo pipefail
RUNTIME="${1:-}"
if [[ -z "$RUNTIME" || ! -d "$RUNTIME" ]]; then
  echo "usage: $0 <runtime-directory>" >&2
  exit 2
fi
for f in libllama.so libggml.so libllama-common.so; do
  [[ -f "$RUNTIME/$f" ]] || { echo "missing $f" >&2; exit 3; }
done
for sym in llama_tokenize llama_chat_apply_template llama_sampler_init_top_k llama_sampler_init_top_p llama_sampler_init_temp llama_sampler_init_dist; do
  if ! nm -D --defined-only "$RUNTIME/libllama.so" | grep -q " $sym$"; then
    echo "missing symbol: $sym" >&2
    exit 4
  fi
done
echo "runtime ABI symbols: OK"
