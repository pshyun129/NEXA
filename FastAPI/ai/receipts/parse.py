"""영수증 OCR 텍스트에서 정해진 모양의 값을 정규식으로 뽑는다 (LLM 없이, 빠르고 항상 같은 결과).

LLM 추출(상호·금액·날짜·품목)과 별개로 쓰인다.
  - 결제 시각·결제 수단·승인번호·사업자번호·공급가액·부가세·찍힌 인원: 모양이 정해져 있어 코드가 더 정확하다
  - 합계 후보: LLM이 고른 금액이 맞는지 보는 두 번째 의견(교차 검증에서 GMS 대신)
값을 못 찾으면 None (= 모름). 추측해서 채우지 않는다. 찾은 값은 근거 줄(evidence)도 같이 돌려준다.
"""
import re
from dataclasses import asdict, dataclass, field

# 금액: OCR이 원화 기호를 #·W·₩·\ 로 읽거나 쉼표를 마침표로 읽는 경우까지
MONEY = re.compile(r"(?<![\d:])[#W₩\\]?\s*(-?\d{1,3}(?:[,.]\d{3})+|-?\d{3,9})(?![\d:])")
TIME = re.compile(r"(?<!\d)([01]?\d|2[0-3])\s*:\s*([0-5]\d)(?!\d)")
APPROVAL_NO = re.compile(r"승\s*인\s*번\s*호\s*[:：]?\s*(\d{6,12})")
BIZ_NO = re.compile(r"(?<!\d)(\d{3})\s*-\s*(\d{2})\s*-\s*(\d{5})(?!\d)")
HEADCOUNT = re.compile(r"(인\s*원\s*수?|고\s*객\s*수|손\s*님\s*수|PAX|pax|Pax)\s*[:：]?\s*(\d{1,3})\s*(명|인)?")
SUPPLY_LABEL = re.compile(r"과세\s*물품\s*가액|공급\s*가액|과세\s*금액|공급\s*금액")
VAT_LABEL = re.compile(r"부\s*가\s*세|부가\s*가치\s*세|세\s*액")
TOTAL_LABEL = re.compile(r"합\s*계|총\s*액|총\s*금\s*액|받\s*을\s*금\s*액|결\s*제\s*금\s*액|실\s*결\s*제|승\s*인\s*금\s*액|청\s*구\s*금\s*액|판\s*매\s*금\s*액")
# 금액 줄인데 합계가 아닌 것 (거스름돈·받은 돈·포인트 잔액 등)
NOT_TOTAL = re.compile(r"거\s*스\s*름|받\s*은|분\s*은|잔\s*여|적\s*립|할\s*인|포\s*인\s*트|쿠\s*폰|부\s*가\s*세|과\s*세|면\s*세")
CORP_CARD = re.compile(r"법\s*인\s*카\s*드|법\s*인|기\s*업\s*카\s*드")
CARD = re.compile(r"신\s*용\s*카\s*드|체\s*크\s*카\s*드|카\s*드|승\s*인\s*번\s*호|VISA|MASTER")
# 결제 수단이 아닌 카드 줄 (포인트·멤버십 카드, 안내 문구)
NOT_PAYMENT = re.compile(r"포\s*인\s*트|멤\s*버\s*십|적\s*립|쿠\s*폰|캐\s*쉬\s*백|안\s*내|발\s*행\s*가\s*능")
# 간편결제: 카드 연결인지 계좌 연결인지 영수증만으로는 알 수 없음 → 증빙 구분은 '모름'
EASY_PAY = re.compile(r"카\s*카\s*오\s*페\s*이|네\s*이\s*버\s*페\s*이|페\s*이\s*코|PAYCO|삼\s*성\s*페\s*이|토\s*스\s*페\s*이|제\s*로\s*페\s*이|애\s*플\s*페\s*이|간\s*편\s*결\s*제")
CASH_RECEIPT = re.compile(r"현\s*금\s*영\s*수\s*증|지\s*출\s*증\s*빙")
CASH = re.compile(r"현\s*금")
# 합계 다음에 나오는 차감 줄 (포인트 사용·쿠폰·할인) → 실제 결제 금액은 그 아래의 마지막 합계 금액
DEDUCTION = re.compile(r"포\s*인\s*트\s*(사\s*용|차\s*감|결\s*제)|쿠\s*폰\s*(사\s*용|할\s*인)|할\s*인|상\s*품\s*권\s*사\s*용")


def money(text: str) -> int | None:
    """'#14,700' · 'W1,500' · '46.700' → 정수. 못 읽으면 None"""
    m = MONEY.search(text or "")
    if not m:
        return None
    try:
        return int(re.sub(r"[,.\s]", "", m.group(1)))
    except ValueError:
        return None


@dataclass
class Found:
    value: object
    evidence: str          # 값을 찾은 OCR 줄 (사람이 보고 확인할 수 있게)


