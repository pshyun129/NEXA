"""문서 분류 DSPy 모듈: OCR 텍스트 → 문서 종류 (영수증 / 세금계산서 / 출장 정산서 / 휴가 신청서 / 기타).

할루시네이션 방어 (규정 RAG와 같은 원칙)
  - '기타'가 있음: 애매하면 억지로 아는 종류에 넣지 않음 → 뒤에서 사람이 확인
  - 근거(evidence)는 원문에서 글자 그대로 옮긴 짧은 문구 → 코드가 원문에 있는지 확인, 없으면 확신도를 '낮음'으로
  - 확신도가 기준보다 낮으면 노드가 '기타' 분기로 보냄
"""
import json
import re
from pathlib import Path
from typing import Literal

import dspy
from pydantic import BaseModel, Field

from ai.dspy_lm import run_with_fallback

PROGRAM_DIR = Path(__file__).resolve().parent.parent / "programs"
DOC_TYPES = ["영수증", "세금계산서", "출장 정산서", "휴가 신청서", "기타"]
CONFIDENCE = ["높음", "보통", "낮음"]


class DocClass(BaseModel):
    doc_type: Literal["영수증", "세금계산서", "출장 정산서", "휴가 신청서", "기타"] = Field(
        description="문서 종류. 다섯 종류 중 어느 것인지 분명하지 않으면 '기타'")
    confidence: Literal["높음", "보통", "낮음"] = Field(description="판단 확신도")
    evidence: str = Field(description="판단 근거가 된 문구 하나 (문서 원문에서 글자 그대로, 30자 이내)")


class ClassifyDocument(dspy.Signature):
    """사내 업무 문서의 OCR 텍스트를 보고 문서 종류를 고른다.
    영수증: 가게에서 결제하고 받은 영수증·카드 매출전표 (상호, 결제 금액, 거래 일시)
    세금계산서: 공급자·공급받는자 사업자등록번호, 공급가액, 세액이 있는 (전자)세금계산서
    출장 정산서: 출장 기간·출장지·일비·숙박비 등 여비를 정산하는 사내 서식
    휴가 신청서: 휴가 종류·기간·사유를 적어 신청하는 사내 서식
    기타: 위 넷이 아니거나, 분명하지 않은 문서 (메뉴판·견적서·회의록·공지 등)
    근거는 문서에서 글자 그대로 옮겨 적는다."""

    text: str = dspy.InputField(desc="문서 OCR 텍스트")
    result: DocClass = dspy.OutputField()


class DocClassifier(dspy.Module):
    """최적화(compile)·저장·불러오기 단위 (영수증 추출 모듈과 같은 방식으로 MLOps에 올림)"""

    def __init__(self):
        super().__init__()
        self.classify = dspy.Predict(ClassifyDocument)

    def forward(self, text: str):
        return self.classify(text=text)


def _load_classifier() -> tuple[DocClassifier, str]:
    """챔피언 파일(ai/programs/classify.json)이 있으면 불러오고, 없으면 기본(base)"""
    clf = DocClassifier()
    path = PROGRAM_DIR / "classify.json"
    if not path.exists():
        return clf, "classify@base"
    clf.load(str(path))
    meta_path = PROGRAM_DIR / "classify.meta.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.exists() else {}
    return clf, meta.get("label", "classify@champion")


_classifier, CLASSIFY_VERSION = _load_classifier()


def _squash(text: str) -> str:
    return re.sub(r"\s+", "", text or "")


def verify_evidence(result: DocClass, text: str) -> DocClass:
    """근거 문구가 원문에 없으면(지어낸 근거) 확신도를 '낮음'으로 → 노드에서 '기타'(사람 확인)로 감"""
    ev = _squash(result.evidence)
    if result.doc_type != "기타" and (len(ev) < 2 or ev not in _squash(text)):
        return result.model_copy(update={"confidence": "낮음"})
    return result


def classify_document(text: str, program: DocClassifier | None = None, runner=None) -> DocClass:
    """OCR 텍스트 → 검증된 분류 결과. program·runner는 평가에서 후보 프로그램·로컬 전용 실행을 끼우기 위함"""
    run = runner or run_with_fallback
    result = run(program or _classifier, text=text).result
    return verify_evidence(result, text)