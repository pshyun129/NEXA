#!/usr/bin/env bash
# MLflow 추적 서버 (실험 기록·모델 레지스트리). GPU 서버의 tmux 안에서 실행.
# 인증이 없으므로 127.0.0.1에만 열고 Tailscale로만 접근한다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
set -a
source "$SCRIPT_DIR/../.env"
set +a

source "$HOME/ai/.venv-mlflow/bin/activate"
mkdir -p "$HOME/ai/logs" "$HOME/ai/mlflow/artifacts"

# 사용 통계를 외부로 보내지 않음 (공용 서버)
export MLFLOW_DISABLE_TELEMETRY=true

# --allowed-hosts: Tailscale 주소로 들어오는 요청도 허용 (없으면 DNS rebinding 방어에 막힘)
mlflow server \
  --host 127.0.0.1 \
  --port "$MLFLOW_PORT" \
  --backend-store-uri "sqlite:///$HOME/ai/mlflow/mlflow.db" \
  --artifacts-destination "$HOME/ai/mlflow/artifacts" \
  --allowed-hosts "$MLFLOW_ALLOWED_HOSTS" \
  --workers 2 \
  2>&1 | tee -a "$HOME/ai/logs/mlflow-$(date +%F).log"