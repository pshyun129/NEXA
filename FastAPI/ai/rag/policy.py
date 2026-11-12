"""규정 검토: 업무 데이터(영수증 등) → 관련 조항 검색 → 조항별 판정 → 코드로 검증 → 종합.

할루시네이션 방어 (README 설계의 ③~⑥)
  ③ 근거 안에서만: 조항 하나씩 판정, 출력 형식 고정 (판정·인용 문장·이유·모르는 사실)
  ④ 인용 검증: 인용 문장이 조항 원문에 글자 그대로 있는지 코드로 확인 → 아니면 '확인 필요'
  ⑤ 숫자는 코드: 숫자 기준은 규정 front matter '수치'에 적혀 있고 코드가 비교 (LLM은 결과만 받음)
  ⑥ 비대칭: '준수'는 인용 검증·숫자 비교까지 통과해야 인정, 한도 초과는 LLM 판단과 상관없이 위반
그리고 "모르는 사실 ≠ 위반": 판정에 필요한 사실이 없으면 추측하지 말고 '확인 필요 + 무엇이 없는지'.

실행 결과로 고친 것
  1차: 시스템이 아는 사실(정산 신청일·공휴일·필수 기재·증빙 종류)을 직접 채움, 사실 이름을 규정 용어와 통일,
       사용 목적별 조항만 판정(front matter '목적'), 추가로 필요한 정보(needed_facts)를 모아서 반환
  2차: LLM에게 한도를 찾게 했더니 안 적거나 / 다른 항을 인용하거나 / '넘으면 증빙 필요' 같은 기준을 한도로 착각
       → 숫자 기준을 front matter '수치'로 옮겨 코드가 비교, 인용 검증은 항 번호·따옴표 차이 허용
  3차: 영수증 수백 장 일괄 처리 (병목 줄이기)
       - 사실만으로 답이 정해지는 조항(제6·7·8·9·10·14·15조)은 코드가 판정 (ai/rag/rules.py) → LLM은 남은 조항만
       - 조항은 front matter(집행·목적)로 고르고, 검색 질문에서 상호·금액을 빼서 검색은 전부 캐시·미리 계산
       - LLM 조항에는 그 조항에 필요한 사실만 넘기고, (조항, 사실)이 같으면 판정을 재사용
       - 업종은 상호 키워드 규칙 → 모르면 LLM 분류('모름' 허용, 가게별 캐시) (ai/receipts/store_kind.py)
       - 품목·결제 시각·결제 수단·찍힌 인원은 추출 노드의 장별 결과에서 가져옴 (사용자 입력이 먼저)
"""
import json
import logging
import re
import threading
import time
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path
from typing import Literal

import dspy
import holidays
from pydantic import BaseModel, Field

from ai.dspy_lm import run_with_fallback
from ai.rag.index import get_index, warm_up as warm_up_models
from core.metrics import JUDGE_CACHE, POLICY_SECONDS
from ai.rag.parse import Clause
from ai.rag.rules import CLAUSE_FACTS, code_judge
from ai.rag.search import MIN_REL, retrieve
from ai.receipts.headcount import inflation_issue
from ai.receipts.store_kind import store_kind

# 문서 종류 → 검색할 규정 (엉뚱한 규정이 검색되는 것 원천 차단)
DOC_SCOPE = {
    "영수증": ["EXP"],
    "출장 정산": ["TRV", "EXP"],
    "휴가 신청": ["LEV"],
}
# 경비 사용 목적 (규정 front matter '목적'에 쓰는 이름과 같아야 함)
PURPOSES = ["야근", "주말근무", "회식", "회의", "접대", "교통", "소모품", "기타"]
CARD_PAYMENTS = {"법인카드", "개인카드", "카드"}  # 신용카드 매출전표 = 적격증빙 (경비 규정 제3조③)
JUDGE_CACHE_SIZE = 4096                         # (조항, 사실) → 판정 재사용 개수
WEEKDAYS = ["월", "화", "수", "목", "금", "토", "일"]
DERIVED_FROM = {"1인당 금액": "참석 인원"}         # 계산된 사실이 없을 때 사용자에게 물어볼 원래 사실
ESTIMATED = "품목 기준 최소 추정"                 # 참석 인원 근거가 품목 추정이면 이 말로 시작 (한도 초과는 위반 대신 확인 필요)
FROM_MEMO = "영수증 메모"                         # 손글씨를 OCR로 읽은 인원도 빠뜨렸을 수 있어 같은 취급
UNSURE_HEADCOUNT = (ESTIMATED, FROM_MEMO)
MIN_HIT_REL = MIN_REL                           # 판정 대상 조항도 같은 기준 (관련 없는 조항이 끼면 과잉 신중·거짓 위반이 생김)
KR_HOLIDAYS = holidays.KR()
Verdict = Literal["준수", "위반", "해당 없음", "확인 필요"]


