"""
연차관리대장.xlsx — 직원별 발생·사용·잔여(수식) + 사용 내역 + 사용 촉진 기록.

회사마다 발생 기준이 다르고(입사일 / 회계연도), 일부 회사는 법정 기준과 다른 수식을 쓴다.
사용 내역은 facts의 연차 사용일(근태기록과 같은 날짜)에서 만든다.
"""
import datetime as dt

from .common import Accidents, clean, to_fullwidth
from .facts import LEDGER_DATE, is_holiday, statutory_leave
from .xl import ROUND, Book, datedif_m, datedif_y

BASE = LEDGER_DATE
NEEDS_FORMULA = ["FORMULA_READ", "HARDCODED_IN_FORMULA_COLUMN", "RECOMPUTE_CHECK"]


def build(company, roster, facts, out_dir, rng):
    members = sorted([e for e in roster if e["resignDate"] is None and e["hireDate"] <= BASE], key=lambda e: e["empNo"])
    uses = sorted(({"emp": e, **u, "intended": u["days"], "cell": u["days"]} for e in members for u in facts[e["empNo"]]["leave"]),
                  key=lambda u: (u["date"], u["emp"]["empNo"]))
    path = out_dir / "연차관리대장.xlsx"
    return {"ganatech": _gana, "daramulsan": _dara, "mabaservice": _maba}[company["id"]](members, uses, path, rng)


# ---------------------------------------------------------------- 공통 사고

def _move_to_holiday(u, uses):
    """사용일을 바로 뒤의 휴일(주말·공휴일)로 옮긴다: '휴일에 연차 사용' 사고"""
    taken = {x["date"] for x in uses if x["emp"] is u["emp"]}
    d = u["date"]
    while True:
        d += dt.timedelta(days=1)
        if d > BASE:
            d = u["date"] - dt.timedelta(days=1)
            while not is_holiday(d) or d in taken:
                d -= dt.timedelta(days=1)
            break
        if is_holiday(d) and d not in taken:
            break
    u["orig"], u["date"] = u["date"], d


def _employee_truth(members, uses, granted_fn):
    out = {}
    for e in members:
        used = sum(u["intended"] for u in uses if u["emp"] is e and not u.get("dup"))
        g = granted_fn(e)
        out[e["empNo"]] = {"granted": g, "statutory": statutory_leave(e["hireDate"]), "used": used}
    return out


def _promotions(members, uses, granted):
    """사용 촉진 대상: 7월 1일 기준 미사용 5일 이상 (촉진 시기는 달력 기준으로 단순화)"""
    rows = []
    for e in members:
        before = sum(u["intended"] for u in uses if u["emp"] is e and u["date"] < dt.date(2026, 7, 1) and not u.get("dup"))
        unused = granted[e["empNo"]] - before
        if e["hireDate"] < dt.date(2025, 7, 1) and unused >= 5:
            rows.append({"emp": e, "unused": unused})
    return rows


def _key(path, sheets, mapping, rules, members, truth, A, noise, legal):
    return clean({
        "file": path.name, "kind": "LEAVE", "closestEntity": "LEAVE_BALANCE", "supported": False,
        "sheetOrder": list(sheets), "sheets": sheets, "expectedMapping": mapping, "rules": rules,
        "legalDeviations": legal, "employees": truth, "accidents": A.items, "valueNoise": noise,
    })


# ---------------------------------------------------------------- 가나테크: 입사일 기준, 법정대로

