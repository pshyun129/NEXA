#!/usr/bin/env bash
# 야간 모드 시작: night_job.sh를 tmux night 세션에서 실행 (SSH·Jupyter 연결이 끊겨도 계속 돌게).
# 사용법: bash start_night.sh [어댑터 이름] [학습 데이터 jsonl]
# 진행 확인: tail -f ~/ai/logs/night-$(date +%F).log   /   끝나면 night 세션은 스스로 사라짐
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HOME/ai/logs"

if tmux has-session -t night 2>/dev/null; then
  echo "[night] 이미 실행 중"; exit 1
fi
tmux new-session -d -s night "bash $SCRIPT_DIR/night_job.sh $* 2>&1 | tee -a $HOME/ai/logs/night-$(date +%F).log"
echo "[night] 시작 → tail -f ~/ai/logs/night-$(date +%F).log"