# ---------------------------------------------------------------- 사실 계산 (코드)
def _parse_time(text) -> tuple[int, int] | None:
    """'19:40', '1940', '19시 40분' → (19, 40)"""
    m = re.search(r"(\d{1,2})\D{0,2}(\d{2})", str(text or ""))
    if not m or int(m.group(1)) > 23 or int(m.group(2)) > 59:
        return None
    return int(m.group(1)), int(m.group(2))


def _details_value(receipt: dict | None, name: str):
    """추출 노드가 정규식으로 찾은 값 (receipts[i].details.<name>.value)"""
    f = ((receipt or {}).get("details") or {}).get(name)
    return f.get("value") if isinstance(f, dict) else None


def item_names(receipt: dict | None, extra: dict) -> dict:
    """품목: 사용자가 준 목록이 있으면 그것, 없으면 추출 결과 → {names, kinds, labels(표시·LLM용)}"""
    given = extra.get("구매 품목")
    if given:
        return {"names": list(given), "kinds": [], "labels": list(given)}
    items = (receipt or {}).get("items") or []
    names = [str(i.get("name") or "").strip() for i in items if str(i.get("name") or "").strip()]
    labels = [f"{i['name']}{' ×' + str(i['quantity']) if (i.get('quantity') or 1) > 1 else ''}"
              f"{' (' + i['kind'] + ')' if i.get('kind') else ''}" for i in items if i.get("name")]
    return {"names": names, "kinds": [i.get("kind") for i in items if i.get("name")], "labels": labels}


