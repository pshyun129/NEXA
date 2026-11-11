"""코드로 판정하는 조항: 사실만 있으면 답이 정해지는 조항은 LLM을 부르지 않는다 (빠르고, 실행마다 같은 답).

  EXP-6  사용 제한 업종·품목 : 업종이 제한 업종이면 위반, 음식점·카페 등에서 먹고 마신 것만이면 준수, 나머지는 LLM
  EXP-7  야간·주말·공휴일    : 해당하면 사용 사유가 있어야 함
  EXP-8  식대               : 야근 = 오후 7시 이후 근무, 주말근무 = 주말·공휴일, 1인당 한도, 참석자 이름·인원
  EXP-9  주류               : 주류가 있으면 회식·접대만
  EXP-10 회의비             : 1인당 한도
  EXP-14 증빙               : 필수 기재, 3만 원 넘는데 적격증빙이 아니면 확인 필요
  EXP-15 정산 기한          : 사용일부터 30일

각 함수는 판정(dict)을 돌려주거나, 코드로 정할 수 없으면 None (→ LLM 판정).
판정의 인용 문장은 조항 원문의 줄을 그대로 쓴다 (인용 검증을 항상 통과).
조항 ID는 규정 문서(DOCS/regulations/expense.md)의 조번호다. 조번호가 바뀌면 여기도 바꿔야 한다.
"""
import math
import re

from ai.rag.parse import Clause
from ai.receipts.store_kind import FOOD_STORES, RESTRICTED

FOOD_KINDS = {"1인 음료", "1인 식사", "공용 요리", "디저트·사이드", "주류"}
ALCOHOL = re.compile(r"소주|맥주|생맥|와인|막걸리|하이볼|사케|위스키|칵테일|보드카|데킬라|진토닉|소맥|양주|샴페인|동동주|복분자|백세주"
                     r"|참이슬|처음처럼|하이네켄|버드와이저|호가든|칭따오|아사히|(?<![가-힣])(카스|테라|켈리|청하|클라우드)(?![가-힣])")
ALCOHOL_OK = {"회식", "접대"}                 # 제9조①
CASH_LIKE = re.compile(r"상품권|기프트\s*카드|선불\s*카드|기프티콘|문화\s*상품권|충전\s*카드")   # 제6조①2
LATE_KEYS = ("오후 7시 이후까지 근무 여부(근태 기록)", "오후 7시 이후까지 근무 여부(결제 시각 기준)")


def _line(c: Clause, needle: str) -> str:
    """조항 원문에서 needle이 들어 있는 줄 (인용 문장)"""
    return next((ln.strip() for ln in c.body.splitlines() if needle in ln), c.body.splitlines()[0].strip())


def _finding(c: Clause, verdict: str, quote: str, reason: str, missing=None, numeric=None) -> dict:
    return {"clause_id": c.id, "label": c.label, "verdict": verdict, "llm_verdict": None, "quote": quote,
            "reason": reason, "missing_facts": list(missing or []), "numeric": list(numeric or []), "checks": [],
            "by": "code"}


def has_alcohol(items: dict) -> bool | None:
    """주류 여부: 품목 종류(추출 모델) 또는 품목 이름. 품목을 모르면 None"""
    names, kinds = items.get("names") or [], items.get("kinds") or []
    if not names:
        return None
    return "주류" in kinds or any(ALCOHOL.search(n) for n in names)


