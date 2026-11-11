"""규정 검색: ① 하이브리드로 넓게 찾고 ② 재정렬 모델로 정확하게 다시 줄 세운다.

1차 (후보 찾기, 놓치지 않는 게 목표)
  - 키워드: 글자 2·3개 묶음 BM25 → "유흥주점", "30,000원", "제8조" 같은 정확한 표현에 강함
  - 의미: 문장 임베딩 → 표현이 달라도 비슷한 뜻을 잡음
  - 두 순위를 RRF로 합쳐 상위 N개 후보
2차 (재정렬, 정확도가 목표)
  - 질문과 조항을 한 쌍씩 함께 읽는 모델로 관련도 0~1
  - 최고 관련도가 MIN_REL 미만이면 abstain → 판정 단계에서 LLM을 부르지 않고 '확인 필요'
"""
import math
import re
from collections import Counter
from dataclasses import dataclass, field
from functools import lru_cache

import numpy as np

from ai.rag.index import RegulationIndex, embed, get_index, rerank
from ai.rag.parse import Clause
from core.metrics import RETRIEVAL_CACHE

CANDIDATES = 15     # 1차에서 넘길 후보 수 (조항이 적어서 넉넉하게)
MIN_REL = 0.30      # 재정렬 관련도가 이보다 낮으면 '관련 규정 없음'으로 기권 (평가셋으로 조정)
RRF_K = 60          # 순위 합치기 상수 (관례값)


def ngrams(text: str) -> list[str]:
    """띄어쓰기를 지우고 글자 2개·3개 묶음으로 (형태소 분석기 없이 한국어 키워드 매칭)"""
    s = re.sub(r"\s+", "", text.lower())
    return [s[i:i + n] for n in (2, 3) for i in range(len(s) - n + 1)]


class BM25:
    """작은 문서 묶음용 BM25 (조항 수십~수백 개라 외부 라이브러리 없이)"""

    def __init__(self, docs: list[str], k1: float = 1.5, b: float = 0.75):
        self.k1, self.b = k1, b
        self.tfs = [Counter(ngrams(d)) for d in docs]
        self.lens = np.array([sum(tf.values()) for tf in self.tfs], dtype=float)
        self.avg = float(self.lens.mean()) if len(docs) else 0.0
        df = Counter(g for tf in self.tfs for g in tf)
        n = len(docs)
        self.idf = {g: math.log(1 + (n - c + 0.5) / (c + 0.5)) for g, c in df.items()}

    def scores(self, query: str) -> np.ndarray:
        q = Counter(ngrams(query))
        out = np.zeros(len(self.tfs))
        for i, tf in enumerate(self.tfs):
            norm = self.k1 * (1 - self.b + self.b * self.lens[i] / self.avg)
            out[i] = sum(self.idf.get(g, 0) * tf[g] * (self.k1 + 1) / (tf[g] + norm) for g in q if g in tf)
        return out


@dataclass
class Hit:
    clause: Clause
    rel: float        # 재정렬 관련도 0~1 (정렬 기준)
    sim: float        # 임베딩 유사도 (참고용)
    bm25: float       # 키워드 점수 (참고용)


@dataclass
class Retrieval:
    hits: list[Hit]
    top_rel: float                      # 최고 관련도
    abstain: bool                       # True면 근거 부족 → 판정하지 말 것
    index_version: str
    trace: dict = field(default_factory=dict)   # 노드 출력에 남길 추적 정보


_bm25_cache: dict[int, BM25] = {}


def _bm25(index: RegulationIndex) -> BM25:
    key = id(index)
    if key not in _bm25_cache:
        _bm25_cache[key] = BM25([c.search_text() for c in index.clauses])
    return _bm25_cache[key]


def candidates(query: str, index: RegulationIndex, allowed: np.ndarray, n: int) -> tuple[list[int], np.ndarray, np.ndarray]:
    """1차: 키워드·의미 순위를 RRF로 합쳐 후보 n개"""
    sims = index.vectors @ embed([query])[0]
    kw = _bm25(index).scores(query)
    sims[~allowed], kw[~allowed] = -np.inf, -np.inf
    rrf = np.zeros(len(index.clauses))
    for scores in (sims, kw):
        for rank, i in enumerate(np.argsort(-scores)):
            if allowed[i]:
                rrf[i] += 1 / (RRF_K + rank + 1)     # r등이면 1/(60+r) 점 → 점수 단위가 달라도 공정하게 합쳐짐
    return [i for i in np.argsort(-rrf) if allowed[i]][:n], sims, kw


def retrieve(query: str, doc_codes: list[str] | None = None, k: int = 5,
             index: RegulationIndex | None = None, min_rel: float = MIN_REL) -> Retrieval:
    """같은 질문·같은 색인이면 결과가 항상 같다(질문은 템플릿) → 운영 색인은 캐시에서 꺼낸다.
    시간 대부분이 CPU 재정렬이라, 고정 질문(주말·야간, 주류, 정산 기한 등)을 영수증마다 다시 채점하지 않게 한다.
    색인 버전이 키에 들어가므로 규정을 다시 색인하면 자연히 새로 계산한다. 반환값은 읽기 전용으로 쓸 것."""
    if index is not None:                                   # 평가용 별도 색인은 캐시하지 않음
        return _retrieve(query, doc_codes, k, index, min_rel)
    before = _retrieve_cached.cache_info().hits
    r = _retrieve_cached(query, tuple(doc_codes) if doc_codes else None, k, min_rel, get_index().version)
    RETRIEVAL_CACHE.labels("hit" if _retrieve_cached.cache_info().hits > before else "miss").inc()
    return r


@lru_cache(maxsize=2048)
def _retrieve_cached(query: str, doc_codes: tuple[str, ...] | None, k: int, min_rel: float, version: str) -> Retrieval:
    return _retrieve(query, list(doc_codes) if doc_codes else None, k, get_index(), min_rel)


def _retrieve(query: str, doc_codes: list[str] | None, k: int, index: RegulationIndex, min_rel: float) -> Retrieval:
    allowed = np.array([doc_codes is None or c.doc_code in doc_codes for c in index.clauses])
    if not allowed.any():
        return Retrieval([], 0.0, True, index.version, {"reason": "해당 규정 없음"})

    cand, sims, kw = candidates(query, index, allowed, CANDIDATES)
    rel = rerank(query, [index.clauses[i].search_text() for i in cand])       # 2차: 정확한 관련도
    order = np.argsort(-rel)[:k]
    hits = [Hit(index.clauses[cand[j]], float(rel[j]), float(sims[cand[j]]), float(kw[cand[j]])) for j in order]

    top_rel = float(rel.max()) if len(rel) else 0.0
    abstain = top_rel < min_rel
    trace = {
        "index_version": index.version,
        "top_rel": round(top_rel, 4),
        "abstain": abstain,
        "hits": [{"id": h.clause.id, "rel": round(h.rel, 4), "sim": round(h.sim, 4)} for h in hits],
    }
    return Retrieval(hits, top_rel, abstain, index.version, trace)