def receipt_facts(fields: dict, extra: dict, today: date, receipt: dict | None = None) -> tuple[dict, list[str]]:
    """영수증 추출 결과 + 사용자가 준 정보 → (알고 있는 사실, 모르는 사실).
    receipt: 추출 노드의 장별 결과(receipts[i]) — 품목·결제 시각·결제 수단·찍힌 인원·증빙 구분을 사실로 씀
    요일·공휴일·경과일·1인당 금액·시각 비교처럼 계산으로 나오는 건 LLM에게 맡기지 않고 여기서 계산.
    사실 이름은 규정 본문의 용어와 똑같이 씀 (이름이 다르면 LLM이 '없다'고 착각함)"""
    known: dict = {}
    unknown: list[str] = []

    def put(name, value):
        if value in (None, "", []):
            unknown.append(name)
        else:
            known[name] = value

    put("상호", fields.get("store"))
    put("결제 금액", fields.get("total"))
    put("거래일자", fields.get("date"))

    # 날짜 → 요일·주말·공휴일·경과일
    try:
        d = date.fromisoformat(fields.get("date") or "")
        known["요일"] = WEEKDAYS[d.weekday()]
        known["주말 여부"] = d.weekday() >= 5
        known["공휴일 여부"] = KR_HOLIDAYS.get(d) or False      # 공휴일이면 이름(예: 개천절), 아니면 False
        known["사용일부터 경과일"] = (today - d).days
    except ValueError:
        pass                                                     # 거래일자가 이미 모르는 사실에 들어감
    known["정산 신청일"] = today.isoformat()                      # 검토는 정산 신청 시점에 돌아감

    # 경비 규정 제14조④: 영수증 필수 기재 항목
    missing = [n for n, k in (("상호", "store"), ("거래일자", "date"), ("결제 금액", "total")) if fields.get(k) in (None, "")]
    known["영수증 필수 기재(상호·거래일자·결제 금액)"] = "모두 확인됨" if not missing else f"{', '.join(missing)} 확인 안 됨"

    # 결제 시각 → 야간·오후 7시 이후 (시각 비교도 코드가 함)
    t = _parse_time(extra.get("결제 시각") or fields.get("time") or _details_value(receipt, "pay_time"))
    if t:
        h, m = t
        known["결제 시각"] = f"{h:02d}:{m:02d}"
        known["야간 결제 여부(22시~06시)"] = h >= 22 or h < 6
        # 경비 규정 제8조① 용어 그대로. 지금은 결제 시각으로 추정 → 나중에 attendance-service 퇴근 기록으로 교체
        known["오후 7시 이후까지 근무 여부(결제 시각 기준)"] = h >= 19 or h < 6
    else:
        unknown.append("결제 시각")
    # 근태 조회 노드가 있으면: 결제 시각 추정 대신 실제 퇴근 기록 (경비 규정 제8조①), 회사 달력의 휴일 (대체휴일 포함)
    if extra.get("퇴근 시각"):
        known["퇴근 시각"] = extra["퇴근 시각"]
        known.pop("오후 7시 이후까지 근무 여부(결제 시각 기준)", None)
        known["오후 7시 이후까지 근무 여부(근태 기록)"] = int(str(extra["퇴근 시각"])[:2]) >= 19
    if isinstance(extra.get("회사 휴일 여부"), bool):
        known["회사 휴일 여부(근태 기록)"] = extra["회사 휴일 여부"]

    # 결제 수단 → 증빙 종류 (경비 규정 제3조③)
    # 사용자가 준 값이 먼저, 없으면 영수증에서 찾은 값 (카드 전표·현금영수증 문구)
    pay = extra.get("결제 수단") or _details_value(receipt, "pay_method")
    evidence = (receipt or {}).get("evidence_kind") if not extra.get("결제 수단") else None
    if pay:
        known["결제 수단"] = pay
        if pay in CARD_PAYMENTS or evidence == "신용카드 매출전표":
            known["증빙 종류"] = "신용카드 매출전표(적격증빙)"
        elif pay == "현금영수증" or evidence == "현금영수증":
            known["증빙 종류"] = "지출증빙 현금영수증(적격증빙)"
        elif pay == "현금":
            known["증빙 종류"] = "간이영수증(현금, 현금영수증 여부 모름)"
        else:
            known["증빙 종류"] = "확인 필요"
    else:
        unknown.append("결제 수단")

    # 사용자가 알려줘야만 아는 사실
    # 사람이 적은 값이 없으면 영수증에 손으로 적은 메모 (참석: … / 목적: … / 사유: …)
    memo = {k: _details_value(receipt, f"memo_{k}") for k in ("purpose", "attendees", "headcount", "reason", "uncertain")}
    if not extra.get("사용 목적") and memo["purpose"]:
        extra = {**extra, "사용 목적": memo["purpose"]}
        known["사용 목적 근거"] = "영수증 메모"
    names_from_memo = False
    if not extra.get("참석자") and (memo["attendees"] or memo["uncertain"]):
        # 다 못 나눈 이름 줄은 적힌 그대로를 참석자로 남김 (제8조④ 이름 기재는 충족, 인원 수로는 안 씀)
        extra = {**extra, "참석자": [memo["uncertain"]] if memo["uncertain"] else memo["attendees"]}
        known["참석자 근거"] = "영수증 메모" + (" (손글씨 판독 불확실)" if memo["uncertain"] else "")
        names_from_memo = True
    if not extra.get("사용 사유") and memo["reason"]:
        extra = {**extra, "사용 사유": memo["reason"]}
    purpose = extra.get("사용 목적")
    if purpose and purpose not in PURPOSES:
        raise ValueError(f"사용 목적 '{purpose}'는 {PURPOSES} 중 하나여야 합니다")
    put("사용 목적", purpose)
    # 참석 인원: 사용자 입력 → 영수증에 찍힌 인원(고객수·PAX) → 메모의 인원('외 2명') → 참석자 이름 수 → 품목 추정 범위의 최솟값
    #   최솟값으로 나누면 1인당 금액이 가장 크게 나옴 → 그래도 한도 안이면 실제 인원이 더 많아도 준수 (부풀림에 안 속음)
    #   한도를 넘으면 안 시킨 사람이 있었을 수 있어 위반 대신 '확인 필요' (numeric_checks의 estimated)
    head = (receipt or {}).get("headcount") or {}
    printed, estimate, names = head.get("printed"), head.get("estimate"), extra.get("참석자")
    people = None
    if extra.get("참석 인원"):
        people, known["참석 인원 근거"] = extra["참석 인원"], "사용자 입력"
    elif printed:
        people, known["참석 인원 근거"] = printed, "영수증에 찍힌 인원"
    elif memo["headcount"]:
        people, known["참석 인원 근거"] = memo["headcount"], f"{FROM_MEMO} ('외 N명'·'총 N명')"
    elif names and not (names_from_memo and memo["uncertain"]):
        people = len(names)
        known["참석 인원 근거"] = f"{FROM_MEMO} 이름 수" if names_from_memo else "참석자 이름 수"
    elif estimate and estimate.get("min"):
        people = estimate["min"]
        known["참석 인원 근거"] = f"{ESTIMATED} ({estimate['min']}~{estimate['max']}명, {estimate.get('basis', '')})"
    put("참석 인원", people)
    put("참석자 이름", extra.get("참석자"))                 # 경비 규정 제8조④
    put("구매 품목", item_names(receipt, extra)["labels"])
    put("사용 사유", extra.get("사용 사유"))                 # 경비 규정 제7조① (야간·주말·공휴일 사용 시)

    n, total = known.get("참석 인원"), known.get("결제 금액")
    if isinstance(n, int) and n > 0 and isinstance(total, int):
        known["1인당 금액"] = total // n                       # 경비 규정 제8조④: 결제 금액 ÷ 참석 인원
    return known, unknown


