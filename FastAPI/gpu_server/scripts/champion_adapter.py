"""지금 champion인 QLoRA 어댑터를 vLLM --lora-modules 형식(이름=폴더)으로 출력. 없으면 아무것도 출력 안 함.

serve.sh가 vLLM을 띄울 때 부름 → 밤 작업이 승격한 어댑터가 다음 날 아침부터 자동으로 서빙됨.
MLflow(같은 서버 127.0.0.1)가 꺼져 있거나 champion이 없거나 폴더가 없으면 → 출력 없음 = 기본 모델만 (서빙은 그대로 뜸)
표준 라이브러리만 사용 (vLLM 가상환경에서 그냥 python3로 실행)
"""
import json
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path


def main():
    port = os.environ.get("MLFLOW_PORT", "18200")
    q = urllib.parse.urlencode({"name": "receipt-adapter", "alias": "champion"})
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/2.0/mlflow/registered-models/alias?{q}", timeout=5) as r:
            mv = json.load(r)["model_version"]
    except Exception:
        return
    tags = {t["key"]: t["value"] for t in mv.get("tags", [])}
    name, path = tags.get("adapter_name"), tags.get("adapter_path")
    if not name or not path or not (Path(path) / "adapter_config.json").exists():
        print(f"champion 어댑터 정보가 없거나 폴더가 없음: {name} {path}", file=sys.stderr)
        return
    print(f"{name}={path}")


if __name__ == "__main__":
    main()
