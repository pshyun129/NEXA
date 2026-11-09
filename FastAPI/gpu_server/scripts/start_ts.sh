#!/usr/bin/env bash
# Tailscale (userspace-networking, sudo 없음): EC2·PC가 GPU 서버의 vLLM·OCR·MLflow로 들어오는 통로.
# 로그인 상태는 ~/ai/tailscale/state 에 남아 있어서 데몬만 다시 켜면 됨 (재로그인 불필요).
# GPU 서버가 재시작되면 tmux 세션이 전부 사라짐 → start_day.sh가 이 스크립트를 먼저 부름.
# (2026-11-08 ts가 꺼져 EC2가 OCR을 못 쓴 일이 있어 스크립트로 남김)
set -euo pipefail

TS_DIR="${TS_DIR:-$HOME/ai/tailscale}"
SOCK="$TS_DIR/tailscaled.sock"
LOG="$HOME/ai/logs/ts-$(date +%F).log"
mkdir -p "$HOME/ai/logs"

if tmux has-session -t ts 2>/dev/null; then
  echo "[ts] 이미 실행 중"
else
  tmux new-session -d -s ts "$TS_DIR/tailscaled --tun=userspace-networking --statedir=$TS_DIR/state --socket=$SOCK --port=0 2>&1 | tee -a $LOG"
  echo "[ts] 시작"
  sleep 3
fi

# 연결 확인: 100.x 주소가 나오면 OK
"$TS_DIR/tailscale" --socket="$SOCK" ip -4 || echo "[ts] 아직 연결 전 → 몇 초 뒤: $TS_DIR/tailscale --socket=$SOCK status"