def receipt_queries(known: dict) -> list[str]:
    """사실 하나하나를 규정 검색 질문으로 (템플릿 → 결과가 항상 같아 평가·재현 쉬움)"""
    # 상호·금액을 넣지 않는다: 문장이 정해져 있어야 검색 결과를 캐시·미리 계산할 수 있음
    # (업종은 store_kind가 사실로 주고, 조항 선택은 front matter의 목적 표가 맡음 → 검색은 보조)
    qs = [
        "법인카드 사용 제한 업종 품목",
        "주말 공휴일 야간 법인카드 사용 사유",
        f"{known.get('사용 목적') or '식대'} 1인당 한도",
        "주류 포함 결제",
        "증빙 요건 적격증빙 간이영수증",
        "경비 정산 기한",
    ]
    if known.get("사용 목적") == "접대":
        qs.append("접대비 사전 승인")
    return qs


# ---------------------------------------------------------------- 조항별 판정 (DSPy)
class Judgment(BaseModel):
    verdict: Verdict = Field(description="준수 / 위반 / 해당 없음(이 업무와 관계없는 조항) / 확인 필요(사실이 부족)")
    quote: str = Field(default="", description="판정 근거 문장. 조항 원문에서 글자 그대로 옮겨 적기 (판정 결과와 상관없이)")
    reason: str = Field(description="판정 이유 한두 문장")
    missing_facts: list[str] = Field(default_factory=list, description="판정에 필요한데 모르는 사실 (모르는 사실 목록의 이름 그대로)")


class JudgeClause(dspy.Signature):
    """사내 규정 조항 하나와 업무 사실을 보고, 이 업무가 그 조항을 지켰는지 판정한다.
    1) 주어진 조항에 적힌 내용만 근거로 삼는다. 조항에 없는 기준을 만들지 않는다.
    2) '확인 필요'는 모르는 사실에 따라 판정이 달라질 때만 쓰고, missing_facts에 그 사실 이름을 적는다.
       이 조항의 판정과 상관없는 사실을 모르는 것은 '확인 필요'의 이유가 되지 않는다.
    3) 알고 있는 사실만으로 조항이 금지하거나 요구하는 상황이 아님이 분명하면 '준수'.
       금지 상황에 해당하지만 예외(사전 승인 등)가 있으면, 예외에 필요한 사실을 모를 때 '확인 필요'.
    4) 이 조항이 이 업무와 관계없으면 '해당 없음'.
    5) 판정 결과와 상관없이, 근거가 된 문장 하나를 조항 원문에서 글자 그대로 quote에 옮겨 적는다.
    6) 금액·일수 비교는 '코드 계산 결과'를 그대로 믿는다. 직접 계산하지 않는다.
    7) reason과 verdict는 서로 맞아야 한다. 이유가 '위반이 아니다'이면 판정은 '준수' 또는 '해당 없음'."""

    clause: str = dspy.InputField(desc="규정 조항 원문 (조항 ID, 규정명, 제목 포함)")
    known_facts: str = dspy.InputField(desc="알고 있는 사실과 코드 계산 결과 (JSON)")
    unknown_facts: str = dspy.InputField(desc="모르는 사실 목록 (JSON)")
    judgment: Judgment = dspy.OutputField()


