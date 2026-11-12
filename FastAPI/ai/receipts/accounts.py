"""정산 용어: 사용 목적 → 계정과목, 장별 결과 → 정산서 한 줄.

계정과목은 회사 회계 기준에 맞춰 바꿀 수 있게 표 하나로 둔다 (결재 묶음 기준·엑셀 정산서가 같이 씀).
"""

ACCOUNTS = {
    "야근": "복리후생비", "주말근무": "복리후생비", "회식": "복리후생비",
    "회의": "회의비",
    "접대": "기업업무추진비(접대비)",
    "교통": "여비교통비",
    "소모품": "소모품비",
}
UNKNOWN_ACCOUNT = "미분류"


def purpose_of(r: dict) -> str | None:
    """장의 사용 목적: 사람이 적은 값 → 규정 검토에 쓴 사실"""
    facts = r.get("facts") or {}
    known = (r.get("policy") or {}).get("known_facts") or {}
    return facts.get("purpose") or facts.get("사용 목적") or known.get("사용 목적")


def account_of(r: dict) -> str:
    return ACCOUNTS.get(purpose_of(r) or "", UNKNOWN_ACCOUNT)


def verdict_of(r: dict) -> str:
    return (r.get("policy") or {}).get("verdict") or "준수"


def headcount_of(r: dict) -> tuple[int | None, str]:
    """(인원, 근거) — 사람 입력 → 영수증에 찍힌 인원. 품목 추정은 판정에 안 쓰므로 여기서도 근거로 안 씀"""
    facts = r.get("facts") or {}
    known = (r.get("policy") or {}).get("known_facts") or {}
    n = facts.get("headcount") or known.get("참석 인원")
    src = known.get("참석 인원 근거") or ("사용자 입력" if facts.get("headcount") else "")
    if not n and (r.get("headcount") or {}).get("printed"):
        return (r["headcount"]["printed"], "영수증에 찍힌 인원")
    try:
        return (int(n), src) if n else (None, "")
    except (TypeError, ValueError):
        return None, ""


def attendees_of(r: dict) -> str:
    facts = r.get("facts") or {}
    v = facts.get("attendees") or (r.get("policy") or {}).get("known_facts", {}).get("참석자 이름")
    return ", ".join(v) if isinstance(v, list) else (v or "")
