"""교차 검증의 '싼 검증' (LLM 호출 없이): 근거 확인 · 영수증 산수 · 정규식 두 번째 의견.

여기서 모두 통과하면 GMS 교차 추출을 건너뛸 수 있다 (llm_verify).
하나라도 어긋나면 사유를 돌려주고, 그 장만 GMS → 그래도 어긋나면 사람 확인.
"""
import re

from ai.receipts.parse import MONEY, ReceiptDetails, money


def _compact(s: str) -> str:
    return re.sub(r"\s+", "", s or "")


def store_grounded(store: str | None, text: str) -> bool:
    """상호가 OCR 원문에 있는지 (띄어쓰기 무시, 지점명까지 전부 또는 앞부분 4글자 이상)"""
    s, t = _compact(store), _compact(text)
    if not s:
        return False
    return s in t or (len(s) >= 4 and s[:4] in t)


def total_grounded(total: int | None, text: str) -> bool:
    """금액이 OCR 원문에 숫자로 있는지 ('46,700' / '46700' / '46.700')"""
    if not total:
        return False
    # 금액 덩어리 단위로 비교 (글자를 다 이어 붙여 찾으면 '19700'·'1,970 0' 같은 엉뚱한 곳에서도 맞아 버림)
    return any(money(m.group(0)) == total for m in MONEY.finditer(text or ""))


def item_sum(items: list[dict] | None) -> int | None:
    amounts = [i.get("amount") for i in (items or []) if isinstance(i.get("amount"), int)]
    return sum(amounts) if amounts and len(amounts) == len(items or []) else None


def cheap_checks(fields: dict, items: list[dict] | None, details: ReceiptDetails, text: str) -> list[str]:
    """LLM 없이 하는 검증. 어긋난 이유 목록 (비어 있으면 통과)"""
    reasons = []
    store, total = fields.get("store"), fields.get("total")

    if not store_grounded(store, text):
        reasons.append(f"원문에 없는 상호: {store}")
    if not total_grounded(total, text):
        reasons.append(f"원문에 없는 금액: {total}")

    # 정규식 두 번째 의견: 합계 줄 금액 중에 LLM 금액이 있어야 함
    cands = details.total_candidates
    if cands and isinstance(total, int) and total not in cands:
        reasons.append(f"합계 줄 금액({', '.join(f'{c:,}' for c in cands)})과 다른 금액: {total:,}")
    elif len(cands) > 1 and details.deduction_after_total and isinstance(total, int) and total != cands[-1]:
        # 포인트·쿠폰 차감 '전' 합계를 고른 경우 (흔한 오답): 실제 결제는 마지막 합계 줄
        reasons.append(f"차감 전 금액일 수 있음: {total:,} (차감 뒤 결제 금액 {cands[-1]:,})")

    # 영수증 산수: 공급가액 + 부가세 = 합계(또는 할인·포인트 전 총액) ±10원(반올림)
    supply, vat = details.value("supply"), details.value("vat")
    if isinstance(supply, int) and isinstance(vat, int) and isinstance(total, int):
        s = supply + vat
        if not any(abs(s - c) <= 10 for c in [total, *cands]):
            reasons.append(f"공급가액+부가세 {s:,}원이 합계와 맞지 않음")
    return reasons