def _gana(members, uses, path, rng):
    A = Accidents("GANA-LV", path.name)
    book = Book(path)
    s_sum, s_use, s_pro = book.sheet("연차현황"), book.sheet("사용내역"), book.sheet("사용촉진")

    granted = {e["empNo"]: statutory_leave(e["hireDate"]) for e in members}
    moved = rng.sample(uses[:-10], 2)
    for u in moved:
        _move_to_holiday(u, uses)
    fw = rng.choice([u for u in uses[:-10] if u["days"] == 1 and u not in moved])
    fw["cell"] = to_fullwidth(1)
    uses.sort(key=lambda u: (u["date"], u["emp"]["empNo"]))

    # 사용내역: 날짜순. 12월 말에 추가한 마지막 5행은 연차현황의 SUMIFS 범위 밖
    first, last = 2, 1 + len(uses)
    rl = last - 5
    for c, h in zip("ABCDEF", ("사번", "성명", "사용일", "구분", "일수", "사유")):
        s_use.put(c, 1, h, "head")
    for n, u in enumerate(uses):
        r = first + n
        u["row"] = r
        s_use.put("A", r, u["emp"]["empNo"], "text")
        s_use.put("B", r, u["emp"]["name"])
        s_use.put("C", r, u["date"])
        s_use.put("D", r, u["kind"])
        s_use.put("E", r, u["cell"])
        s_use.put("F", r, u["reason"])
    s_use.widths({"C": 12})
    for u in moved:
        A.add("USED_ON_HOLIDAY", "사용내역", "연차 사용일이 휴일(주말·공휴일)", ["HOLIDAY_CALENDAR"],
              empNo=u["emp"]["empNo"], rowNo=u["row"], cells=["C%d" % u["row"]], values={"사용일": {"actual": u["date"], "originalWorkday": u["orig"]}})
    A.add("TEXT_NUMBER", "사용내역", "일수를 전각 숫자 문자 '１'로 입력 → SUMIFS가 더하지 않아 사용일수 1일 누락",
          ["VALUE_NORMALIZE", "RECOMPUTE_CHECK"], empNo=fw["emp"]["empNo"], rowNo=fw["row"], cells=["E%d" % fw["row"]])
    tail = uses[-5:]
    A.add("SUM_RANGE_SHORT", "연차현황", "사용일수 SUMIFS 범위가 사용내역 %d행까지만 → 12월 말에 추가한 %d~%d행(5건)이 빠짐" % (rl, rl + 1, last),
          ["FORMULA_READ", "RECOMPUTE_CHECK", "CROSS_SHEET_LOOKUP"], cells=["H4:H%d" % (3 + len(members))],
          missedUsageRows=[u["row"] for u in tail], empNos=sorted({u["emp"]["empNo"] for u in tail}))

    used_actual = {e["empNo"]: 0.0 for e in members}
    for u in uses:
        if u["row"] <= rl and isinstance(u["cell"], (int, float)):
            used_actual[u["emp"]["empNo"]] += u["cell"]
    truth = _employee_truth(members, uses, lambda e: granted[e["empNo"]])

    # 연차현황
    s_sum.put("A", 1, "2026년 연차 관리대장 (입사일 기준, 2026-12-31 현재)", "title")
    heads = ("사번", "성명", "부서", "입사일", "근속연수", "근속월수", "발생일수", "사용일수", "잔여일수", "비고")
    for c, h in zip("ABCDEFGHIJ", heads):
        s_sum.put(c, 3, h, "head")
    dec_users = [e for e in members if any(u["emp"] is e and u["date"].month == 12 for u in uses)
                 and e["empNo"] not in {u["emp"]["empNo"] for u in tail + [fw]}]
    stale = set(x["empNo"] for x in rng.sample(dec_users, 2))
    row_of = {}
    for n, e in enumerate(members):
        r = 4 + n
        row_of[e["empNo"]] = r
        y, m = datedif_y(e["hireDate"], BASE), datedif_m(e["hireDate"], BASE)
        g = granted[e["empNo"]]
        s_sum.put("A", r, e["empNo"], "text")
        s_sum.put("B", r, e["name"])
        s_sum.put("C", r, e["dept"])
        s_sum.put("D", r, e["hireDate"])
        s_sum.formula("E", r, f'=DATEDIF(D{r},DATE(2026,12,31),"Y")', y)
        s_sum.formula("F", r, f'=DATEDIF(D{r},DATE(2026,12,31),"M")', m)
        s_sum.formula("G", r, f"=IF(E{r}<1,MIN(F{r},11),MIN(15+INT((E{r}-1)/2),25))", g)
        s_sum.formula("H", r, f"=SUMIFS(사용내역!$E$2:$E${rl},사용내역!$A$2:$A${rl},A{r})", used_actual[e["empNo"]], "num")
        remain = g - used_actual[e["empNo"]]
        if e["empNo"] in stale:
            before_dec = sum(u["intended"] for u in uses if u["emp"] is e and u["date"] < dt.date(2026, 12, 1))
            typed = g - before_dec
            s_sum.put("I", r, typed, "num")
            A.add("REMAIN_HARDCODED", "연차현황", "잔여일수를 수식 대신 11월 말 값으로 입력 → 잔여 ≠ 발생 − 사용 (12월 사용분 빠짐)",
                  NEEDS_FORMULA, empNo=e["empNo"], rowNo=r, cells=["I%d" % r], values={"잔여일수": {"actual": typed, "expected": remain}})
        else:
            s_sum.formula("I", r, f"=G{r}-H{r}", remain, "num")
        truth[e["empNo"]]["remain"] = g - truth[e["empNo"]]["used"]
    notes = rng.sample(members, 3)
    for e, text in zip(notes, ("１２월 이월 요청", "육아휴직 복귀 ２０２６.３.２", "장기근속 포상휴가 ３일 별도")):
        s_sum.put("J", row_of[e["empNo"]], text)
    s_sum.widths({"C": 14, "D": 12, "J": 24})

    # 사용촉진
    promos = _promotions(members, uses, granted)
    for c, h in zip("ABCDEFG", ("사번", "성명", "1차 통보일", "1차 통보 시 미사용일수", "사용계획 제출일", "2차 통보일", "비고")):
        s_pro.put(c, 1, h, "head")
    bad = rng.choice(promos)
    for n, p in enumerate(promos):
        r = 2 + n
        s_pro.put("A", r, p["emp"]["empNo"], "text")
        s_pro.put("B", r, p["emp"]["name"])
        s_pro.put("C", r, dt.date(2026, 7, 1))
        s_pro.put("D", r, p["unused"])
        planned = rng.random() < 0.7
        s_pro.put("E", r, dt.date(2026, 7, 10) if planned else None)
        second = None if planned else dt.date(2026, 10, 31)
        if p is bad:
            second = dt.date(2026, 6, 15)
            A.add("DATE_ORDER", "사용촉진", "2차 통보일이 1차 통보일보다 빠름", ["CROSS_FIELD_RULE"],
                  empNo=p["emp"]["empNo"], rowNo=r, cells=["C%d" % r, "F%d" % r])
        s_pro.put("F", r, second)

    book.close()
    rules = {
        "basis": "hireDate", "referenceDate": "DATE(2026,12,31) 수식에 직접",
        "underOneYear": {"perMonth": 1, "max": 11}, "base": 15,
        "seniorityIncrement": {"every": 2, "afterYears": 1}, "cap": 25, "firstYearProration": None,
        "usage": "SUMIFS(사용내역 일수, 사번)", "halfDay": 0.5, "remain": "granted - used", "carryOver": None,
        "formulas": {"granted": "=IF(E4<1,MIN(F4,11),MIN(15+INT((E4-1)/2),25))", "used": "=SUMIFS(사용내역!$E$2:$E$%d,사용내역!$A$2:$A$%d,A4)" % (rl, rl)},
    }
    sheets = {"연차현황": {"role": "leaveSummary", "headerRows": [3], "firstDataRow": 4, "lastDataRow": 3 + len(members), "rowOf": row_of},
              "사용내역": {"role": "leaveUsage", "headerRows": [1], "firstDataRow": 2, "lastDataRow": last},
              "사용촉진": {"role": "leavePromotion", "headerRows": [1], "firstDataRow": 2, "lastDataRow": 1 + len(promos)}}
    mapping = {"leaveSummary": dict(zip(heads, ("empNo", "name", "dept", "hireDate", "serviceYears", "serviceMonths", "granted", "used", "remain", "memo"))),
               "leaveUsage": {"사번": "empNo", "성명": "name", "사용일": "leaveDate", "구분": "leaveType", "일수": "days", "사유": "reason"},
               "leavePromotion": {"사번": "empNo", "성명": "name", "1차 통보일": "notice1Date", "1차 통보 시 미사용일수": "unusedAtNotice1",
                                  "사용계획 제출일": "planDate", "2차 통보일": "notice2Date", "비고": "memo"}}
    noise = [{"type": "FULLWIDTH_DIGITS", "sheet": "연차현황", "column": "비고", "empNos": sorted(e["empNo"] for e in notes)},
             {"type": "FULLWIDTH_DIGITS", "sheet": "사용내역", "column": "일수", "rowNo": fw["row"], "detail": "숫자 대신 문자 '１'"}]
    return _key(path, sheets, mapping, rules, members, truth, A, noise, [])