def exp6(c, known, items, checks):
    kind = known.get("업종")
    if kind in RESTRICTED:
        return _finding(c, "위반", _line(c, RESTRICTED[kind]),
                        f"{kind}({known.get('상호')})은 법인카드 사용 제한 업종 (재무팀장 사전 승인이 있으면 예외, 제6조②)")
    cash = [n for n in items.get("names") or [] if CASH_LIKE.search(n)]
    if cash:
        return _finding(c, "위반", _line(c, "2."), f"현금성 자산 구매: {', '.join(cash)} (재무팀장 사전 승인이 있으면 예외, 제6조②)")
    kinds = items.get("kinds") or []
    if kind in FOOD_STORES and all(k in FOOD_KINDS for k in kinds if k):
        what = "먹고 마신 품목만 있음" if kinds else "식음료 업종"
        return _finding(c, "준수", _line(c, "다음 각 호의"), f"{kind}에서 결제, {what} (제한 업종·개인 소비재 아님)")
    if kind == "교통" and not items.get("names"):
        return _finding(c, "준수", _line(c, "다음 각 호의"), "교통 이용 결제 (제한 업종 아님)")
    return None                                         # 편의점·생활용품점·업종 모름·상품 품목 → LLM이 품목을 보고 판단


def exp7(c, known, items, checks):
    flags = []
    if known.get("야간 결제 여부(22시~06시)"):
        flags.append(f"야간({known.get('결제 시각')})")
    if known.get("주말 여부"):
        flags.append(f"{known.get('요일')}요일")
    if known.get("공휴일 여부"):
        flags.append(f"공휴일({known['공휴일 여부']})")
    if known.get("회사 휴일 여부(근태 기록)"):
        flags.append("회사 휴일")
    quote = _line(c, "사용 사유")
    if not flags:
        if "결제 시각" not in known:
            return _finding(c, "확인 필요", quote, "평일이지만 결제 시각을 몰라 야간 사용인지 알 수 없음", ["결제 시각"])
        return _finding(c, "해당 없음", quote, "평일 주간 사용")
    if known.get("사용 사유"):
        return _finding(c, "준수", quote, f"{', '.join(flags)} 사용, 사용 사유 기재 (업무 관련성은 결재자가 확인)")
    return _finding(c, "확인 필요", quote, f"{', '.join(flags)} 사용 → 사용 사유를 적어야 함", ["사용 사유"])


def guessed_over(known: dict, checks: list[dict]) -> str | None:
    """품목으로 추정한 최소 인원으로만 한도를 넘었을 때 안내 문구 (아니면 None) → 위반 대신 확인 필요"""
    xs = [x for x in checks if x["kind"] == "최대" and x["over"] and x.get("estimated")]
    if not xs:
        return None
    x, total = xs[0], known.get("결제 금액")
    need = math.ceil(total / x["limit"]) if isinstance(total, int) and x["limit"] else None
    where = "품목 기준 최소" if str(known.get("참석 인원 근거", "")).startswith("품목") else "영수증 메모에서 읽은"
    return (f"{where} {known.get('참석 인원')}명으로 나누면 1인당 {x['value']:,}원 > 한도 {x['limit']:,}원"
            + (f" → {need}명 이상이면 한도 안" if need else "") + " (참석 인원을 적어 주세요)")


def _limits(c, checks, num_missing, verdict, reasons, missing, known=None):
    """수치 기준(1인당 한도 등) 결과를 판정에 반영"""
    over = [x for x in checks if x["kind"] == "최대" and x["over"] and not x.get("estimated")]
    if over:
        reasons.append(f"한도 초과: {', '.join(x['text'] for x in over)}")
        return "위반"
    guess = guessed_over(known or {}, checks)
    if guess:
        reasons.append(guess)
        if "참석 인원" not in missing:
            missing.append("참석 인원")
        return "확인 필요"
    for x in checks:
        if x["kind"] == "최대":
            reasons.append(x["text"])
    missing += [m for m in num_missing if m not in missing]
    return verdict


def _worse(a: str, b: str) -> str:
    order = ["위반", "확인 필요", "준수", "해당 없음"]
    return min(a, b, key=order.index)


