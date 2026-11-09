"""규정 색인: 조항 + 임베딩을 파일로 저장하고, 서버에서는 불러와 재사용한다.

색인은 mlops/build_index.py로 만들고 FastAPI/ai/rag/index/에 커밋한다 (Docker 빌드가 ./FastAPI만 복사하므로).
  clauses.jsonl   조항 (본문 원문 포함 → 인용 검증 기준)
  embeddings.npy  조항 임베딩 (정규화, float32)
  meta.json       모델 이름, 원문 파일별 해시, 만든 시각 → 원문만 바뀌고 색인이 옛것인지 확인
"""
import json
import logging
import os
import threading
from dataclasses import asdict
from functools import lru_cache
from pathlib import Path

import numpy as np

from ai.rag.parse import Clause

INDEX_DIR = Path(__file__).resolve().parent / "index"
# 다국어 문장 임베딩 (한국어 지원, 0.22GB, ONNX라 CPU에서 빠름 → 낮 GPU 예산을 쓰지 않음)
EMBED_MODEL = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
# 모델 저장 위치: Docker에서는 빌드 때 받아둔 /opt/models (FASTEMBED_CACHE_PATH), 로컬은 비워두면 fastembed 기본(임시 폴더)
MODEL_CACHE = os.environ.get("FASTEMBED_CACHE_PATH") or None
logger = logging.getLogger(__name__)

_lock = threading.Lock()


@lru_cache(maxsize=1)
def _embedder():
    """처음 쓸 때 한 번만 모델을 불러옴 (첫 실행 때 약 220MB 다운로드)"""
    from fastembed import TextEmbedding
    return TextEmbedding(EMBED_MODEL, cache_dir=MODEL_CACHE)


def embed(texts: list[str]) -> np.ndarray:
    """문장들 → 정규화된 벡터 (코사인 유사도 = 내적)"""
    with _lock:                                   # ONNX 세션을 여러 스레드가 동시에 쓰지 않게
        vecs = np.asarray(list(_embedder().embed(texts)), dtype=np.float32)
    return vecs / np.clip(np.linalg.norm(vecs, axis=1, keepdims=True), 1e-12, None)


def save_index(clauses: list[Clause], vectors: np.ndarray, meta: dict, out: Path = INDEX_DIR) -> None:
    out.mkdir(parents=True, exist_ok=True)
    with (out / "clauses.jsonl").open("w", encoding="utf-8") as f:
        for c in clauses:
            f.write(json.dumps(asdict(c), ensure_ascii=False) + "\n")
    np.save(out / "embeddings.npy", vectors.astype(np.float32))
    (out / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


class RegulationIndex:
    """서버에서 쓰는 읽기 전용 색인"""

    def __init__(self, path: Path = INDEX_DIR):
        if not (path / "meta.json").exists():
            raise FileNotFoundError(f"규정 색인이 없습니다: {path} (python -m mlops.build_index 로 먼저 만드세요)")
        self.meta = json.loads((path / "meta.json").read_text(encoding="utf-8"))
        if self.meta.get("model") != EMBED_MODEL:   # 색인과 질문을 다른 모델로 임베딩하면 검색이 엉망이 됨
            raise ValueError(f"색인 모델({self.meta.get('model')})과 현재 모델({EMBED_MODEL})이 다릅니다. 색인을 다시 만드세요")
        with (path / "clauses.jsonl").open(encoding="utf-8") as f:
            self.clauses = [Clause(**json.loads(line)) for line in f if line.strip()]
        self.vectors = np.load(path / "embeddings.npy")
        self.by_id = {c.id: c for c in self.clauses}
        self.version = self.meta.get("version", "?")     # 판정 결과에 함께 기록 → 어떤 규정본을 봤는지 추적


@lru_cache(maxsize=1)
def get_index() -> RegulationIndex:
    return RegulationIndex()


# 재정렬 모델: 질문과 조항을 한 쌍씩 함께 읽고 관련도를 매김 (다국어, int8 ONNX, CPU)
RERANK_MODEL = "BAAI/bge-reranker-v2-m3-int8"


@lru_cache(maxsize=1)
def _reranker():
    """처음 쓸 때 한 번만 불러옴 (첫 실행 때 약 570MB 다운로드)"""
    from fastembed.rerank.cross_encoder import TextCrossEncoder
    return TextCrossEncoder(RERANK_MODEL, cache_dir=MODEL_CACHE)


def rerank(query: str, docs: list[str]) -> np.ndarray:
    """질문-조항 쌍마다 관련도 0~1 (모델 출력 로짓에 시그모이드)"""
    with _lock:
        logits = np.asarray(list(_reranker().rerank(query, docs)), dtype=np.float32)
    return 1 / (1 + np.exp(-logits))


def warm_up() -> None:
    """서버 시작 때 색인·두 모델을 미리 불러옴 → 첫 규정 검토 요청이 느리지 않게.
    실패해도 서버는 계속 뜸 (규정 검토 노드만 처음 쓸 때 다시 시도)"""
    try:
        get_index()
        embed(["준비"])
        rerank("준비", ["준비"])
        logger.info("규정 RAG 준비 완료 (색인 %s)", get_index().version)
    except Exception:
        logger.exception("규정 RAG 미리 불러오기 실패")


if __name__ == "__main__":
    # Docker 빌드에서 모델을 미리 받는 용도: python -m ai.rag.index
    _embedder()
    _reranker()
    print(f"모델 준비 완료 → {MODEL_CACHE or '기본 캐시'}")