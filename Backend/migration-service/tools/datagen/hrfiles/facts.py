"""
여러 인사 파일이 함께 쓰는 '사실' (연봉, 부양가족, 월별 근태 시간, 공휴일).

파일마다 따로 만들면 급여대장의 가족 수와 부양가족 파일이 어긋나는 식의 '의도하지 않은' 불일치가 생긴다.
그래서 한 곳에서 만들고, 일부러 어긋나게 한 곳만 정답지에 사고로 남긴다.
"""
import datetime as dt
import random

from .xl import ROUND, ROUNDDOWN, ROUNDUP

CUTOVER = dt.date(2027, 1, 1)
TAX_YEAR = 2026  # 부양가족 기본공제 나이 기준 (귀속연도: 자녀 20세 이하, 직계존속 60세 이상)

# 2026년 공휴일 (대체공휴일, 6/3 지방선거, 2026년부터 공휴일인 7/17 제헌절 포함). 생성기 내장값이다
# 추석(9/24~26)은 토요일과 겹쳐도 대체공휴일이 없다 (설·추석 대체공휴일은 일요일과 겹칠 때만)
HOLIDAYS_2026 = {dt.date(2026, m, d) for m, d in [
    (1, 1), (2, 16), (2, 17), (2, 18), (3, 1), (3, 2), (5, 1), (5, 5), (5, 24), (5, 25), (6, 3), (6, 6),
    (7, 17), (8, 15), (8, 17), (9, 24), (9, 25), (9, 26), (10, 3), (10, 5), (10, 9), (12, 25)]}
# 생성기 첫 버전의 목록(7/17 없음, 9/28 있음). 휴가 사용일을 '뽑을' 때만 쓴다: 목록을 고쳐도 난수열과 다른 날짜가
# 그대로 남게 하고, 뽑힌 날이 실제 공휴일(7/17)이면 shift_workday로 다음 근무일로 옮긴다
_DRAW_HOLIDAYS = (HOLIDAYS_2026 - {dt.date(2026, 7, 17)}) | {dt.date(2026, 9, 28)}


def is_holiday(d):
    return d.weekday() >= 5 or d in HOLIDAYS_2026


def draw_days(days):
    """휴가 사용일을 뽑을 후보 (첫 버전 달력의 근무일). 뽑은 뒤 shift_workday를 거친다"""
    return [d for d in days if not (d.weekday() >= 5 or d in _DRAW_HOLIDAYS)]


def shift_workday(d, taken, last=dt.date(2026, 12, 31)):
    """d가 휴일이면 그 뒤 첫 근무일(taken에 없는 날)로 옮긴다. last를 넘으면 앞쪽에서 찾는다"""
    if not is_holiday(d):
        return d
    x = d
    while is_holiday(x) or x in taken:
        x += dt.timedelta(days=1)
    if x > last:
        x = d
        while is_holiday(x) or x in taken:
            x -= dt.timedelta(days=1)
    return x


# 월 기본급 = 연봉 / 12 의 끝수 처리 (연봉이력 파일의 규칙과 같다)
MONTHLY_BASE_ROUNDING = {
    "ganatech": ("floor10", lambda a: ROUNDDOWN(a / 12, -1)),
    "daramulsan": ("round1", lambda a: ROUND(a / 12, 0)),
    "mabaservice": ("ceil100", lambda a: ROUNDUP(a / 12, -2)),
}

# 업종별 월 근태 시간 분포 (연장, 야간, 휴일 8h 이내, 휴일 8h 초과)
HOURS = {
    "IT": {"ot": (0, 24), "night": (0, 6), "hol": (0, 16), "hol8": (0, 4), "nightCrew": 0.0},
    "제조": {"ot": (8, 40), "night": (0, 8), "hol": (0, 24), "hol8": (0, 6), "nightCrew": 0.35},
    "서비스": {"ot": (0, 16), "night": (0, 10), "hol": (0, 24), "hol8": (0, 4), "nightCrew": 0.0},
}


def month_range(first, last):
    """'2026-07', '2026-12' → ['2026-07', ..., '2026-12']"""
    y, m = map(int, first.split("-"))
    out = []
    while True:
        out.append("%04d-%02d" % (y, m))
        if out[-1] == last:
            return out
        y, m = (y, m + 1) if m < 12 else (y + 1, 1)


def month_bounds(ym):
    y, m = map(int, ym.split("-"))
    start = dt.date(y, m, 1)
    end = (dt.date(y + (m == 12), m % 12 + 1, 1)) - dt.timedelta(days=1)
    return start, end


def active_in(e, ym):
    start, end = month_bounds(ym)
    return e["hireDate"] <= end and (e["resignDate"] is None or e["resignDate"] >= start)