def exp8(c, known, items, checks, num_missing):
    purpose = known.get("사용 목적")
    verdict, reasons, missing = "준수", [], []
    quote = _line(c, "1인당 금액은")
    if purpose == "야근":
        quote = _line(c, "야근 식대")
        late = next((known[k] for k in LATE_KEYS if k in known), None)
        if late is False:
            src = f"퇴근 {known['퇴근 시각']}" if "퇴근 시각" in known else f"결제 {known.get('결제 시각')}"
            verdict, reasons = "위반", [f"오후 7시 이후까지 근무하지 않음 ({src})"]
        elif late is None:
            verdict, missing = "확인 필요", ["결제 시각"]
            reasons.append("오후 7시 이후까지 근무했는지 알 수 없음")
    elif purpose == "주말근무":
        quote = _line(c, "주말이나 공휴일")
        if not (known.get("주말 여부") or known.get("공휴일 여부") or known.get("회사 휴일 여부(근태 기록)")):
            verdict = "확인 필요"
            reasons.append(f"평일({known.get('요일', '?')}요일) 거래인데 사용 목적이 주말근무")
    elif purpose == "회식":
        quote = _line(c, "회식비")
    verdict = _worse(verdict, _limits(c, checks, num_missing, verdict, reasons, missing, known))
    # 제8조④ 참석자 이름과 인원
    need = [m for m in ("참석자 이름", "참석 인원") if m not in known and m not in missing]
    if need:
        verdict = _worse(verdict, "확인 필요")
        missing += need
        reasons.append(f"식대 정산에는 {'·'.join(need)}이 필요 (제8조④)")
    if verdict != "준수" and any("한도" in r for r in reasons):
        quote = next((_line(c, f"{x['limit']:,}") for x in checks if x["over"]), quote)
    return _finding(c, verdict, quote, "; ".join(reasons) or "기준 충족", missing, [x["text"] for x in checks])


def exp9(c, known, items, checks):
    alcohol = has_alcohol(items)
    purpose = known.get("사용 목적")
    if alcohol is None:
        if purpose in ALCOHOL_OK:
            return _finding(c, "준수", _line(c, "부서 회식과 고객 접대"), f"{purpose}는 주류 포함 결제 인정")
        return _finding(c, "확인 필요", _line(c, "주류가 포함된"), "품목을 몰라 주류 포함 여부를 알 수 없음", ["구매 품목"])
    if not alcohol:
        return _finding(c, "해당 없음", _line(c, "주류가 포함된"), "주류 없음")
    if purpose in ALCOHOL_OK:
        return _finding(c, "준수", _line(c, "부서 회식과 고객 접대"), f"주류 포함, {purpose}라 인정")
    quote = _line(c, "야근 식대와") if purpose in ("야근", "주말근무") else _line(c, "주류가 포함된")
    return _finding(c, "위반", quote, f"주류 포함 결제는 회식·접대만 인정 (사용 목적: {purpose})")


def exp10(c, known, items, checks, num_missing):
    verdict, reasons, missing = "준수", [], []
    verdict = _limits(c, checks, num_missing, verdict, reasons, missing, known)
    if missing and verdict == "준수":
        verdict = "확인 필요"
        reasons.append("1인당 금액을 계산할 참석 인원이 없음")
    return _finding(c, verdict, _line(c, "다과비"), "; ".join(reasons) or "기준 충족", missing, [x["text"] for x in checks])


def exp11(c, known, items, checks, num_missing):
    """접대비: 숫자로 정해지는 경우만 코드 (1인당 한도 초과 → 위반, 30만 원 넘음 → 사전 승인 확인). 나머지(상대방 정보 등)는 LLM"""
    over = [x for x in checks if x["kind"] == "최대" and x["over"] and not x.get("estimated")]
    guess = guessed_over(known, checks)
    if guess and not over:
        return _finding(c, "확인 필요", _line(c, "50,000원까지"), guess, ["참석 인원"], [x["text"] for x in checks])
    if over:
        return _finding(c, "위반", _line(c, "50,000원까지"), f"한도 초과: {', '.join(x['text'] for x in over)}",
                        numeric=[x["text"] for x in checks])
    big = [x for x in checks if x["kind"] == "기준" and x["over"]]
    if big:
        return _finding(c, "확인 필요", _line(c, "300,000원을 넘는"),
                        f"{big[0]['text']} → 본부장 사전 승인이 있어야 함 (승인 여부는 결재자가 확인)",
                        numeric=[x["text"] for x in checks])
    return None


