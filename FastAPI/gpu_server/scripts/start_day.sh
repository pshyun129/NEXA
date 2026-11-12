#!/usr/bin/env bash
# 하루 시작 (서빙 모드): vllm, ocr, mlflow 세션을 tmux로 띄운다. 이미 떠 있는 세션은 건너뜀.
# GPU 서버 규칙(데몬·cron 금지, 24시간마다 재시작)에 맞춰 매일 아침 직접 실행하고, 저녁에 stop_day.sh로 내린다.
# RESTART=1: 떠 있는 세션을 먼저 끄고(stop_day.sh) 우리 프로세스가 다 내려간 뒤 다시 켬 → 밤사이 살아 있던 세션도
#            새 코드(git pull)·설정으로 갱신. 아침 루틴: RESTART=1 NIGHT_AT=20:00 bash scripts/start_day.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if [[ "${RESTART:-0}" == 1 ]]; then
  ME="${USER:-$(id -un)}"
  # 밤 학습이 아직 돌고 있으면(전날 밤 것이 길어진 경우) 서빙을 다시 올리면 GPU 메모리가 겹침 → 멈춤
  # 예약만 걸려 기다리는 night 세션은 그대로 둠 (학습 프로세스가 없으면 통과)
  if pgrep -u "$ME" -f qlora_train.py >/dev/null; then
    echo "[night] 학습 중이라 재시작하지 않습니다 (끝나면 night_job이 vLLM을 다시 올림: tail -f ~/ai/logs/night-*.log)"; exit 1
  fi
  bash "$SCRIPT_DIR/stop_day.sh"
  # 우리 서빙 프로세스(vLLM·OCR·MLflow)가 GPU 메모리를 놓을 때까지 기다림 (바로 켜면 메모리 부족으로 vLLM이 못 뜰 수 있음)
  for i in $(seq 1 30); do
    pgrep -u "$ME" -f "vllm serve|ocr_server.py|mlflow server" >/dev/null || break
    [[ $i == 1 ]] && echo "이전 프로세스가 내려가길 기다리는 중…"
    sleep 3
  done
  if pgrep -u "$ME" -f "vllm serve|ocr_server.py|mlflow server" >/dev/null; then
    echo "90초가 지나도 이전 프로세스가 남아 있습니다: pgrep -af -u $ME 'vllm|ocr_server|mlflow' 로 확인"; exit 1
  fi
  sleep 5
fi

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

# MLflow 먼저: vLLM이 뜰 때 serve.sh가 MLflow에서 champion 어댑터를 확인함 (못 받으면 기본 모델만으로 뜸)
start mlflow serve_mlflow.sh
for _ in $(seq 1 20); do
  curl -sf "http://127.0.0.1:${MLFLOW_PORT:-18200}/health" >/dev/null 2>&1 && break
  sleep 3
done
start vllm serve.sh
start ocr serve_ocr.sh

echo "세션 확인: tmux ls"
echo "로그 확인: tail -f ~/ai/logs/*-$(date +%F).log"

# 밤 MLOps 예약 (선택): NIGHT_AT=20:00 bash start_day.sh → 오늘 한국 시간 20:00에 night_job 1회
#   데이터 준비 → DSPy 최적화·게이트 → QLoRA 학습 데이터 → (새 데이터면) QLoRA 학습 → 어댑터 게이트, 결과는 MLflow 실험 nightly
# cron 아님: 오늘 하루만 예약 (서버가 매일 재시작되므로 매일 아침 이 스크립트로 다시 예약)
# NIGHT_DATA를 주면 그 데이터로 QLoRA 학습만 (예전 방식)
if [[ -n "${NIGHT_AT:-}" ]]; then
  # 예약 실패를 삼키지 않고 결과를 항상 보여 줌 (2026-11-09~12 예약이 조용히 거절된 문제)
  set +e
  START_AT="$NIGHT_AT" bash "$SCRIPT_DIR/start_night.sh" "${NIGHT_NAME:-receipt-qlora-$(date +%Y%m%d)}" ${NIGHT_DATA:+"$NIGHT_DATA"}
  RC=$?
  set -e
  if [[ $RC == 0 ]]; then
    echo "[night] 밤 MLOps 예약됨: $NIGHT_AT${NIGHT_DATA:+ (학습만: $(basename "$NIGHT_DATA"))}"
  elif [[ $RC == 2 ]]; then
    echo "[night] 기존 예약·학습을 그대로 둠 (tail -n 3 ~/ai/logs/night-$(date +%F).log)"
  else
    echo "⚠ [night] 밤 MLOps 예약 안 됨 (위 메시지 확인). 직접 걸기: START_AT=$NIGHT_AT bash scripts/start_night.sh"
  fi
else
  echo "[night] 밤 MLOps 예약 없음 (예약하려면 앞에 NIGHT_AT=20:00 을 붙여 실행)"
fi
