"""MLOps 스크립트 공용 도구: 데이터 읽기, 채점, 로컬 LLM, MLflow 연결.

evaluate(5-4) · optimize(5-5) · promote(5-6)가 모두 같은 채점 기준을 쓰도록 한 곳에 모은다.
스크립트마다 채점 기준이 다르면 점수끼리 비교하는 것 자체가 의미 없어지기 때문.
"""
import hashlib
import json
import os
from pathlib import Path

import dspy

from ai.dspy_lm import get_local_lm
from ai.llm import _local_enabled
from ai.modules.receipt import ReceiptExtractor
from core.config import settings

DATA_DIR = Path("mlops/data/receipts_v2")   # 기본 데이터셋 (v1 = mlops/data/receipts)
EXPERIMENT = "receipt-extraction"           # MLflow 실험 이름 (영수증 추출 관련 run은 전부 여기로)
FIELDS = ("store", "total", "date")


def load_records(split: str | None = None, data_dir: Path = DATA_DIR, ocr_file: str = "ocr.jsonl") -> list[dict]:
    """5-2에서 만든 OCR 텍스트셋 읽기. split을 주면 그 묶음(train/dev/test)만"""
    path = Path(data_dir) / ocr_file
    if not path.exists():
        raise SystemExit(f"{path}가 없습니다. 먼저 python -m mlops.build_textset --data {data_dir} --out {ocr_file} 를 실행하세요")
    with path.open(encoding="utf-8") as f:
        rows = [json.loads(line) for line in f if line.strip()]
    return [r for r in rows if split is None or r["split"] == split]


def dataset_info(data_dir: Path = DATA_DIR, ocr_file: str = "ocr.jsonl") -> dict:
    """데이터 버전 기록용: 폴더 이름 + OCR 파일 이름 + 내용 해시.
    내용이 1바이트라도 바뀌면 해시가 바뀜 → '어떤 데이터로 잰 점수인지' run마다 확인 가능"""
    path = Path(data_dir) / ocr_file
    return {
        "dataset": Path(data_dir).name,
        "ocr_file": ocr_file,
        "dataset_hash": hashlib.sha256(path.read_bytes()).hexdigest()[:12],
    }


def squash(text) -> str:
    """띄어쓰기·줄바꿈 제거. '카페 온도 판교점' == '카페온도 판교점'으로 보기 위함"""
    return "".join(str(text).split())


def score_fields(pred, label: dict) -> dict[str, bool]:
    """필드별 정답 여부. pred는 Receipt 객체, None이면 실행 실패 → 전부 오답"""
    if pred is None:
        return {k: False for k in FIELDS}
    try:
        total_ok = int(pred.total) == int(label["total"])
    except (TypeError, ValueError):
        total_ok = False
    return {
        "store": squash(pred.store) == squash(label["store"]),   # 상호명: 띄어쓰기 무시
        "total": total_ok,                                       # 금액: 숫자로 정확히 일치
        "date": str(pred.date).strip() == label["date"],         # 날짜: YYYY-MM-DD 문자열 일치
    }


def dspy_metric(example, pred, trace=None):
    """5-5 DSPy 최적화용 점수.
    평소(trace=None): 맞힌 필드 비율 0~1 → 부분 점수가 있어야 최적화가 방향을 잡음
    예시 수집 중(trace 있음): 세 필드 전부 맞아야 True → 틀린 답이 '모범 예시'로 들어가는 것 방지
    """
    s = score_fields(getattr(pred, "receipt", None), example.label)
    if trace is not None:
        return all(s.values())
    return sum(s.values()) / len(s)


def to_examples(records: list[dict]) -> list[dspy.Example]:
    """DSPy 학습 예시로 변환. text만 입력이고 label은 채점용 정답"""
    return [dspy.Example(text=r["text"], label=r["label"]).with_inputs("text") for r in records]


def load_program(path: str | Path | None = None) -> ReceiptExtractor:
    """경로가 없으면 기본 프로그램(base), 있으면 최적화된 상태(JSON)를 덮어씌움"""
    program = ReceiptExtractor()
    if path:
        program.load(str(path))
    return program


def require_local_lm() -> dspy.LM:
    """평가·최적화는 로컬 LLM 전용 (GMS 대체 없음)"""
    if not _local_enabled():
        raise SystemExit("로컬 LLM 설정이 없습니다 (.env의 LOCAL_LLM_BASE_URL 확인)")
    return get_local_lm()


def setup_mlflow():
    """MLflow 서버 연결 + 실험 선택. 텔레메트리(사용 통계 전송)는 mlflow import 전에 끔"""
    os.environ.setdefault("MLFLOW_DISABLE_TELEMETRY", "true")
    import mlflow

    if not settings.mlflow_tracking_uri:
        raise SystemExit(".env에 MLFLOW_TRACKING_URI가 없습니다")
    mlflow.set_tracking_uri(settings.mlflow_tracking_uri)
    mlflow.set_experiment(EXPERIMENT)       # 없으면 자동 생성
    return mlflow