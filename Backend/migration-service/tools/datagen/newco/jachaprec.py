"""
자차정밀 — 600명 제조업, 주야 2교대(A조·B조 주 단위 교대) + 상주 근무.

파일: 직원명부.xlsx(2줄 병합 헤더·제목 행·숨긴 주민번호 열), 조직.csv(UTF-8 BOM, 폐지 부서 행 포함),
     급여대장_2026-12.xlsx(부서별 시트 + 조 소계 행 + 결재란 + 숨긴 통상임금 열 + 총괄 시트),
     교대근무기록_2026-12.xlsx(조별 시트, 자정 넘는 야간 근무, 월집계 시트).
급여 규칙: 단체협약상 토요일 8시간 유급 → 통상시급 = 통상임금/243, 국민연금은 기준소득월액(신고값) × 요율에 상·하한.
"""
import datetime as dt

from xlsxwriter.utility import xl_col_to_name

from hrfiles.common import Accidents
from hrfiles.facts import _dependents, is_holiday
from hrfiles.payroll import TAX_ROWS
from hrfiles.xl import ROUND, ROUNDDOWN, Book, datedif_y, vlookup_approx

from . import base as B

CID, NAME = "jachaprec", "자차정밀"
PAY_FILE, ATT_FILE = "급여대장_2026-12.xlsx", "교대근무기록_2026-12.xlsx"
SIZE = 600
RENAME = ("생산2팀", "가공1팀", dt.date(2026, 9, 1))

# (본부, 팀, 직군, 근무)
TEAMS = [
    ("생산본부", "프레스팀", "생산직", "2교대"), ("생산본부", "가공1팀", "생산직", "2교대"),
    ("생산본부", "가공2팀", "생산직", "2교대"), ("생산본부", "도장팀", "생산직", "2교대"),
    ("생산본부", "조립팀", "생산직", "2교대"), ("생산본부", "설비보전팀", "생산직", "상주"),
    ("생산본부", "물류팀", "생산직", "상주"), ("품질본부", "품질관리팀", "사무직", "상주"),
    ("품질본부", "생산관리팀", "사무직", "상주"), ("경영지원본부", "인사총무팀", "사무직", "상주"),
    ("경영지원본부", "재무팀", "사무직", "상주"), ("경영지원본부", "구매팀", "사무직", "상주"),
    ("영업본부", "영업팀", "사무직", "상주"),
]
TEAM = {t[1]: t for t in TEAMS}
PROD_WEIGHT = {"프레스팀": 14, "가공1팀": 14, "가공2팀": 12, "도장팀": 10, "조립팀": 16, "설비보전팀": 5, "물류팀": 7}
PROD_LADDER = [(29, "사원", "J1", 2_250_000), (36, "주임", "J2", 2_450_000), (44, "기장", "J3", 2_700_000), (99, "기정", "J4", 2_950_000)]
OFFICE_LADDER = [(29, "사원", "G1", 2_600_000), (34, "대리", "G2", 3_000_000), (40, "과장", "G3", 3_500_000),
                 (46, "차장", "G4", 4_100_000), (99, "부장", "G5", 4_800_000)]

SETTINGS = [  # (키, 항목, 값, 서식)
    ("np", "국민연금(근로자)", B.RATES_2026["np"], "pct"), ("hi", "건강보험(근로자)", B.RATES_2026["hi"], "pct"),
    ("ltc", "장기요양(건강보험료 대비)", B.RATES_2026["ltc"], "pct"), ("ei", "고용보험(근로자)", B.RATES_2026["ei"], "pct"),
    ("meal", "식대 비과세 한도", B.NONTAX_LIMIT, "won"), ("hours", "월 통상임금 산정시간(단협: 토 8h 유급)", 243, None),
    ("shift", "교대수당(2교대)", 150_000, "won"), ("tenureUnit", "근속수당 1년당", 10_000, "won"),
    ("tenureMax", "근속수당 인정 상한(년)", 20, None), ("npFloor", "국민연금 기준소득월액 하한", B.NP_FLOOR, "won"),
    ("npCap", "국민연금 기준소득월액 상한", B.NP_CAP, "won"), ("local", "지방소득세율", 0.1, "pct"),
]
SET_ROW = {k: 4 + i for i, (k, *_r) in enumerate(SETTINGS)}


def S(k):
    return "설정!$C$%d" % SET_ROW[k]


def SV(k, override=None):
    return (override or {}).get(k, next(v for kk, _l, v, _f in SETTINGS if kk == k))


# ================================================================ 사람

def _team_of(occ, rng):
    for keys, team in [(("회계", "경리", "재무", "세무", "출납"), "재무팀"), (("인사", "노무", "총무", "비서"), "인사총무팀"),
                       (("영업", "판매", "마케팅", "상품"), "영업팀"), (("구매", "자재", "조달"), "구매팀"),
                       (("품질", "검사", "시험"), "품질관리팀"), (("기획", "관리자", "공정"), "생산관리팀")]:
        if B.has(occ, *keys):
            return team
    if B.has(occ, "사무", "행정") and rng.random() < 0.5:
        return rng.choice(["인사총무팀", "구매팀", "생산관리팀"])
    teams = list(PROD_WEIGHT)
    return rng.choices(teams, [PROD_WEIGHT[t] for t in teams])[0]


def people(df, used, seed):
    rng = B.rng_for(CID, "people", seed)
    w = lambda o: 3.0 if B.has(o, "생산", "조립", "기계", "설비", "제조", "가공", "용접", "조작원", "운전", "검사", "물류", "운송") else 1.0  # noqa: E731
    picked = B.pick(df, used, SIZE, rng, CID, w)
    emps, seq = [], {}
    for p in sorted(picked, key=lambda x: (-x["age"], x["uuid"])):
        team = _team_of(p["occupation"], rng)
        hq, _, family, work = TEAM[team]
        age = int(p["age"])
        birth = B.birth_of(age, rng)
        hire = dt.date(birth.year + rng.randint(20, min(age, 40)), rng.randint(1, 12), rng.randint(1, 28))
        hire = min(hire, dt.date(2026, 11, 30) - dt.timedelta(days=rng.randint(0, 300)))
        if team == "물류팀" and hire.year == 2026:  # 작년 시트 복사 사고(물류팀)가 DATEDIF 음수를 만들지 않게
            hire = hire.replace(year=2025)
        ladder = PROD_LADDER if family == "생산직" else OFFICE_LADDER
        _, pos, grade, pay = next(x for x in ladder if age <= x[0])
        resigned = rng.random() < 0.05
        resign = None
        if resigned:
            resign = hire + dt.timedelta(days=rng.randint(120, max(121, (dt.date(2026, 11, 30) - hire).days)))
            resign = min(resign, dt.date(2026, 11, 30))
        emp_type = "촉탁직" if age >= 58 and rng.random() < 0.5 else rng.choices(["정규직", "계약직"], [93, 7])[0]
        seq[hire.year] = seq.get(hire.year, 0) + 1
        emps.append({
            "empNo": "%d%04d" % (hire.year, seq[hire.year]), "name": p["name"], "birthDate": birth, "sex": p["sex"],
            "hq": hq, "dept": team, "family": family, "work": work, "position": pos, "grade": grade, "duty": "팀원",
            "empType": emp_type, "hireDate": hire, "resignDate": resign, "status": "퇴직" if resigned else "재직",
            "mobile": B.phone(rng),
            "email": ("j%s@jachaprec.co.kr" % "%d%04d" % (hire.year, seq[hire.year])) if family == "사무직" or rng.random() < 0.12 else None,
            "basePay": pay + rng.randint(0, 25) * 10_000 + (rng.randint(0, 100) * 10_000 if pos == "부장" else 0),
            "_familyType": p["family_type"], "uuid": p["uuid"],
        })
    # 조 배정(2교대 팀은 A·B 번갈아), 팀장(최연장 재직자), 조장(조별 최연장 생산직)
    for t in TEAM:
        members = [e for e in emps if e["dept"] == t]
        for n, e in enumerate(sorted(members, key=lambda x: x["empNo"])):
            e["shift"] = ("A" if n % 2 == 0 else "B") if e["work"] == "2교대" else "상주"
        active = [e for e in members if e["status"] == "재직"]
        if active:
            active[0]["duty"] = "팀장"
            for s in ("A", "B"):
                cand = [e for e in active[1:] if e["shift"] == s]
                if cand:
                    cand[0]["duty"] = "조장"
    rng2 = B.rng_for(CID, "deps", seed)
    for e in emps:
        deps = _dependents(e, rng2)
        e["taxFamily"] = 1 + sum(d["basic"] for d in deps)
        e["familyAlw"] = sum(40_000 if d["relation"] == "배우자" else 30_000 if d["relation"] == "자녀" and d["basic"] else 0 for d in deps)
        e["raise"] = rng2.choice([0.03, 0.035, 0.04, 0.045])
    emps.sort(key=lambda e: e["empNo"])
    return emps


# ================================================================ 근태 계획 (12월)

