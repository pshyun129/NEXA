"""MLflow Model Registry 도우미 (밤 작업 mlops/nightly.py 전용, GPU 서버에서 실행).

모델 3개, 각각 별칭 champion = 지금 서빙 중인 버전 (ai/champions.py가 EC2에서 이 별칭을 보고 바꿔 끼움)
  receipt-extractor  영수증 추출 DSPy 프로그램   (버전 태그 artifact = program.json 위치, label = receipt@vN)
  policy-judge       규정 판정 DSPy 프로그램     (artifact, label = policy@vN)
  receipt-adapter    영수증 추출 QLoRA 어댑터    (adapter_name, adapter_path = GPU 서버의 어댑터 폴더)
버전마다 previous_* 태그로 직전 champion을 기록 → 자동 롤백이 어디로 돌아갈지 앎 (비어 있으면 Git 파일 기준)
"""
import json
import time
from pathlib import Path

from ai.champions import ADAPTER, ALIAS, POLICY, RECEIPT
from mlops.common import setup_mlflow

DESCRIPTIONS = {
    RECEIPT: "영수증 항목 추출 DSPy 프로그램 (지시문 + 예시)",
    POLICY: "규정 판정 DSPy 프로그램 (조항별 판정 지시문)",
    ADAPTER: "영수증 추출 QLoRA 어댑터 (vLLM --lora-modules로 서빙)",
}
CACHE = Path("mlops/data/champions")      # 내려받은 champion 프로그램 (git에 안 올라감)


def client():
    setup_mlflow()
    from mlflow import MlflowClient

    return MlflowClient()


def champion(name: str) -> dict | None:
    """별칭 champion 버전 → {version, tags, run_id} (없으면 None)"""
    from mlflow.exceptions import MlflowException

    try:
        mv = client().get_model_version_by_alias(name, ALIAS)
    except MlflowException:
        return None
    return {"version": str(mv.version), "tags": dict(mv.tags), "run_id": mv.run_id}


def download_program(ch: dict, name: str) -> Path:
    """champion 프로그램 JSON을 내려받아 경로를 돌려줌"""
    import mlflow

    dst = CACHE / name / ch["version"]
    dst.mkdir(parents=True, exist_ok=True)
    return Path(mlflow.artifacts.download_artifacts(artifact_uri=ch["tags"]["artifact"], dst_path=str(dst)))


def register(name: str, run_id: str, artifact: str | None, tags: dict, previous: dict | None) -> str:
    """새 버전 등록 + 태그 + 별칭 champion 이동 → 버전 번호
    artifact: 버전이 가리킬 MLflow 아티팩트 (프로그램 JSON). 어댑터는 run 아티팩트의 adapter_ref.json"""
    from mlflow.exceptions import MlflowException

    c = client()
    try:
        c.get_registered_model(name)
    except MlflowException:                               # 처음 승격이면 모델 이름부터 만듦
        c.create_registered_model(name, description=DESCRIPTIONS[name])
    run = c.get_run(run_id)
    source = artifact.rsplit("/", 1)[0] if artifact else f"{run.info.artifact_uri}"
    mv = c.create_model_version(name, source=source, run_id=run_id)
    prev_t = (previous or {}).get("tags", {})
    all_tags = {
        **tags,
        "promoted_at": time.strftime("%Y-%m-%d %H:%M"),
        "previous_version": (previous or {}).get("version", ""),
        "previous_label": prev_t.get("label", tags.get("previous_label", "")),
        "previous_adapter": prev_t.get("adapter_name", ""),
    }
    if artifact:
        all_tags["artifact"] = artifact
    if name in (RECEIPT, POLICY):
        all_tags["label"] = f"{'receipt' if name == RECEIPT else 'policy'}@v{mv.version}"
    for k, v in all_tags.items():
        c.set_model_version_tag(name, mv.version, k, str(v))
    c.set_registered_model_alias(name, ALIAS, mv.version)   # 별칭은 한 버전에만 → 이전 champion에서 자동으로 떨어짐
    return str(mv.version)


def artifact_uri(run_id: str, path: str) -> str:
    return f"{client().get_run(run_id).info.artifact_uri}/{path}"


def log_json(run_id: str, obj: dict, path: str):
    """이미 끝난 run에도 아티팩트 추가 (게이트 판정 기록 등)"""
    import mlflow

    with mlflow.start_run(run_id=run_id):
        mlflow.log_dict(obj, path)


def tags_json(d: dict) -> str:
    return json.dumps(d, ensure_ascii=False)