def exp14(c, known, items, checks):
    if "확인 안 됨" in str(known.get("영수증 필수 기재(상호·거래일자·결제 금액)", "")):
        return _finding(c, "위반", _line(c, "상호, 거래일자, 결제 금액"),
                        f"영수증 필수 기재 {known['영수증 필수 기재(상호·거래일자·결제 금액)']}")
    total, kind = known.get("결제 금액"), str(known.get("증빙 종류") or "")
    if "적격증빙" in kind:
        return _finding(c, "준수", _line(c, "적격증빙을 갖추어야"), f"{kind}")
    if isinstance(total, int) and total <= 30000:
        return _finding(c, "준수", _line(c, "30,000원 이하"), f"{total:,}원 ≤ 30,000원이라 간이영수증도 인정",
                        numeric=[f"결제 금액 {total:,} ≤ 기준 30,000"])
    if not isinstance(total, int):
        return _finding(c, "확인 필요", _line(c, "적격증빙을 갖추어야"), "결제 금액을 몰라 증빙 요건을 판단할 수 없음", ["결제 금액"])
    why = "현금 결제" if "간이" in kind else "결제 수단을 모름"
    return _finding(c, "확인 필요", _line(c, "30,000원을 넘는데"),
                    f"{total:,}원 > 30,000원인데 {why} → 적격증빙(카드 전표·지출증빙 현금영수증)이 있는지 확인",
                    [] if "간이" in kind else ["결제 수단"], [f"결제 금액 {total:,} > 기준 30,000"])


def exp15(c, known, items, checks, num_missing):
    days = known.get("사용일부터 경과일")
    if not isinstance(days, int):
        return _finding(c, "확인 필요", _line(c, "30일 이내"), "거래일자를 몰라 경과일을 계산할 수 없음", ["거래일자"])
    if days > 30:
        return _finding(c, "위반", _line(c, "30일 이내"), f"사용일부터 {days}일 경과 (팀장 사유서가 있으면 정산 가능, 제15조③)",
                        numeric=[f"사용일부터 경과일 {days} > 최대 30"])
    return _finding(c, "준수", _line(c, "30일 이내"), f"사용일부터 {days}일", numeric=[f"사용일부터 경과일 {days} ≤ 최대 30"])


# 조항 ID → (판정 함수, 수치 기준을 받는지)
CODE_RULES = {
    "EXP-6": (exp6, False), "EXP-7": (exp7, False), "EXP-8": (exp8, True), "EXP-9": (exp9, False),
    "EXP-10": (exp10, True), "EXP-11": (exp11, True), "EXP-14": (exp14, False), "EXP-15": (exp15, True),
}

# LLM이 판정하는 조항에 넘길 사실 (조항과 상관없는 사실을 빼야 판단이 흔들리지 않고, 같은 사실이면 판정을 재사용할 수 있음)
# 적지 않은 조항은 모든 사실을 넘긴다
CLAUSE_FACTS = {
    "EXP-6": ["상호", "업종", "구매 품목", "사용 목적"],
    "EXP-11": ["사용 목적", "결제 금액", "참석 인원", "참석자 이름", "1인당 금액", "사용 사유", "코드 계산 결과"],
    "EXP-12": ["사용 목적", "상호", "결제 시각", "야간 결제 여부(22시~06시)", "퇴근 시각", "사용 사유"],
    "EXP-13": ["사용 목적", "상호", "결제 금액", "구매 품목", "코드 계산 결과"],
}


def code_judge(c: Clause, known: dict, items: dict, checks: list[dict], num_missing: list[str]) -> dict | None:
    rule = CODE_RULES.get(c.id)
    if rule is None:
        return None
    fn, numeric = rule
    return fn(c, known, items, checks, num_missing) if numeric else fn(c, known, items, checks)
