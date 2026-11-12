#!/usr/bin/env bash
# 야간 작업 (start_night.sh가 tmux night 세션에서 실행) = 매일 밤 자동 MLOps. 끝나면 스스로 종료.
#   1) 데이터 준비   합성 데이터셋 받기 + 골든셋(실제 영수증) 받아 학습용/평가용 고정 분할        (vLLM 켜진 상태)
#   2) DSPy          영수증 추출·규정 판정 프롬프트 최적화 → 자동 게이트 → 통과하면 champion    (vLLM 켜진 상태)
#   3) 학습 데이터    지금 champion 프롬프트 형식 + 골든 학습용으로 QLoRA 데이터 생성            (vLLM 켜진 상태)
#   4) QLoRA         데이터가 지난번과 같으면 건너뜀 / 다르면 vLLM 내림 → 학습 → champion+새 어댑터로 vLLM 재시작
#   5) 어댑터 게이트  새 어댑터 vs 지금 champion → 통과하면 champion (다음 날 아침 start_day가 붙여서 서빙)
#   결과: 이 로그 끝의 요약 + MLflow 실험 nightly
# 사용법: bash night_job.sh [어댑터 이름] [학습 데이터 jsonl]
#   학습 데이터를 주면 1~3·5를 건너뛰고 그 데이터로 학습만 (예전 방식, 수동 실험용)
# 규칙: 데몬·cron 없음 (사람이 실행, 끝나면 스스로 종료), GPU_ID 한 장만, 우리 tmux 세션만 건드림
# 메모리: 학습 최대 32.5GB + OCR 약 2GB < 우리 몫 47GB (2026-11-06 측정) → OCR은 끄지 않음
set -uo pipefail          # -e 없음: 어느 단계가 실패해도 마지막의 vLLM 재시작은 반드시 실행

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FASTAPI="$(cd "$SCRIPT_DIR/../.." && pwd)"
set -a; source "$SCRIPT_DIR/../.env"; set +a

NAME="${1:-receipt-qlora-$(date +%Y%m%d)}"
DATA="${2:-}"
ADAPTER="$HOME/ai/adapters/$NAME"
PY="$HOME/ai/.venv-mlops/bin/python"
step() { echo "[$(date '+%F %T')] $*"; }
mlops() { (cd "$FASTAPI" && "$PY" -X utf8 -m mlops.nightly "$@"); }
vllm_ready() {            # vLLM이 요청을 받을 수 있을 때까지 (최대 15분)
  for _ in $(seq 1 90); do
    curl -sf -H "Authorization: Bearer $VLLM_API_KEY" "http://127.0.0.1:$PORT/v1/models" >/dev/null && return 0
    sleep 10
  done
  return 1
}

step "야간 작업 시작: 어댑터 $NAME / 데이터 ${DATA:-밤 MLOps가 생성}"
FULL=0
if [[ -z "$DATA" ]]; then
  if [[ ! -x "$PY" ]]; then
    step "mlops 가상환경이 없습니다 ($PY) → 먼저 bash scripts/setup_mlops.sh"; exit 1
  fi
  FULL=1
  step "1) 데이터 준비";  mlops prepare || step "데이터 준비 실패 → 있는 데이터로 계속"
  step "2) DSPy 최적화·게이트"; mlops dspy || step "DSPy 단계 실패 → 학습은 계속"
  DATA="$HOME/ai/sft/nightly-$(date +%Y%m%d).jsonl"
  step "3) QLoRA 학습 데이터"
  if ! mlops sft --out "$DATA"; then
    step "학습 데이터 생성 실패 → 학습 건너뜀"; mlops summary; exit 0
  fi
fi
[[ -f "$DATA" ]] || { step "학습 데이터가 없습니다: $DATA"; exit 1; }

# 4) 같은 데이터로 이미 학습한 어댑터가 있으면 건너뜀 (새 데이터가 있을 때만 학습, vLLM도 안 내림)
SHA=$(sha256sum "$DATA" | cut -c1-12)
DONE=$(grep -lx "$SHA" "$HOME"/ai/adapters/*/data.sha 2>/dev/null | head -1 || true)
if [[ -n "$DONE" && "${FORCE:-0}" != 1 ]]; then
  step "4) 이미 학습한 데이터 (sha $SHA → $(dirname "$DONE")) → QLoRA 건너뜀 (다시 하려면 FORCE=1)"
  [[ $FULL == 1 ]] && mlops summary
  exit 0
fi

step "4) QLoRA 학습 (데이터 sha $SHA)"
tmux kill-session -t vllm 2>/dev/null && step "[vllm] 종료 (OCR·MLflow는 그대로)"
sleep 30
nvidia-smi --query-gpu=index,memory.used --format=csv,noheader
CUDA_VISIBLE_DEVICES="$GPU_ID" MLFLOW_TRACKING_URI="http://127.0.0.1:$MLFLOW_PORT" \
  "$HOME/ai/.venv-train/bin/python" "$SCRIPT_DIR/qlora_train.py" --data "$DATA" --name "$NAME"
STATUS=$?
step "학습 종료 (코드 $STATUS)"

# vLLM 다시 올리기: champion 어댑터는 serve.sh가 자동으로 붙임 + 학습 성공이면 새 어댑터도 (게이트에서 비교)
if [[ $STATUS -eq 0 && -f "$ADAPTER/adapter_config.json" ]]; then
  echo "$SHA" > "$ADAPTER/data.sha"                 # 이 데이터로 학습했다는 기록 (다음 밤에 건너뛰는 기준)
  tmux new-session -d -s vllm "LORA_PATH=$ADAPTER LORA_NAME=$NAME bash $SCRIPT_DIR/serve.sh"
  step "[vllm] 시작 (기본 모델 + champion 어댑터(있으면) + 새 어댑터 $NAME)"
else
  tmux new-session -d -s vllm "bash $SCRIPT_DIR/serve.sh"
  step "[vllm] 시작 (학습 실패 → 새 어댑터 없이)"
fi
# OCR이 어떤 이유로 꺼져 있으면 다시 올림 (켜져 있으면 그대로)
tmux has-session -t ocr 2>/dev/null || { tmux new-session -d -s ocr "bash $SCRIPT_DIR/serve_ocr.sh"; step "[ocr] 시작"; }

if ! vllm_ready; then
  step "vLLM이 15분 안에 안 떴습니다 → 게이트 건너뜀 (tail ~/ai/logs/vllm-$(date +%F).log)"; exit 1
fi
if [[ $FULL == 1 && $STATUS -eq 0 ]]; then
  step "5) 어댑터 게이트"; mlops adapter --name "$NAME" --path "$ADAPTER" || step "어댑터 게이트 실패"
fi
[[ $FULL == 1 ]] && mlops summary
step "야간 작업 끝"