class PolicyJudge(dspy.Module):
    """최적화(compile)·저장·불러오기 단위 (영수증 추출 모듈과 같은 방식으로 MLOps에 올림)"""

    def __init__(self):
        super().__init__()
        self.judge = dspy.Predict(JudgeClause)

    def forward(self, clause: str, known_facts: str, unknown_facts: str):
        return self.judge(clause=clause, known_facts=known_facts, unknown_facts=unknown_facts)


PROGRAM_DIR = Path(__file__).resolve().parent.parent / "programs"


def _load_judge() -> tuple[PolicyJudge, str]:
    """챔피언 파일(ai/programs/policy.json)이 있으면 불러오고, 없으면 기본(base) — 문서 분류와 같은 방식"""
    judge = PolicyJudge()
    path = PROGRAM_DIR / "policy.json"
    if not path.exists():
        return judge, "policy@base"
    judge.load(str(path))
    meta_path = PROGRAM_DIR / "policy.meta.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.exists() else {}
    return judge, meta.get("label", "policy@champion")


_judge, JUDGE_VERSION = _load_judge()
BUNDLED_JUDGE = (_judge, JUDGE_VERSION)      # Git 파일 기준 (MLflow champion이 없거나 못 받을 때 돌아갈 곳)


def swap_judge(judge: "PolicyJudge | None", label: str | None):
    """판정 프로그램 교체 (ai/champions.py가 MLflow champion으로, 재시작 없이). None이면 Git 파일 기준으로.
    판정 캐시 키에 버전이 들어가 있어서, 바뀐 뒤에는 이전 버전의 판정을 다시 쓰지 않음"""
    global _judge, JUDGE_VERSION
    _judge, JUDGE_VERSION = BUNDLED_JUDGE if judge is None else (judge, label)


# ---------------------------------------------------------------- 코드 검증
_MARKS = re.compile(r"[\s①-⑳\"'“”‘’「」『』]")


def _norm(text: str) -> str:
    """공백·항 번호(①②)·따옴표를 지우고 비교 (인용할 때 생기는 사소한 차이만 허용)"""
    return _MARKS.sub("", text or "").rstrip(".")


def quote_ok(clause: Clause, quote: str) -> bool:
    """④ 인용 검증: '…'·줄바꿈으로 이어 붙인 경우 조각마다 원문에 글자 그대로 있어야 함"""
    body = _norm(clause.body)
    parts = [_norm(p) for p in re.split(r"…|\.\.\.|\n| / ", quote or "")]
    parts = [p for p in parts if p]
    return bool(parts) and sum(map(len, parts)) >= 8 and all(len(p) >= 4 and p in body for p in parts)


def numeric_checks(clause: Clause, known: dict) -> tuple[list[dict], list[str]]:
    """⑤ 숫자는 코드: front matter '수치' 기준을 사실과 비교.
    반환: (검사 결과 목록, 비교하려 했는데 모르는 사실)"""
    purpose = known.get("사용 목적")
    results, missing = [], []
    for rule in clause.numeric:
        if rule.get("목적") and rule["목적"] != purpose:       # 다른 목적의 기준 (예: 야근인데 회식 한도)
            continue
        kind = "최대" if "최대" in rule else "기준"
        fact, limit = rule["사실"], rule[kind]
        value = known.get(fact)
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            missing.append(DERIVED_FROM.get(fact, fact))
            continue
        over = value > limit
        basis = str(known.get("참석 인원 근거", ""))
        estimated = fact == "1인당 금액" and basis.startswith(UNSURE_HEADCOUNT)
        note = " (품목 기준 최소 인원으로 계산)" if basis.startswith(ESTIMATED) else " (영수증 손글씨에서 읽은 인원)" if estimated else ""
        text = f"{fact} {value:,} {'>' if over else '≤'} {kind} {limit:,}" + note
        results.append({"kind": kind, "fact": fact, "value": value, "limit": limit, "over": over, "text": text,
                        "estimated": estimated})
    return results, missing


