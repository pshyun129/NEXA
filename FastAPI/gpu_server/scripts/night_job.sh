#!/usr/bin/env bash
# 야간 작업 (start_night.sh가 tmux night 세션에서 실행): vLLM 내림 → QLoRA 학습 → 어댑터를 붙여 vLLM 재시작 → 끝나면 종료.
# 사용법: bash night_job.sh [어댑터 이름] [학습 데이터 jsonl]
# 규칙: 데몬·cron 없음 (사람이 실행, 끝나면 스스로 종료), GPU_ID 한 장만, 우리 tmux 세션만 건드림
# 메모리: 학습 최대 32.5GB + OCR 약 2GB < 우리 몫 47GB (2026-11-06 측정) → OCR은 끄지 않음
set -uo pipefail          # -e 없음: 학습이 실패해도 마지막의 vLLM 재시작은 반드시 실행

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
set -a; source "$SCRIPT_DIR/../.env"; set +a

NAME="${1:-receipt-qlora-$(date +%Y%m%d)}"
DATA="${2:-$HOME/ai/sft/receipt_sft.jsonl}"
ADAPTER="$HOME/ai/adapters/$NAME"
step() { echo "[$(date '+%F %T')] $*"; }

step "야간 작업 시작: 어댑터 $NAME / 데이터 $DATA"
[[ -f "$DATA" ]] || { step "학습 데이터가 없습니다: $DATA"; exit 1; }

# 0) 같은 데이터로 이미 학습한 어댑터가 있으면 건너뜀 (매일 예약돼도 새 데이터가 있을 때만 학습, vLLM도 안 내림)
SHA=$(sha256sum "$DATA" | cut -c1-12)
DONE=$(grep -lx "$SHA" "$HOME"/ai/adapters/*/data.sha 2>/dev/null | head -1 || true)
if [[ -n "$DONE" && "${FORCE:-0}" != 1 ]]; then
  step "이미 학습한 데이터 (sha $SHA → $(dirname "$DONE")) → 건너뜀 (다시 하려면 FORCE=1)"; exit 0
fi

# 1) vLLM만 내리기 (OCR·MLflow는 그대로: OCR은 메모리가 충분하고, MLflow는 학습 기록용)
tmux kill-session -t vllm 2>/dev/null && step "[vllm] 종료"
sleep 30
nvidia-smi --query-gpu=index,memory.used --format=csv,noheader

# 2) 학습
step "QLoRA 학습 시작"
CUDA_VISIBLE_DEVICES="$GPU_ID" MLFLOW_TRACKING_URI="http://127.0.0.1:$MLFLOW_PORT" \
  "$HOME/ai/.venv-train/bin/python" "$SCRIPT_DIR/qlora_train.py" --data "$DATA" --name "$NAME"
STATUS=$?
step "학습 종료 (코드 $STATUS)"

# 3) vLLM 다시 올리기: 학습 성공이면 어댑터를 붙여서, 실패면 원래대로
if [[ $STATUS -eq 0 && -f "$ADAPTER/adapter_config.json" ]]; then
  echo "$SHA" > "$ADAPTER/data.sha"                 # 이 데이터로 학습했다는 기록 (다음 예약 때 건너뛰는 기준)
  tmux new-session -d -s vllm "LORA_PATH=$ADAPTER LORA_NAME=receipt-lora bash $SCRIPT_DIR/serve.sh"
  step "[vllm] 시작 (기본 모델 + 어댑터 receipt-lora)"
else
  tmux new-session -d -s vllm "bash $SCRIPT_DIR/serve.sh"
  step "[vllm] 시작 (기본 모델만, 학습 실패)"
fi
# OCR이 어떤 이유로 꺼져 있으면 다시 올림 (켜져 있으면 그대로)
tmux has-session -t ocr 2>/dev/null || { tmux new-session -d -s ocr "bash $SCRIPT_DIR/serve_ocr.sh"; step "[ocr] 시작"; }
step "야간 작업 끝. PC에서 평가: LOCAL_LLM_MODEL=receipt-lora python -X utf8 -m mlops.evaluate --split dev --name $NAME"