# ---------------------------------------------------------------- 다라물산: 회계연도 기준, 가산 누락

def _dara_granted(hire):
    if hire.year == 2026:
        return min(datedif_m(hire, BASE), 11)
    if hire.year == 2025:
        return ROUND(15 * ((dt.date(2025, 12, 31) - hire).days + 1) / 365, 1)
    return 15  # 3년차 이상 가산(2년마다 +1)을 빠뜨림


def _dara(members, uses, path, rng):
    A = Accidents("DARA-LV", path.name)
    book = Book(path)
    s_sum, s_use, s_pro = book.sheet("2026 연차대장"), book.sheet("연차사용"), book.sheet("촉진")
    for u in uses:
        u["kindCell"] = "연차" if u["kind"] == "연차" else "반차"

    moved = rng.sample(uses, 2)
    for u in moved:
        _move_to_holiday(u, uses)
    typo = rng.choice([u for u in uses if u["kindCell"] == "연차" and u not in moved])
    typo["kindCell"] = "연차 "
    dup_src = rng.choice([u for u in uses if u not in moved and u is not typo])
    dup = {**dup_src, "dup": True}
    uses.append(dup)
    uses.sort(key=lambda u: (u["date"], u["emp"]["empNo"], bool(u.get("dup"))))

    first, last = 2, 1 + len(uses)
    for c, h in zip("ABCDE", ("사번", "성명", "사용일", "구분", "비고")):
        s_use.put(c, 1, h, "head")
    for n, u in enumerate(uses):
        r = first + n
        u["row"] = r
        s_use.put("A", r, int(u["emp"]["empNo"]))
        s_use.put("B", r, u["emp"]["name"])
        s_use.put("C", r, u["date"])
        s_use.put("D", r, u["kindCell"])
        s_use.put("E", r, u["reason"])
    for u in moved:
        A.add("USED_ON_HOLIDAY", "연차사용", "연차 사용일이 휴일(주말·공휴일)", ["HOLIDAY_CALENDAR"],
              empNo=u["emp"]["empNo"], rowNo=u["row"], cells=["C%d" % u["row"]], values={"사용일": {"actual": u["date"], "originalWorkday": u["orig"]}})
    A.add("KIND_TYPO", "연차사용", "구분이 '연차 '(뒤에 공백) → COUNTIFS 조건 '연차'에 안 걸려 사용 1일 누락",
          ["VALUE_NORMALIZE", "RECOMPUTE_CHECK"], empNo=typo["emp"]["empNo"], rowNo=typo["row"], cells=["D%d" % typo["row"]])
    A.add("DUPLICATE_ROW", "연차사용", "같은 사람·같은 날짜 사용 기록이 두 번 → 사용일수가 %s일 더 잡힘" % ("1" if dup["kindCell"] != "반차" else "0.5"),
          ["DUPLICATE_DETECT"], empNo=dup["emp"]["empNo"], rowNo=dup["row"], cells=["A%d:E%d" % (dup["row"], dup["row"])])

    full = {e["empNo"]: 0 for e in members}
    half = {e["empNo"]: 0 for e in members}
    for u in uses:
        if u["kindCell"] == "연차":
            full[u["emp"]["empNo"]] += 1
        elif u["kindCell"] == "반차":
            half[u["emp"]["empNo"]] += 1
    truth = _employee_truth(members, uses, lambda e: _dara_granted(e["hireDate"]))

    s_sum.put("A", 1, "연차대장 (회계연도 기준, 2026.1.1 발생)", "title")
    heads = ("사번", "성명", "부서", "입사일", "입사연도", "발생", "연차사용", "반차사용(일)", "사용계", "잔여")
    for c, h in zip("ABCDEFGHIJ", heads):
        s_sum.put(c, 2, h, "head")
    odd = [e for e in members if _dara_granted(e["hireDate"]) != 15]
    hard = rng.sample(odd, min(2, len(odd)))
    row_of = {}
    granted_formula = ('=IF(E{r}=2026,MIN(DATEDIF(D{r},DATE(2026,12,31),"M"),11),'
                       'IF(E{r}=2025,ROUND(15*(DATE(2025,12,31)-D{r}+1)/365,1),15))')
    rng_a, rng_d = f"연차사용!$A$2:$A${last}", f"연차사용!$D$2:$D${last}"
    for n, e in enumerate(members):
        r = 3 + n
        row_of[e["empNo"]] = r
        g = _dara_granted(e["hireDate"])
        s_sum.put("A", r, int(e["empNo"]))
        s_sum.put("B", r, e["name"])
        s_sum.put("C", r, e["dept"])
        s_sum.put("D", r, e["hireDate"])
        s_sum.formula("E", r, f"=YEAR(D{r})", e["hireDate"].year)
        if e in hard:
            s_sum.put("F", r, 15)
            A.add("GRANTED_HARDCODED", "2026 연차대장", "발생을 수식 대신 15로 입력 (수식대로면 %s)" % g, NEEDS_FORMULA,
                  empNo=e["empNo"], rowNo=r, cells=["F%d" % r], values={"발생": {"actual": 15, "expected": g}})
            g_actual = 15
        else:
            s_sum.formula("F", r, granted_formula.format(r=r), g, "num")
            g_actual = g
        s_sum.formula("G", r, f'=COUNTIFS({rng_a},A{r},{rng_d},"연차")', full[e["empNo"]])
        s_sum.formula("H", r, f'=COUNTIFS({rng_a},A{r},{rng_d},"반차")*0.5', half[e["empNo"]] * 0.5, "num")
        used = full[e["empNo"]] + half[e["empNo"]] * 0.5
        s_sum.formula("I", r, f"=G{r}+H{r}", used, "num")
        s_sum.formula("J", r, f"=F{r}-I{r}", ROUND(g_actual - used, 1), "num")
        truth[e["empNo"]]["remain"] = ROUND(g - truth[e["empNo"]]["used"], 1)
        if truth[e["empNo"]]["remain"] < 0:
            A.add("NEGATIVE_REMAIN", "2026 연차대장", "회계연도 비례 발생보다 많이 사용해 잔여가 음수 (입사 첫해 비례 계산의 부작용)",
                  ["RECOMPUTE_CHECK", "LEGAL_RULE_COMPARE"], empNo=e["empNo"], rowNo=r, cells=["J%d" % r],
                  values={"잔여": {"actual": ROUND(g_actual - used, 1), "expected": truth[e["empNo"]]["remain"]}})
    s_sum.widths({"C": 14, "D": 12})
    legal_rows = [row_of[e["empNo"]] for e in members if truth[e["empNo"]]["statutory"] > _dara_granted(e["hireDate"]) and e["hireDate"].year <= 2024]
    legal = [{"rule": "seniorityIncrement", "company": "1년 이상은 근속과 관계없이 15일 (2년마다 1일 가산 없음)",
              "statutory": "최초 1년 초과 매 2년마다 1일 가산, 최대 25일 (근로기준법 제60조 제4항)",
              "favorableToEmployee": False, "sheet": "2026 연차대장", "column": "발생", "rows": legal_rows}]
    A.add("LEGAL_SENIORITY_MISSING", "2026 연차대장", "발생 수식에 근속 가산이 없음 → 3년 이상 근속자 %d명이 법정보다 적게 발생" % len(legal_rows),
          ["FORMULA_READ", "RULE_EXTRACTION", "LEGAL_RULE_COMPARE"], legal=True, cells=["F3:F%d" % (2 + len(members))], rows=legal_rows)

    promos = _promotions(members, uses, {e["empNo"]: _dara_granted(e["hireDate"]) for e in members})
    for c, h in zip("ABCD", ("사번", "성명", "1차 촉진일", "2차 촉진일")):
        s_pro.put(c, 1, h, "head")
    for n, p in enumerate(promos):
        r = 2 + n
        s_pro.put("A", r, int(p["emp"]["empNo"]))
        s_pro.put("B", r, p["emp"]["name"])
        s_pro.put("C", r, dt.date(2026, 7, 1))
        s_pro.put("D", r, dt.date(2026, 10, 31) if rng.random() < 0.4 else None)

    book.close()
    rules = {
        "basis": "fiscalYear", "referenceDate": "DATE(2026,12,31) 수식에 직접",
        "underOneYear": {"perMonth": 1, "max": 11}, "base": 15, "seniorityIncrement": None, "cap": None,
        "firstYearProration": "입사 다음 해: ROUND(15 × 전년 근무일수 / 365, 1)",
        "usage": "COUNTIFS(구분='연차') + COUNTIFS(구분='반차') × 0.5", "halfDay": 0.5, "remain": "granted - used", "carryOver": None,
        "formulas": {"granted": granted_formula.format(r=3), "used": f"=G3+H3"},
    }
    sheets = {"2026 연차대장": {"role": "leaveSummary", "headerRows": [2], "firstDataRow": 3, "lastDataRow": 2 + len(members), "rowOf": row_of},
              "연차사용": {"role": "leaveUsage", "headerRows": [1], "firstDataRow": 2, "lastDataRow": last},
              "촉진": {"role": "leavePromotion", "headerRows": [1], "firstDataRow": 2, "lastDataRow": 1 + len(promos)}}
    mapping = {"leaveSummary": dict(zip(heads, ("empNo", "name", "dept", "hireDate", "hireYear", "granted", "usedFullDays", "usedHalfDays", "used", "remain"))),
               "leaveUsage": {"사번": "empNo", "성명": "name", "사용일": "leaveDate", "구분": "leaveType", "비고": "reason"},
               "leavePromotion": {"사번": "empNo", "성명": "name", "1차 촉진일": "notice1Date", "2차 촉진일": "notice2Date"}}
    noise = [{"type": "EMPNO_NUMERIC", "scope": "모든 시트", "detail": "사번 숫자 셀 (앞자리 0 없음)"}]
    return _key(path, sheets, mapping, rules, members, truth, A, noise, legal)


