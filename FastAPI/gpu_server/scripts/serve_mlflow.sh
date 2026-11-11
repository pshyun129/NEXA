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
# --cors-allowed-origins: 브라우저(웹 화면)에서 오는 POST도 허용. 기본은 localhost뿐이라
#   Tailscale 주소로 화면을 열면 실험·run 검색(POST)이 "Cross-origin request blocked"(403)로 막혀 목록이 빈 채로 보였다 (2026-11-11)
#   따로 안 적으면 허용 호스트마다 http://호스트:포트 로 만든다. 파이썬 스크립트·골든셋 전송은 Origin이 없어 원래 영향 없음
if [ -z "${MLFLOW_CORS_ALLOWED_ORIGINS:-}" ]; then
  MLFLOW_CORS_ALLOWED_ORIGINS=$(echo "$MLFLOW_ALLOWED_HOSTS" | tr ',' '\n' | sed 's#^#http://#' | paste -sd, -)
fi

mlflow server \
  --host 127.0.0.1 \
  --port "$MLFLOW_PORT" \
  --backend-store-uri "sqlite:///$HOME/ai/mlflow/mlflow.db" \
  --artifacts-destination "$HOME/ai/mlflow/artifacts" \
  --allowed-hosts "$MLFLOW_ALLOWED_HOSTS" \
  --cors-allowed-origins "$MLFLOW_CORS_ALLOWED_ORIGINS" \
  --workers 2 \
  2>&1 | tee -a "$HOME/ai/logs/mlflow-$(date +%F).log"