def _dependents(e, rng):
    """family_type(합성 인물의 가구 형태)로 부양가족을 만든다. basic=기본공제 대상"""
    ft, birth = e["_familyType"] or "", e["birthDate"]
    deps = []
    if "배우자" in ft and "별거" not in ft:
        sb = birth.replace(year=birth.year + rng.randint(-3, 3), day=min(birth.day, 28))
        deps.append({"relation": "배우자", "birthDate": sb, "basic": rng.random() < 0.55})  # 맞벌이면 공제 안 됨
    if "자녀" in ft:
        for _ in range(rng.choices([1, 2, 3], [45, 45, 10])[0]):
            cy = birth.year + rng.randint(27, 38)
            if cy >= CUTOVER.year:
                continue
            cb = dt.date(cy, rng.randint(1, 12), rng.randint(1, 28))
            deps.append({"relation": "자녀", "birthDate": cb, "basic": TAX_YEAR - cb.year <= 20})
    if "부모" in ft or "어머니" in ft or "3세대" in ft:
        pb = dt.date(birth.year - rng.randint(25, 32), rng.randint(1, 12), rng.randint(1, 28))
        deps.append({"relation": "모" if "어머니" in ft or rng.random() < 0.5 else "부", "birthDate": pb,
                     "basic": TAX_YEAR - pb.year >= 60})
    return deps


LEDGER_DATE = dt.date(2026, 12, 31)  # 연차·퇴직금 등 '전환 직전' 기준일


def statutory_leave(hire, ref=LEDGER_DATE):
    """근로기준법 제60조: 1년 미만 월 1일(최대 11), 1년 이상 15일, 최초 1년 초과 2년마다 1일 가산, 최대 25일"""
    from .xl import datedif_m, datedif_y
    n = datedif_y(hire, ref)
    if n < 1:
        return min(datedif_m(hire, ref), 11)
    return min(15 + (n - 1) // 2, 25)


def _leave_usage(e, rng):
    """2026년에 쓴 연차 (근무일만, 일부 반차). 근태기록과 연차관리대장이 같이 쓴다"""
    if e["resignDate"] is not None or e["hireDate"] > LEDGER_DATE:
        return []
    start = max(dt.date(2026, 1, 1), e["hireDate"] + dt.timedelta(days=31))
    days = [start + dt.timedelta(days=i) for i in range((LEDGER_DATE - start).days + 1)]
    days = draw_days(days)
    target = rng.uniform(0.3, 0.95) * min(statutory_leave(e["hireDate"]), 15)
    out, total = [], 0.0
    for d in sorted(rng.sample(days, min(len(days), int(target) + 2))):
        if total + 1 > target:
            break
        kind = rng.choices(["연차", "오전반차", "오후반차"], [80, 10, 10])[0]
        n = 1.0 if kind == "연차" else 0.5
        out.append({"date": d, "kind": kind, "days": n, "reason": rng.choice(["개인사유", "가족행사", "병원", "여행", "휴식", "이사"])})
        total += n
    taken = {u["date"] for u in out}
    for u in out:  # 7/17 제헌절에 뽑힌 사용일 → 다음 근무일
        if is_holiday(u["date"]):
            u["date"] = shift_workday(u["date"], taken, LEDGER_DATE)
            taken.add(u["date"])
    return sorted(out, key=lambda u: u["date"])


def build_facts(company, employees, months, seed):
    """사번 → 사실. employees는 오류를 넣기 전 '깨끗한' 직원 목록"""
    rng = random.Random("%s:facts:%d" % (company["id"], seed))
    rounding = MONTHLY_BASE_ROUNDING[company["id"]][1]
    hours = HOURS[company["industry"]]
    facts = {}
    for e in employees:
        # 연봉은 만 원 단위, 12로 나누어 떨어지지 않게 해서 끝수 처리 규칙이 드러나게 한다
        annual = (e["_salary"] * 12 // 10_000 + rng.randint(-20, 20)) * 10_000 + rng.choice([0, 0, 5_000, 30_000])
        deps = _dependents(e, rng)
        night_crew = rng.random() < hours["nightCrew"]
        hrs = {}
        for ym in months:
            r = lambda k: rng.randint(*hours[k])
            hrs[ym] = {"ot": r("ot"), "night": r("night") + (rng.randint(30, 60) if night_crew else 0),
                       "hol": r("hol") if rng.random() < 0.4 else 0, "hol8": 0}
            if hrs[ym]["hol"] >= 8 and rng.random() < 0.5:
                hrs[ym]["hol8"] = r("hol8")
        facts[e["empNo"]] = {
            "annual2026": annual,
            "basePay": rounding(annual),
            "dependents": deps,
            "taxFamily": 1 + sum(d["basic"] for d in deps),  # 본인 포함
            "withholdRatio": rng.choices([100, 80, 120], [80, 10, 10])[0],
            "hours": hrs,
            "nightCrew": night_crew,
        }
    leave_rng = random.Random("%s:leave:%d" % (company["id"], seed))  # 따로 둬서 위 값들이 바뀌지 않게
    for e in employees:
        facts[e["empNo"]]["leave"] = _leave_usage(e, leave_rng)
    return facts