# ---------------------------------------------------------------- 마바서비스: 입사일 기준, 25일 상한 없음, 이월

def _maba_granted(hire):
    n = datedif_y(hire, BASE)
    return min(datedif_m(hire, BASE), 11) if n < 1 else 15 + (n - 1) // 2


def _maba(members, uses, path, rng):
    A = Accidents("MABA-LV", path.name)
    book = Book(path)
    s_sum, s_use, s_pro, s_cfg = (book.sheet(n) for n in ("LEAVE_SUMMARY", "LEAVE_USAGE", "PROMOTION", "CONFIG"))
    types = {"연차": "ANNUAL", "오전반차": "HALF_AM", "오후반차": "HALF_PM"}

    moved = rng.sample(uses, 2)
    for u in moved:
        _move_to_holiday(u, uses)
    noemp = rng.choice([u for u in uses if u not in moved])
    uses.sort(key=lambda u: (u["date"], u["emp"]["empNo"]))
    first, last = 2, 1 + len(uses)
    for c, h in zip("ABCDEF", ("EMP_NO", "NAME", "LEAVE_DATE", "TYPE", "DAYS", "REASON")):
        s_use.put(c, 1, h, "head")
    for n, u in enumerate(uses):
        r = first + n
        u["row"] = r
        s_use.put("A", r, None if u is noemp else u["emp"]["empNo"], "text")
        s_use.put("B", r, u["emp"]["name"])
        s_use.put("C", r, u["date"])
        s_use.put("D", r, types[u["kind"]])
        s_use.put("E", r, u["days"])
        s_use.put("F", r, u["reason"])
    for u in moved:
        A.add("USED_ON_HOLIDAY", "LEAVE_USAGE", "연차 사용일이 휴일(주말·공휴일)", ["HOLIDAY_CALENDAR"],
              empNo=u["emp"]["empNo"], rowNo=u["row"], cells=["C%d" % u["row"]], values={"LEAVE_DATE": {"actual": u["date"], "originalWorkday": u["orig"]}})
    A.add("KEY_MISSING", "LEAVE_USAGE", "EMP_NO 없이 이름만 입력 → SUMIFS에 안 잡혀 사용 %s일 누락" % noemp["days"],
          ["CROSS_SHEET_LOOKUP", "NAME_MATCH", "RECOMPUTE_CHECK"], empNo=noemp["emp"]["empNo"], rowNo=noemp["row"], cells=["A%d" % noemp["row"]])

    s_cfg.put("A", 1, "KEY", "head")
    s_cfg.put("B", 1, "VALUE", "head")
    s_cfg.put("A", 2, "BASE_DATE")
    s_cfg.put("B", 2, BASE)
    s_cfg.put("A", 3, "CARRY_CAP")
    s_cfg.put("B", 3, 5)
    book.wb.define_name("BASE_DATE", "=CONFIG!$B$2")
    book.wb.define_name("CARRY_CAP", "=CONFIG!$B$3")

    used_actual = {e["empNo"]: 0.0 for e in members}
    for u in uses:
        if u is not noemp:
            used_actual[u["emp"]["empNo"]] += u["days"]
    truth = _employee_truth(members, uses, lambda e: _maba_granted(e["hireDate"]))
    heads = ("EMP_NO", "NAME", "DEPT", "HIRE_DATE", "YEARS", "MONTHS", "GRANTED", "CARRY_OVER", "USED", "REMAIN")
    for c, h in zip("ABCDEFGHIJ", heads):
        s_sum.put(c, 1, h, "head")
    carry = {e["empNo"]: (rng.randint(0, 5) if e["hireDate"].year <= 2024 else 0) for e in members}
    over = rng.choice([e for e in members if e["hireDate"].year <= 2024])
    carry_actual = dict(carry)
    carry_actual[over["empNo"]] = 8
    stale = rng.choice([e for e in members if e is not over and any(u["emp"] is e and u["date"].month == 12 for u in uses) and e is not noemp["emp"]])
    row_of = {}
    for n, e in enumerate(members):
        r = 2 + n
        row_of[e["empNo"]] = r
        g = _maba_granted(e["hireDate"])
        s_sum.put("A", r, e["empNo"], "text")
        s_sum.put("B", r, e["name"])
        s_sum.put("C", r, e["dept"])
        s_sum.put("D", r, e["hireDate"])
        s_sum.formula("E", r, f'=DATEDIF(D{r},BASE_DATE,"Y")', datedif_y(e["hireDate"], BASE))
        s_sum.formula("F", r, f'=DATEDIF(D{r},BASE_DATE,"M")', datedif_m(e["hireDate"], BASE))
        s_sum.formula("G", r, f"=IF(E{r}<1,MIN(F{r},11),15+INT((E{r}-1)/2))", g)
        s_sum.put("H", r, carry_actual[e["empNo"]])
        s_sum.formula("I", r, f"=SUMIFS(LEAVE_USAGE!$E${first}:$E${last},LEAVE_USAGE!$A${first}:$A${last},A{r})", used_actual[e["empNo"]], "num")
        remain = g + carry_actual[e["empNo"]] - used_actual[e["empNo"]]
        if e is stale:
            typed = g + carry[e["empNo"]] - sum(u["days"] for u in uses if u["emp"] is e and u["date"] < dt.date(2026, 12, 1))
            s_sum.put("J", r, typed, "num")
            A.add("REMAIN_HARDCODED", "LEAVE_SUMMARY", "REMAIN을 수식 대신 11월 말 값으로 입력 → 잔여 ≠ 발생 + 이월 − 사용",
                  NEEDS_FORMULA, empNo=e["empNo"], rowNo=r, cells=["J%d" % r], values={"REMAIN": {"actual": typed, "expected": remain}})
        else:
            s_sum.formula("J", r, f"=G{r}+H{r}-I{r}", remain, "num")
        truth[e["empNo"]]["carryOver"] = carry[e["empNo"]]
        truth[e["empNo"]]["remain"] = g + carry[e["empNo"]] - truth[e["empNo"]]["used"]
    A.add("CAP_EXCEEDED", "LEAVE_SUMMARY", "CARRY_OVER 8일 > 회사 이월 한도 CARRY_CAP 5일 (설정값과 입력값 불일치)",
          ["RULE_EXTRACTION", "CROSS_FIELD_RULE"], empNo=over["empNo"], rowNo=row_of[over["empNo"]], cells=["H%d" % row_of[over["empNo"]]],
          values={"CARRY_OVER": {"actual": 8, "expected": carry[over["empNo"]]}})
    s_sum.widths({"C": 14, "D": 12})
    long_rows = [row_of[e["empNo"]] for e in members if _maba_granted(e["hireDate"]) > 25]
    legal = [{"rule": "cap", "company": "25일 상한 없음 (MIN 없이 15+INT((근속-1)/2))", "statutory": "최대 25일 (근로기준법 제60조 제4항)",
              "favorableToEmployee": True, "note": "법보다 많이 주는 쪽이라 위법은 아니지만 법정 공식과 다름",
              "sheet": "LEAVE_SUMMARY", "column": "GRANTED", "rows": long_rows}]
    A.add("LEGAL_CAP_MISSING", "LEAVE_SUMMARY", "GRANTED 수식에 25일 상한이 없음 → 장기 근속자 %d명이 25일 초과" % len(long_rows),
          ["FORMULA_READ", "RULE_EXTRACTION", "LEGAL_RULE_COMPARE"], legal=True, cells=["G2:G%d" % (1 + len(members))], rows=long_rows)

    granted = {e["empNo"]: _maba_granted(e["hireDate"]) for e in members}
    promos = _promotions(members, uses, granted)
    skip = max(promos, key=lambda p: p["unused"])
    for c, h in zip("ABCDEF", ("EMP_NO", "NAME", "NOTICE1_DATE", "UNUSED_AT_NOTICE1", "PLAN_DATE", "NOTICE2_DATE")):
        s_pro.put(c, 1, h, "head")
    n = 0
    for p in promos:
        if p is skip:
            continue
        r = 2 + n
        n += 1
        s_pro.put("A", r, p["emp"]["empNo"], "text")
        s_pro.put("B", r, p["emp"]["name"])
        s_pro.put("C", r, dt.date(2026, 7, 1))
        s_pro.put("D", r, p["unused"])
        planned = rng.random() < 0.6
        s_pro.put("E", r, dt.date(2026, 7, 10) if planned else None)
        s_pro.put("F", r, None if planned else dt.date(2026, 10, 31))
    A.add("PROMOTION_MISSING", "PROMOTION", "7월 1일 미사용 %s일로 촉진 대상인데 촉진 기록이 없음 (미사용 수당 면제 근거 없음)" % skip["unused"],
          ["CROSS_SHEET_LOOKUP", "LEGAL_RULE_COMPARE"], empNo=skip["emp"]["empNo"])

    book.close()
    rules = {
        "basis": "hireDate", "referenceDate": "이름 정의 BASE_DATE (CONFIG!B2)",
        "underOneYear": {"perMonth": 1, "max": 11}, "base": 15,
        "seniorityIncrement": {"every": 2, "afterYears": 1}, "cap": None, "firstYearProration": None,
        "usage": "SUMIFS(DAYS, EMP_NO)", "halfDay": 0.5, "remain": "granted + carryOver - used", "carryOver": {"cap": 5, "capFrom": "이름 정의 CARRY_CAP (수식에서 쓰지 않음)"},
        "formulas": {"granted": "=IF(E2<1,MIN(F2,11),15+INT((E2-1)/2))", "remain": "=G2+H2-I2"},
    }
    sheets = {"LEAVE_SUMMARY": {"role": "leaveSummary", "headerRows": [1], "firstDataRow": 2, "lastDataRow": 1 + len(members), "rowOf": row_of},
              "LEAVE_USAGE": {"role": "leaveUsage", "headerRows": [1], "firstDataRow": 2, "lastDataRow": last},
              "PROMOTION": {"role": "leavePromotion", "headerRows": [1], "firstDataRow": 2, "lastDataRow": 1 + n},
              "CONFIG": {"role": "settings", "values": {"BASE_DATE": BASE, "CARRY_CAP": 5}}}
    mapping = {"leaveSummary": dict(zip(heads, ("empNo", "name", "dept", "hireDate", "serviceYears", "serviceMonths", "granted", "carryOver", "used", "remain"))),
               "leaveUsage": {"EMP_NO": "empNo", "NAME": "name", "LEAVE_DATE": "leaveDate", "TYPE": "leaveType", "DAYS": "days", "REASON": "reason"},
               "leavePromotion": {"EMP_NO": "empNo", "NAME": "name", "NOTICE1_DATE": "notice1Date", "UNUSED_AT_NOTICE1": "unusedAtNotice1",
                                  "PLAN_DATE": "planDate", "NOTICE2_DATE": "notice2Date"}}
    return _key(path, sheets, mapping, rules, members, truth, A, [], legal)