def decide(clause: Clause, j: Judgment, checks: list[dict], num_missing: list[str]) -> tuple[Verdict, list[str], list[str]]:
    """LLM 판정 + 코드 검사 → 최종 판정. 반환: (판정, 검증 메모, 모르는 사실)"""
    verdict, notes, missing = j.verdict, [], list(j.missing_facts)

    if verdict in ("준수", "위반") and not quote_ok(clause, j.quote):
        verdict = "확인 필요"
        notes.append("인용 문장이 조항 원문에 없음")

    guessed = [c for c in checks if c["kind"] == "최대" and c["over"] and c.get("estimated")]
    exceeded = [c for c in checks if c["kind"] == "최대" and c["over"] and not c.get("estimated")]
    if guessed and not exceeded and verdict != "위반":        # 추정 인원으로만 넘음 → 인원을 물어봄
        verdict = "확인 필요"
        notes.append(f"품목 기준 최소 인원으로는 한도 초과: {', '.join(c['text'] for c in guessed)} → 참석 인원 확인")
        num_missing = [*num_missing, "참석 인원"]
    if exceeded:                                              # 한도 초과는 다른 조건과 상관없이 위반 (초과분 불인정)
        if verdict != "위반":
            notes.append(f"코드 계산으로 위반: {', '.join(c['text'] for c in exceeded)}")
        verdict = "위반"
    elif num_missing and verdict == "준수":                   # 한도 비교를 못 했는데 '준수'일 수는 없음
        verdict = "확인 필요"
        notes.append(f"한도 비교에 필요한 사실 없음: {', '.join(num_missing)}")
    missing += [m for m in num_missing if m not in missing]
    return verdict, notes, missing


# ---------------------------------------------------------------- 전체 흐름
def _clause_text(c: Clause) -> str:
    return f"[{c.id}] {c.label}\n{c.body}"


def _collect(known: dict, doc_codes: list[str], k: int) -> tuple[dict[str, Clause], list[dict]]:
    """① 질문마다 검색 → 조항 ID로 합침  +  ② 사용 목적에 딸린 조항은 검색과 상관없이 추가"""
    found: dict[str, Clause] = {}
    traces = []
    for q in receipt_queries(known):
        r = retrieve(q, doc_codes=doc_codes, k=k)
        traces.append({"query": q, **r.trace})
        if not r.abstain:
            found.update({h.clause.id: h.clause for h in r.hits if h.rel >= MIN_HIT_REL})

    # 규정 front matter로 고르기: 판정 대상(AI) 조항 중 모든 경비에 적용되는 것 + 이 목적에 딸린 것.
    # 목적을 모르면 목적별 조항도 넣어 두고(→ 아래에서 '사용 목적' 필요로 분류) 검색 결과와 상관없이 빠지지 않게
    purpose = known.get("사용 목적")
    for c in get_index().clauses:
        if c.doc_code in doc_codes and c.enforcement == "AI" and (not c.purposes or not purpose or purpose in c.purposes):
            found.setdefault(c.id, c)
    return found, traces


_judge_cache: "OrderedDict[str, Judgment]" = OrderedDict()
_judge_lock = threading.Lock()


def _llm_facts(c: Clause, known: dict, unknown: list[str]) -> tuple[dict, list[str]]:
    """조항에 넘길 사실만 고른다 (rules.CLAUSE_FACTS, 없으면 전부)"""
    names = CLAUSE_FACTS.get(c.id)
    if not names:
        return known, unknown
    return {k: v for k, v in known.items() if k in names}, [u for u in unknown if u in names]


def _judge_cached(active, use_cache: bool, clause: Clause, facts: dict, unknown: list[str]) -> Judgment:
    """같은 조항·같은 사실이면 판정을 다시 묻지 않는다 (실행 중 반복되는 가게·목적 조합이 많음)"""
    facts_json = json.dumps(facts, ensure_ascii=False, default=str, sort_keys=True)
    unknown_json = json.dumps(sorted(unknown), ensure_ascii=False)
    key = f"{JUDGE_VERSION}|{get_index().version}|{clause.id}|{facts_json}|{unknown_json}"
    if use_cache:
        with _judge_lock:
            if key in _judge_cache:
                _judge_cache.move_to_end(key)
                JUDGE_CACHE.labels("hit").inc()
                return _judge_cache[key]
        JUDGE_CACHE.labels("miss").inc()
    j: Judgment = run_with_fallback(active, clause=_clause_text(clause), known_facts=facts_json,
                                    unknown_facts=unknown_json).judgment
    if use_cache:
        with _judge_lock:
            _judge_cache[key] = j
            while len(_judge_cache) > JUDGE_CACHE_SIZE:
                _judge_cache.popitem(last=False)
    return j