@dataclass
class ReceiptDetails:
    """정규식으로 찾은 값들. 못 찾은 것은 None"""
    pay_time: Found | None = None          # "15:02"
    pay_method: Found | None = None        # 법인카드 / 카드 / 현금영수증 / 현금
    approval_no: Found | None = None       # 카드 승인번호
    biz_no: Found | None = None            # 사업자등록번호 000-00-00000
    supply: Found | None = None            # 공급가액(과세물품가액)
    vat: Found | None = None               # 부가세
    printed_headcount: Found | None = None # 영수증에 찍힌 인원(고객수·PAX)
    total_candidates: list[int] = field(default_factory=list)   # 합계로 보이는 금액들(두 번째 의견, 나온 순서)
    deduction_after_total: bool = False    # 첫 합계 뒤에 포인트·쿠폰 차감이 있음 → 결제 금액은 마지막 후보

    def to_dict(self) -> dict:
        return asdict(self)

    def value(self, name: str):
        f = getattr(self, name)
        return f.value if isinstance(f, Found) else None


def _lines(text: str) -> list[str]:
    return [ln.strip() for ln in (text or "").splitlines() if ln.strip()]


def _date_keys(date_iso: str | None) -> list[re.Pattern]:
    """'2026-11-10' → 그 날짜가 OCR에 찍히는 여러 모양(2026.11.10 / 26-11-10 / 2026년 11월 10일 / 20261110)"""
    if not date_iso or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date_iso):
        return []
    y, m, d = date_iso.split("-")
    sep = r"\s*[-./년월일]?\s*"
    return [re.compile(rf"(?:{y}|{y[2:]}){sep}0?{int(m)}{sep}0?{int(d)}(?!\d{{2}}[-./])"),
            re.compile(rf"{y}{m}{d}")]


def _amount_after_label(lines: list[str], i: int, label: re.Pattern) -> int | None:
    """라벨 줄에서 라벨 뒤 금액, 없으면 다음 줄 금액 ('합계금액' 다음 줄에 '46,700'만 찍힌 영수증)"""
    after = label.split(lines[i], maxsplit=1)[-1]
    v = money(after)
    if v is None and i + 1 < len(lines) and not re.search(r"[가-힣]{2,}", lines[i + 1]):
        v = money(lines[i + 1])
    return v


def parse_details(text: str, date_iso: str | None = None) -> ReceiptDetails:
    lines = _lines(text)
    out = ReceiptDetails()

    # 결제 시각: 거래 날짜가 찍힌 줄의 시각 (재발행 '출력' 시각·쿠폰 유효기간 줄은 건너뜀)
    keys = _date_keys(date_iso)
    for ln in lines:
        if re.search(r"출\s*력|재\s*발\s*행|유\s*효|기\s*한|까\s*지", ln):
            continue
        dm = next((k.search(ln) for k in keys if k.search(ln)), None)
        if dm:
            t = TIME.search(ln[dm.end():]) or TIME.search(ln)
            if t:
                out.pay_time = Found(f"{int(t.group(1)):02d}:{t.group(2)}", ln)
                break

    for i, ln in enumerate(lines):
        if out.approval_no is None and (m := APPROVAL_NO.search(ln)):
            out.approval_no = Found(m.group(1), ln)
        if out.biz_no is None and (m := BIZ_NO.search(ln)) and ("사업자" in ln.replace(" ", "") or i < 6):
            out.biz_no = Found(f"{m.group(1)}-{m.group(2)}-{m.group(3)}", ln)
        if out.printed_headcount is None and (m := HEADCOUNT.search(ln)):
            n = int(m.group(2))
            if 1 <= n <= 200:
                out.printed_headcount = Found(n, ln)
        if out.supply is None and SUPPLY_LABEL.search(ln):
            v = _amount_after_label(lines, i, SUPPLY_LABEL)
            if v is not None:
                out.supply = Found(v, ln)
        if out.vat is None and VAT_LABEL.search(ln) and not SUPPLY_LABEL.search(ln):
            v = _amount_after_label(lines, i, VAT_LABEL)
            if v is not None:
                out.vat = Found(v, ln)
        if TOTAL_LABEL.search(ln) and not NOT_TOTAL.search(ln):
            v = _amount_after_label(lines, i, TOTAL_LABEL)
            if v is not None and v > 0 and v not in out.total_candidates:
                out.total_candidates.append(v)
        elif out.total_candidates and DEDUCTION.search(ln):
            out.deduction_after_total = True

    # 결제 수단: 법인카드 > 카드 > 현금영수증 > 현금 (근거 줄과 함께). 못 찾으면 None
    pay_lines = [x for x in lines if not NOT_PAYMENT.search(x)]
    for pattern, kind in ((CORP_CARD, "법인카드"), (CASH_RECEIPT, "현금영수증"), (CARD, "카드"),
                          (EASY_PAY, "간편결제"), (CASH, "현금")):
        ln = next((x for x in pay_lines if pattern.search(x)), None)
        if ln:
            # '현금'이 받은 금액·거스름돈 줄에만 있어도 현금 결제로 본다 (카드 흔적이 없을 때만 여기까지 옴)
            out.pay_method = Found(kind, ln)
            break
    return out


# 결제 수단 → 증빙 구분 (경비 규정 제3조③: 신용카드 매출전표·지출증빙용 현금영수증 = 적격증빙)
EVIDENCE_KIND = {"법인카드": "신용카드 매출전표", "카드": "신용카드 매출전표", "현금영수증": "현금영수증", "현금": "간이영수증"}
# 간편결제는 없음(모름) → 규정 검토가 30,000원 초과면 증빙을 물어봄
QUALIFIED_EVIDENCE = {"신용카드 매출전표", "현금영수증", "세금계산서"}
