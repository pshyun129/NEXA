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
"""
import json
import re
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path
from typing import Literal

import dspy
import holidays
from pydantic import BaseModel, Field

from ai.dspy_lm import run_with_fallback
from ai.rag.index import get_index
from ai.rag.parse import Clause
from ai.rag.search import MIN_REL, retrieve

# 문서 종류 → 검색할 규정 (엉뚱한 규정이 검색되는 것 원천 차단)
DOC_SCOPE = {
    "영수증": ["EXP"],
    "출장 정산": ["TRV", "EXP"],
    "휴가 신청": ["LEV"],
}
# 경비 사용 목적 (규정 front matter '목적'에 쓰는 이름과 같아야 함)
PURPOSES = ["야근", "주말근무", "회식", "회의", "접대", "교통", "소모품", "기타"]
CARD_PAYMENTS = {"법인카드", "개인카드"}        # 신용카드 매출전표 = 적격증빙 (경비 규정 제3조③)
WEEKDAYS = ["월", "화", "수", "목", "금", "토", "일"]
DERIVED_FROM = {"1인당 금액": "참석 인원"}         # 계산된 사실이 없을 때 사용자에게 물어볼 원래 사실
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


def receipt_facts(fields: dict, extra: dict, today: date) -> tuple[dict, list[str]]:
    """영수증 추출 결과 + 사용자가 준 정보 → (알고 있는 사실, 모르는 사실).
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
    t = _parse_time(extra.get("결제 시각") or fields.get("time"))
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
    pay = extra.get("결제 수단")
    if pay:
        known["결제 수단"] = pay
        known["증빙 종류"] = "신용카드 매출전표(적격증빙)" if pay in CARD_PAYMENTS else "확인 필요"
    else:
        unknown.append("결제 수단")

    # 사용자가 알려줘야만 아는 사실
    purpose = extra.get("사용 목적")
    if purpose and purpose not in PURPOSES:
        raise ValueError(f"사용 목적 '{purpose}'는 {PURPOSES} 중 하나여야 합니다")
    put("사용 목적", purpose)
    put("참석 인원", extra.get("참석 인원"))
    put("참석자 이름", extra.get("참석자"))                 # 경비 규정 제8조④
    put("구매 품목", extra.get("구매 품목"))
    put("사용 사유", extra.get("사용 사유"))                 # 경비 규정 제7조① (야간·주말·공휴일 사용 시)

    n, total = known.get("참석 인원"), known.get("결제 금액")
    if isinstance(n, int) and n > 0 and isinstance(total, int):
        known["1인당 금액"] = total // n                       # 경비 규정 제8조④: 결제 금액 ÷ 참석 인원
    return known, unknown


def receipt_queries(known: dict) -> list[str]:
    """사실 하나하나를 규정 검색 질문으로 (템플릿 → 결과가 항상 같아 평가·재현 쉬움)"""
    qs = [
        f"{known.get('상호') or ''} 업종 법인카드 사용 제한 업종",
        "주말 공휴일 야간 법인카드 사용 사유",
        f"{known.get('사용 목적') or '식대'} 1인당 한도",
        "주류 포함 결제",
        f"증빙 요건 {known.get('결제 금액') or ''}원 영수증",
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
        text = f"{fact} {value:,} {'>' if over else '≤'} {kind} {limit:,}"
        results.append({"kind": kind, "fact": fact, "value": value, "limit": limit, "over": over, "text": text})
    return results, missing


def decide(clause: Clause, j: Judgment, checks: list[dict], num_missing: list[str]) -> tuple[Verdict, list[str], list[str]]:
    """LLM 판정 + 코드 검사 → 최종 판정. 반환: (판정, 검증 메모, 모르는 사실)"""
    verdict, notes, missing = j.verdict, [], list(j.missing_facts)

    if verdict in ("준수", "위반") and not quote_ok(clause, j.quote):
        verdict = "확인 필요"
        notes.append("인용 문장이 조항 원문에 없음")

    exceeded = [c for c in checks if c["kind"] == "최대" and c["over"]]
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

    purpose = known.get("사용 목적")
    if purpose:
        for c in get_index().clauses:
            if c.doc_code in doc_codes and purpose in c.purposes:
                found.setdefault(c.id, c)
    return found, traces


def check_receipt(fields: dict, extra: dict | None = None, today: date | None = None, k: int = 3,
                  judge: PolicyJudge | None = None) -> dict:
    """영수증 규정 검토. 반환값은 노드 출력에 그대로 들어감 (추적 정보 포함)
    judge: 최적화 중인 후보 판정 모듈 (없으면 운영 챔피언 _judge)"""
    today = today or date.today()
    known, unknown = receipt_facts(fields, extra or {}, today)
    purpose = known.get("사용 목적")
    found, traces = _collect(known, DOC_SCOPE["영수증"], k)

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
            "known_facts": known, "unknown_facts": unknown,
            "retrieval": traces, "index_version": get_index().version, "judge_version": JUDGE_VERSION}
    if not ai_clauses:                                        # ② 판정할 근거가 없으면 LLM을 부르지 않음
        return {"verdict": "확인 필요", "findings": [], "needed_facts": needed,
                "reason": "판정할 수 있는 규정을 찾지 못했습니다", **base}

    unknown_json = json.dumps(unknown, ensure_ascii=False)
    active = judge if judge is not None else _judge          # 후보 판정 모듈이 들어오면 그것으로 판정

    def judge_one(c: Clause) -> dict:
        checks, num_missing = numeric_checks(c, known)
        facts = {**known, "코드 계산 결과": [x["text"] for x in checks]} if checks else known
        j: Judgment = run_with_fallback(active, clause=_clause_text(c),
                                        known_facts=json.dumps(facts, ensure_ascii=False, default=str),
                                        unknown_facts=unknown_json).judgment
        verdict, notes, missing = decide(c, j, checks, num_missing)
        return {"clause_id": c.id, "label": c.label, "verdict": verdict, "llm_verdict": j.verdict,
                "quote": j.quote, "reason": j.reason, "missing_facts": missing,
                "numeric": [x["text"] for x in checks], "checks": notes}

    with ThreadPoolExecutor(max_workers=4) as pool:          # 조항마다 독립이라 동시에 판정
        findings = sorted(pool.map(judge_one, ai_clauses), key=lambda f: int(f["clause_id"].split("-")[1]))

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