#!/usr/bin/env bash
# OCR 서빙 (PyTriton). GPU 서버의 tmux 안에서 실행.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
set -a
source "$SCRIPT_DIR/../.env"
set +a

source "$HOME/ai/.venv-ocr/bin/activate"
mkdir -p "$HOME/ai/logs"

# Triton이 Python 공유 라이브러리를 찾을 수 있게 경로 지정
export LD_LIBRARY_PATH="/opt/tljh/user/lib:${LD_LIBRARY_PATH:-}"
# PaddlePaddle이 GPU 메모리를 필요한 만큼만 쓰게 (공유 GPU)
export FLAGS_allocator_strategy=auto_growth
export PADDLE_PDX_CACHE_HOME="$HOME/ai/paddlex"
# 모델 다운로드 서버 연결 확인 생략 (이미 받아 둠)
export PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True

CUDA_VISIBLE_DEVICES="$GPU_ID" python "$SCRIPT_DIR/ocr_server.py" \
  2>&1 | tee -a "$HOME/ai/logs/ocr-$(date +%F).log"