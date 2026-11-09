#!/usr/bin/env bash
# 하루 종료: 서빙 세션(mlflow, ocr, vllm)을 내린다.
# tmux 세션은 사용자별이라 다른 팀 프로세스는 건드리지 않는다.
# Tailscale(ts)은 남겨둠 (로그인 유지). 같이 끄려면: tmux kill-session -t ts
for s in mlflow ocr vllm; do
  if tmux kill-session -t "$s" 2>/dev/null; then
    echo "[$s] 종료"
  else
    echo "[$s] 실행 중 아님"
  fi
done

# GPU 메모리가 실제로 내려갔는지 확인 (우리 몫이 비었는지)
nvidia-smi --query-gpu=index,memory.used,memory.total --format=csv