def _clause_order(clause_id: str) -> tuple:
    head, _, num = clause_id.partition("-")
    return (head == "CHK", int(num) if num.isdigit() else 0)


def check_receipt(fields: dict, extra: dict | None = None, today: date | None = None, k: int = 3,
                  judge: PolicyJudge | None = None, receipt: dict | None = None) -> dict:
    """영수증 규정 검토. 반환값은 노드 출력에 그대로 들어감 (추적 정보 포함)
    judge: 최적화 중인 후보 판정 모듈 (없으면 운영 챔피언 _judge, 이때만 판정 캐시 사용)
    receipt: 추출 노드의 장별 결과 (품목·결제 시각·결제 수단·찍힌 인원·품목 기준 추정 인원)"""
    today = today or date.today()
    extra = extra or {}
    t0 = time.perf_counter()
    known, unknown = receipt_facts(fields, extra, today, receipt)
    items = item_names(receipt, extra)
    kind, kind_src = store_kind(known.get("상호"), items["names"])
    known["업종"] = kind
    if kind == "모름":
        unknown.append("업종")
    purpose = known.get("사용 목적")
    found, traces = _collect(known, DOC_SCOPE["영수증"], k)
    t_retrieval = time.perf_counter() - t0
    POLICY_SECONDS.labels("retrieval").observe(t_retrieval)
    # 단계별 시간(ms): 결과에 남겨 실행 화면·평가에서 어디가 느린지 바로 보이게
    timing = {"retrieval_ms": round(t_retrieval * 1000), "judge_ms": 0, "total_ms": round(t_retrieval * 1000)}

    # 집행 방식으로 나눔: AI → 판정 / 시스템·설정 → 안내만
    system_rules = [{"clause_id": c.id, "label": c.label, "enforcement": c.enforcement, "note": c.enforcement_note}
                    for c in found.values() if c.enforcement in ("시스템", "설정")]
    ai_clauses, skipped = [], []
    for c in found.values():
        if c.enforcement != "AI":
            continue
        if c.purposes and purpose not in c.purposes:          # 다른 목적용 조항 (예: 회식인데 접대비 조항)
            skipped.append(c.id)
        else:
            ai_clauses.append(c)

    needed: list[str] = []
    if not purpose and skipped:                               # 목적을 모르면 목적별 조항은 판정 못 함
        needed.append("사용 목적")

    base = {"system_rules": system_rules, "skipped_by_purpose": sorted(skipped),
            "known_facts": known, "unknown_facts": unknown, "store_kind_source": kind_src,
            "retrieval": traces, "index_version": get_index().version, "judge_version": JUDGE_VERSION,
            "timing": timing}

    # 시스템 검사: 사용자가 적은 인원이 품목으로 본 인원보다 너무 많으면 (부풀림 의심) 확인
    extra_findings = []
    estimate = ((receipt or {}).get("headcount") or {}).get("estimate")
    if known.get("참석 인원 근거") == "사용자 입력":
        issue = inflation_issue(known.get("참석 인원"), estimate)
        if issue:
            extra_findings.append({"clause_id": "CHK-1", "label": "참석 인원 확인 (품목 기준)", "verdict": "확인 필요",
                                   "llm_verdict": None, "quote": "", "reason": issue, "missing_facts": [],
                                   "numeric": [], "checks": [], "by": "code"})

    # 중복 영수증: 같은 실행 안 / 다른 실행에서 이미 청구 → 위반 (돈이 두 번 나가지 않게, 사람이 보고 빼거나 설명)
    dup_of, dup_prev = (receipt or {}).get("duplicate_of"), (receipt or {}).get("duplicate_prev")
    if dup_of is not None or dup_prev:
        why = (f"이번에 올린 No.{dup_of + 1}과 같은 결제" if dup_of is not None else
               f"실행 #{dup_prev['run_id']}에서 이미 정산 신청 ({dup_prev.get('state')}"
               + (f", 문서 {dup_prev['doc_no']}" if dup_prev.get("doc_no") else "") + ")")
        extra_findings.append({"clause_id": "CHK-2", "label": "중복 영수증 (이중 청구)", "verdict": "위반",
                               "llm_verdict": None, "quote": "", "reason": why + " → 같은 영수증이면 정산에서 빼 주세요",
                               "missing_facts": [], "numeric": [], "checks": [], "by": "code"})

    if not ai_clauses and not extra_findings:                 # ② 판정할 근거가 없으면 LLM을 부르지 않음
        return {"verdict": "확인 필요", "findings": [], "needed_facts": needed,
                "reason": "판정할 수 있는 규정을 찾지 못했습니다", **base}

    active = judge if judge is not None else _judge          # 후보 판정 모듈이 들어오면 그것으로 판정
    use_cache = judge is None
    code_count = 0

    def judge_one(c: Clause) -> dict:
        nonlocal code_count
        checks, num_missing = numeric_checks(c, known)
        by_code = code_judge(c, known, items, checks, num_missing)
        if by_code is not None:                               # 사실만으로 답이 정해지는 조항 → 코드 판정
            code_count += 1
            return by_code
        facts = {**known, "코드 계산 결과": [x["text"] for x in checks]} if checks else known
        facts, unk = _llm_facts(c, facts, unknown)
        j = _judge_cached(active, use_cache, c, facts, unk)
        verdict, notes, missing = decide(c, j, checks, num_missing)
        return {"clause_id": c.id, "label": c.label, "verdict": verdict, "llm_verdict": j.verdict,
                "quote": j.quote, "reason": j.reason, "missing_facts": missing,
                "numeric": [x["text"] for x in checks], "checks": notes, "by": "llm"}

    t1 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=4) as pool:          # 조항마다 독립이라 동시에 판정
        findings = list(pool.map(judge_one, ai_clauses))
    findings = sorted(findings + extra_findings, key=lambda f: _clause_order(f["clause_id"]))
    t_judge = time.perf_counter() - t1
    POLICY_SECONDS.labels("judge").observe(t_judge)
    timing["judge_ms"] = round(t_judge * 1000)
    timing["total_ms"] = round((time.perf_counter() - t0) * 1000)
    timing["code_clauses"] = code_count
    timing["llm_clauses"] = len(ai_clauses) - code_count
    POLICY_SECONDS.labels("total").observe(time.perf_counter() - t0)

    # 추가로 필요한 정보: 확인 필요 조항들이 말한 모르는 사실을 중복 없이 모음 → 사용자에게 한 번에 질문
    # 모르는 사실 목록에 없는 이름(LLM이 지어낸 사실, 이미 아는 사실)은 사용자에게 묻지 않음
    askable = set(unknown) | set(DERIVED_FROM.values())
    for f in findings:
        if f["verdict"] == "확인 필요":
            needed += [m for m in f["missing_facts"] if m in askable and m not in needed]

    # ⑥ 종합: 위반이 하나라도 → 위반, 확인 필요가 하나라도 → 확인 필요, 나머지는 준수
    verdicts = {f["verdict"] for f in findings}
    overall = "위반" if "위반" in verdicts else "확인 필요" if ("확인 필요" in verdicts or needed) else "준수"
    return {"verdict": overall, "findings": findings, "needed_facts": needed, **base}


