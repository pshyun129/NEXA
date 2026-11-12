"""골든셋(사람이 확정한 실제 영수증) 고정 분할: 학습용 70% / 평가용 30%.

  python -X utf8 -m mlops.golden_split            # MLflow에서 최신 골든셋 받기 → 나누기 → 몇 장인지 출력

나누는 기준 = 행 id의 해시 → 같은 행은 매일 같은 쪽 (오늘 평가용이던 행이 내일 학습용으로 넘어가지 않음)
  → 학습에 쓴 영수증으로 시험을 보는 일이 생기지 않고, 새로 쌓인 행만 70:30으로 나뉘어 들어감
결과: mlops/data/golden/ocr.jsonl (split = train / test, evaluate·promote가 그대로 읽는 형식)
"""
import hashlib
import json
from pathlib import Path

GOLDEN_DIR = Path("mlops/data/golden")
TEST_SHARE = 3                 # 10칸 중 3칸 = 평가용 30%
MIN_ROWS = 30                  # 골든셋이 이보다 적으면 학습에 섞지 않고, 게이트에도 안 씀 (몇 장으로는 우연에 흔들림)


def split_of(row_id: str) -> str:
    h = int(hashlib.sha256(f"golden:{row_id}".encode()).hexdigest(), 16)
    return "test" if h % 10 < TEST_SHARE else "train"


def load(split: str | None = None) -> list[dict]:
    path = GOLDEN_DIR / "ocr.jsonl"
    if not path.exists():
        return []
    rows = [json.loads(x) for x in path.read_text(encoding="utf-8").splitlines() if x.strip()]
    return [r for r in rows if split is None or r["split"] == split]


def rows_sha(rows: list[dict]) -> str:
    body = "".join(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n" for r in rows)
    return hashlib.sha256(body.encode("utf-8")).hexdigest()[:12] if rows else "-"


def refresh(experiment: str = "golden-set") -> dict:
    """MLflow 최신 골든셋 받기(없으면 빈 셋) → 나눠서 저장 → 통계"""
    from mlops.export_golden import from_mlflow

    try:
        rows = from_mlflow("영수증", GOLDEN_DIR, experiment)
    except SystemExit as e:                       # 아직 EC2가 한 번도 안 보냄
        print(f"골든셋 없음: {e}")
        rows = []
    for r in rows:
        r["split"] = split_of(r["id"])
    GOLDEN_DIR.mkdir(parents=True, exist_ok=True)
    (GOLDEN_DIR / "ocr.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    train, test = [r for r in rows if r["split"] == "train"], [r for r in rows if r["split"] == "test"]
    return {"golden_n": len(rows), "golden_train_n": len(train), "golden_test_n": len(test),
            "golden_train_sha": rows_sha(train), "golden_test_sha": rows_sha(test),
            "golden_usable": len(rows) >= MIN_ROWS}


if __name__ == "__main__":
    print(refresh())
