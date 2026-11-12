#!/usr/bin/env bash
# 야간 모드 시작: night_job.sh를 tmux night 세션에서 실행 (SSH·Jupyter 연결이 끊겨도 계속 돌게).
# 사용법: bash start_night.sh [어댑터 이름] [학습 데이터 jsonl]
#         START_AT=20:00 bash start_night.sh ...  → 오늘 한국 시간 20:00까지 기다렸다가 시작
#         FORCE=1 bash start_night.sh ...         → 이미 학습한 데이터여도 다시 학습
#         (cron 아님: night 세션 안에서 한 번 기다렸다가 실행, 끝나면 세션은 스스로 사라짐)
# 진행 확인: tail -f ~/ai/logs/night-$(date +%F).log
# 취소: tmux kill-session -t night  (대기 중이면 아무 일도 안 일어남 / 학습 중이었다면 vLLM은 start_day.sh로 다시 올림)
# 종료 코드: 0 예약·시작함 / 1 실패 / 2 이미 예약·실행 중이라 그대로 둠
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HOME/ai/logs"
LOG="$HOME/ai/logs/night-$(date +%F).log"

# 이미 night 세션이 있으면: 안에서 무언가 돌고 있으면(대기 sleep·학습) 그대로 두고,
# 아무것도 안 도는 빈 셸이면(손으로 열어 두고 잊은 세션) 정리하고 새로 예약 → 빈 세션 때문에 예약이 막히던 문제 방지
if tmux has-session -t night 2>/dev/null; then
  PANE=$(tmux list-panes -t night -F '#{pane_pid}' | head -1)
  if pgrep -P "$PANE" >/dev/null; then
    echo "[night] 이미 예약·실행 중이라 그대로 둡니다 → tail $LOG"; exit 2
  fi
  tmux kill-session -t night
  echo "[night] 아무 작업도 없는 빈 night 세션이 남아 있어 정리했습니다"
fi

# 시작 시각 예약 (선택): 서버 시계가 UTC여도 한국 시간 기준. KST-9 = UTC+9 (tzdata가 없어도 동작)
WAIT=0
if [[ -n "${START_AT:-}" ]]; then
  [[ "$START_AT" =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]] || { echo "[night] START_AT은 HH:MM 형식 (예: 20:00)"; exit 1; }
  WAIT=$(( $(TZ=KST-9 date -d "today $START_AT" +%s) - $(date +%s) ))
  if (( WAIT <= 0 )); then
    echo "[night] 오늘 한국 시간 $START_AT은 이미 지났습니다"; exit 1
  fi
fi

# 대기 → 야간 작업. 로그에 예약 기록도 남김 (내일 아침에 '예약은 됐는데 왜 안 돌았나' 확인용)
# FORCE는 명령 안에 직접 넣음: tmux가 이미 떠 있으면 새 세션이 지금 셸의 환경변수를 물려받지 않음
tmux new-session -d -s night "{ echo \"[\$(date '+%F %T')] 예약: ${WAIT}초 대기 (START_AT=${START_AT:-즉시})\"; sleep $WAIT; FORCE=${FORCE:-0} bash $SCRIPT_DIR/night_job.sh $*; } 2>&1 | tee -a $LOG"

# 세션이 실제로 떠서 예약 줄을 남겼는지 확인 (조용히 실패하지 않게)
sleep 1
if ! tmux has-session -t night 2>/dev/null || ! grep -q "예약:" "$LOG" 2>/dev/null; then
  echo "[night] night 세션을 띄우지 못했습니다 (tmux ls, $LOG 확인)"; exit 1
fi
if (( WAIT > 0 )); then
  echo "[night] 한국 시간 $START_AT 시작 예약 ($(( WAIT / 60 ))분 뒤) → tail -f $LOG"
else
  echo "[night] 시작 → tail -f $LOG"
fi