DAYS = [dt.date(2026, 12, d) for d in range(1, 32)]
WEEK = {d: (d - dt.date(2026, 11, 30)).days // 7 + 1 for d in DAYS}
DOW = "월화수목금토일"
DAY_START, DAY_END, NIGHT_START, NIGHT_END = 480, 1020, 1200, 300   # 08:00~17:00, 20:00~05:00(다음날)
WEEK_BUDGET = 640  # 연장+특근(분) 주 한도: 조기 출근 분까지 합쳐도 52시간을 넘지 않게


def _kind(e, d):
    if e["shift"] == "상주":
        return "주간"
    odd = WEEK[d] % 2 == 1
    return "야간" if (e["shift"] == "A") == odd else "주간"


def _row(e, d, kind, start, end, brk=60):
    return {"emp": e, "date": d, "kind": kind, "in": start, "out": end, "break": brk}


def plan(e, rng, force52=False):
    rows = []
    active = [d for d in DAYS if d >= e["hireDate"] and (e["resignDate"] is None or d <= e["resignDate"])]
    workdays = [d for d in active if not is_holiday(d)]
    leave = set(rng.sample(workdays, rng.choice([0, 0, 0, 1, 1, 2]))) if workdays else set()
    budget = {}
    for d in active:
        w = WEEK[d]
        budget.setdefault(w, WEEK_BUDGET)
        if is_holiday(d):
            if d.weekday() == 5 and e["family"] == "생산직" and _kind(e, d - dt.timedelta(days=1)) == "주간" and \
                    (rng.random() < 0.3 or (force52 and w == 3)) and budget[w] >= 480:
                rows.append(_row(e, d, "특근", DAY_START, DAY_END))
                budget[w] -= 480
            continue
        if d in leave:
            rows.append(_row(e, d, "연차", None, None, None))
            continue
        kind = _kind(e, d)
        early = rng.randint(0, 15)
        ot = 120 if force52 and w == 3 else min(rng.choice([0, 0, 30, 60, 90, 120]) if e["family"] == "생산직" else rng.choice([0, 0, 0, 30, 60]),
                                               max(0, budget[w] - 9) // 30 * 30)
        jitter = rng.randint(0, 9) if ot or rng.random() < 0.5 else 0
        if not force52:
            jitter = min(jitter, max(0, budget[w] - ot))
        budget[w] -= ot + jitter
        if kind == "주간":
            rows.append(_row(e, d, kind, DAY_START - early, DAY_END + ot + jitter))
        else:
            rows.append(_row(e, d, kind, NIGHT_START - early, NIGHT_END + ot + jitter))
    if force52:  # 3주차 토요일 특근 (주 52시간 초과)
        for d in (dt.date(2026, 12, 19),):
            if not any(r["date"] == d for r in rows):
                rows.append(_row(e, d, "특근", DAY_START, DAY_END))
        rows.sort(key=lambda r: r["date"])
    return rows


def _std_end(kind):
    return NIGHT_END if kind == "야간" else DAY_END


def work_min(r, midnight=True):
    if r["in"] is None:
        return 0
    if r["out"] is None:  # 퇴근 빈 칸 = 0시
        return (0 - r["in"]) % 1440 - r["break"]
    return ((r["out"] - r["in"]) % 1440 if midnight else (r["out"] % 1440 - r["in"])) - r["break"]


def ot_min(r):
    if r["kind"] == "특근" or r["in"] is None:
        return 0
    out = 0 if r["out"] is None else r["out"]
    return (out - _std_end(r["kind"])) % 1440


def night_min(r):
    if r["kind"] != "야간":
        return 0
    out = 0 if r["out"] is None else r["out"] % 1440
    return min(out, 360) + 1440 - max(r["in"], 1320) - r["break"]


def hol_min(r, midnight=True):
    return work_min(r, midnight) if r["kind"] == "특근" else 0


def to_hours(m):
    return ROUNDDOWN(m / 30, 0) / 2  # 30분 미만 버림


def _t(m):
    return None if m is None else dt.time((m // 60) % 24, m % 60)


# ================================================================ 근태 파일

ATT_COLS = ["일자", "요일", "사번", "성명", "부서", "근무구분", "출근", "퇴근", "휴게(분)", "근무(분)", "연장(분)", "야간(분)", "휴일(분)"]


def write_attendance(path, emps, plans, rng, acc_plan):
    A = Accidents("JACH-AT", path.name)
    book = Book(path)
    sheets = {"A": "A조", "B": "B조", "상주": "상주"}
    rows_by_sheet = {s: [] for s in sheets}
    for e in emps:
        for r in plans.get(e["empNo"], []):
            rows_by_sheet[e["shift"]].append(r)
    # 사고 계획 반영
    orig_dup = acc_plan["dupRow"]
    dup = {**orig_dup, "dup": True}
    rows_by_sheet[dup["emp"]["shift"]].append(dup)
    for s in rows_by_sheet:
        rows_by_sheet[s].sort(key=lambda r: (r["date"], r["emp"]["empNo"], bool(r.get("dup"))))
    meta, row_no = {}, {}
    month_vals = {}
    for s, name in sheets.items():
        sh = book.sheet(name)
        for i, h in enumerate(ATT_COLS):
            sh.put(xl_col_to_name(i), 1, h, "head")
        r = 2
        for x in rows_by_sheet[s]:
            e = x["emp"]
            bad_midnight = s == "B" and x["date"] >= acc_plan["midnightFrom"]
            sh.put("A", r, x["date"])
            sh.put("B", r, DOW[x["date"].weekday()])
            sh.put("C", r, int(e["empNo"]))
            sh.put("D", r, e["name"])
            sh.put("E", r, e["dept"])
            kind_cell = "주간" if x.get("holidayAsWeekday") else x["kind"]
            sh.put("F", r, kind_cell)
            sh.put("G", r, _t(x["in"]), "time")
            sh.put("H", r, _t(x["out"] % 1440 if x["out"] is not None else None), "time")
            sh.put("I", r, x["break"])
            xx = {**x, "kind": kind_cell}
            if bad_midnight:
                sh.formula("J", r, f'=IF(G{r}="",0,ROUND((H{r}-G{r})*1440,0)-I{r})', work_min(xx, midnight=False))
            else:
                sh.formula("J", r, f'=IF(G{r}="",0,MOD(ROUND((H{r}-G{r})*1440,0),1440)-I{r})', work_min(xx))
            sh.formula("K", r, f'=IF(OR(F{r}="특근",G{r}=""),0,MOD(ROUND((H{r}-IF(F{r}="야간",5/24,17/24))*1440,0),1440))', ot_min(xx))
            sh.formula("L", r, f'=IF(F{r}="야간",ROUND((MIN(H{r},6/24)+1-MAX(G{r},22/24))*1440,0)-I{r},0)', night_min(xx))
            sh.formula("M", r, f'=IF(F{r}="특근",J{r},0)', hol_min(xx, midnight=not bad_midnight))
            x["_actual"] = {"work": work_min(xx, midnight=not bad_midnight), "ot": ot_min(xx), "night": night_min(xx),
                            "hol": hol_min(xx, midnight=not bad_midnight)}
            row_no[id(x)] = (name, r)
            r += 1
        sh.ws.freeze_panes(1, 4)
        sh.widths({"A": 11, "D": 9, "E": 11})
        meta[name] = {"role": "dailyAttendance", "shift": s, "headerRows": [1], "firstDataRow": 2, "lastDataRow": r - 1}
    # 월집계
    sm = book.sheet("월집계")
    heads = ["사번", "성명", "부서", "조", "연장(분)", "야간(분)", "휴일(분)", "연장(h)", "야간(h)", "휴일(h)"]
    for i, h in enumerate(heads):
        sm.put(xl_col_to_name(i), 1, h, "head")
    r = 2
    month_row = {}
    for e in emps:
        if e["empNo"] not in plans:
            continue
        name = sheets[e["shift"]]
        last = meta[name]["lastDataRow"]
        rows = [x for x in rows_by_sheet[e["shift"]] if x["emp"] is e]
        tot = {k: sum(x["_actual"][k] for x in rows) for k in ("ot", "night", "hol")}
        sm.put("A", r, int(e["empNo"]))
        sm.put("B", r, e["name"])
        sm.put("C", r, e["dept"])
        sm.put("D", r, name)
        for col, src, k in (("E", "K", "ot"), ("F", "L", "night"), ("G", "M", "hol")):
            sm.formula(col, r, f"=SUMIFS('{name}'!${src}$2:${src}${last},'{name}'!$C$2:$C${last},A{r})", tot[k])
        for col, src, k in (("H", "E", "ot"), ("I", "F", "night"), ("J", "G", "hol")):
            sm.formula(col, r, f"=ROUNDDOWN({src}{r}/30,0)/2", to_hours(tot[k]), "num")
        month_vals[e["empNo"]] = {**{k: to_hours(tot[k]) for k in ("ot", "night", "hol")}, "min": tot}
        month_row[e["empNo"]] = r
        r += 1
    meta["월집계"] = {"role": "monthlySummary", "headerRows": [1], "firstDataRow": 2, "lastDataRow": r - 1}
    book.close()

    # ---- 정답지 사고
    def loc(x):
        return row_no[id(x)]

    bad = [x for x in rows_by_sheet["B"] if x["date"] >= acc_plan["midnightFrom"] and x["kind"] == "야간"]
    no_mod = sorted(loc(x)[1] for x in rows_by_sheet["B"] if x["date"] >= acc_plan["midnightFrom"])  # MOD 없는 수식을 넣은 행 전부
    A.add("MIDNIGHT_NOT_HANDLED", "B조", "B조 시트 12/%d 이후 행(%d~%d행)의 근무(분) 수식이 MOD 없이 (퇴근-출근)×1440 — 주간 양식을 복사해 붙여 "
          "자정을 넘는 야간 근무 %d행이 음수 (예: %d분). 같은 범위의 주간·특근·연차 행은 수식은 틀렸지만 값은 우연히 맞음"
          % (acc_plan["midnightFrom"].day, no_mod[0], no_mod[-1], len(bad), bad[0]["_actual"]["work"]),
          ["FORMULA_READ", "RECOMPUTE_CHECK", "RULE_EXTRACTION"], rows=sorted(loc(x)[1] for x in bad),
          cells=["J%d:J%d" % (no_mod[0], no_mod[-1])], formulaRows=[no_mod[0], no_mod[-1]],
          formula={"actual": '=IF(G{r}="",0,ROUND((H{r}-G{r})*1440,0)-I{r})',
                   "expected": '=IF(G{r}="",0,MOD(ROUND((H{r}-G{r})*1440,0),1440)-I{r})'})
    om = acc_plan["outMissing"]
    sname, rn = loc(om)
    A.add("OUT_MISSING", sname, "퇴근 시각 빈 칸 → 빈 칸을 0시로 계산해 근무 %d분·연장 %d분이 생김 (월집계 연장 부풀려짐)"
          % (om["_actual"]["work"], om["_actual"]["ot"]), ["REQUIRED_PAIR", "RECOMPUTE_CHECK"],
          empNo=om["emp"]["empNo"], rowNo=rn, cells=["H%d" % rn],
          values={"근무(분)": {"actual": om["_actual"]["work"]}, "연장(분)": {"actual": om["_actual"]["ot"], "expected": om["_otTrue"]}})
    sname, rn = loc(dup)
    orig = orig_dup
    twice = ", ".join("%s %d분" % (lab, dup["_actual"][k]) for k, lab in (("ot", "연장"), ("night", "야간"), ("hol", "휴일")) if dup["_actual"][k])
    A.add("DUPLICATE_ROW", sname, "같은 사람·같은 날 출퇴근 두 번 (단말기 기록 중복 반영) → 월집계에 %s이 한 번 더 더해짐" % (twice or "0분"),
          ["DUPLICATE_DETECT"], empNo=dup["emp"]["empNo"], rows=sorted([loc(orig)[1], rn]), rowNo=rn)
    hx = acc_plan["holidayAsWeekday"]
    sname, rn = loc(hx)
    prev = [x for x in plans[hx["emp"]["empNo"]] if x["date"] == hx["date"] - dt.timedelta(days=1) and x["kind"] == "야간" and x["out"] is not None]
    hx_note = ("전날 야간 근무(%s 퇴근) 뒤 같은 날 %s 출근이라 근무 간 휴식 %d분 — 이 사고 행이 만든 값 (12/25 특근 행 자체가 사고)"
               % (_t(prev[0]["out"] % 1440).strftime("%H:%M"), _t(hx["in"]).strftime("%H:%M"), hx["in"] - prev[0]["out"] % 1440)) if prev else None
    A.add("HOLIDAY_WORK_AS_WEEKDAY", sname, "12/25(성탄절) 특근을 근무구분 '주간'으로 기록 → 휴일(분) 0, 정시 이후만 연장으로 잡힘",
          ["HOLIDAY_CALENDAR", "CROSS_FIELD_RULE"], empNo=hx["emp"]["empNo"], rowNo=rn, cells=["F%d" % rn],
          values={"근무구분": {"actual": "주간", "expected": "특근"}}, **({"note": hx_note} if hx_note else {}))
    for e in acc_plan["force52"]:
        rows = [x for x in plans[e["empNo"]] if WEEK[x["date"]] == 3]
        wk = sum(x["_actual"]["work"] for x in rows)
        A.add("WEEKLY_52H_EXCEEDED", sheets[e["shift"]], "3주차(12/14~20) 평일 매일 2시간 연장 + 토요일 특근 → 주 %.1f시간" % (wk / 60),
              ["LEGAL_RULE_COMPARE", "RECOMPUTE_CHECK"], legal=True, empNo=e["empNo"], rows=sorted(loc(x)[1] for x in rows),
              intended=True)
    # 일부러 넣지 않은 52시간 초과가 있으면 따로 남긴다
    for e in emps:
        if e in acc_plan["force52"] or e["empNo"] not in plans:
            continue
        for w in range(1, 6):
            rows = [x for x in plans[e["empNo"]] if WEEK[x["date"]] == w]
            wk = sum(work_min({**x, "out": x.get("_outTrue", x["out"])}) for x in rows)  # 사고 없는 값으로 판단
            if wk > 52 * 60:
                A.add("WEEKLY_52H_EXCEEDED", sheets[e["shift"]], "%d주차 주 %.1f시간 (다른 사고의 결과)" % (w, wk / 60),
                      ["LEGAL_RULE_COMPARE", "RECOMPUTE_CHECK"], legal=True, empNo=e["empNo"],
                      rows=sorted(loc(x)[1] for x in rows), intended=False)
    # 사고 겹침: 12/25 '주간' 행이 MOD 없는 수식 범위 안
    a_mid, a_hx = A.items[0], next(a for a in A.items if a["type"] == "HOLIDAY_WORK_AS_WEEKDAY")
    if a_hx["sheet"] == "B조" and no_mod[0] <= a_hx["rowNo"] <= no_mod[-1]:
        cell = "J%d" % a_hx["rowNo"]
        a_mid["overlaps"] = [{"with": a_hx["id"], "cells": [cell], "trueValue": hx["_actual"]["work"],
                              "note": "12/25 특근을 '주간'으로 기록한 행도 MOD 없는 수식 범위 안. 낮 근무라 근무(분) 값은 맞음"}]
        a_hx["overlaps"] = [{"with": a_mid["id"], "cells": [cell], "trueValue": hx["_actual"]["work"],
                             "note": "이 행의 근무(분) 수식은 MOD가 없지만(%s) 낮 근무라 값은 맞음" % a_mid["id"]}]
    return meta, A.items, month_vals, month_row


# ================================================================ 급여 계산

PAY_COLS = [("empNo", "사번"), ("name", "성명"), ("shift", "조"), ("position", "직위"), ("hire", "입사일"), ("family", "공제가족"),
            ("basePay", "기본급"), ("posAlw", "직책수당"), ("tenureAlw", "근속수당"), ("shiftAlw", "교대수당"),
            ("familyAlw", "가족수당"), ("meal", "식대"), ("ordinary", "통상임금"), ("hourly", "통상시급"),
            ("otH", "연장(h)"), ("nightH", "야간(h)"), ("holH", "휴일(h)"),
            ("otPay", "연장수당"), ("nightPay", "야간수당"), ("holPay", "휴일수당"), ("gross", "지급계"), ("taxable", "과세대상"),
            ("npBase", "기준소득월액"), ("np", "국민연금"), ("hi", "건강보험"), ("ltc", "장기요양"), ("ei", "고용보험"),
            ("tax", "소득세"), ("ltax", "지방소득세"), ("ded", "공제계"), ("net", "실지급액")]
L = {k: xl_col_to_name(i) for i, (k, _) in enumerate(PAY_COLS)}
MONEY = [k for k, _ in PAY_COLS[PAY_COLS.index(("basePay", "기본급")):] if k not in ("ordinary", "hourly")]
GROUPS = [("empNo", "family", "인적사항"), ("basePay", "meal", "지급내역(고정)"), ("ordinary", "hourly", "통상임금"),
          ("otH", "holH", "근태(시간)"), ("otPay", "holPay", "법정수당"), ("gross", "taxable", "합계"),
          ("npBase", "ded", "공제내역"), ("net", "net", "실지급")]
POS_ALW = {"팀장": 300_000, "조장": 100_000}


def pay_inputs(e, hours, year=2026):
    base = e["basePay"] if year == 2026 else e["basePay2025"]
    return {"empNo": int(e["empNo"]), "name": e["name"], "shift": {"A": "A조", "B": "B조"}.get(e["shift"], "상주"),
            "position": e["position"], "hire": e["hireDate"], "family": e["taxFamily"], "basePay": base,
            "posAlw": POS_ALW.get(e["duty"], 0), "familyAlw": e["familyAlw"], "meal": 200_000,
            "otH": hours["ot"], "nightH": hours["night"], "holH": hours["hol"], "npBase": e["npBase"]}


def pay_calc(v, divisor=243, cap=None, base_date=dt.date(2026, 12, 31), ordinary=None):
    """한 행의 계산값 (엑셀 수식과 같은 순서·끝수)"""
    v = dict(v)
    v["tenureAlw"] = min(datedif_y(v["hire"], base_date), SV("tenureMax")) * SV("tenureUnit")
    v["shiftAlw"] = 0 if v["shift"] == "상주" else SV("shift")
    v["ordinary"] = ordinary if ordinary is not None else v["basePay"] + v["posAlw"] + v["tenureAlw"] + v["shiftAlw"]
    v["hourly"] = ROUND(v["ordinary"] / divisor, 0)
    v["otPay"] = ROUND(v["hourly"] * v["otH"] * 1.5, 0)
    v["nightPay"] = ROUND(v["hourly"] * v["nightH"] * 0.5, 0)
    v["holPay"] = ROUND(v["hourly"] * v["holH"] * 1.5, 0)
    v["gross"] = sum(v[k] for k in ("basePay", "posAlw", "tenureAlw", "shiftAlw", "familyAlw", "meal", "otPay", "nightPay", "holPay"))
    v["taxable"] = v["gross"] - min(v["meal"], SV("meal"))
    v["np"] = ROUNDDOWN(min(max(v["npBase"], SV("npFloor")), cap or SV("npCap")) * SV("np"), -1)
    v["hi"] = ROUNDDOWN(v["taxable"] * SV("hi"), -1)
    v["ltc"] = ROUNDDOWN(v["hi"] * SV("ltc"), -1)
    v["ei"] = ROUNDDOWN(v["taxable"] * SV("ei"), -1)
    v["tax"] = vlookup_approx(v["taxable"], TAX_ROWS, min(v["family"], 5) + 2)
    v["ltax"] = ROUNDDOWN(v["tax"] * SV("local"), -1)
    v["ded"] = v["np"] + v["hi"] + v["ltc"] + v["ei"] + v["tax"] + v["ltax"]
    v["net"] = v["gross"] - v["ded"]
    return v


def formulas(r, divisor_ref, year=2026):
    R = lambda k: "%s%d" % (L[k], r)  # noqa: E731
    return {
        "tenureAlw": f"=MIN(DATEDIF({R('hire')},DATE({year},12,31),\"Y\"),{S('tenureMax')})*{S('tenureUnit')}",
        "shiftAlw": f'=IF({R("shift")}="상주",0,{S("shift")})',
        "ordinary": f"={R('basePay')}+{R('posAlw')}+{R('tenureAlw')}+{R('shiftAlw')}",
        "hourly": f"=ROUND({R('ordinary')}/{divisor_ref},0)",
        "otPay": f"=ROUND({R('hourly')}*{R('otH')}*1.5,0)",
        "nightPay": f"=ROUND({R('hourly')}*{R('nightH')}*0.5,0)",
        "holPay": f"=ROUND({R('hourly')}*{R('holH')}*1.5,0)",
        "gross": f"=SUM({L['basePay']}{r}:{L['meal']}{r},{L['otPay']}{r}:{L['holPay']}{r})",
        "taxable": f"={R('gross')}-MIN({R('meal')},{S('meal')})",
        "np": f"=ROUNDDOWN(MIN(MAX({R('npBase')},{S('npFloor')}),{S('npCap')})*{S('np')},-1)",
        "hi": f"=ROUNDDOWN({R('taxable')}*{S('hi')},-1)",
        "ltc": f"=ROUNDDOWN({R('hi')}*{S('ltc')},-1)",
        "ei": f"=ROUNDDOWN({R('taxable')}*{S('ei')},-1)",
        "tax": f"=VLOOKUP({R('taxable')},간이세액표!$A$4:$G${3 + len(TAX_ROWS)},MIN({R('family')},5)+2,TRUE)",
        "ltax": f"=ROUNDDOWN({R('tax')}*{S('local')},-1)",
        "ded": f"=SUM({L['np']}{r}:{L['ltax']}{r})",
        "net": f"={R('gross')}-{R('ded')}",
    }


def write_payroll(path, emps, hours, acc_plan):
    A = Accidents("JACH-PAY", path.name)
    book = Book(path)
    teams = [t for _, t, _, _ in TEAMS]
    s_sum = book.sheet("총괄")
    team_sheets = {t: book.sheet(t) for t in teams}
    s_set = book.sheet("설정")
    s_tax = book.sheet("간이세액표")
    cap_override = {"npCap": B.NP_CAP_OLD}

    # 설정
    s_set.put("A", 1, "급여 계산 기준 (2026년, 단체협약 반영)", "title")
    s_set.put("B", 3, "항목", "head")
    s_set.put("C", 3, "값", "head")
    for k, label, v, fmt in SETTINGS:
        s_set.put("B", SET_ROW[k], label)
        s_set.put("C", SET_ROW[k], SV(k, cap_override), fmt)
    s_set.widths({"B": 36, "C": 14})
    # 간이세액표
    s_tax.put("A", 1, "근로소득 간이세액표 (단순화, 공제대상 가족 수별, 단위: 원)", "title")
    for c, h in zip("ABCDEFG", ["월급여(이상)", "월급여(미만)", "1명", "2명", "3명", "4명", "5명 이상"]):
        s_tax.put(c, 3, h, "head")
    for n, row in enumerate(TAX_ROWS):
        for c, v in zip("ABCDEFG", row):
            s_tax.put(c, 4 + n, v, "won")

    sheet_meta, values, totals = {}, {}, {}
    for t in teams:
        sh = team_sheets[t]
        wrong_year = t == acc_plan["wrongYearTeam"]
        year = 2025 if wrong_year else 2026
        sh.put("A", 1, "%d년 12월 급여대장" % year, "title")
        sh.put("A", 2, "부서: %s     지급일: %s     (단위: 원)" % (t, "2026-01-09" if wrong_year else "2027-01-08"))
        for i, lab in enumerate(["담당", "팀장", "공장장", "대표이사"]):
            c = xl_col_to_name(len(PAY_COLS) - 4 + i)
            sh.put(c, 1, lab, "head")
            sh.merge("%s2:%s3" % (c, c), "", "head")
        for a, b, label in GROUPS:
            if a == b:
                sh.put(L[a], 5, label, "head")
            else:
                sh.merge("%s5:%s5" % (L[a], L[b]), label)
        for k, h in PAY_COLS:
            sh.put(L[k], 6, h, "head")
        sh.ws.set_column("%s:%s" % (L["ordinary"], L["ordinary"]), None, None, {"hidden": True})
        sh.ws.freeze_panes(6, 2)
        sh.widths({L["name"]: 9, L["hire"]: 11})

        members = [e for e in emps if e["dept"] == t and e["empNo"] in hours]
        groups = [("A조", [e for e in members if e["shift"] == "A"]), ("B조", [e for e in members if e["shift"] == "B"])] \
            if TEAM[t][3] == "2교대" else [(None, members)]
        r = 7
        row_of, vals, subtotal_rows, data_rows = {}, {}, [], []
        divisor_ref = "209" if t == acc_plan["divisorTeam"] else S("hours")
        divisor = 209 if t == acc_plan["divisorTeam"] else 243
        for gname, gm in groups:
            first = r
            for e in sorted(gm, key=lambda x: x["empNo"]):
                inp = pay_inputs(e, hours[e["empNo"]], year if e["hireDate"].year < 2026 else 2026)
                hard_ord = acc_plan["hiddenStale"].get(e["empNo"])
                if e["empNo"] == acc_plan["hoursTypo"]["empNo"]:
                    inp["otH"] = acc_plan["hoursTypo"]["typed"]
                actual = pay_calc(inp, divisor, B.NP_CAP_OLD, dt.date(year, 12, 31), ordinary=hard_ord)
                ideal = pay_calc(pay_inputs(e, hours[e["empNo"]]))
                F = formulas(r, divisor_ref, year)
                for k, _ in PAY_COLS:
                    if k == "ordinary" and hard_ord is not None:
                        sh.put(L[k], r, hard_ord, "won")
                    elif k in F:
                        sh.formula(L[k], r, F[k], actual[k], "num" if k == "hourly" else "won")
                    else:
                        x = actual[k]
                        sh.put(L[k], r, x, "date" if k == "hire" else ("won" if isinstance(x, int) and x >= 1000 and k != "empNo" else None))
                row_of[e["empNo"]] = r
                vals[e["empNo"]] = {"actual": actual, "ideal": ideal}
                data_rows.append(r)
                r += 1
            if gname:
                sh.put(L["name"], r, gname + " 소계", "totalLabel")
                for k in MONEY:
                    sh.formula(L[k], r, "=SUM(%s%d:%s%d)" % (L[k], first, L[k], r - 1),
                               sum(vals[e["empNo"]]["actual"][k] for e in gm), "total")
                subtotal_rows.append(r)
                r += 1
        last = r - 1
        total_row = r
        sh.put(L["name"], r, "합계", "totalLabel")
        team_tot = {}
        for k in MONEY:
            right = sum(v["actual"][k] for v in vals.values())
            if subtotal_rows and t == acc_plan["doubleCountTeam"]:
                sh.formula(L[k], r, "=SUM(%s7:%s%d)" % (L[k], L[k], last), right * 2, "total")
                team_tot[k] = right * 2
            elif subtotal_rows:
                sh.formula(L[k], r, "=" + "+".join("%s%d" % (L[k], x) for x in subtotal_rows), right, "total")
                team_tot[k] = right
            else:
                sh.formula(L[k], r, "=SUM(%s7:%s%d)" % (L[k], L[k], last), right, "total")
                team_tot[k] = right
        totals[t] = {"row": total_row, "values": team_tot, "count": len(vals),
                     "ideal": {k: sum(v["ideal"][k] for v in vals.values()) for k in MONEY}}
        values[t] = vals
        sheet_meta[t] = {"role": "monthlyPayroll", "payMonth": "2026-12" if not wrong_year else "2026-12 (제목은 2025년)",
                         "headerRows": [5, 6], "firstDataRow": 7, "lastDataRow": last, "totalRow": total_row,
                         "subtotalRows": subtotal_rows, "dataRows": len(data_rows), "titleRows": [1, 2],
                         "approvalBox": "%s1:%s3" % (xl_col_to_name(len(PAY_COLS) - 4), xl_col_to_name(len(PAY_COLS) - 1)),
                         "hiddenColumns": [L["ordinary"]], "rowOf": row_of,
                         "formulas": {k: f for k, f in formulas(7, divisor_ref, year).items()}}

    # 총괄
    s_sum.put("A", 1, "2026년 12월 급여 총괄", "title")
    for c, h in zip("ABCDE", ["부서", "인원", "지급계", "공제계", "실지급액"]):
        s_sum.put(c, 3, h, "head")
    r = 4
    sum_row = {}
    for t in teams:
        tt = totals[t]
        s_sum.put("A", r, t)
        s_sum.formula("B", r, "=COUNT('%s'!A7:A%d)" % (t, tt["row"] - 1), tt["count"])
        for c, k in zip("CDE", ("gross", "ded", "net")):
            s_sum.formula(c, r, "='%s'!%s%d" % (t, L[k], tt["row"]), tt["values"][k], "won")
        sum_row[t] = r
        r += 1
    s_sum.put("A", r, "총계", "totalLabel")
    short = acc_plan["summaryShort"]
    last_inc = r - 2 if short else r - 1
    s_sum.formula("B", r, "=SUM(B4:B%d)" % last_inc, sum(totals[t]["count"] for t in teams[:last_inc - 3]), "total")
    for c, k in zip("CDE", ("gross", "ded", "net")):
        s_sum.formula(c, r, "=SUM(%s4:%s%d)" % (c, c, last_inc), sum(totals[t]["values"][k] for t in teams[:last_inc - 3]), "total")
    s_sum.widths({"A": 14, "C": 16, "D": 16, "E": 16})
    sum_total_row = r
    book.close()

    # ---------------- 정답지 사고
    hdr = dict(PAY_COLS)

    def diff_cols(t, emp):
        v = values[t][emp]
        return [hdr[k] for k, _ in PAY_COLS if k not in ("empNo", "name", "shift", "hire") and v["actual"].get(k) != v["ideal"].get(k)]

    def spread(t, rows):
        """값이 바뀐 행 → 그 행이 들어가는 조 소계·부서 합계·총괄 부서 행·총괄 총계(SUM 범위 안일 때)까지"""
        m = sheet_meta[t]
        subs = sorted({next(s for s in m["subtotalRows"] if s > r) for r in rows}) if m["subtotalRows"] else []
        out = [{"sheet": t, "rowNo": s} for s in subs] + [{"sheet": t, "rowNo": m["totalRow"]}, {"sheet": "총괄", "rowNo": sum_row[t]}]
        return out + ([{"sheet": "총괄", "rowNo": sum_total_row}] if sum_row[t] <= last_inc else [])

    def row_vals(t, emp, keys):
        v = values[t][emp]
        return {hdr[k]: {"actual": v["actual"][k], "expected": v["ideal"][k]} for k in keys if v["actual"][k] != v["ideal"][k]}

    t = acc_plan["divisorTeam"]
    m = sheet_meta[t]
    A.add("DIVISOR_INCONSISTENT", t, "%s 시트만 통상시급 수식이 '/209' 숫자 (다른 부서 시트는 설정!243 참조) — 단협 개정 전 양식을 복사해 "
          "연장·야간·휴일수당이 다른 부서와 다른 기준으로 계산됨 → 지급계·공제·합계, 총괄까지 달라짐" % t, ["FORMULA_READ", "RULE_EXTRACTION", "MULTI_SHEET"],
          cells=["%s%d:%s%d" % (L["hourly"], m["firstDataRow"], L["hourly"], m["lastDataRow"])],
          formula={"actual": "=ROUND(M7/209,0)", "expected": "=ROUND(M7/%s,0)" % S("hours")},
          rowsWithDifferentValue=sorted(m["rowOf"][x] for x in values[t] if values[t][x]["actual"]["otPay"] != values[t][x]["ideal"]["otPay"]
                                        or values[t][x]["actual"]["nightPay"] != values[t][x]["ideal"]["nightPay"]),
          affected=[{"sheet": t, "rowNo": None}] + [x for x in spread(t, []) if x["sheet"] == "총괄"])  # 시트 전체(행 None) + 총괄
    t = acc_plan["doubleCountTeam"]
    m = sheet_meta[t]
    A.add("SUBTOTAL_DOUBLE_COUNT", t, "%s 합계 행이 SUM(7행:마지막 행)으로 A조·B조 소계 행까지 더해 모든 금액이 2배 → 총괄 시트로 전파" % t,
          ["TOTAL_ROW_SKIP", "RECOMPUTE_CHECK"], rowNo=m["totalRow"],
          cells=["%s%d:%s%d" % (L["basePay"], m["totalRow"], L["net"], m["totalRow"]),
                 "총괄!C%d:E%d" % (sum_row[t], sum_row[t]), "총괄!C%d:E%d" % (sum_total_row, sum_total_row)],
          values={"지급계": {"actual": totals[t]["values"]["gross"], "expected": totals[t]["values"]["gross"] // 2}},
          affected=[{"sheet": t, "rowNo": m["totalRow"]}, {"sheet": "총괄", "rowNo": sum_row[t]}, {"sheet": "총괄", "rowNo": sum_total_row}])
    a_double = A.items[-1]
    t = acc_plan["wrongYearTeam"]
    m = sheet_meta[t]
    old = [x for x in values[t] if any(values[t][x]["actual"][k] != values[t][x]["ideal"][k] for k in MONEY)]
    A.add("WRONG_YEAR_SHEET", t, "작년(2025년 12월) 시트를 복사해 사람만 바꿈: 제목 '2025년 12월'·지급일 2026-01-09, 2026년 이전 입사자의 기본급이 "
          "2025년 금액, 근속수당 기준일 DATE(2025,12,31) (행별 기대값은 affected의 values)", ["PERIOD_LABEL_CHECK", "CROSS_FILE_MATCH", "FORMULA_READ"],
          cells=["A1", "A2", "%s7:%s%d" % (L["basePay"], L["basePay"], m["lastDataRow"]),
                 "%s7:%s%d" % (L["tenureAlw"], L["tenureAlw"], m["lastDataRow"])],
          rows=sorted(m["rowOf"][x] for x in old),
          values={"제목": {"actual": "2025년 12월 급여대장", "expected": "2026년 12월 급여대장"}},
          affected=[{"sheet": t, "rowNo": m["rowOf"][x], "empNo": x, "values": row_vals(t, x, ["basePay", "tenureAlw", "gross", "ded", "net"])}
                    for x in sorted(old, key=lambda x: m["rowOf"][x])] + spread(t, [m["rowOf"][x] for x in old]))
    for emp, val in acc_plan["hiddenStale"].items():
        t = next(e["dept"] for e in emps if e["empNo"] == emp)
        rn = sheet_meta[t]["rowOf"][emp]
        v = values[t][emp]
        A.add("HARDCODED_STALE", t, "숨긴 열 '통상임금'에 수식 대신 작년 금액 %s 입력 → 통상시급·법정수당 과소" % format(val, ","),
              ["FORMULA_READ", "HARDCODED_IN_FORMULA_COLUMN", "HIDDEN_CELLS"], empNo=emp, rowNo=rn,
              cells=["%s%d" % (L["ordinary"], rn)], hiddenColumn=True,
              values={"통상임금": {"actual": val, "expected": v["ideal"]["ordinary"]},
                      "통상시급": {"actual": v["actual"]["hourly"], "expected": v["ideal"]["hourly"]}},
              cascade=diff_cols(t, emp), affected=[{"sheet": t, "rowNo": rn}] + spread(t, [rn]))
    cap_rows = [(t, x) for t in teams for x in values[t]
                if values[t][x]["actual"]["np"] != values[t][x]["ideal"]["np"] and values[t][x]["ideal"]["npBase"] > B.NP_CAP_OLD]
    aff = [{"sheet": t, "rowNo": sheet_meta[t]["rowOf"][x], "empNo": x, "values": row_vals(t, x, ["np", "ded", "net"])} for t, x in cap_rows]
    aff_tot = []
    for t in dict.fromkeys(t for t, _ in cap_rows):
        aff_tot += [x for x in spread(t, [sheet_meta[t]["rowOf"][e] for tt, e in cap_rows if tt == t]) if x not in aff_tot]
    A.add("STALE_CAP", "설정", "국민연금 기준소득월액 상한이 2024-07~2025-06 고시값 %s원으로 남아 있음 (2026-12 귀속은 2026-07~ 상한 %s원·하한 %s원) "
          "→ 기준소득월액이 두 상한 사이인 %d명 국민연금 과소 공제 (공제계·실지급액, 부서 합계·총괄도 달라짐)"
          % (format(B.NP_CAP_OLD, ","), format(B.NP_CAP, ","), format(B.NP_FLOOR, ","), len(aff)), ["RATE_TABLE_BY_PERIOD", "LEGAL_RULE_COMPARE"], legal=True,
          rowNo=SET_ROW["npCap"], cells=["C%d" % SET_ROW["npCap"]] + ["%s!%s%d" % (x["sheet"], L[k], x["rowNo"]) for x in aff for k in ("np", "ded", "net")],
          values={"국민연금 상한": {"actual": B.NP_CAP_OLD, "expected": B.NP_CAP}},
          affected=[{"sheet": "설정", "rowNo": SET_ROW["npCap"]}] + aff + aff_tot)
    A.add("SUM_RANGE_MISSING", "총괄", "총괄 총계 SUM 범위가 마지막 부서(%s) 행을 빠뜨림" % teams[-1],
          ["FORMULA_READ", "RECOMPUTE_CHECK", "TOTAL_ROW_SKIP"], rowNo=sum_total_row,
          cells=["B%d:E%d" % (sum_total_row, sum_total_row)],
          formula={"actual": "=SUM(C4:C%d)" % last_inc, "expected": "=SUM(C4:C%d)" % (sum_total_row - 1)})
    a_short = A.items[-1]
    ht = acc_plan["hoursTypo"]
    t = next(e["dept"] for e in emps if e["empNo"] == ht["empNo"])
    rn = sheet_meta[t]["rowOf"][ht["empNo"]]
    A.add("HOURS_MISMATCH_CROSS_FILE", t, "연장시간을 근태 월집계에서 옮겨 적다 숫자 순서가 바뀜 (%s → %s)" % (ht["true"], ht["typed"]),
          ["CROSS_FILE_MATCH"], empNo=ht["empNo"], rowNo=rn, cells=["%s%d" % (L["otH"], rn)],
          values={"연장(h)": {"actual": ht["typed"], "expected": ht["true"]}}, cascade=diff_cols(t, ht["empNo"]),
          affected=[{"sheet": t, "rowNo": rn}] + spread(t, [rn]))
    # 사고 겹침: 총괄 총계 행은 프레스팀 2배(합계 행 사고)와 마지막 부서 누락(SUM 범위 사고)이 함께 틀리게 한다
    true_tot = {c: sum(sum(v["actual"][k] for v in values[t].values()) for t in teams) for c, k in zip("CDE", ("gross", "ded", "net"))}
    true_tot["B"] = sum(totals[t]["count"] for t in teams)
    cells17 = ["B%d" % sum_total_row] + ["%s%d" % (c, sum_total_row) for c in "CDE"]
    note = "둘 다 고친 값(다른 사고로 틀린 부서 금액은 그대로)"
    a_double["overlaps"] = [{"with": a_short["id"], "cells": cells17[1:], "note": note,
                             "trueValue": {"%s%d" % (c, sum_total_row): true_tot[c] for c in "CDE"}}]
    a_short["overlaps"] = [{"with": a_double["id"], "cells": cells17, "note": note,
                            "trueValue": {"%s%d" % (c, sum_total_row): true_tot[c] for c in "BCDE"}}]
    # 휴일근로 8시간 초과분: 회사 수식은 휴일(h) 전체 × 1.5 (법정은 8시간 초과분 2배)
    hol8 = []
    for t in teams:
        for x, v in values[t].items():
            over = acc_plan["holidayOver8"].get(x, 0)
            if over:
                h_act, h_ok, hh = v["actual"]["hourly"], v["ideal"]["hourly"], v["ideal"]["holH"]
                prem = lambda hr: ROUND(hr * (hh - over) * 1.5 + hr * over * 2, 0)  # noqa: E731
                hol8.append({"sheet": t, "rowNo": sheet_meta[t]["rowOf"][x], "empNo": x, "cells": ["%s%d" % (L["holPay"], sheet_meta[t]["rowOf"][x])],
                             "values": {"휴일수당": {"actual": v["actual"]["holPay"], "statutory": prem(h_act),
                                                 "statutoryWithCorrectHourly": prem(h_ok)}},
                             "note": "휴일 %s시간 중 하루 8시간 초과분 %s시간. statutory는 이 행의 통상시급 %s원 그대로 8시간 초과분만 2배로 고친 값, "
                                     "statutoryWithCorrectHourly는 통상시급도 설정 243 기준(%s원)으로 고친 값%s"
                                     % (hh, over, format(h_act, ","), format(h_ok, ","),
                                        " (이 시트는 /209 사고 JACH-PAY-01과 겹침)" if t == acc_plan["divisorTeam"] else "")})
    accidents_rows = {(a["sheet"], x) for a in A.items for x in ([a.get("rowNo")] + a.get("rows", [])) if x} | \
                     {(x["sheet"], x["rowNo"]) for a in A.items for x in a.get("affected", [])}
    # 근태 사고(퇴근 빈 칸·중복 행·12/25 '주간')로 월집계와 급여대장이 달라지는 사람이 있는 부서도 깨끗한 비교군에서 뺀다
    att_teams = {e["dept"] for e in emps if e["empNo"] in acc_plan["attEmps"]}
    clean_sheets = [t for t in teams if not any(s == t for s, _ in accidents_rows) and t not in
                    (acc_plan["divisorTeam"], acc_plan["wrongYearTeam"]) and t not in att_teams and not any(h["sheet"] == t for h in hol8)]
    key = {
        "file": path.name, "kind": "PAYROLL", "closestEntity": "PAY_RESULT_HISTORY", "supported": False,
        "sheetOrder": ["총괄"] + teams + ["설정", "간이세액표"],
        "sheets": {"총괄": {"role": "payrollSummary", "headerRows": [3], "firstDataRow": 4, "lastDataRow": sum_total_row - 1,
                          "totalRow": sum_total_row, "rowOf": sum_row},
                   **sheet_meta,
                   "설정": {"role": "settings", "values": {k: SV(k, cap_override) for k, *_ in SETTINGS},
                          "cells": {k: S(k) for k, *_ in SETTINGS}},
                   "간이세액표": {"role": "lookupTable", "note": "단순화한 간이세액표 (실제 국세청 표 아님)"}},
        "expectedMapping": {"monthlyPayroll": {h: k for k, h in PAY_COLS},
                            "payrollSummary": {"부서": "dept", "인원": "headcount", "지급계": "gross", "공제계": "ded", "실지급액": "net"}},
        "rules": {
            "ordinaryWage": {"expr": "basePay+positionAllowance+tenureAllowance+shiftAllowance", "hiddenColumn": L["ordinary"],
                             "note": "가족수당·식대는 통상임금에서 뺌 (회사 규칙. 고정 식대는 판례상 통상임금일 수 있음 — README 가정값)"},
            "ordinaryHourlyWage": {"expr": "round(ordinaryWage/243)", "divisor": 243, "divisorFrom": S("hours"), "rounding": "round1",
                                   "note": "단체협약상 토요일 8시간 유급 → 월 243시간"},
            "tenureAllowance": {"expr": "min(DATEDIF(입사일, 2026-12-31, Y), 20) × 10,000"},
            "shiftAllowance": {"expr": "조가 '상주'가 아니면 150,000"},
            "overtimePremium": {"rate": 1.5}, "nightPremium": {"rate": 0.5},
            "holidayPremium": {"rate": 1.5, "over8h": None, "note": "회사 수식은 휴일(h) 전체 × 1.5. 8시간 초과분 2배(근로기준법 제56조 제2항)를 따로 계산하지 않음 → 하루 8시간을 넘긴 특근이 있는 행은 legalDeviations"},
            "mealInInsuranceBase": False, "insuranceRounding": "floor10",
            "nationalPension": {"base": "기준소득월액(신고값, 열 '%s')" % hdr["npBase"], "floor": B.NP_FLOOR, "cap": B.NP_CAP,
                                "note": "상·하한은 2026-07~2027-06 고시값 (귀속월 2026-12). 건강·고용보험은 그달 과세대상 기준"},
            "incomeTax": "간이세액표 VLOOKUP(과세대상, 가족 수 최대 5)",
            "hoursSource": "교대근무기록 월집계(30분 미만 버림)를 값으로 옮겨 적음",
        },
        "legalDeviations": [{"rule": "nationalPensionCap", "company": "상한 %s원 (설정!C%d, 2024-07~2025-06 고시값)" % (format(B.NP_CAP_OLD, ","), SET_ROW["npCap"]),
                             "statutory": "%s원 (2026-07~ 귀속)" % format(B.NP_CAP, ","), "rows": len(aff)}]
                           + [{"rule": "holidayPremium", "sheet": h["sheet"], "rows": [h["rowNo"]], "empNo": h["empNo"], "cells": h["cells"],
                               "company": "휴일근로 전 시간 1.5배", "statutory": "8시간 이내 1.5배, 8시간 초과분 2배 (근로기준법 제56조 제2항)",
                               "values": h["values"], "note": h["note"]} for h in hol8],
        "accidents": A.items,
        "cleanSheets": clean_sheets,
        "valueNoise": [
            {"type": "EMPNO_NUMERIC", "detail": "사번이 8자리 숫자 셀 (입사연도+순번)"},
            {"type": "TWO_ROW_HEADER", "detail": "5행 병합 묶음 헤더, 6행 컬럼 헤더, 1~3행 제목·결재란"},
            {"type": "SUBTOTAL_ROWS", "detail": "2교대 부서는 A조·B조 소계 행이 데이터 중간에 있음 (subtotalRows)"},
            {"type": "HIDDEN_COLUMN", "column": L["ordinary"], "detail": "통상임금 열 숨김"},
            {"type": "ONE_SHEET_PER_DEPT", "detail": "부서마다 시트 하나, 같은 양식"},
        ],
        "crossFile": {"attendance": {"file": ATT_FILE, "sheet": "월집계",
                                     "detail": "연장(h)·야간(h)·휴일(h) = 월집계 값. 다른 곳은 근태 쪽 사고(OUT_MISSING·DUPLICATE_ROW·"
                                               "HOLIDAY_WORK_AS_WEEKDAY — 급여대장 값이 맞고 월집계가 틀림, 근태 정답지 crossFileAffected)나 "
                                               "급여 쪽 HOURS_MISMATCH_CROSS_FILE로 설명됨"}},
    }
    return key, values


def _att_spread(att_acc, att_emps, month_vals, month_row, true_min, pay_key, by_no):
    """근태 사고(퇴근 빈 칸·중복 행·12/25 '주간')가 바꾼 월집계 행과, 그 때문에 어긋나는 급여대장 행(값은 급여대장이 맞음)을 정답지에 남긴다"""
    owner = {a["empNo"]: a for a in att_acc if a["type"] in ("OUT_MISSING", "DUPLICATE_ROW", "HOLIDAY_WORK_AS_WEEKDAY")}
    lab = {"ot": "연장", "night": "야간", "hol": "휴일"}
    min_col, h_col, pay_col = {"ot": "E", "night": "F", "hol": "G"}, {"ot": "H", "night": "I", "hol": "J"}, {"ot": "otH", "night": "nightH", "hol": "holH"}
    for x, diff in att_emps.items():
        a = owner[x]  # 사고 없는 사람의 월집계는 급여대장과 같아야 한다
        mr = month_row[x]
        rows = a.get("rows") or [a["rowNo"]]
        a["affected"] = [{"sheet": a["sheet"], "rowNo": r} for r in rows] + [{"sheet": "월집계", "rowNo": mr}]
        ks = [k for k in ("ot", "night", "hol") if month_vals[x]["min"][k] != true_min[x][k] or k in diff]
        a["cells"] = a.get("cells", []) + ["월집계!%s%d" % (min_col[k], mr) for k in ks] + ["월집계!%s%d" % (h_col[k], mr) for k in diff]
        a.setdefault("values", {}).update({"월집계!%s(분)" % lab[k]: {"actual": month_vals[x]["min"][k], "expected": true_min[x][k]} for k in ks})
        a["values"].update({"월집계!%s(h)" % lab[k]: {"actual": m, "expected": p} for k, (m, p) in diff.items()})
        t = by_no[x]["dept"]
        pr = pay_key["sheets"][t]["rowOf"][x]
        L2 = {k: xl_col_to_name([c for c, _ in PAY_COLS].index(pay_col[k])) for k in diff}
        a["crossFileAffected"] = [{"file": PAY_FILE, "sheet": t, "rowNo": pr, "empNo": x, "cells": ["%s%d" % (L2[k], pr) for k in diff],
                                   "values": {"%s(h)" % lab[k]: {"payroll": p, "attendanceSummary": m} for k, (m, p) in diff.items()},
                                   "note": "급여대장은 사고 없는 근태 시간이라 값이 맞고, 월집계가 이 사고로 틀림 → 두 파일 대조 시 불일치"}]


# ================================================================ 회사 전체

def build(df, used, out_dir, seed):
    company = {"id": CID, "name": NAME, "industry": "제조",
               "profile": "600명 제조업, 주야 2교대, 부서별 급여 시트·조 소계·결재란·숨긴 열, 통상시급 /243, 국민연금 기준소득월액 상한"}
    cdir = out_dir / CID
    cdir.mkdir(parents=True, exist_ok=True)
    emps = people(df, used, seed)
    rng = B.rng_for(CID, "files", seed)
    for e in emps:  # 국민연금 기준소득월액(작년 소득 기준 신고값), 2025년 기본급
        e["basePay2025"] = ROUNDDOWN(e["basePay"] / (1 + e["raise"]), -4)
        fixed = e["basePay"] + POS_ALW.get(e["duty"], 0) + e["familyAlw"] + (150_000 if e["work"] == "2교대" else 0)
        e["npBase"] = ROUNDDOWN(fixed + (rng.randint(30, 70) * 10_000 if e["family"] == "생산직" else 0), -3)
    # 상한 사고가 드러나도록: 기준소득월액이 옛 상한(617만)~2026-07 상한(659만) 사이인 사람을 최소 3명
    tops = sorted([e for e in emps if e["status"] == "재직" and e["family"] == "사무직"], key=lambda e: -e["npBase"])[:3]
    for n, e in enumerate(tops):
        e["npBase"] = max(e["npBase"], [6_220_000, 6_300_000, 6_480_000][n])
    active = [e for e in emps if e["resignDate"] is None or e["resignDate"] >= dt.date(2026, 12, 1)]

    # 근태 사고 계획
    arng = B.rng_for(CID, "attendance", seed)
    prod_day = [e for e in active if e["family"] == "생산직" and e["hireDate"] < dt.date(2026, 11, 1)]
    force52 = arng.sample([e for e in prod_day if e["shift"] == "상주"], 2)
    plans = {e["empNo"]: plan(e, arng, force52=e in force52) for e in active}
    used_emps = set(e["empNo"] for e in force52)
    pick_row = lambda cond: arng.choice([r for e in active if e["empNo"] not in used_emps for r in plans[e["empNo"]] if cond(r)])  # noqa: E731
    om = pick_row(lambda r: r["kind"] == "주간" and r["emp"]["shift"] == "상주" and r["out"] is not None)
    used_emps.add(om["emp"]["empNo"])
    om["_otTrue"] = ot_min(om)
    om["_outTrue"], om["out"] = om["out"], None
    dup = pick_row(lambda r: r["kind"] == "야간" and r["date"] < dt.date(2026, 12, 21))
    used_emps.add(dup["emp"]["empNo"])
    # 12/25 특근을 '주간'으로 기록한 사람
    hx_e = arng.choice([e for e in active if e["family"] == "생산직" and e["empNo"] not in used_emps and e["shift"] == "B"
                        and not any(r["date"] == dt.date(2026, 12, 25) for r in plans[e["empNo"]])])
    hx = _row(hx_e, dt.date(2026, 12, 25), "특근", DAY_START, DAY_END + 60)
    hx["holidayAsWeekday"] = True
    plans[hx_e["empNo"]].append(hx)
    plans[hx_e["empNo"]].sort(key=lambda r: r["date"])
    used_emps.add(hx_e["empNo"])
    acc_att = {"midnightFrom": dt.date(2026, 12, 21), "outMissing": om, "dupRow": dup, "holidayAsWeekday": hx, "force52": force52}

    # 급여용 시간 = 근태 단말기 기준 '사고 없는' 값 (월집계가 아닌 원천)
    hours, true_min, hol_over8 = {}, {}, {}
    for e in active:
        rs = plans[e["empNo"]]
        ot = sum(om["_otTrue"] if r is om else ot_min(r) for r in rs)
        true_min[e["empNo"]] = {"ot": ot, "night": sum(night_min(r) for r in rs), "hol": sum(work_min(r) for r in rs if r["kind"] == "특근")}
        over = to_hours(sum(max(0, work_min(r) - 480) for r in rs if r["kind"] == "특근"))
        if over:
            hol_over8[e["empNo"]] = over
        hours[e["empNo"]] = {"ot": to_hours(ot), "night": to_hours(sum(night_min(r) for r in rs)),
                             "hol": to_hours(sum(work_min(r) for r in rs if r["kind"] == "특근"))}
    att_meta, att_acc, month_vals, month_row = write_attendance(cdir / ATT_FILE, active, plans, arng, acc_att)
    # 근태 사고가 월집계로 번진 사람 (급여대장은 사고 없는 시간을 쓰므로 두 파일이 달라진다)
    att_emps = {x: {k: (month_vals[x][k], hours[x][k]) for k in ("ot", "night", "hol") if month_vals[x][k] != hours[x][k]}
                for x in hours if any(month_vals[x][k] != hours[x][k] for k in ("ot", "night", "hol"))}

    # 급여 사고 계획
    prng = B.rng_for(CID, "payroll", seed)
    cand = lambda team: [e for e in active if e["dept"] == team and e["hireDate"] < dt.date(2026, 1, 1)]  # noqa: E731
    stale = {}
    for e in prng.sample(cand("가공1팀"), 3):
        prev_tenure = min(datedif_y(e["hireDate"], dt.date(2025, 12, 31)), 20) * 10_000
        stale[e["empNo"]] = e["basePay2025"] + POS_ALW.get(e["duty"], 0) + prev_tenure + (150_000 if e["work"] == "2교대" else 0)
    typo_c = [e for e in cand("조립팀") if e["empNo"] not in used_emps and e["empNo"] not in stale
              and hours[e["empNo"]]["ot"] >= 10 and len(set(str(int(hours[e["empNo"]]["ot"])))) == 2]
    te = prng.choice(typo_c)
    true_h = hours[te["empNo"]]["ot"]
    s = str(int(true_h))
    typed = float(s[::-1]) + (true_h - int(true_h))
    acc_pay = {"divisorTeam": "도장팀", "doubleCountTeam": "프레스팀", "wrongYearTeam": "물류팀", "hiddenStale": stale,
               "summaryShort": True, "hoursTypo": {"empNo": te["empNo"], "true": true_h, "typed": typed},
               "attEmps": set(att_emps), "holidayOver8": hol_over8}
    pay_key, _ = write_payroll(cdir / PAY_FILE, active, hours, acc_pay)
    _att_spread(att_acc, att_emps, month_vals, month_row, true_min, pay_key, {e["empNo"]: e for e in active})

    att_key = {
        "file": ATT_FILE, "kind": "ATTENDANCE", "closestEntity": None, "supported": False, "period": "2026-12",
        "sheetOrder": ["A조", "B조", "상주", "월집계"], "sheets": att_meta,
        "expectedMapping": {"dailyAttendance": {h: f for h, f in zip(ATT_COLS, ["workDate", "dayOfWeek", "empNo", "name", "dept", "workType",
                                                                                  "clockIn", "clockOut", "breakMinutes", "workMinutes",
                                                                                  "overtimeMinutes", "nightMinutes", "holidayMinutes"])},
                            "monthlySummary": {"사번": "empNo", "성명": "name", "부서": "dept", "조": "shift", "연장(분)": "overtimeMinutes",
                                               "야간(분)": "nightMinutes", "휴일(분)": "holidayMinutes", "연장(h)": "overtimeHours",
                                               "야간(h)": "nightHours", "휴일(h)": "holidayHours"}},
        "rules": {"workMinutes": "MOD(퇴근-출근,1)×1440 - 휴게 (자정 넘김 처리)",
                  "overtimeBasis": "정시퇴근(주간 17:00, 야간 05:00) 이후 퇴근 분, 특근은 연장 대신 휴일",
                  "nightWindow": "22:00~06:00 (야간 근무의 휴게 60분은 이 안에 있다고 봄)",
                  "shiftRotation": "A조는 1·3·5주차 야간, B조는 2·4주차 야간 (월요일 시작 주), 상주는 주간 고정",
                  "monthlyRounding": "월 합산 후 30분 미만 버림", "midnightCrossing": True},
        "accidents": att_acc,
        "valueNoise": [{"type": "EMPNO_NUMERIC", "detail": "사번 숫자 셀"},
                       {"type": "TIME_ONLY_ACROSS_MIDNIGHT", "detail": "야간 근무 퇴근은 날짜 없이 시각만 (다음날 05:xx)"}],
        "crossFile": {"payroll": {"file": PAY_FILE, "detail": "월집계 연장(h)·야간(h)·휴일(h) = 급여대장 근태(시간) (사고 행 제외)",
                                  "rows": len(month_vals)}},
    }

    # 직원명부·조직
    roster_key = write_roster(cdir, emps, seed)
    org_rows, org_cols = write_org(cdir)
    hr = {PAY_FILE: pay_key, ATT_FILE: att_key}
    key = B.answer_key(company, "직원명부.xlsx", "조직.csv", roster_key["mapping"], org_cols, roster_key["errors"],
                       {"employees": len(emps), "rosterRows": roster_key["rows"], "orgs": len(org_rows),
                        "payrollRows": len(hours)}, hr,
                       {"rosterLayout": roster_key["layout"]})
    return key


# ================================================================ 직원명부·조직

ROSTER_COLS = [("empNo", "사원코드"), ("name", "사원명"), ("birthDate", "생년월일"), ("_sex", "성별"), ("_rrn", "주민번호"),
               ("_hq", "본부"), ("dept", "부서명"), ("_shift", "근무조"), ("position", "직위"), ("grade", "직급"), ("duty", "직책"),
               ("jobFamily", "직군"), ("empType", "고용구분"), ("hireDate", "입사일"), ("status", "재직상태"),
               ("resignDate", "퇴사일자"), ("mobile", "핸드폰"), ("email", "E-MAIL")]
ROSTER_GROUPS = [("empNo", "_rrn", "인적사항"), ("_hq", "_shift", "소속"), ("position", "jobFamily", "직급·직책"),
                 ("empType", "resignDate", "근무"), ("mobile", "email", "연락처")]


def write_roster(cdir, emps, seed):
    rng = B.rng_for(CID, "roster", seed)
    errs = B.RosterErrors()
    rows = []
    for e in emps:
        rows.append({"empNo": int(e["empNo"]), "name": e["name"], "birthDate": e["birthDate"], "_sex": "남" if e["sex"] == "남자" else "여",
                     "_rrn": B.masked_rrn(e["birthDate"], e["sex"]), "_hq": e["hq"], "dept": e["dept"],
                     "_shift": {"A": "A조", "B": "B조"}.get(e["shift"], "상주"), "position": e["position"], "grade": e["grade"],
                     "duty": e["duty"], "jobFamily": e["family"], "empType": e["empType"], "hireDate": e["hireDate"],
                     "status": e["status"], "resignDate": e["resignDate"], "mobile": e["mobile"], "email": e["email"],
                     "_e": e})
    idx = list(range(len(rows)))
    rng.shuffle(idx)
    take = lambda k, cond=lambda r: True: [idx.pop(idx.index(i)) for i in [j for j in idx if cond(rows[j])][:k]]  # noqa: E731
    for i in take(3):
        rows[i]["hireDate"] = None
        errs.add(i, "hireDate", "REQUIRED", "입사일 빈 칸")
    for n, i in enumerate(take(2, lambda r: r["email"])):
        rows[i]["email"] = rows[i]["email"].replace("@", "") if n == 0 else rows[i]["email"].replace(".co.kr", ",co,kr")
        errs.add(i, "email", "INVALID_EMAIL", "이메일 형식 오류: %s" % rows[i]["email"])
    i, j = take(2)
    rows[j]["empNo"] = rows[i]["empNo"]
    errs.add(i, "empNo", "DUPLICATE_KEY", "사번 중복 (다른 사람)")
    errs.add(j, "empNo", "DUPLICATE_KEY", "사번 중복 (다른 사람) — 이 행의 실제 사번은 %s" % rows[j]["_e"]["empNo"])
    for i in take(1):
        rows[i]["empType"] = "정규"
        errs.add(i, "empType", "INVALID_ENUM", "고용구분 '정규' (회사 코드표에는 정규직·계약직·촉탁직)")
    for i in take(1, lambda r: r["status"] == "재직" and r["hireDate"]):
        rows[i]["status"], rows[i]["resignDate"] = "퇴직", rows[i]["hireDate"] - dt.timedelta(days=rng.randint(10, 200))
        errs.add(i, "resignDate", "DATE_ORDER", "퇴사일자 < 입사일")
    for i in take(1, lambda r: r["position"] == "기장"):
        rows[i]["position"] = "기쟝"
        errs.add(i, "position", "POSITION_TYPO", "직위 오타 '기쟝'", detectable=False)
    renamed = take(4, lambda r: r["dept"] == RENAME[1] and r["status"] == "재직")
    for i in renamed:
        rows[i]["dept"] = RENAME[0]
        errs.add(i, "dept", "DEPT_RENAMED", "부서명 '생산2팀'은 2026-09-01 '가공1팀'으로 바뀜 (조직.csv에는 사용여부 N)", detectable=False)
    for i in take(2, lambda r: r["status"] == "퇴직" and r["resignDate"]):
        rows[i]["status"] = "재직"
        errs.add(i, "status", "STATUS_CONFLICT", "재직상태 '재직'인데 퇴사일자가 있음 (퇴사 처리 누락)", detectable=False)
    # 같은 사람이 두 사번으로: 재입사 처리하며 새 사번을 받았는데 예전 행을 퇴사 처리하지 않음
    dup_src = [i for i in take(2, lambda r: r["status"] == "재직" and r["hireDate"] and r["dept"] != RENAME[0])]
    next_seq = max([int(r["empNo"]) for r in rows if str(r["empNo"]).startswith("2026")], default=20260000) + 1
    for n, i in enumerate(dup_src):
        new = dict(rows[i])
        new["empNo"] = next_seq + n
        new["hireDate"] = dt.date(2026, 3, 2) + dt.timedelta(days=n * 31)
        rows.append(new)
        errs.add(i, "name", "DUPLICATE_SUSPECTED", "같은 사람(이름·생년월일 같음)이 사번 %s로 다시 등록, 두 행 모두 재직" % new["empNo"])
        errs.add(len(rows) - 1, "name", "DUPLICATE_SUSPECTED", "같은 사람이 예전 사번 %s로도 재직 중" % rows[i]["empNo"])
    # 날짜 표기: 대부분 날짜 셀, 일부는 서식 없는 일련번호·문자
    for r in rows:
        for f in ("hireDate", "birthDate"):
            if r[f] is not None:
                style = B.date_style(rng, "cell", ["serial", "serial", "dot0"], 0.06)
                r[f] = B.fmt_date(r[f], style)
    B.date_format_errors(rows, ("hireDate", "birthDate", "resignDate"), errs)
    head_rows, first = B.write_roster_xlsx(
        cdir / "직원명부.xlsx", "사원명부", ROSTER_COLS, rows,
        title_rows=["사원명부", "출력일시: 2026-12-28 09:12   출력자: 인사총무팀"], groups=ROSTER_GROUPS, hidden=("_rrn",),
        widths={"name": 9, "dept": 11, "email": 26, "mobile": 14})
    mapping = {h: (None if f.startswith("_") else f) for f, h in ROSTER_COLS}
    return {"mapping": mapping, "errors": errs.rows(first), "rows": len(rows),
            "layout": {"sheet": "사원명부", "titleRows": [1, 2], "headerRows": head_rows, "firstDataRow": first,
                       "hiddenColumns": ["E (주민번호, 마스킹 값)"],
                       "notes": ["사번은 8자리 숫자 셀", "생산직 다수는 회사 이메일이 없음 (빈 칸은 오류 아님)",
                                 "고용구분 '촉탁직'은 정년 후 재고용 (오류 아님)"]}}


def write_org(cdir):
    org, code = [], {}
    hqs = sorted({t[0] for t in TEAMS})
    for i, hq in enumerate(hqs):
        code[hq] = "JC%02d00" % (i + 1)
        org.append({"deptCode": code[hq], "name": hq, "parentDeptCode": None, "_use": "Y"})
    n = {}
    for hq, team, _, _ in TEAMS:
        n[hq] = n.get(hq, 0) + 1
        org.append({"deptCode": code[hq][:4] + "%02d" % (n[hq] * 10), "name": team, "parentDeptCode": code[hq], "_use": "Y"})
    org.append({"deptCode": "JC0190", "name": RENAME[0], "parentDeptCode": code["생산본부"], "_use": "N"})
    cols = [("deptCode", "부서코드"), ("name", "부서명"), ("parentDeptCode", "상위부서코드"), ("_use", "사용여부")]
    B.write_org(cdir / "조직.csv", org, cols, "utf-8-sig")
    return org, cols
