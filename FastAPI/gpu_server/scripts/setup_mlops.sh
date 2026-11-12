#!/usr/bin/env bash
# 밤 MLOps 준비 (GPU 서버에서 한 번만, requirements가 바뀌면 다시): mlops 실행용 가상환경 + GPU 서버용 FastAPI/.env + 데이터셋
#   bash scripts/setup_mlops.sh
# 만드는 것
#   ~/ai/.venv-mlops        FastAPI + mlops 의존성 (vLLM·학습 가상환경과 분리 → 서로 버전이 안 꼬임)
#   FastAPI/.env            mlops 스크립트용 설정: 같은 서버의 vLLM·MLflow 주소 (DB는 안 씀 → 더미 값). 이미 있으면 안 건드림
#   mlops/data/...          합성 데이터셋 (PC가 MLflow에 올린 것, python -m mlops.datasets push)
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FASTAPI="$(cd "$SCRIPT_DIR/../.." && pwd)"
set -a; source "$SCRIPT_DIR/../.env"; set +a
VENV="$HOME/ai/.venv-mlops"

# 서비스 이미지와 같은 파이썬 3.11 이상 (numpy 2.4 등이 3.11부터)
PYBIN=""
for p in python3.12 python3.11 python3; do
  if command -v "$p" >/dev/null && "$p" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)'; then PYBIN="$p"; break; fi
done
[[ -n "$PYBIN" ]] || { echo "파이썬 3.11 이상이 없습니다 (python3 --version 확인)"; exit 1; }

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "[1/3] 가상환경 만들기: $VENV ($($PYBIN --version))"
  "$PYBIN" -m venv "$VENV"
fi
echo "[1/3] 의존성 설치 (FastAPI + mlops)"
"$VENV/bin/pip" install -q --upgrade pip
"$VENV/bin/pip" install -q -r "$FASTAPI/requirements.txt" -r "$FASTAPI/mlops/requirements.txt"

ENV_FILE="$FASTAPI/.env"
if [[ -f "$ENV_FILE" ]]; then
  echo "[2/3] $ENV_FILE 이미 있음 → 그대로 둠"
else
  echo "[2/3] $ENV_FILE 만들기 (같은 서버의 vLLM·MLflow)"
  umask 077
  cat > "$ENV_FILE" <<ENV
# GPU 서버에서 mlops 스크립트(밤 작업)를 돌리기 위한 설정. 서비스(EC2)와 상관없음. setup_mlops.sh가 만듦
DB_HOST=unused
DB_PORT=5432
DB_NAME=unused
DB_USER=unused
DB_PASSWORD=unused
LOCAL_LLM_BASE_URL=http://127.0.0.1:${PORT}/v1
LOCAL_LLM_API_KEY=${VLLM_API_KEY}
LOCAL_LLM_MODEL=${SERVED_MODEL_NAME}
MLFLOW_TRACKING_URI=http://127.0.0.1:${MLFLOW_PORT}
GOLDEN_SYNC_HOURS=0
CHAMPION_SYNC_MINUTES=0
ENV
fi

echo "[3/3] 합성 데이터셋 받기 (MLflow 실험 datasets)"
(cd "$FASTAPI" && "$VENV/bin/python" -X utf8 -m mlops.datasets pull)
echo "준비 끝. 확인: cd $FASTAPI && $VENV/bin/python -X utf8 -m mlops.nightly prepare"
