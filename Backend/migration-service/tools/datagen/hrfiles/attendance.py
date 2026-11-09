"""
근태기록.xlsx (12월 일별 출퇴근 + 주별·월별 집계) / 연장근무신청대장.xlsx (연장·휴일근무 신청·승인).

전환 직전 한 달(2026-12, 약 4.4주). 급여대장 2026-12 시트의 연장·휴일 시간과 맞도록 만든다:
일별 연장(분)을 모아 회사 규칙대로 끝수 처리하면 급여대장의 연장시간이 된다 (야간 시간은 맞추지 않음).
"""
import datetime as dt
import math

from .common import Accidents, clean
from .facts import is_holiday
from .xl import ROUND, Book

MONTH = "2026-12"
DAYS = [dt.date(2026, 12, d) for d in range(1, 32)]
WEEK_OF = {d: (d - dt.date(2026, 11, 30)).days // 7 + 1 for d in DAYS}  # 월요일 시작 주차 1~5
WEEKDAY = "월화수목금토일"


def _hm(m):
    return dt.time((m // 60) % 24, m % 60)


COMPANY = {
    "ganatech": {"start": (520, 540), "end": 1080, "otExtra": (0, 9), "order": "date",
                 "monthlyRounding": ("floor10", "월 합산 후 10분 미만 버림", lambda raw: raw // 10 * 10)},
    "daramulsan": {"start": (465, 480), "end": 1020, "otExtra": (0, 29), "order": "emp",
                   "monthlyRounding": ("round60", "월 합산 후 30분 미만 버림·30분 이상 1시간 (시간 단위 반올림)", lambda raw: ROUND(raw / 60, 0) * 60)},
    "mabaservice": {"start": (510, 600), "end": None, "otExtra": None, "order": "date",
                    "monthlyRounding": ("dailyFloor15", "일별 연장을 15분 단위로 버린 뒤 합산", lambda raw: raw)},
}


# ---------------------------------------------------------------- 하루 단위 계획

def _split(total, k, rng, unit, minimum):
    """total을 k개로 나눈다 (각 minimum 이상, unit 단위로 무작위 배분 + 끝수는 마지막)"""
    if k == 0 or total <= 0:
        return []
    k = max(1, min(k, total // minimum))
    parts = [minimum] * k
    left = total - minimum * k
    while left >= unit:
        parts[rng.randrange(k)] += unit
        left -= unit
    parts[-1] += left
    return parts


BASE_DAY = {"ganatech": 500, "daramulsan": 495, "mabaservice": 480}  # 평일 정상 근무(분)의 최댓값
WEEK_LIMIT = 52 * 60


def _plan_person(cid, e, f, rng, concentrate):
    """한 사람의 12월 일별 행. 연장·휴일 시간 합계는 급여대장 12월과 같고, 주 52시간은 넘지 않게 배분한다
    (concentrate=True면 연장을 한 주에 몰아 52시간을 일부러 넘긴다)"""
    cfg = COMPANY[cid]
    hrs = f["hours"][MONTH]
    days = [d for d in DAYS if d >= e["hireDate"] and (e["resignDate"] is None or d <= e["resignDate"])]
    leave = {u["date"]: u["kind"] for u in f["leave"] if u["date"].month == 12 and u["date"].year == 2026}
    full = [d for d in days if not is_holiday(d) and d not in leave]

    # 휴일근무: 8시간 초과분이 있으면 한 날에 몰아서
    hol_days, left = [], hrs["hol"]
    if hrs["hol8"]:
        hol_days.append(480 + hrs["hol8"] * 60)
        left -= 8
    while left > 0:
        hol_days.append(min(8, left) * 60)
        left -= min(8, left)
    # 주별 여유 = 52시간 - 평일 기본근무 - 반차 - 휴일근무
    cap = {w: WEEK_LIMIT for w in range(1, 6)}
    for d in days:
        if d in full:
            cap[WEEK_OF[d]] -= BASE_DAY[cid]
        elif leave.get(d) in ("오전반차", "오후반차"):
            cap[WEEK_OF[d]] -= 240
    weekend = [d for d in days if is_holiday(d)]
    rng.shuffle(weekend)
    hol = {}
    for m in sorted(hol_days, reverse=True):  # 여유가 가장 큰 주의 주말부터
        free = [d for d in weekend if d not in hol]
        if not free:
            break
        d = max(free, key=lambda x: cap[WEEK_OF[x]])
        hol[d] = m
        cap[WEEK_OF[d]] -= m

    # 연장: 급여대장 연장시간 × 60 + 회사 끝수 규칙에 걸려 사라질 만큼의 분
    pool = _biggest_week(full) if concentrate else full
    if cid == "mabaservice":
        units = hrs["ot"] * 4  # 15분 단위로 버리므로 하루 연장 = 15분 × n + 0~14분
        k = min(len(pool), math.ceil(units / 6)) if units else 0
        parts = [p * 15 + rng.randint(0, 14) for p in _split(units, k, rng, 1, 1)]
    else:
        total = hrs["ot"] * 60 + (rng.randint(*cfg["otExtra"]) if hrs["ot"] else 0)
        k = min(len(pool), math.ceil(total / 90)) if total else 0
        parts = _split(total, k, rng, 5, 10)
    ot = dict(zip(pool, parts)) if concentrate else _assign(parts, full, cap, rng)

    rows = []
    end_of = lambda s: cfg["end"] if cfg["end"] is not None else s + 540  # noqa: E731
    for d in days:
        kind = leave.get(d)
        if kind == "연차":
            rows.append({"date": d, "type": "연차", "in": None, "out": None, "break": 0})
        elif kind in ("오전반차", "오후반차"):
            s = cfg["start"][1]
            s = end_of(s) - 240 if kind == "오전반차" else s  # 오전 쉬면 정시 4시간 전 출근
            rows.append({"date": d, "type": kind, "in": s, "out": s + 240, "break": 0})
        elif not is_holiday(d):
            s = rng.randint(*cfg["start"])
            out = end_of(s) + ot.get(d, 0)
            assert cid == "daramulsan" or out < 1440, "자정을 넘는 퇴근은 다라물산 양식만 계산할 수 있다"
            rows.append({"date": d, "type": "정상", "in": s, "out": out % 1440 if cid == "daramulsan" else out, "break": 60})
        elif d in hol:
            m = hol[d]
            brk = 60 if m >= 480 else (30 if m >= 240 else 0)
            rows.append({"date": d, "type": "휴일근무", "in": 600, "out": 600 + m + brk, "break": brk})
    missing_hol = len(hol_days) - len(hol)
    return rows, ot, missing_hol


def _biggest_week(full):
    by_week = {}
    for d in full:
        by_week.setdefault(WEEK_OF[d], []).append(d)
    return sorted(max(by_week.values(), key=len)) if by_week else []


def _assign(parts, full, cap, rng):
    """연장 분 덩어리를 날짜에 하나씩 배정. 큰 덩어리부터 여유가 가장 큰 주로"""
    free = list(full)
    rng.shuffle(free)
    out = {}
    for p in sorted(parts, reverse=True):
        d = max(free, key=lambda x: cap[WEEK_OF[x]])  # 여유가 가장 큰 주
        out[d] = p
        cap[WEEK_OF[d]] -= p
        free.remove(d)
    return out


# ---------------------------------------------------------------- 일별 계산 (엑셀 수식과 같은 값)

def _calc(cid, r, row, L):
    """한 행의 (수식, 값) 목록: work·ot·hol·hol8 (분)"""
    i, o, b, t = row["in"], row["out"], row["break"], row["type"]
    c = L
    if cid == "ganatech":
        work = (o or 0) - (i or 0) - b
        ot = 0 if t == "휴일근무" else max(0, (o or 0) - 1080)
        f_work = f"=ROUND(({c['out']}{r}-{c['in']}{r})*1440,0)-{c['break']}{r}"
        f_ot = f'=IF({c["type"]}{r}="휴일근무",0,MAX(0,ROUND(({c["out"]}{r}-TIME(18,0,0))*1440,0)))'
        hol_t = '"휴일근무"'
    elif cid == "daramulsan":
        blank = i is None or o is None
        work = 0 if blank else (o - i) % 1440 - b
        ot = 0 if (t == "휴일근무" or o is None) else max(0, o + 1440 * (o < (i or 0)) - 1020)
        f_work = f'=IF(OR({c["in"]}{r}="",{c["out"]}{r}=""),0,ROUND(MOD({c["out"]}{r}-{c["in"]}{r},1)*1440,0)-{c["break"]}{r})'
        f_ot = (f'=IF(OR({c["type"]}{r}="휴일근무",{c["out"]}{r}=""),0,'
                f'MAX(0,ROUND(({c["out"]}{r}+({c["out"]}{r}<{c["in"]}{r})-TIME(17,0,0))*1440,0)))')
        hol_t = '"휴일근무"'
    else:
        work = (o or 0) - (i or 0) - b
        ot = 0 if t == "HOLIDAY" else max(0, work - 480) // 15 * 15
        f_work = f"=ROUND(({c['out']}{r}-{c['in']}{r})*1440,0)-{c['break']}{r}"
        f_ot = f'=IF({c["type"]}{r}="HOLIDAY",0,ROUNDDOWN(MAX(0,{c["work"]}{r}-480)/15,0)*15)'
        hol_t = '"HOLIDAY"'
    is_hol = t in ("휴일근무", "HOLIDAY")
    hol = min(work, 480) if is_hol else 0
    hol8 = max(0, work - 480) if is_hol else 0
    f_hol = f"=IF({c['type']}{r}={hol_t},MIN({c['work']}{r},480),0)"
    f_hol8 = f"=IF({c['type']}{r}={hol_t},MAX(0,{c['work']}{r}-480),0)"
    return {"work": (f_work, work), "ot": (f_ot, ot), "hol": (f_hol, hol), "hol8": (f_hol8, hol8)}


LAYOUT = {
    "ganatech": {"sheets": ("일별근태", "주간집계", "월집계"),
                 "cols": [("date", "일자"), ("dow", "요일"), ("empNo", "사번"), ("name", "성명"), ("dept", "부서"), ("type", "구분"),
                          ("in", "출근"), ("out", "퇴근"), ("break", "휴게(분)"), ("week", "주차"), ("work", "근무(분)"),
                          ("ot", "연장(분)"), ("hol", "휴일(분)"), ("hol8", "휴일8h초과(분)")],
                 "types": {"정상": "정상", "연차": "연차", "오전반차": "오전반차", "오후반차": "오후반차", "휴일근무": "휴일근무"},
                 "title": "2026년 12월 근태기록 (출퇴근 단말기 내려받기 + 수기 보정)"},
    "daramulsan": {"sheets": ("근태", "주별", "월별"),
                   "cols": [("empNo", "사번"), ("name", "성명"), ("date", "근무일"), ("type", "근무구분"), ("in", "출근시각"),
                            ("out", "퇴근시각"), ("break", "휴게"), ("week", "주"), ("work", "실근로(분)"), ("ot", "연장(분)"),
                            ("hol", "휴일(분)"), ("hol8", "휴일초과(분)")],
                   "types": {"정상": "정상", "연차": "연차", "오전반차": "반차", "오후반차": "반차", "휴일근무": "휴일근무"},
                   "title": None},
    "mabaservice": {"sheets": ("DAILY", "WEEKLY", "MONTHLY"),
                    "cols": [("date", "WORK_DATE"), ("empNo", "EMP_NO"), ("name", "NAME"), ("dept", "DEPT"), ("type", "TYPE"),
                             ("in", "CLOCK_IN"), ("out", "CLOCK_OUT"), ("break", "BREAK_MIN"), ("week", "WEEK"),
                             ("work", "WORK_MIN"), ("ot", "OT_MIN"), ("hol", "HOL_MIN"), ("hol8", "HOL_OVER8_MIN")],
                    "types": {"정상": "NORMAL", "연차": "ANNUAL", "오전반차": "HALF_AM", "오후반차": "HALF_PM", "휴일근무": "HOLIDAY"},
                    "title": None},
}


# ---------------------------------------------------------------- 만들기

def build(company, roster, facts, out_dir, rng, payroll_key):
    cid = company["id"]
    lay = LAYOUT[cid]
    L = {k: chr(ord("A") + i) for i, (k, _) in enumerate(lay["cols"])}
    members = sorted([e for e in roster if e["hireDate"] <= DAYS[-1] and (e["resignDate"] is None or e["resignDate"] >= DAYS[0])],
                     key=lambda e: e["empNo"])
    A = Accidents("%s-AT" % cid[:4].upper(), "근태기록.xlsx")
    R = Accidents("%s-OT" % cid[:4].upper(), "연장근무신청대장.xlsx")

    heavy = [e for e in members if facts[e["empNo"]]["hours"][MONTH]["ot"] >= 14 and e["hireDate"] < DAYS[0]]
    n52 = {"ganatech": 1, "daramulsan": 2, "mabaservice": 1}[cid]
    conc = {e["empNo"] for e in rng.sample(heavy, min(n52, len(heavy)))}
    rows, plans, cross_exceptions = [], {}, []
    for e in members:
        prs, ot, missing_hol = _plan_person(cid, e, facts[e["empNo"]], rng, e["empNo"] in conc)
        plans[e["empNo"]] = ot
        if missing_hol:
            cross_exceptions.append({"empNo": e["empNo"], "detail": "입사가 늦어 12월 주말이 모자라 휴일근무 %d일을 근태기록에 못 넣음" % missing_hol})
        for p in prs:
            rows.append({"emp": e, **p})

    # 사고 (행 단위). 연장이 없는 평일 정상 행에 넣어 급여 대조를 흐리지 않는다
    plain = [x for x in rows if x["type"] == "정상" and x["date"] not in plans[x["emp"]["empNo"]]]
    picks = rng.sample(plain, 3)
    acc_rows = {}
    if cid in ("ganatech", "daramulsan"):
        x = picks[0]
        x["out"] = None
        acc_rows["OUT_MISSING"] = x
    if cid in ("ganatech", "mabaservice"):
        x = picks[1]
        x["in"], x["out"] = x["out"], x["in"]
        acc_rows["IN_AFTER_OUT"] = x
    if cid in ("ganatech", "mabaservice"):
        lv = [y for y in rows if y["type"] == "연차" and y["emp"]["empNo"] not in conc]
        x = rng.choice(lv)
        x.update(type="정상", **{"in": COMPANY[cid]["start"][1], "out": (COMPANY[cid]["end"] or COMPANY[cid]["start"][1] + 540), "break": 60})
        acc_rows["LEAVE_CONFLICT"] = x
    if cid == "daramulsan":
        src = rng.choice([y for y in rows if y["type"] == "정상" and y["date"] in plans[y["emp"]["empNo"]]])
        dup = {**src, "dup": True}
        rows.append(dup)
        acc_rows["DUPLICATE_PUNCH"] = dup

    order = (lambda x: (x["date"], x["emp"]["empNo"], bool(x.get("dup")))) if COMPANY[cid]["order"] == "date" \
        else (lambda x: (x["emp"]["empNo"], x["date"], bool(x.get("dup"))))
    rows.sort(key=order)

    path = out_dir / "근태기록.xlsx"
    book = Book(path)
    s_day, s_week, s_mon = (book.sheet(n) for n in lay["sheets"])
    hr = 3 if lay["title"] else 1
    if lay["title"]:
        s_day.put("A", 1, lay["title"], "title")
    for k, h in lay["cols"]:
        s_day.put(L[k], hr, h, "head")
    first = hr + 1
    for n, x in enumerate(rows):
        r = first + n
        x["row"] = r
        e = x["emp"]
        vals = {"date": x["date"], "dow": WEEKDAY[x["date"].weekday()], "empNo": int(e["empNo"]) if cid == "daramulsan" else e["empNo"],
                "name": e["name"], "dept": e["dept"], "type": lay["types"][x["type"]], "week": WEEK_OF[x["date"]], "break": x["break"]}
        for k, v in vals.items():
            if k in L:
                s_day.put(L[k], r, v, "text" if k == "empNo" and isinstance(v, str) else None)
        for k in ("in", "out"):
            s_day.put(L[k], r, None if x[k] is None else _hm(x[k]), "time")
        x["type"] = lay["types"][x["type"]]
        calc = _calc(cid, r, x, L)
        for k, (f, v) in calc.items():
            s_day.formula(L[k], r, f, v)
            x[k + "Min"] = v
    last = first + len(rows) - 1
    s_day.ws.freeze_panes(hr, 0)
    s_day.widths({L["date"]: 11, L["dept"]: 12} if "dept" in L else {L["date"]: 11})

    # 사고 기록
    if "OUT_MISSING" in acc_rows:
        x = acc_rows["OUT_MISSING"]
        detail = ("퇴근 시각 누락 → 근무(분)가 음수 %d" % x["workMin"]) if cid == "ganatech" else "퇴근 시각 누락 → 수식이 빈 칸을 0으로 처리해 그날 근무가 조용히 0분"
        A.add("OUT_MISSING", lay["sheets"][0], detail, ["REQUIRED_PAIR", "RECOMPUTE_CHECK"], empNo=x["emp"]["empNo"], rowNo=x["row"],
              cells=["%s%d" % (L["out"], x["row"])], values={"work": {"actual": x["workMin"]}})
    if "IN_AFTER_OUT" in acc_rows:
        x = acc_rows["IN_AFTER_OUT"]
        A.add("IN_AFTER_OUT", lay["sheets"][0], "출근·퇴근 시각이 뒤바뀜 (출근 > 퇴근) → 근무(분) %d" % x["workMin"],
              ["CROSS_FIELD_RULE", "RECOMPUTE_CHECK"], empNo=x["emp"]["empNo"], rowNo=x["row"],
              cells=["%s%d" % (L["in"], x["row"]), "%s%d" % (L["out"], x["row"])])
    if "LEAVE_CONFLICT" in acc_rows:
        x = acc_rows["LEAVE_CONFLICT"]
        A.add("LEAVE_CONFLICT", lay["sheets"][0], "연차관리대장에는 연차 사용일인데 근태기록은 정상 출퇴근", ["CROSS_FILE_MATCH"],
              empNo=x["emp"]["empNo"], rowNo=x["row"], date=x["date"], otherFile="연차관리대장.xlsx")
    if "DUPLICATE_PUNCH" in acc_rows:
        x = acc_rows["DUPLICATE_PUNCH"]
        A.add("DUPLICATE_ROW", lay["sheets"][0], "같은 사람·같은 날 출퇴근 행이 두 번 → 연장 %d분이 두 번 집계돼 급여대장 연장시간과 어긋남" % x["otMin"],
              ["DUPLICATE_DETECT", "CROSS_FILE_MATCH"], empNo=x["emp"]["empNo"], rowNo=x["row"])

    # 주별·월별 집계
    rng_of = lambda k: f"{lay['sheets'][0]}!${L[k]}${first}:${L[k]}${last}"  # noqa: E731
    key_col = rng_of("empNo")
    week_head = {"ganatech": ("사번", "성명", "1주", "2주", "3주", "4주", "5주", "52h초과"),
                 "daramulsan": ("사번", "성명", "1주(12/1~6)", "2주", "3주", "4주", "5주(12/28~31)", "주52시간"),
                 "mabaservice": ("EMP_NO", "NAME", "W1_HRS", "W2_HRS", "W3_HRS", "W4_HRS", "W5_HRS", "OVER_52")}[cid]
    for c, h in zip("ABCDEFGH", week_head):
        s_week.put(c, 1, h, "head")
    over52 = []
    rowof_week, rowof_mon = {}, {}
    for n, e in enumerate(members):
        r = 2 + n
        rowof_week[e["empNo"]] = r
        key = int(e["empNo"]) if cid == "daramulsan" else e["empNo"]
        s_week.put("A", r, key, None if cid == "daramulsan" else "text")
        s_week.put("B", r, e["name"])
        weekly = []
        for w in range(1, 6):
            mins = sum(x["workMin"] for x in rows if x["emp"] is e and WEEK_OF[x["date"]] == w)
            h = ROUND(mins / 60, 1)
            weekly.append(h)
            s_week.formula("CDEFG"[w - 1], r, f"=ROUND(SUMIFS({rng_of('work')},{key_col},A{r},{rng_of('week')},{w})/60,1)", h, "num")
        flag = "초과" if max(weekly) > 52 else ""
        if cid == "mabaservice":
            flag = "Y" if max(weekly) > 52 else ""
        s_week.formula("H", r, f'=IF(MAX(C{r}:G{r})>52,"{"Y" if cid == "mabaservice" else "초과"}","")', flag)
        if flag:
            over52.append({"empNo": e["empNo"], "rowNo": r, "weeks": {w + 1: h for w, h in enumerate(weekly) if h > 52}, "injected": e["empNo"] in conc})
    dup_emp = acc_rows["DUPLICATE_PUNCH"]["emp"]["empNo"] if "DUPLICATE_PUNCH" in acc_rows else None
    for o in over52:
        why = "" if o["injected"] else (" — 중복 행이 더해져서" if o["empNo"] == dup_emp else " — 연장·휴일근무가 많아 주별 배분으로도 못 피함")
        A.add("WEEKLY_52H_EXCEEDED", lay["sheets"][1], "주 52시간 초과 근무 (근로기준법 제53조)" + why,
              ["LEGAL_RULE_COMPARE", "RECOMPUTE_CHECK"], legal=True, empNo=o["empNo"], rowNo=o["rowNo"], weeks=o["weeks"])

    code, desc, fn = COMPANY[cid]["monthlyRounding"]
    mon_head = {"ganatech": ("사번", "성명", "근무시간", "연장(분) 합계", "인정 연장(분)", "인정 연장(시간)", "휴일(분)", "휴일8h초과(분)"),
                "daramulsan": ("사번", "성명", "근로시간", "연장분합계", "인정연장(분)", "연장시간", "휴일분", "휴일초과분"),
                "mabaservice": ("EMP_NO", "NAME", "WORK_HRS", "OT_MIN_RAW", "OT_MIN", "OT_HRS", "HOL_MIN", "HOL_OVER8_MIN")}[cid]
    for c, h in zip("ABCDEFGH", mon_head):
        s_mon.put(c, 1, h, "head")
    pay_rows = payroll_key["sheets"][MONTH]["rowOf"]
    recon = []
    for n, e in enumerate(members):
        r = 2 + n
        rowof_mon[e["empNo"]] = r
        mine = [x for x in rows if x["emp"] is e]
        key = int(e["empNo"]) if cid == "daramulsan" else e["empNo"]
        s_mon.put("A", r, key, None if cid == "daramulsan" else "text")
        s_mon.put("B", r, e["name"])
        work = ROUND(sum(x["workMin"] for x in mine) / 60, 1)
        raw = sum(x["otMin"] for x in mine)
        rec = fn(raw)
        hol = sum(x["holMin"] for x in mine)
        hol8 = sum(x["hol8Min"] for x in mine)
        s_mon.formula("C", r, f"=ROUND(SUMIFS({rng_of('work')},{key_col},A{r})/60,1)", work, "num")
        s_mon.formula("D", r, f"=SUMIFS({rng_of('ot')},{key_col},A{r})", raw)
        f_rec = {"floor10": f"=ROUNDDOWN(D{r}/10,0)*10", "round60": f"=ROUND(D{r}/60,0)*60", "dailyFloor15": f"=D{r}"}[code]
        s_mon.formula("E", r, f_rec, rec)
        s_mon.formula("F", r, f"=E{r}/60", rec / 60, "num")
        s_mon.formula("G", r, f"=SUMIFS({rng_of('hol')},{key_col},A{r})", hol)
        s_mon.formula("H", r, f"=SUMIFS({rng_of('hol8')},{key_col},A{r})", hol8)
        h = facts[e["empNo"]]["hours"][MONTH]
        ok = rec == h["ot"] * 60 and hol == h["hol"] * 60 and hol8 == h["hol8"] * 60
        if e["empNo"] in pay_rows:
            recon.append({"empNo": e["empNo"], "payrollRow": pay_rows[e["empNo"]], "monthlyRow": r, "otHours": rec / 60,
                          "payrollOtHours": h["ot"], "holHours": hol / 60, "payrollHolHours": h["hol"], "hol8Hours": hol8 / 60,
                          "payrollHol8Hours": h["hol8"], "match": ok})
    book.close()

    # ---------------- 연장근무신청대장
    reg_key = _register(cid, members, rows, plans, out_dir, rng, R)

    sheets = {lay["sheets"][0]: {"role": "dailyAttendance", "headerRows": [hr], "firstDataRow": first, "lastDataRow": last},
              lay["sheets"][1]: {"role": "weeklySummary", "headerRows": [1], "firstDataRow": 2, "lastDataRow": 1 + len(members), "rowOf": rowof_week},
              lay["sheets"][2]: {"role": "monthlySummary", "headerRows": [1], "firstDataRow": 2, "lastDataRow": 1 + len(members), "rowOf": rowof_mon}}
    fields = {"date": "workDate", "dow": "dayOfWeek", "empNo": "empNo", "name": "name", "dept": "dept", "type": "workType",
              "in": "clockIn", "out": "clockOut", "break": "breakMinutes", "week": "week", "work": "workMinutes",
              "ot": "overtimeMinutes", "hol": "holidayMinutes", "hol8": "holidayOver8Minutes"}
    legal = []
    if code in ("floor10", "dailyFloor15"):
        legal.append({"rule": "overtimeRounding", "company": desc,
                      "statutory": "실제 근로시간 전부 산입. 행정해석은 월 합산 후 30분 미만 버림·30분 이상 1시간 올림만 인정",
                      "favorableToEmployee": False, "sheet": lay["sheets"][2] if code == "floor10" else lay["sheets"][0],
                      "column": mon_head[4] if code == "floor10" else "OT_MIN"})
    att_key = clean({
        "file": "근태기록.xlsx", "kind": "ATTENDANCE", "closestEntity": None, "supported": False,
        "period": {"from": DAYS[0], "to": DAYS[-1], "note": "전환 직전 한 달 (12월, 월요일 시작 주차 1~5)"},
        "sheetOrder": list(lay["sheets"]), "sheets": sheets,
        "expectedMapping": {"dailyAttendance": {h: fields[k] for k, h in lay["cols"]}},
        "rules": {
            "workMinutes": {"ganatech": "(퇴근-출근)×1440 - 휴게", "daramulsan": "MOD(퇴근-출근,1)×1440 - 휴게, 출퇴근 빈 칸이면 0",
                            "mabaservice": "(OUT-IN)×1440 - BREAK_MIN"}[cid],
            "overtimeBasis": {"ganatech": "정시(18:00) 이후 퇴근 분", "daramulsan": "17:00 이후 퇴근 분 (자정 넘으면 다음날로 계산)",
                              "mabaservice": "하루 근무 480분 초과분 (유연근무)"}[cid],
            "overtimeRounding": {"code": code, "desc": desc},
            "midnightCrossing": cid == "daramulsan",
            "holidayWork": "구분이 휴일근무인 행: 8시간(480분) 이내 / 초과 분리",
            "weeklyLimitCheck": "주별 근무시간 > 52 표시 (휴일근무 포함)",
            "breakMinutes": "값 입력 (휴일근무는 4h 이상 30분, 8h 이상 60분)",
        },
        "legalDeviations": legal,
        "crossFile": {
            "payroll": {"file": "급여대장.xlsx", "sheet": MONTH, "check": "월집계 인정 연장(분)/60 = 급여대장 연장시간, 휴일(분)/60 = 휴일시간, 휴일8h초과(분)/60 = 휴일8h초과",
                        "rows": recon, "exceptions": cross_exceptions,
                        "note": "급여대장 쪽 사고(#N/A 등)와 근태기록 쪽 사고(중복 행)가 있는 사람은 어긋날 수 있다. 야간 시간은 맞추지 않음"},
            "leave": {"file": "연차관리대장.xlsx", "check": "근태 구분 연차·반차 날짜 = 연차 사용내역 날짜"},
            "overtimeRegister": {"file": "연장근무신청대장.xlsx", "check": "연장·휴일근무가 있는 날마다 승인된 신청이 있어야 함"},
        },
        "accidents": A.items,
        "valueNoise": [{"type": "EMPNO_NUMERIC", "scope": "모든 시트"}] if cid == "daramulsan" else [],
    })
    return att_key, reg_key


def _register(cid, members, rows, plans, out_dir, rng, R):
    """연장·휴일근무 신청대장. 근태기록에 연장·휴일근무가 있는 날마다 신청 1건 (일부 누락·반려)"""
    lead = {}
    for e in members:
        if e["duty"] == "팀장":
            lead[e["dept"]] = e["name"]
    work = [x for x in rows if not x.get("dup") and ((x["otMin"] > 0) or x["type"] in ("휴일근무", "HOLIDAY"))]
    work.sort(key=lambda x: (x["date"], x["emp"]["empNo"]))
    n_missing = {"ganatech": 2, "daramulsan": 3, "mabaservice": 2}[cid]
    missing = rng.sample(work, n_missing)
    rest = [x for x in work if x not in missing]
    rejected = rng.choice(rest)
    early = rng.choice([x for x in rest if x is not rejected]) if cid == "daramulsan" else None

    path = out_dir / "연장근무신청대장.xlsx"
    book = Book(path)
    sh = book.sheet({"ganatech": "신청내역", "daramulsan": "연장근로신청", "mabaservice": "OT_REQUEST"}[cid])
    sm = book.sheet({"ganatech": "월별승인", "daramulsan": "승인합계", "mabaservice": "OT_APPROVED"}[cid])
    eng = cid == "mabaservice"
    heads = (("REQ_NO", "REQ_DATE", "EMP_NO", "NAME", "WORK_DATE", "OT_TYPE", "START", "END", "REQ_MIN", "REASON", "APPROVER", "APPROVED_DATE", "STATUS")
             if eng else ("신청번호", "신청일", "사번", "성명", "근무일", "구분", "시작", "종료", "신청시간", "사유", "승인자", "승인일", "상태"))
    for c, h in zip("ABCDEFGHIJKLM", heads):
        sh.put(c, 1, h, "head")
    st = {"승인": "APPROVED", "반려": "REJECTED"} if eng else {"승인": "승인", "반려": "반려"}
    r = 1
    approved = {}
    for n, x in enumerate(rest):
        r = 2 + n
        e = x["emp"]
        hol = x["type"] in ("휴일근무", "HOLIDAY")
        start = x["in"] if hol else {"ganatech": 1080, "daramulsan": 1020, "mabaservice": x["in"] + 540}[cid]
        end = x["out"] if x["out"] is not None else start
        span = (end - start) % 1440 if cid == "daramulsan" else end - start
        end_r = start + math.ceil(span / 30) * 30  # 신청은 30분 단위로 올림
        if cid != "daramulsan":
            end_r = min(end_r, 1439)  # 자정을 넘기는 계산을 못 하는 양식이라 23:59까지만 신청
        req_date = x["date"] - dt.timedelta(days=rng.choice([0, 0, 1]))
        appr_date = req_date + dt.timedelta(days=rng.choice([0, 0, 1]))
        status = "반려" if x is rejected else "승인"
        if x is early:
            appr_date = req_date - dt.timedelta(days=2)
        sh.put("A", r, ("OT-%s-%04d" if eng else "연장-%s-%04d") % ("2612", n + 1))
        sh.put("B", r, req_date)
        sh.put("C", r, int(e["empNo"]) if cid == "daramulsan" else e["empNo"], None if cid == "daramulsan" else "text")
        sh.put("D", r, e["name"])
        sh.put("E", r, x["date"])
        sh.put("F", r, ("HOLIDAY" if hol else "OT") if eng else ("휴일근무" if hol else "연장근무"))
        sh.put("G", r, _hm(start), "time")
        sh.put("H", r, _hm(end_r % 1440), "time")
        mins = (end_r - start)
        if cid == "ganatech":
            sh.formula("I", r, f"=ROUND((H{r}-G{r})*24,1)", ROUND(mins / 60, 1), "num")
        elif cid == "daramulsan":
            sh.formula("I", r, f"=ROUND(MOD(H{r}-G{r},1)*24,1)", ROUND(mins / 60, 1), "num")
        else:
            sh.formula("I", r, f"=ROUND((H{r}-G{r})*1440,0)", mins)
        sh.put("J", r, rng.choice(["프로젝트 마감", "설비 점검", "고객 대응", "월말 정산", "긴급 출하", "장애 대응"]))
        sh.put("K", r, lead.get(e["dept"], "대표이사") if lead.get(e["dept"]) != e["name"] else "대표이사")
        sh.put("L", r, appr_date if status == "승인" or x is rejected else None)
        sh.put("M", r, st[status])
        x["regRow"] = r
        if status == "승인":
            approved[e["empNo"]] = approved.get(e["empNo"], 0) + (mins if eng else ROUND(mins / 60, 1))
    last = 1 + len(rest)
    for x in missing:
        R.add("NO_APPLICATION", sh.name, "근태기록에는 %s인데 신청 기록이 없음 (승인 없는 연장)" % ("휴일근무" if x["type"] in ("휴일근무", "HOLIDAY") else "연장 %d분" % x["otMin"]),
              ["CROSS_FILE_MATCH", "LEGAL_RULE_COMPARE"], empNo=x["emp"]["empNo"], date=x["date"], attendanceRow=x["row"])
    R.add("REJECTED_BUT_WORKED", sh.name, "신청이 반려됐는데 근태기록에는 그날 연장·휴일근무가 있음", ["CROSS_FILE_MATCH"],
          empNo=rejected["emp"]["empNo"], rowNo=rejected["regRow"], date=rejected["date"], attendanceRow=rejected["row"])
    if early:
        R.add("DATE_ORDER", sh.name, "승인일이 신청일보다 빠름", ["CROSS_FIELD_RULE"], empNo=early["emp"]["empNo"], rowNo=early["regRow"],
              cells=["B%d" % early["regRow"], "L%d" % early["regRow"]])

    for c, h in zip("ABC", ("EMP_NO", "NAME", "APPROVED_MIN") if eng else ("사번", "성명", "승인시간 합계")):
        sm.put(c, 1, h, "head")
    for n, e in enumerate(members):
        rr = 2 + n
        sm.put("A", rr, int(e["empNo"]) if cid == "daramulsan" else e["empNo"], None if cid == "daramulsan" else "text")
        sm.put("B", rr, e["name"])
        sm.formula("C", rr, f'=SUMIFS({sh.name}!$I$2:$I${last},{sh.name}!$C$2:$C${last},A{rr},{sh.name}!$M$2:$M${last},"{st["승인"]}")',
                   approved.get(e["empNo"], 0), None if eng else "num")
    book.close()
    return clean({
        "file": "연장근무신청대장.xlsx", "kind": "OVERTIME_REQUEST", "closestEntity": None, "supported": False,
        "sheetOrder": [sh.name, sm.name],
        "sheets": {sh.name: {"role": "overtimeRequests", "headerRows": [1], "firstDataRow": 2, "lastDataRow": last},
                   sm.name: {"role": "approvedSummary", "headerRows": [1], "firstDataRow": 2, "lastDataRow": 1 + len(members)}},
        "expectedMapping": {"overtimeRequests": dict(zip(heads, ("requestNo", "requestDate", "empNo", "name", "workDate", "otType", "start", "end",
                                                                  "requestedTime", "reason", "approver", "approvedDate", "status")))},
        "rules": {"requestedTime": {"ganatech": "ROUND((종료-시작)×24,1) 시간", "daramulsan": "ROUND(MOD(종료-시작,1)×24,1) 시간",
                                    "mabaservice": "ROUND((END-START)×1440,0) 분"}[cid],
                  "requestRounding": "신청 종료 시각은 실제 퇴근을 30분 단위로 올림", "approvedSummary": "SUMIFS(신청시간, 사번, 상태=승인)"},
        "accidents": R.items,
    })
