#!/usr/bin/env bash
# 하루 시작 (서빙 모드): vllm, ocr, mlflow 세션을 tmux로 띄운다. 이미 떠 있는 세션은 건너뜀.
# GPU 서버 규칙(데몬·cron 금지, 24시간마다 재시작)에 맞춰 매일 아침 직접 실행하고, 저녁에 stop_day.sh로 내린다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

start() {   # 사용법: start <세션 이름> <스크립트 파일>
  if tmux has-session -t "$1" 2>/dev/null; then
    echo "[$1] 이미 실행 중"
  else
    tmux new-session -d -s "$1" "bash $SCRIPT_DIR/$2"
    echo "[$1] 시작"
  fi
}

# Tailscale(ts): 없으면 같이 켬 (로그인 상태는 state 파일에 남아 있어 재로그인 불필요, 끌 때는 stop_day.sh도 안 끔)
bash "$SCRIPT_DIR/start_ts.sh"

start vllm serve.sh
start ocr serve_ocr.sh
start mlflow serve_mlflow.sh

echo "세션 확인: tmux ls"
echo "로그 확인: tail -f ~/ai/logs/*-$(date +%F).log"

# 밤 학습 예약 (선택): NIGHT_AT=20:00 bash start_day.sh → 오늘 한국 시간 20:00에 night_job 1회
# cron 아님: 오늘 하루만 예약 (서버가 매일 재시작되므로 매일 아침 이 스크립트로 다시 예약)
# 데이터: NIGHT_DATA가 없으면 ~/ai/sft에서 가장 최근에 올린 jsonl / 이미 학습한 데이터면 night_job이 그냥 끝냄
if [[ -n "${NIGHT_AT:-}" ]]; then
  NIGHT_DATA="${NIGHT_DATA:-$(ls -t "$HOME"/ai/sft/*.jsonl 2>/dev/null | head -1)}"
  START_AT="$NIGHT_AT" bash "$SCRIPT_DIR/start_night.sh" "${NIGHT_NAME:-receipt-qlora-$(date +%Y%m%d)}" "$NIGHT_DATA" || true
fi