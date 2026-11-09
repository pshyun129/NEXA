"""5-7 골든셋: 사람이 확정한 결과(run_feedback, label_source=human)를 평가셋으로 만든다.

합성 데이터 점수는 '우리가 만든 함정'에 대한 점수라 실제 영수증 성능과 다를 수 있다.
그래서 사람이 직접 확정(수정 또는 그대로 승인)한 결과만 모아 '진짜 정답' 평가셋을 만든다.

두 가지 방법
  python -X utf8 -m mlops.export_golden               # DB에서 직접 (DB에 접속되는 곳에서만, 예: 로컬 개발)
  python -X utf8 -m mlops.export_golden --from-mlflow # EC2 서비스가 MLflow에 올린 최신본 받기 (운영 데이터)
  python -X utf8 -m mlops.evaluate --data mlops/data/golden --split test --name base

결과:
  mlops/data/golden/ocr.jsonl              ← evaluate가 그대로 읽는 형식 (split=test)
  mlops/data/golden/ocr_corrections.jsonl  ← 사람이 상호명을 고친 사례 = OCR 인식 모델 파인튜닝 후보 (DB에서 직접일 때만)
"""
import argparse
import json
from collections import Counter
from pathlib import Path

OUT_DIR = Path("mlops/data/golden")       # mlops/data 아래라 git에 안 올라감 (실제 업무 데이터)
MIN_FOR_GATE = 30                          # 이보다 적으면 판정용이 아니라 참고 지표로만


def from_db(doc_type: str) -> tuple[list[dict], list[dict], int]:
    from core.database import SessionLocal
    from runs.golden import collect_golden

    with SessionLocal() as db:
        return collect_golden(db, doc_type)


def from_mlflow(doc_type: str, out: Path, experiment: str) -> list[dict]:
    """runs/golden_sync.py가 올린 run 중 가장 최근 것의 ocr.jsonl 받기"""
    from mlops.common import setup_mlflow

    mlflow = setup_mlflow()
    runs = mlflow.search_runs(experiment_names=[experiment], filter_string=f"tags.doc_type = '{doc_type}'",
                              order_by=["start_time DESC"], max_results=1, output_format="list")
    if not runs:
        raise SystemExit(f"MLflow 실험 {experiment}에 {doc_type} 골든셋이 없습니다 (EC2 전송 전)")
    run = runs[0]
    out.mkdir(parents=True, exist_ok=True)
    path = mlflow.artifacts.download_artifacts(run_id=run.info.run_id, artifact_path="ocr.jsonl", dst_path=str(out))
    print(f"MLflow run {run.info.run_id} (sha {run.data.tags.get('golden_sha')}, "
          f"보낸 곳 {run.data.tags.get('source_host')}) → {path}")
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--doc-type", default="영수증")
    p.add_argument("--out", type=Path, default=OUT_DIR)
    p.add_argument("--from-mlflow", action="store_true", help="DB 대신 MLflow에 올라온 최신 골든셋 받기")
    p.add_argument("--experiment", default="golden-set")
    args = p.parse_args()

    if args.from_mlflow:
        records = from_mlflow(args.doc_type, args.out, args.experiment)
    else:
        records, corrections, skipped = from_db(args.doc_type)
        args.out.mkdir(parents=True, exist_ok=True)
        for name, rows in (("ocr.jsonl", records), ("ocr_corrections.jsonl", corrections)):
            with (args.out / name).open("w", encoding="utf-8") as f:
                for r in rows:
                    f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"텍스트 없어 제외 {skipped}장 | OCR 파인튜닝 후보 (상호명 수정): {len(corrections)}건")

    versions = Counter(r["meta"]["model_version"] for r in records)
    edited = sum(1 for r in records if r["meta"]["edited_fields"])
    print(f"골든셋 {len(records)}장 (사람이 고친 것 {edited}장, 그대로 승인 {len(records) - edited}장)")
    print(f"모델 버전별: {dict(versions)}")
    if len(records) < MIN_FOR_GATE:
        print(f"⚠️ {MIN_FOR_GATE}장 미만 → 승격 판정에는 아직 쓰지 말고 참고 지표로만")
    print(f"저장: {args.out}")


if __name__ == "__main__":
    main()