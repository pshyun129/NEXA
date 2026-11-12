"""영수증 항목 추출 DSPy 모듈."""
import json
from pathlib import Path
from typing import Literal

import dspy
from pydantic import BaseModel, Field

from ai.dspy_lm import get_gms_lm, run_with_fallback
from ai.receipts.headcount import ITEM_KINDS

# 승격된 챔피언 프로그램 위치 (mlops/promote.py가 여기에 내보냄, Git에 포함 → 배포)
PROGRAM_DIR = Path(__file__).resolve().parent.parent / "programs"


ItemKind = Literal[tuple(ITEM_KINDS)]     # 1인 음료 / 1인 식사 / 공용 요리 / 디저트·사이드 / 주류 / 상품·소모품 / 기타


class ReceiptItem(BaseModel):
    """영수증 품목 한 줄. 인원 추정(1인 품목만 셈)과 규정 검토(주류·개인 소비재)에 쓴다."""
    name: str = Field(description="품목 이름 (OCR에 적힌 그대로)")
    quantity: int = Field(default=1, description="수량")
    amount: int | None = Field(default=None, description="그 줄의 금액(원). 안 보이면 null")
    kind: ItemKind = Field(description=(
        "품목 종류. 1인 음료: 커피·차·에이드처럼 한 사람이 한 잔 / 1인 식사: 국밥·정식·덮밥처럼 한 사람이 한 그릇 / "
        "공용 요리: 전골·찜·탕수육·피자·치킨·모둠·고기 N인분처럼 나눠 먹는 것 / 디저트·사이드: 케이크·쿠키·공기밥·추가 메뉴 / "
        "주류: 소주·맥주·와인 등 / 상품·소모품: 편의점·마트 물품, 문구 / 기타"))


class Receipt(BaseModel):
    """영수증에서 추출할 항목."""
    store: str = Field(description="상호명 (지점명 포함)")
    total: int = Field(description="실제로 결제된 최종 금액 (할인·포인트 차감 후), 원 단위 숫자만")
    date: str = Field(description="거래 날짜, YYYY-MM-DD 형식")
    items: list[ReceiptItem] = Field(default_factory=list, description=(
        "구매 품목 목록. 할인·포인트·부가세·과세물품가액·합계·거스름돈 줄은 품목이 아님"))


# 기존 비교·평가·사람 확인 화면이 쓰는 핵심 항목 (품목은 따로 다룸)
CORE_FIELDS = ("store", "total", "date")


class ExtractReceipt(dspy.Signature):
    """영수증 OCR 텍스트에서 상호명, 합계 금액, 거래 날짜, 구매 품목(이름·수량·금액·종류)을 추출한다.
    OCR 텍스트에 없는 값은 지어내지 않는다."""

    text: str = dspy.InputField(desc="영수증 OCR 텍스트")
    receipt: Receipt = dspy.OutputField()


class ReceiptExtractor(dspy.Module):
    """영수증 추출 모듈. 최적화(compile)와 저장·불러오기의 단위."""

    def __init__(self):
        super().__init__()
        self.extract = dspy.Predict(ExtractReceipt)  # 바로 답하기 방식

    def forward(self, text: str):
        return self.extract(text=text)


def _load_extractor() -> tuple[ReceiptExtractor, str]:
    """챔피언 파일이 있으면 지시문·예시를 덮어씌워 불러오고, 없으면 기본(base)으로.
    돌려주는 버전 이름은 노드 출력·run_feedback에 기록됨 → 어떤 버전이 추출했는지 추적"""
    extractor = ReceiptExtractor()
    path = PROGRAM_DIR / "receipt.json"
    if not path.exists():
        return extractor, "receipt@base"
    extractor.load(str(path))
    meta_path = PROGRAM_DIR / "receipt.meta.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.exists() else {}
    return extractor, meta.get("label", "receipt@champion")


class _Active:
    """지금 서빙에 쓰는 추출 프로그램 + 모델(LoRA 어댑터).
    시작할 때는 Git에 포함된 파일(ai/programs), 이후에는 ai/champions.py가 MLflow champion으로 바꿔 끼움 (재시작 없이)
    교체는 객체 하나를 통째로 바꾸는 방식 → 추출 도중 바뀌어도 한 장 안에서는 같은 버전으로 끝남"""

    def __init__(self, extractor: ReceiptExtractor, label: str, adapter: str | None = None):
        self.extractor, self.label, self.adapter = extractor, label, adapter

    @property
    def version(self) -> str:
        # 어댑터를 쓰면 버전에 붙여 기록: receipt@v3+receipt-qlora-20261112 → 롤백 판단·피드백을 조합별로 셈
        return f"{self.label}+{self.adapter}" if self.adapter else self.label


BUNDLED = _Active(*_load_extractor())     # Git 파일 기준 (MLflow champion이 없거나 못 받을 때 돌아갈 곳)
_active = BUNDLED


def receipt_version() -> str:
    """지금 추출에 쓰는 버전 이름 (노드 출력 model_version, run_feedback에 기록)"""
    return _active.version


def current_extractor() -> ReceiptExtractor:
    """지금 서빙 중인 프로그램 (학습 데이터를 서빙과 같은 프롬프트로 만들 때 사용)"""
    return _active.extractor


def swap_program(extractor: ReceiptExtractor | None, label: str | None):
    """프로그램 교체. None이면 Git 파일 기준으로 되돌림 (어댑터 설정은 유지)"""
    global _active
    if extractor is None:
        _active = _Active(BUNDLED.extractor, BUNDLED.label, _active.adapter)
    else:
        _active = _Active(extractor, label, _active.adapter)


def set_adapter(name: str | None):
    """추출에 쓸 vLLM 모델 이름(LoRA 어댑터). None이면 기본 모델"""
    global _active
    _active = _Active(_active.extractor, _active.label, name)


def extract_receipt(text: str) -> Receipt:
    """영수증 텍스트 → Receipt. 로컬 LLM 실패 시 GMS로 대체 (어댑터가 실패하면 기본 모델 먼저)."""
    a = _active
    result = run_with_fallback(a.extractor, _model=a.adapter, text=text)
    return result.receipt


def extract_receipt_with_gms(text: str) -> Receipt:
    """검증용: GMS로 독립 추출 (로컬 결과와 비교하기 위함, 대체 실행 없음)."""
    with dspy.context(lm=get_gms_lm()):
        return _active.extractor(text=text).receipt
