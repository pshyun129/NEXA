"""합성 데이터셋을 MLflow로 주고받기 (PC → GPU 서버). mlops/data는 git에 없어서 밤 작업이 쓸 데이터를 따로 옮김.

  python -X utf8 -m mlops.datasets push    # PC: 텍스트셋(ocr.jsonl)·정답(labels.jsonl)을 MLflow 실험 datasets에 올림 (바뀐 것만)
  python -X utf8 -m mlops.datasets pull    # GPU 서버: 최신본 받기 (밤 작업 prepare 단계가 자동으로 부름)

이미지는 안 옮김: 평가·최적화·학습 데이터는 OCR 텍스트만 쓰고, OCR은 텍스트셋을 만들 때 한 번만 돌림
"""
import argparse
import hashlib
from pathlib import Path

from mlops.common import setup_mlflow

EXPERIMENT = "datasets"
ROOT = Path("mlops/data")
DATASETS = {
    "receipts_v2": ["ocr.jsonl", "labels.jsonl"],          # dev·test = 평가·게이트, train = DSPy 학습
    "receipts_v2_train": ["ocr.jsonl", "labels.jsonl"],    # 학습 전용 (seed 7, dev·test와 겹치지 않음)
}


def local_sha(name: str) -> str | None:
    files = [ROOT / name / f for f in DATASETS[name]]
    if not all(f.exists() for f in files):
        return None
    h = hashlib.sha256()
    for f in files:
        h.update(f.read_bytes())
    return h.hexdigest()[:12]


def _latest(mlflow, name: str):
    runs = mlflow.search_runs(experiment_names=[EXPERIMENT], filter_string=f"tags.dataset = '{name}'",
                              order_by=["start_time DESC"], max_results=1, output_format="list")
    return runs[0] if runs else None


def push():
    mlflow = setup_mlflow()
    mlflow.set_experiment(EXPERIMENT)
    for name, files in DATASETS.items():
        sha = local_sha(name)
        if sha is None:
            print(f"[{name}] 파일이 없어 건너뜀")
            continue
        latest = _latest(mlflow, name)
        if latest and latest.data.tags.get("sha") == sha:
            print(f"[{name}] 이미 올라가 있음 (sha {sha})")
            continue
        with mlflow.start_run(run_name=f"{name}-{sha}"):
            mlflow.set_tags({"dataset": name, "sha": sha})
            for f in files:
                mlflow.log_artifact(str(ROOT / name / f), artifact_path=name)
        print(f"[{name}] 올림 (sha {sha})")


def pull() -> dict[str, str]:
    """최신본과 다르면 받음. {이름: sha}"""
    mlflow = setup_mlflow()
    out = {}
    for name in DATASETS:
        latest = _latest(mlflow, name)
        if latest is None:
            if local_sha(name) is None:
                raise SystemExit(f"[{name}] MLflow에도 로컬에도 없음 → PC에서 python -m mlops.datasets push")
            out[name] = local_sha(name)
            continue
        sha = latest.data.tags["sha"]
        if local_sha(name) != sha:
            mlflow.artifacts.download_artifacts(run_id=latest.info.run_id, artifact_path=name, dst_path=str(ROOT))
            print(f"[{name}] 받음 (sha {sha})")
        out[name] = sha
    return out


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("action", choices=["push", "pull"])
    a = p.parse_args()
    push() if a.action == "push" else print(pull())