# ---------------------------------------------------------------- 서버 시작 때 미리 계산
def fixed_receipt_queries() -> list[str]:
    """영수증과 상관없이 문장이 정해진 질문(목적별 포함). 질문에 상호·금액을 넣지 않으므로 전부 미리 계산된다"""
    qs: list[str] = []
    for purpose in PURPOSES:
        for q in receipt_queries({"사용 목적": purpose}):
            if q not in qs:
                qs.append(q)
    return qs


def warm_up() -> None:
    """모델을 불러오고(색인·임베딩·재정렬) 고정 질문의 검색 결과를 미리 캐시에 채운다.
    첫 영수증부터 재정렬을 그만큼 덜 하게 됨. 실패해도 서버는 계속 뜸 (쓸 때 다시 계산)"""
    warm_up_models()
    try:
        t0 = time.perf_counter()
        qs = fixed_receipt_queries()
        for q in qs:
            retrieve(q, doc_codes=DOC_SCOPE["영수증"], k=3)       # check_receipt 기본 k와 같아야 캐시가 맞음
        logging.getLogger(__name__).info("규정 고정 질문 %d개 미리 계산 (%.1f초)", len(qs), time.perf_counter() - t0)
    except Exception:
        logging.getLogger(__name__).exception("규정 고정 질문 미리 계산 실패")
