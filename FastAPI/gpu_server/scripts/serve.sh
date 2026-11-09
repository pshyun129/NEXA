#!/usr/bin/env bash
# vLLM serving (daytime). Run inside tmux on the GPU server.
# API key is read from the VLLM_API_KEY env var (.env), not passed as an argument,
# so it does not show up in `ps` on the shared server.
# LoRA 어댑터: 실행할 때 LORA_PATH(어댑터 폴더)를 주면 붙여서 서빙 (LORA_NAME = 요청 때 쓰는 모델 이름)
#   예) LORA_PATH=~/ai/adapters/receipt-qlora-20261105 LORA_NAME=receipt-lora bash serve.sh
#   주의: LORA_PATH를 .env에 넣으면 매일 아침에도 붙어서 뜸 → 실행할 때만 앞에 붙여 쓸 것
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
set -a
source "$SCRIPT_DIR/../.env"
set +a

source "$HOME/ai/.venv/bin/activate"
mkdir -p "$HOME/ai/logs"

LORA_ARGS=()
if [[ -n "${LORA_PATH:-}" ]]; then
  LORA_ARGS=(--enable-lora --max-lora-rank "${LORA_RANK:-16}" --lora-modules "${LORA_NAME:-receipt-lora}=$LORA_PATH")
  echo "LoRA 어댑터 사용: ${LORA_NAME:-receipt-lora} = $LORA_PATH"
fi

CUDA_VISIBLE_DEVICES="$GPU_ID" vllm serve "$MODEL_PATH" \
  --served-model-name "$SERVED_MODEL_NAME" \
  --host "${VLLM_HOST:-127.0.0.1}" \
  --port "$PORT" \
  --gpu-memory-utilization "$GPU_MEM_UTIL" \
  --max-model-len "$MAX_MODEL_LEN" \
  --max-num-seqs "$MAX_NUM_SEQS" \
  --limit-mm-per-prompt '{"image": 4, "video": 0}' \
  --reasoning-parser qwen3 \
  "${LORA_ARGS[@]}" \
  2>&1 | tee -a "$HOME/ai/logs/vllm-$(date +%F).log"