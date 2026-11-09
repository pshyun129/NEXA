"""
거너요양병원 — 160명 요양병원. 병동 간호사·간호조무사는 3교대(D/E/N), 야간전담 간호사, 의사·원무 당직.

파일: 직원명부.xlsx(제목 2행 + 빈 행 + 헤더 4행, 직종·면허·자격번호 열, 날짜 셀 사이 문자 날짜),
     조직.csv(UTF-8 BOM, 간호부 아래 병동 3단계),
     근무표_2026-12.xlsx(병동마다 시트, 날짜가 열인 가로 배치, 칸에는 시간이 아니라 근무 코드 D/E/N/상/O/연, 오른쪽 COUNTIF 집계),
     급여대장_2026-12.xlsx(간호부·진료지원 두 시트가 열 구성이 다름, 간호부는 2줄 병합 헤더, 기준·세액표 시트).
급여 규칙: 통상시급 = ROUNDDOWN((기본급+직책수당+면허·자격수당)/209), 나이트수당 = N 횟수 × 4만, 야간가산 = 통상시급 × 야간시간 × 0.5
         (야간시간 = E×1.5 + N×6.5, 22~06시), 휴일(12/25) 근무 8시간 × 1.5, 당직 1회 10만, 4대보험·지방소득세 10원 미만 버림.
"""
import datetime as dt

from xlsxwriter.utility import xl_col_to_name

from hrfiles.common import Accidents
from hrfiles.xl import ROUND, ROUNDDOWN, Book

from . import base as B

CID, NAME = "geoneomed", "거너요양병원"
DUTY_FILE, PAY_FILE = "근무표_2026-12.xlsx", "급여대장_2026-12.xlsx"
WARDS = ["2병동", "3병동", "5병동"]
DAYS = B.days_of(2026, 12)
XMAS = dt.date(2026, 12, 25)
# 3교대 순환표 (21일): D 5일 → OFF 2 → E 5일 → OFF 2 → N 4일 → OFF 3. 어느 7일을 잘라도 OFF가 2일 이상, N 다음 바로 D/E 없음
ROT = "DDDDDOOEEEEEOONNNNOOO"
ROT_NIGHT = "NNNOO"  # 야간전담
SHIFT = {"D": ("07:00", "15:30", 0.0), "E": ("15:00", "23:30", 1.5), "N": ("23:00", "07:30", 6.5), "상": ("08:30", "17:30", 0.0)}
WORK_CODES = ("D", "E", "N", "상")

# (소속, 직종, 인원)
STAFF = [("진료부", "의사", 6), ("간호부", "간호사", 1)] + \
    [(w, k, n) for w in WARDS for k, n in (("간호사", 13), ("간호조무사", 9), ("요양보호사", 16))] + \
    [("재활치료실", "물리치료사", 8), ("약제과", "약사", 2), ("영양팀", "영양사", 2), ("영양팀", "조리원", 9),
     ("원무과", "행정직", 8), ("행정지원팀", "행정직", 6), ("행정지원팀", "시설관리", 4)]
LICENSE = {"의사": "의사", "간호사": "간호사", "간호조무사": "간호조무사", "물리치료사": "물리치료사", "약사": "약사", "영양사": "영양사",
           "요양보호사": "요양보호사"}
LICENSE_ALW = {"간호사": 50_000, "간호조무사": 30_000, "물리치료사": 50_000, "약사": 100_000, "영양사": 30_000}
DUTY_ALW = {"병원장": 1_000_000, "진료부장": 500_000, "간호부장": 400_000, "수간호사": 250_000, "책임간호사": 100_000,
            "과장": 200_000, "팀장": 150_000}
MIN_MONTHLY = B.MIN_WAGE[2026] * 209      # 2,156,880
STALE_MONTHLY = B.MIN_WAGE[2025] * 209    # 2,096,270


def _base_pay(kind, years, rng):
    if kind == "의사":
        return rng.randint(110, 140) * 100_000
    if kind in ("요양보호사", "조리원"):
        return MIN_MONTHLY + (rng.choice([0, 0, 0, 30_000, 50_000]) if years >= 2 else 0)
    start, step, cap = {"간호사": (2_900_000, 70_000, 12), "간호조무사": (2_300_000, 30_000, 10), "물리치료사": (2_700_000, 50_000, 10),
                        "약사": (5_500_000, 100_000, 10), "영양사": (2_600_000, 40_000, 10), "행정직": (2_500_000, 60_000, 15),
                        "시설관리": (2_400_000, 40_000, 10)}[kind]
    return start + step * min(years, cap)


def people(df, used, seed):
    rng = B.rng_for(CID, "people", seed)
    total = sum(n for *_x, n in STAFF)
    w = lambda o: 4.0 if B.has(o, "간호", "의사", "요양", "치료", "약사", "영양", "조리", "보건", "의료", "원무") else 1.0  # noqa: E731
    picked = B.pick(df, used, total, rng, CID, w, ages=(24, 62))
    picked.sort(key=lambda p: p["uuid"])
    rng.shuffle(picked)
    # 의사는 나이 많은 사람, 나머지는 섞어서
    picked.sort(key=lambda p: -p["age"])
    doctors, rest = picked[:6], picked[6:]
    rng.shuffle(rest)
    emps, k = [], 0
    for dept, kind, n in STAFF:
        group = doctors if kind == "의사" else rest[k:k + n]
        if kind != "의사":
            k += n
        group = sorted(group, key=lambda p: (-p["age"], p["uuid"]))
        for j, p in enumerate(group):
            age = int(p["age"])
            birth = B.birth_of(age, rng)
            hire = dt.date(max(2009, birth.year + 23 + rng.randint(0, 6)), rng.randint(1, 12), rng.randint(1, 28))
            hire = min(hire, dt.date(2026, 10, 1))
            if kind == "의사":
                pos = "병원장" if j == 0 else "진료부장" if j == 1 else "전문의"
            elif dept == "간호부":
                pos = "간호부장"
            elif kind == "간호사":
                pos = "수간호사" if j == 0 else "책임간호사" if j < 3 else "간호사"
            elif kind == "행정직":
                pos = ("과장" if dept == "원무과" else "팀장") if j == 0 else "계장" if age >= 40 else "주임" if age >= 32 else "사원"
            else:
                pos = "주임" if age >= 40 else "사원"
            shift = "상근"
            if dept in WARDS and kind in ("간호사", "간호조무사"):
                shift = "상근" if pos == "수간호사" else "3교대"
            elif kind == "의사":
                shift = "상근(당직)"
            years = 2026 - hire.year
            emps.append({"name": p["name"], "birthDate": birth, "sex": p["sex"], "dept": dept, "kind": kind, "position": pos,
                         "shift": shift, "hireDate": hire, "resignDate": None, "status": "재직",
                         "empType": "촉탁직" if age >= 60 else rng.choices(["정규직", "계약직"], [88, 12])[0],
                         "mobile": B.phone(rng), "basePay": _base_pay(kind, years, rng) // (1 if kind in ("요양보호사", "조리원") else 10_000) *
                         (1 if kind in ("요양보호사", "조리원") else 10_000),
                         "dutyAlw": DUTY_ALW.get(pos, 0), "licAlw": LICENSE_ALW.get(kind, 0),
                         "family": 1 + ("배우자" in (p["family_type"] or "")) + ("자녀" in (p["family_type"] or "")),
                         "license": ("%s 제%06d호" % (LICENSE[kind], rng.randint(10_000, 399_999))) if kind in LICENSE else None})
    # 병동마다 간호사 1명은 야간전담
    for wname in WARDS[:2]:
        nurse = next(e for e in emps if e["dept"] == wname and e["kind"] == "간호사" and e["position"] == "간호사")
        nurse["shift"] = "야간전담"
    # 올해 퇴직 6명, 휴직 2명, 12/1 신규 입사 4명 (12월 급여대장 맨 아래 행)
    pool = [e for e in emps if e["position"] in ("간호사", "사원") and e["shift"] != "야간전담"]
    pool.sort(key=lambda e: e["name"] + e["mobile"])
    rng.shuffle(pool)
    for e in pool[:6]:
        e["resignDate"] = dt.date(2026, rng.randint(2, 10), rng.randint(1, 28))
        e["hireDate"] = min(e["hireDate"], e["resignDate"] - dt.timedelta(days=200))
        e["status"] = "퇴직"
    for e in pool[6:8]:
        e["status"] = "휴직"
    newbies = [e for e in pool[8:] if e["dept"] in WARDS][:4]
    for e in newbies:
        e["hireDate"] = dt.date(2026, 12, 1)
        e["basePay"] = _base_pay(e["kind"], 0, rng) // (1 if e["kind"] == "요양보호사" else 10_000) * (1 if e["kind"] == "요양보호사" else 10_000)
        e["newbie"] = True
    emps.sort(key=lambda e: (e["hireDate"], e["name"]))
    seq = {}
    for e in emps:
        seq[e["hireDate"].year] = seq.get(e["hireDate"].year, 0) + 1
        e["empNo"] = "%d%03d" % (e["hireDate"].year, seq[e["hireDate"].year])
        e["email"] = ("%s@geoneo-hospital.kr" % e["empNo"]) if e["kind"] in ("의사", "행정직", "약사", "영양사") else None
    return emps


# ================================================================ 근무표

def plan_duty(emps, rng):
    """사번 → [코드 31개]. 병동 간호사·간호조무사만"""
    duty = {}
    for e in emps:
        if e["dept"] not in WARDS or e["kind"] not in ("간호사", "간호조무사") or e["status"] != "재직":
            continue
        off = rng.randint(0, 20)
        if e["shift"] == "야간전담":
            codes = [ROT_NIGHT[(k + off) % 5] for k in range(31)]
        elif e["position"] == "수간호사":
            codes = ["O" if d.weekday() >= 5 or d == XMAS else "상" for d in DAYS]
        else:
            codes = [ROT[(k + off) % 21] for k in range(31)]
        if e.get("newbie"):
            pass
        work = [k for k, c in enumerate(codes) if c in ("D", "E")]
        for k in rng.sample(work, min(len(work), rng.choice([0, 0, 1, 2]))):
            codes[k] = "연"
        duty[e["empNo"]] = codes
    return duty


def counts(codes):
    c = {x: sum(1 for v in codes if v == x) for x in ("D", "E", "N", "상", "O", "연")}
    c["work"] = c["D"] + c["E"] + c["N"] + c["상"]
    c["night"] = c["E"] * 1.5 + c["N"] * 6.5
    return c


def _consecutive(codes):
    best = run = 0
    for c in codes:
        run = run + 1 if c in WORK_CODES else 0
        best = max(best, run)
    return best


def inject_duty(duty, emps, rng):
    """근무표 사고. 반환: {종류: (사번, 날짜 인덱스 …)}"""
    by_ward = {w: sorted(no for no in duty if next(e for e in emps if e["empNo"] == no)["dept"] == w) for w in WARDS}
    shiftw = {e["empNo"]: e["shift"] for e in emps}
    acc = {}
    # 1) 3병동: N 칸 하나가 'N '(뒤 공백, 다른 표에서 복사해 붙임) → COUNTIF("N")가 못 셈
    cand = [no for no in by_ward["3병동"] if shiftw[no] == "3교대" and "N" in duty[no]]
    no = rng.choice(cand)
    k = rng.choice([i for i, c in enumerate(duty[no]) if c == "N"])
    acc["typo"] = (no, k)
    # 2) 2병동: N 다음 날 D (N이 07:30에 끝나는데 D는 07:00 시작 → 시간이 겹침)
    cand = [n for n in by_ward["2병동"] if shiftw[n] == "3교대"]
    rng.shuffle(cand)
    for no2 in cand:
        ks = [i for i in range(30) if duty[no2][i] == "N" and duty[no2][i + 1] == "O" and (i + 2 > 30 or duty[no2][i + 2] == "O")]
        if ks:
            acc["overlap"] = (no2, ks[0] + 1)
            break
    # 3) 2병동: E 다음 날 D (23:30 퇴근 → 07:00 출근, 휴식 7시간 30분 < 11시간)
    for no3 in cand:
        if no3 == acc["overlap"][0]:
            continue
        ks = [i for i in range(30) if duty[no3][i] == "E" and duty[no3][i + 1] == "O" and i + 2 <= 30 and duty[no3][i + 2] == "O"]
        if ks:
            acc["rest"] = (no3, ks[0] + 1)
            break
    # 4) 3병동: 결원 대체로 OFF 두 칸을 D로 → 12일 이상 연속 근무 (월~일 한 주 전체 휴일 없음)
    for no4 in sorted(set(by_ward["3병동"]) - {acc["typo"][0]}):
        if shiftw[no4] != "3교대":
            continue
        codes = duty[no4]
        for i in range(1, 29):
            if codes[i - 1] == "D" and codes[i] == "O" and codes[i + 1] == "O" and i + 2 < 31 and codes[i + 2] == "E":
                trial = list(codes)
                trial[i] = trial[i + 1] = "D"
                if _consecutive(trial) >= 10 and any(all(trial[j] in WORK_CODES for j in range(s, s + 7))
                                                      for s in range(31 - 6) if DAYS[s].weekday() == 0):
                    acc["norest"] = (no4, [i, i + 1])
                    break
        if "norest" in acc:
            break
    return acc


DUTY_COUNT_COLS = [("D", "D"), ("E", "E"), ("N", "N"), ("상", "상"), ("O", "O"), ("연", "연")]


def write_duty(path, emps, duty, acc):
    A = Accidents("GNMD-DUTY", path.name)
    book = Book(path)
    by_no = {e["empNo"]: e for e in emps}
    meta = {}
    first_day = 3  # D열부터
    day_col = {k: xl_col_to_name(first_day + k) for k in range(31)}
    cnt_col = {x: xl_col_to_name(first_day + 31 + i) for i, (x, _) in enumerate(DUTY_COUNT_COLS)}
    work_c, night_c = xl_col_to_name(first_day + 37), xl_col_to_name(first_day + 38)
    actual_counts = {}
    for w in WARDS:
        sh = book.sheet(w)
        sh.put("A", 1, "%s 2026년 12월 근무표" % w, "title")
        head = next(e for e in emps if e["dept"] == w and e["position"] == "수간호사")
        sh.put("A", 2, "작성: 수간호사 %s   확인: 간호부장 (인)   D 07:00~15:30 / E 15:00~23:30 / N 23:00~07:30 / 상 08:30~17:30" % head["name"])
        for c, h in zip("ABC", ("사번", "성명", "직종")):
            sh.put(c, 3, h, "head")
        for k, d in enumerate(DAYS):
            sh.put(day_col[k], 3, "%d(%s)" % (d.day, B.DOW[d.weekday()]), "head")
        for x, h in DUTY_COUNT_COLS:
            sh.put(cnt_col[x], 3, h, "head")
        sh.put(work_c, 3, "근무일수", "head")
        sh.put(night_c, 3, "야간시간", "head")
        members = sorted([no for no in duty if by_no[no]["dept"] == w],
                         key=lambda no: ({"수간호사": 0, "책임간호사": 1, "간호사": 2}.get(by_no[no]["position"], 3), by_no[no]["kind"] != "간호사", no))
        r, row_of = 4, {}
        for no in members:
            e = by_no[no]
            codes = list(duty[no])
            shown = list(codes)
            if acc["typo"][0] == no:
                shown[acc["typo"][1]] = "N "
            sh.put("A", r, no, "text")
            sh.put("B", r, e["name"])
            sh.put("C", r, e["kind"] if e["shift"] != "야간전담" else "간호사(야간전담)")
            for k in range(31):
                sh.put(day_col[k], r, shown[k])
            rng_ = "$%s%d:$%s%d" % (day_col[0], r, day_col[30], r)
            c = {x: sum(1 for v in shown if v == x) for x, _ in DUTY_COUNT_COLS}
            for x, _ in DUTY_COUNT_COLS:
                sh.formula(cnt_col[x], r, '=COUNTIF(%s,"%s")' % (rng_, x), c[x])
            work = c["D"] + c["E"] + c["N"] + c["상"]
            sh.formula(work_c, r, "=%s%d+%s%d+%s%d+%s%d" % (cnt_col["D"], r, cnt_col["E"], r, cnt_col["N"], r, cnt_col["상"], r), work)
            night = c["E"] * 1.5 + c["N"] * 6.5
            sh.formula(night_c, r, "=%s%d*1.5+%s%d*6.5" % (cnt_col["E"], r, cnt_col["N"], r), night, "num")
            actual_counts[no] = {"N": c["N"], "night": night}
            row_of[no] = r
            r += 1
        last = r - 1
        # 아래: 날짜별 근무 인원 (데이터 아님)
        sh.put("B", r + 1, "D 인원", "totalLabel")
        sh.put("B", r + 2, "E 인원", "totalLabel")
        sh.put("B", r + 3, "N 인원", "totalLabel")
        for i, x in enumerate("DEN"):
            for k in range(31):
                n = sum(1 for no in members if (duty[no][k] if not (acc["typo"][0] == no and acc["typo"][1] == k) else "N ") == x)
                sh.formula(day_col[k], r + 1 + i, '=COUNTIF(%s4:%s%d,"%s")' % (day_col[k], day_col[k], last, x), n)
        sh.ws.freeze_panes(3, 3)
        sh.widths({"A": 9, "B": 8, "C": 10})
        for k in range(31):
            sh.ws.set_column(first_day + k, first_day + k, 5)
        meta[w] = {"role": "dutyRosterWide", "headerRows": [3], "firstDataRow": 4, "lastDataRow": last, "titleRows": [1, 2],
                   "summaryRows": [r + 1, r + 2, r + 3], "dayColumns": "%s:%s (1~31일)" % (day_col[0], day_col[30]), "rowOf": row_of}
    book.close()

    no, k = acc["typo"]
    w = by_no[no]["dept"]
    r = meta[w]["rowOf"][no]
    tc = counts(duty[no])
    n_row = meta[w]["summaryRows"][2]  # 아래 'N 인원' 행
    n_day = sum(1 for x in duty if by_no[x]["dept"] == w and duty[x][k] == "N")
    A.add("KIND_TYPO", w, "12/%d 근무 코드가 'N '(뒤에 공백, 다른 표에서 복사) → COUNTIF(\"N\")가 세지 않아 N %d회 → %d회, 근무일수 %d → %d, "
          "야간시간 %s → %s, 아래 12/%d N 인원 %d → %d. 급여대장 나이트횟수·야간시간이 이 집계를 옮겨 적어 같이 틀림 (GNMD-PAY-03)"
          % (DAYS[k].day, tc["N"], tc["N"] - 1, tc["work"], tc["work"] - 1, tc["night"], tc["night"] - 6.5, DAYS[k].day, n_day, n_day - 1),
          ["VALUE_NORMALIZE", "RECOMPUTE_CHECK"], empNo=no, rowNo=r,
          cells=["%s%d" % (day_col[k], r), "%s%d" % (cnt_col["N"], r), "%s%d" % (work_c, r), "%s%d" % (night_c, r), "%s%d" % (day_col[k], n_row)],
          code={"cell": "%s%d" % (day_col[k], r), "actual": "N ", "expected": "N"},
          values={"N": {"actual": tc["N"] - 1, "expected": tc["N"]}, "근무일수": {"actual": tc["work"] - 1, "expected": tc["work"]},
                  "야간시간": {"actual": tc["night"] - 6.5, "expected": tc["night"]},
                  "N 인원": {"cell": "%s%d" % (day_col[k], n_row), "actual": n_day - 1, "expected": n_day}},
          affected=[{"sheet": w, "rowNo": r}, {"sheet": w, "rowNo": n_row}])
    no, k = acc["overlap"]
    w = by_no[no]["dept"]
    r = meta[w]["rowOf"][no]
    A.add("SHIFT_OVERLAP", w, "12/%d N(23:00~익일 07:30) 다음 날 12/%d D(07:00~) → 근무 시간이 30분 겹침 (있을 수 없는 배치)"
          % (DAYS[k - 1].day, DAYS[k].day), ["SHIFT_CODE_TIME", "CROSS_FIELD_RULE"], empNo=no, rowNo=r,
          cells=["%s%d" % (day_col[k - 1], r), "%s%d" % (day_col[k], r)])
    no, k = acc["rest"]
    w = by_no[no]["dept"]
    r = meta[w]["rowOf"][no]
    A.add("REST_INTERVAL_SHORT", w, "12/%d E(~23:30) 다음 날 12/%d D(07:00~) → 근무 사이 휴식 7시간 30분. 근로시간 특례(보건업, 제59조)를 "
          "쓰는 병원은 연속 11시간 휴식을 줘야 함" % (DAYS[k - 1].day, DAYS[k].day), ["SHIFT_CODE_TIME", "LEGAL_RULE_COMPARE"], legal=True,
          empNo=no, rowNo=r, cells=["%s%d" % (day_col[k - 1], r), "%s%d" % (day_col[k], r)])
    no, ks = acc["norest"]
    w = by_no[no]["dept"]
    r = meta[w]["rowOf"][no]
    A.add("NO_WEEKLY_REST", w, "결원 대체로 12/%d·%d OFF를 D로 바꿔 %d일 연속 근무 — 월~일 한 주에 휴무(O)가 하루도 없음. 병원 근무표 작성 기준"
          "(7일마다 휴무 1일 이상, rules.weeklyRest)에 어긋남. 근로기준법 제55조는 '1주 평균 1회 이상' 유급휴일이라 교대제에서 법 위반인지는 "
          "기간 평균과 다른 주 휴무로 따로 판단해야 함 (확정 위반 아님)"
          % (DAYS[ks[0]].day, DAYS[ks[1]].day, _consecutive(duty[no])), ["SHIFT_CODE_TIME", "CROSS_FIELD_RULE"],
          empNo=no, rowNo=r, cells=["%s%d" % (day_col[x], r) for x in ks], basis="회사 근무표 기준 (가정)")
    key = {"file": path.name, "kind": "ATTENDANCE", "closestEntity": None, "supported": False, "period": "2026-12",
           "sheetOrder": WARDS, "sheets": meta,
           "expectedMapping": {"dutyRosterWide": {"사번": "empNo", "성명": "name", "직종": "jobKind", "D": "dayShifts", "E": "eveningShifts",
                                                  "N": "nightShifts", "상": "regularDays", "O": "offDays", "연": "annualLeaveDays",
                                                  "근무일수": "workDays", "야간시간": "nightHours"}},
           "rules": {"codes": {c: "%s~%s" % SHIFT[c][:2] for c in SHIFT} | {"O": "휴무", "연": "연차"},
                     "nightHours": "야간(22~06시) = E 1.5시간 + N 6.5시간 (N 휴게 30분은 야간 중)",
                     "rotation": "21일 순환 (D5·O2·E5·O2·N4·O3), 야간전담은 N3·O2, 수간호사는 평일 '상'",
                     "restInterval": "근로시간 특례(보건업) 서면합의 병원 — 근무 사이 연속 11시간 휴식 (가정: 이 병원은 특례 합의가 있음)",
                     "weeklyRest": "근무표 작성 기준: 월~일 7일마다 휴무(O) 1일 이상 (병원 내부 기준, 가정. 근로기준법 제55조는 '1주 평균 1회 이상')"},
           "accidents": A.items, "cleanSheets": ["5병동"],
           "valueNoise": [{"type": "SHIFT_CODES", "detail": "칸 값이 시간이 아니라 근무 코드. 시간·야간시간은 코드표(2행 안내문)로 바꿔야 함"},
                          {"type": "DAY_HEADER_TEXT", "detail": "날짜 열 머리글이 '1(화)' 같은 문자 (연·월은 제목에만)"},
                          {"type": "SUMMARY_ROWS_BELOW", "detail": "데이터 아래 한 줄 띄고 날짜별 D/E/N 인원 행 3개"}],
           "crossFile": {"payroll": {"file": PAY_FILE, "detail": "간호부 시트 나이트횟수 = N 집계, 야간시간 = 야간시간 열 (옮겨 적은 값)"}}}
    return key, actual_counts, meta


# ================================================================ 급여대장

SETS = [("np", "국민연금", B.RATES_2026["np"], "pct"), ("hi", "건강보험", B.RATES_2026["hi"], "pct"),
        ("ltc", "장기요양(건보료 대비)", B.RATES_2026["ltc"], "pct"), ("ei", "고용보험", B.RATES_2026["ei"], "pct"),
        ("meal", "식대 비과세 한도", B.NONTAX_LIMIT, "won"), ("npFloor", "국민연금 기준소득월액 하한", B.NP_FLOOR, "won"),
        ("npCap", "국민연금 기준소득월액 상한", B.NP_CAP, "won"), ("nightAlw", "나이트 1회 수당", 40_000, "won"),
        ("dutyAlw", "당직 1회 수당", 100_000, "won")]

NURSE_COLS = [("empNo", "사번"), ("name", "성명"), ("dept", "병동"), ("kind", "직종"), ("shift", "근무형태"), ("family", "공제가족"),
              ("basePay", "기본급"), ("dutyAlw", "직책수당"), ("licAlw", "면허·자격수당"), ("hourly", "통상시급"), ("nights", "나이트횟수"),
              ("nightH", "야간시간"), ("holH", "휴일시간"), ("otH", "연장시간"), ("nightPay", "나이트수당"), ("nightPrem", "야간가산"),
              ("holPay", "휴일수당"), ("otPay", "연장수당"), ("meal", "식대"), ("gross", "지급계"), ("np", "국민연금"), ("hi", "건강보험"),
              ("ltc", "장기요양"), ("ei", "고용보험"), ("tax", "소득세"), ("ltax", "지방소득세"), ("ded", "공제계"), ("net", "실지급액"),
              ("memo", "비고")]
NURSE_GROUPS = [("empNo", "family", "인적사항"), ("basePay", "licAlw", "고정급"), ("hourly", "hourly", "통상임금"),
                ("nights", "otH", "근무실적(근무표)"), ("nightPay", "otPay", "변동수당"), ("meal", "meal", "비과세"),
                ("gross", "gross", "지급"), ("np", "ded", "공제"), ("net", "net", "차인"), ("memo", "memo", "")]
SUP_COLS = [("empNo", "사번"), ("name", "성명"), ("dept", "소속"), ("kind", "직종"), ("family", "공제가족"), ("basePay", "기본급"),
            ("dutyAlw", "직책수당"), ("licAlw", "면허·자격수당"), ("hourly", "통상시급"), ("duties", "당직횟수"), ("otH", "연장시간"),
            ("dutyPay", "당직수당"), ("otPay", "연장수당"), ("meal", "식대"), ("gross", "지급계"), ("np", "국민연금"), ("hi", "건강보험"),
            ("ltc", "장기요양"), ("ei", "고용보험"), ("tax", "소득세"), ("ltax", "지방소득세"), ("ded", "공제계"), ("net", "실지급액")]
INPUTS = ("empNo", "name", "dept", "kind", "shift", "family", "basePay", "dutyAlw", "licAlw", "nights", "nightH", "holH", "otH", "duties",
          "meal", "memo")


def calc(v, S, no_lic_in_hourly=False, zero_prem=False):
    v = dict(v)
    v["hourly"] = ROUNDDOWN((v["basePay"] + v["dutyAlw"] + (0 if no_lic_in_hourly else v["licAlw"])) / 209, 0)
    if "nights" in v:
        v["nightPay"] = v["nights"] * S["nightAlw"]
        v["nightPrem"] = 0 if zero_prem else ROUND(v["hourly"] * v["nightH"] * 0.5, 0)
        v["holPay"] = ROUND(v["hourly"] * v["holH"] * 1.5, 0)
        var = v["nightPay"] + v["nightPrem"] + v["holPay"]
    else:
        v["dutyPay"] = v["duties"] * S["dutyAlw"]
        var = v["dutyPay"]
    v["otPay"] = ROUND(v["hourly"] * v["otH"] * 1.5, 0)
    v["gross"] = v["basePay"] + v["dutyAlw"] + v["licAlw"] + var + v["otPay"] + v["meal"]
    taxable = v["gross"] - min(v["meal"], S["meal"])
    v["np"] = ROUNDDOWN(min(max(ROUNDDOWN(taxable, -3), S["npFloor"]), S["npCap"]) * S["np"], -1)
    v["hi"] = ROUNDDOWN(taxable * S["hi"], -1)
    v["ltc"] = ROUNDDOWN(v["hi"] * S["ltc"], -1)
    v["ei"] = ROUNDDOWN(taxable * S["ei"], -1)
    v["tax"] = B.tax_of(taxable, v["family"])
    v["ltax"] = ROUNDDOWN(v["tax"] * 0.1, -1)
    v["ded"] = v["np"] + v["hi"] + v["ltc"] + v["ei"] + v["tax"] + v["ltax"]
    v["net"] = v["gross"] - v["ded"]
    return v


def formulas(L, r, st, nurse, no_lic_in_hourly=False):
    R = lambda k: "%s%d" % (L[k], r)  # noqa: E731
    taxable = "(%s-MIN(%s,%s))" % (R("gross"), R("meal"), st.ref("meal"))
    f = {"hourly": "=ROUNDDOWN((%s+%s%s)/209,0)" % (R("basePay"), R("dutyAlw"), "" if no_lic_in_hourly else "+" + R("licAlw")),
         "otPay": "=ROUND(%s*%s*1.5,0)" % (R("hourly"), R("otH")),
         "np": "=ROUNDDOWN(MIN(MAX(ROUNDDOWN(%s,-3),%s),%s)*%s,-1)" % (taxable, st.ref("npFloor"), st.ref("npCap"), st.ref("np")),
         "hi": "=ROUNDDOWN(%s*%s,-1)" % (taxable, st.ref("hi")),
         "ltc": "=ROUNDDOWN(%s*%s,-1)" % (R("hi"), st.ref("ltc")),
         "ei": "=ROUNDDOWN(%s*%s,-1)" % (taxable, st.ref("ei")),
         "tax": "=" + B.tax_formula(taxable, R("family")),
         "ltax": "=ROUNDDOWN(%s*0.1,-1)" % R("tax"),
         "ded": "=SUM(%s:%s)" % (R("np"), R("ltax")),
         "net": "=%s-%s" % (R("gross"), R("ded"))}
    if nurse:
        f.update(nightPay="=%s*%s" % (R("nights"), st.ref("nightAlw")),
                 nightPrem="=ROUND(%s*%s*0.5,0)" % (R("hourly"), R("nightH")),
                 holPay="=ROUND(%s*%s*1.5,0)" % (R("hourly"), R("holH")),
                 gross="=SUM(%s:%s)+SUM(%s:%s)" % (R("basePay"), R("licAlw"), R("nightPay"), R("meal")))
    else:
        f.update(dutyPay="=%s*%s" % (R("duties"), st.ref("dutyAlw")),
                 gross="=SUM(%s:%s)+SUM(%s:%s)" % (R("basePay"), R("licAlw"), R("dutyPay"), R("meal")))
    return f


HOURS = ("nights", "nightH", "holH", "otH", "duties", "family")


def write_payroll(path, emps, duty, duty_counts, acc, rng):
    A = Accidents("GNMD-PAY", path.name)
    book = Book(path)
    s_n, s_s = book.sheet("간호부"), book.sheet("진료지원")
    st = B.Settings2(book, "기준", SETS)
    tax_meta = B.write_tax_sheet(book)
    S = st.val
    active = [e for e in emps if e["status"] == "재직"]
    nursing = [e for e in active if e["dept"] in WARDS or e["dept"] == "간호부"]
    support = [e for e in active if e not in nursing]
    order = lambda e: (WARDS.index(e["dept"]) if e["dept"] in WARDS else -1, ["간호사", "간호조무사", "요양보호사"].index(e["kind"]), e["empNo"])  # noqa: E731
    nursing = sorted([e for e in nursing if not e.get("newbie")], key=order) + sorted([e for e in nursing if e.get("newbie")], key=lambda e: e["empNo"])
    support.sort(key=lambda e: (["진료부", "재활치료실", "약제과", "영양팀", "원무과", "행정지원팀"].index(e["dept"]), e["empNo"]))
    meta, vals = {}, {}

    # ---- 간호부 (2줄 병합 헤더)
    L = {k: xl_col_to_name(i) for i, (k, _) in enumerate(NURSE_COLS)}
    s_n.put("A", 1, "2026년 12월 급여대장 (간호부)", "title")
    s_n.put("A", 2, "지급일 2027-01-10 · 작성 원무과 · 근무실적은 병동 근무표 집계를 옮겨 적음")
    for a, b, label in NURSE_GROUPS:
        if a == b:
            s_n.put(L[a], 3, label, "head")
        else:
            s_n.merge("%s3:%s3" % (L[a], L[b]), label)
    for k, h in NURSE_COLS:
        s_n.put(L[k], 4, h, "head")
    r, row_of = 5, {}
    for e in nursing:
        c = counts(duty[e["empNo"]]) if e["empNo"] in duty else None
        shown = duty_counts.get(e["empNo"])
        inp = {"empNo": e["empNo"], "name": e["name"], "dept": e["dept"], "kind": e["kind"], "shift": e["shift"], "family": min(e["family"], 5),
               "basePay": e["basePay"], "dutyAlw": e["dutyAlw"], "licAlw": e["licAlw"],
               "nights": shown["N"] if shown else 0, "nightH": shown["night"] if shown else 0,
               "holH": 8 if c and duty[e["empNo"]][XMAS.day - 1] in ("D", "E", "N") else 0,
               "otH": rng.choice([0, 0, 1, 1.5, 2, 3, 4, 5.5]) if e["kind"] != "요양보호사" else rng.choice([0, 0, 0, 1, 2]),
               "meal": 200_000, "memo": None}
        ideal_inp = dict(inp)
        if c:
            ideal_inp.update(nights=c["N"], nightH=c["night"])
        opts = {}
        if e["empNo"] == acc["stale"]:
            inp["basePay"] = STALE_MONTHLY
            ideal_inp["basePay"] = MIN_MONTHLY
            inp["memo"] = "2025 계약 갱신 전"
        if e["empNo"] == acc["hoursTypo"][0]:
            inp["nights"] = acc["hoursTypo"][1]
        if e["shift"] == "야간전담":
            opts["zero_prem"] = True
            inp["memo"] = "야간전담수당에 포함"
        if e.get("newbie"):
            opts["no_lic_in_hourly"] = True
            inp["memo"] = "12/1 입사"
        actual, ideal = calc(inp, S, **opts), calc(ideal_inp, S)
        fs = formulas(L, r, st, True, no_lic_in_hourly=bool(e.get("newbie")))
        for k, _ in NURSE_COLS:
            v = actual[k]
            f = fs.get(k)
            if k == "nightPrem" and opts.get("zero_prem"):
                f = None
            if f:
                s_n.formula(L[k], r, f, v, B.money(v) or ("num" if k in HOURS else None))
            else:
                s_n.put(L[k], r, v, "text" if k == "empNo" else (B.money(v) if k not in HOURS else "num"))
        row_of[e["empNo"]] = r
        vals[e["empNo"]] = (actual, ideal)
        r += 1
    total = r
    s_n.put(L["name"], total, "합계", "totalLabel")
    for k, _ in NURSE_COLS[NURSE_COLS.index(("basePay", "기본급")):-1]:
        s_n.formula(L[k], total, "=SUM(%s5:%s%d)" % (L[k], L[k], total - 1), sum(vals[e["empNo"]][0][k] for e in nursing),
                    "total" if k not in HOURS else "num")
    s_n.ws.freeze_panes(4, 2)
    s_n.widths({"B": 8, "E": 9})
    meta["간호부"] = {"role": "monthlyPayrollNursing", "payMonth": "2026-12", "headerRows": [3, 4], "firstDataRow": 5, "lastDataRow": total - 1,
                    "totalRow": total, "titleRows": [1, 2], "rowOf": row_of}

    # ---- 진료지원 (1줄 헤더, 사고 없음)
    L2 = {k: xl_col_to_name(i) for i, (k, _) in enumerate(SUP_COLS)}
    s_s.put("A", 1, "2026년 12월 급여대장 (진료·지원)", "title")
    for k, h in SUP_COLS:
        s_s.put(L2[k], 3, h, "head")
    r, row2 = 4, {}
    for e in support:
        inp = {"empNo": e["empNo"], "name": e["name"], "dept": e["dept"], "kind": e["kind"], "family": min(e["family"], 5),
               "basePay": e["basePay"], "dutyAlw": e["dutyAlw"], "licAlw": e["licAlw"],
               "duties": rng.choice([4, 5, 5, 6]) if e["kind"] == "의사" else (rng.choice([1, 2, 2]) if e["dept"] == "원무과" else 0),
               "otH": 0 if e["kind"] == "의사" else rng.choice([0, 0, 2, 4, 6]), "meal": 200_000}
        v = calc(inp, S)
        fs = formulas(L2, r, st, False)
        for k, _ in SUP_COLS:
            if fs.get(k):
                s_s.formula(L2[k], r, fs[k], v[k], B.money(v[k]) or ("num" if k in HOURS else None))
            else:
                s_s.put(L2[k], r, v[k], "text" if k == "empNo" else (B.money(v[k]) if k not in HOURS else "num"))
        row2[e["empNo"]] = r
        vals[e["empNo"]] = (v, v)
        r += 1
    s_s.put(L2["name"], r, "합계", "totalLabel")
    for k, _ in SUP_COLS[SUP_COLS.index(("basePay", "기본급")):]:
        s_s.formula(L2[k], r, "=SUM(%s4:%s%d)" % (L2[k], L2[k], r - 1), sum(vals[e["empNo"]][0][k] for e in support),
                    "total" if k not in HOURS else "num")
    s_s.ws.freeze_panes(3, 2)
    meta["진료지원"] = {"role": "monthlyPayrollSupport", "payMonth": "2026-12", "headerRows": [3], "firstDataRow": 4, "lastDataRow": r - 1,
                     "totalRow": r, "titleRows": [1], "rowOf": row2}
    book.close()

    # ---- 정답지
    def cell(k, no):
        return "%s%d" % (L[k], row_of[no])

    H = dict(NURSE_COLS)

    def changed(no):
        """사고로 값이 달라진 열 (입력 칸 + 수식으로 번진 칸)"""
        a, i = vals[no]
        return [k for k, _ in NURSE_COLS if k not in ("memo",) and a.get(k) != i.get(k)]

    def diff_vals(no):
        a, i = vals[no]
        return {H[k]: {"actual": a[k], "expected": i[k]} for k in changed(no)}

    def where(*nos):
        """값이 달라진 행 + 합계 행 (합계는 SUM이라 같이 달라진다)"""
        return [{"sheet": "간호부", "rowNo": row_of[x]} for x in nos] + [{"sheet": "간호부", "rowNo": total}]

    no = acc["stale"]
    a, i = vals[no]
    A.add("MIN_WAGE_STALE", "간호부", "요양보호사 기본급 2,096,270원 = 2025 최저시급 10,030원 × 209 그대로 (2026은 10,320원 × 209 = 2,156,880원)",
          ["LEGAL_RULE_COMPARE", "RATE_TABLE_BY_PERIOD"], legal=True, empNo=no, rowNo=row_of[no], cells=[cell(k, no) for k in changed(no)],
          values=diff_vals(no), cascade=[H[k] for k in changed(no) if k != "basePay"], affected=where(no))
    no, typed = acc["hoursTypo"]
    a, i = vals[no]
    A.add("HOURS_MISMATCH_CROSS_FILE", "간호부", "근무표 N %d회를 나이트횟수 %d로 옮겨 적음 (야간시간은 맞음) → 나이트수당 %s원 과다"
          % (i["nights"], typed, format(a["nightPay"] - i["nightPay"], ",")), ["CROSS_FILE_MATCH"], empNo=no, rowNo=row_of[no],
          cells=[cell(k, no) for k in changed(no)], values=diff_vals(no), cascade=[H[k] for k in changed(no) if k != "nights"], affected=where(no))
    no = acc["typoEmp"]
    a, i = vals[no]
    A.add("CARRIED_FROM_SOURCE", "간호부", "근무표의 'N '(공백) 사고(GNMD-DUTY-01)로 줄어든 N 집계를 그대로 옮김 → 나이트횟수 1회·야간시간 6.5시간 부족, "
          "나이트수당·야간가산과 그 뒤 지급계·보험료·세금·실지급액까지 같이 틀림",
          ["CROSS_FILE_MATCH"], empNo=no, rowNo=row_of[no], source="GNMD-DUTY-01", cells=[cell(k, no) for k in changed(no)],
          values=diff_vals(no), cascade=[H[k] for k in changed(no) if k not in ("nights", "nightH")], affected=where(no))
    night = sorted((e["empNo"] for e in nursing if e["shift"] == "야간전담"), key=lambda x: row_of[x])
    A.add("NIGHT_PREMIUM_MISSING", "간호부", "야간전담 간호사 %d명: 야간가산 칸에 수식 대신 0 (비고 '야간전담수당에 포함'), 별도 야간전담수당 항목·포괄 약정 없음 "
          "→ 야간근로 가산(통상임금 50%%) 미지급 (제56조)" % len(night), ["HARDCODED_IN_FORMULA_COLUMN", "LEGAL_RULE_COMPARE"], legal=True,
          empNos=night, rows=[row_of[x] for x in night], cells=[cell("nightPrem", x) for x in night],
          values={x: diff_vals(x) for x in night}, cascade=sorted({H[k] for x in night for k in changed(x)} - {"야간가산"}, key=list(H.values()).index),
          affected=where(*night))
    nb = sorted((e["empNo"] for e in nursing if e.get("newbie")), key=lambda x: row_of[x])
    hit = [x for x in nb if vals[x][0]["hourly"] != vals[x][1]["hourly"]]
    A.add("ORDINARY_WAGE_ITEM_MISSING", "간호부", "12/1 입사자 %d명 행(맨 아래 추가)의 통상시급 수식이 옛 양식 =ROUNDDOWN((기본급+직책수당)/209,0) — "
          "매달 고정 지급하는 면허·자격수당이 통상임금에서 빠짐 → 야간가산·휴일·연장수당 과소 (값이 달라진 행 %d명)" % (len(nb), len(hit)),
          ["FORMULA_READ", "RULE_EXTRACTION", "LEGAL_RULE_COMPARE"], legal=True, empNos=nb, rows=[row_of[x] for x in nb],
          cells=[cell("hourly", x) for x in nb], formula={"actual": "=ROUNDDOWN((G{r}+H{r})/209,0)", "expected": "=ROUNDDOWN((G{r}+H{r}+I{r})/209,0)"},
          values={x: diff_vals(x) for x in hit}, cascade=sorted({H[k] for x in hit for k in changed(x)} - {"통상시급"}, key=list(H.values()).index),
          affected=where(*nb))
    return {"file": path.name, "kind": "PAYROLL", "closestEntity": "PAY_RESULT_HISTORY", "supported": False,
            "sheetOrder": ["간호부", "진료지원", "기준", "세액표"],
            "sheets": {**meta, "기준": st.meta(), "세액표": tax_meta},
            "expectedMapping": {"monthlyPayrollNursing": {h: k for k, h in NURSE_COLS},
                                "monthlyPayrollSupport": {h: k for k, h in SUP_COLS}},
            "rules": {"ordinaryHourlyWage": {"expr": "floor((basePay+dutyAllowance+licenseAllowance)/209)",
                                             "note": "면허·자격수당(간호사·약사 등 면허, 간호조무사·영양사 자격)은 매달 고정 지급 → 통상임금"},
                      "nightAllowance": "N 1회 4만원 (회사 수당, 법정 야간가산과 별개)",
                      "nightPremium": "ROUND(통상시급 × 야간시간 × 0.5)", "holidayWork": "12/25 근무표 D/E/N이면 8시간 × 1.5 (회사 단순화)",
                      "dutyAllowance": "당직 1회 10만원 (의사·원무과)", "insuranceRounding": "floor10", "mealInInsuranceBase": False,
                      "nationalPension": {"floor": B.NP_FLOOR, "cap": B.NP_CAP, "period": "2026-07 ~ 2027-06 (보건복지부고시 제2026-31호, 귀속월 기준)"}, "incomeTax": "간이세액표(단순화) × 공제가족"},
            "legalDeviations": [{"rule": "minimumWage", "sheet": "간호부", "company": "기본급 2,096,270원 1명", "statutory": "2,156,880원 (10,320×209)"},
                                {"rule": "nightPremium", "sheet": "간호부", "company": "야간전담 야간가산 0", "statutory": "통상임금 50% 가산"},
                                {"rule": "ordinaryWageItems", "sheet": "간호부", "company": "신규 입사자 행 면허·자격수당 제외", "statutory": "고정 수당은 통상임금"}],
            "accidents": A.items, "cleanSheets": ["진료지원"],
            "valueNoise": [{"type": "DIFFERENT_COLUMNS_PER_SHEET", "detail": "간호부(나이트·야간·휴일)와 진료지원(당직) 시트의 열 구성이 다름"},
                           {"type": "TWO_ROW_MERGED_HEADER", "detail": "간호부 시트 3행 병합 묶음 헤더 + 4행 컬럼 헤더"},
                           {"type": "NEW_ROWS_AT_BOTTOM", "detail": "신규 입사자는 병동 순서와 관계없이 맨 아래"}],
            "legitUnusual": ["의사 국민연금은 기준소득월액 상한 %s원 기준 (2026-07부터 적용되는 상한, 보건복지부고시 제2026-31호 — 상한 적용이 맞음)"
                             % format(B.NP_CAP, ","),
                             "휴직자 2명은 급여대장에 없음 (명부 재직구분 '휴직')", "야간전담 간호사 N 18~19회 (정상 근무 형태)"],
            "crossFile": {"duty": {"file": DUTY_FILE, "detail": "나이트횟수·야간시간 = 근무표 N 집계·야간시간"}}}


# ================================================================ 직원명부·조직

ROSTER_COLS = [("empNo", "사번"), ("name", "성명"), ("jobKind", "직종"), ("_license", "면허·자격번호"), ("dept", "소속"), ("position", "직위"),
               ("_shift", "근무형태"), ("empType", "고용형태"), ("hireDate", "입사일"), ("birthDate", "생년월일"), ("_sex", "성별"),
               ("mobile", "휴대폰"), ("email", "이메일"), ("status", "재직구분"), ("resignDate", "퇴직일"), ("_memo", "비고")]


def write_roster(cdir, emps, seed):
    rng = B.rng_for(CID, "roster", seed)
    errs = B.RosterErrors()
    rows = [{"empNo": e["empNo"], "name": e["name"], "jobKind": e["kind"], "_license": e["license"], "dept": e["dept"],
             "position": e["position"], "_shift": e["shift"], "empType": e["empType"], "hireDate": e["hireDate"], "birthDate": e["birthDate"],
             "_sex": "남" if e["sex"] == "남자" else "여", "mobile": e["mobile"], "email": e["email"], "status": e["status"],
             "resignDate": e["resignDate"], "_memo": "육아휴직" if e["status"] == "휴직" else None, "_e": e} for e in emps]
    idx = list(range(len(rows)))
    rng.shuffle(idx)
    take = lambda k, cond=lambda r: True: [idx.pop(idx.index(i)) for i in [j for j in idx if cond(rows[j])][:k]]  # noqa: E731
    for i in take(2, lambda r: r["status"] == "재직" and not r["_e"].get("newbie")):
        rows[i]["hireDate"] = None
        errs.add(i, "hireDate", "REQUIRED", "입사일 빈 칸")
    for i in take(1, lambda r: r["email"]):
        rows[i]["email"] = rows[i]["email"].replace("@", "")
        errs.add(i, "email", "INVALID_EMAIL", "이메일에 @ 없음: %s" % rows[i]["email"])
    i, j = take(2, lambda r: r["status"] == "재직")
    rows[j]["empNo"] = rows[i]["empNo"]
    errs.add(i, "empNo", "DUPLICATE_KEY", "사번 중복 (다른 사람)")
    errs.add(j, "empNo", "DUPLICATE_KEY", "사번 중복 — 이 행의 실제 사번은 %s" % rows[j]["_e"]["empNo"])
    for i in take(1, lambda r: r["empType"] == "계약직"):
        rows[i]["empType"] = "정직원"
        errs.add(i, "empType", "INVALID_ENUM", "고용형태 '정직원' (코드표: 정규직·계약직·촉탁직)")
    for i in take(1, lambda r: r["status"] == "퇴직" and r["hireDate"]):
        rows[i]["resignDate"] = rows[i]["hireDate"] - dt.timedelta(days=rng.randint(20, 300))
        errs.add(i, "resignDate", "DATE_ORDER", "퇴직일 < 입사일")
    for i in take(1, lambda r: r["position"] == "책임간호사"):
        rows[i]["position"] = "책임간호시"
        errs.add(i, "position", "POSITION_TYPO", "직위 오타 '책임간호시'", detectable=False)
    for i in take(1, lambda r: r["jobKind"] == "간호사" and r["status"] == "재직"):
        rows[i]["_license"] = None
        errs.add(i, "면허·자격번호", "LICENSE_MISSING", "직종 간호사인데 면허·자격번호 빈 칸 (간호등급 산정·면허 확인 대상)", detectable=False)
    for i in take(1, lambda r: r["jobKind"] == "간호사" and r["status"] == "재직" and r["position"] == "간호사"):
        rows[i]["_license"] = rows[i]["_license"].replace("간호사", "간호조무사")
        errs.add(i, "jobKind", "LICENSE_MISMATCH", "직종 '간호사'인데 면허·자격번호는 '간호조무사' 자격 (직종 또는 면허 입력 오류)", detectable=False)
    # 재입사: 예전 행을 퇴직 처리하지 않은 1명(오류) + 퇴직 처리된 예전 행이 남은 1명(정상 이력)
    src = take(2, lambda r: r["status"] == "재직" and r["jobKind"] in ("간호사", "간호조무사") and r["hireDate"] and r["hireDate"].year >= 2022)
    old = dict(rows[src[0]])
    old.update(empNo="%d%03d" % (2019, 900 + rng.randint(1, 50)), hireDate=dt.date(2019, 4, 1), _memo="재입사 전 사번", email=None)
    rows.append(old)
    errs.add(src[0], "name", "DUPLICATE_SUSPECTED", "같은 사람(성명·생년월일 같음)이 예전 사번 %s로도 '재직'" % old["empNo"])
    errs.add(len(rows) - 1, "name", "DUPLICATE_SUSPECTED", "재입사 전 행이 퇴직 처리되지 않음 (새 사번 %s)" % rows[src[0]]["empNo"])
    hist = dict(rows[src[1]])
    hist.update(empNo="%d%03d" % (2018, 900 + rng.randint(1, 50)), hireDate=dt.date(2018, 3, 2), status="퇴직",
                resignDate=dt.date(2020, 8, 31), _memo="재입사자 (이전 근무 이력)", email=None)
    rows.append(hist)
    legit_row = len(rows) - 1
    for r in rows:
        for f in ("hireDate", "birthDate", "resignDate"):
            if r[f] is not None:
                r[f] = B.fmt_date(r[f], B.date_style(rng, "cell", ["dot", "dot0"], 0.06))
    B.date_format_errors(rows, ("hireDate", "birthDate", "resignDate"), errs)
    head_rows, first = B.write_roster_xlsx(
        cdir / "직원명부.xlsx", "직원현황", ROSTER_COLS, rows,
        title_rows=["거너요양병원 직원 현황", "기준일 2026-12-20 / 원무과"], widths={"name": 8, "_license": 20, "email": 26, "mobile": 14})
    mapping = {h: (None if f.startswith("_") else f) for f, h in ROSTER_COLS}
    return {"mapping": mapping, "errors": errs.rows(first), "rows": len(rows),
            "layout": {"sheet": "직원현황", "titleRows": [1, 2], "headerRows": head_rows, "firstDataRow": first,
                       "notes": ["사번 = 입사연도 + 순번 (7자리 문자)", "면허·자격번호 '간호사 제123456호' 형식 (간호조무사·요양보호사는 자격, 매핑 없는 열)",
                                 "간호사·간호조무사·요양보호사 등 다수는 이메일 빈 칸 (오류 아님)", "재직구분 재직·휴직·퇴직, 고용형태 촉탁직(60세 이상)",
                                 "직위 병원장·진료부장·간호부장·수간호사·책임간호사·계장 (회사 고유 값)"],
                       "legitUnusual": [{"rowNo": first + legit_row, "detail": "재입사자의 예전 사번 행 — 퇴직 처리돼 있어 이력으로 정상 (오류 아님)"}]}}


def write_org(cdir):
    org = [{"deptCode": "GN000", "name": NAME, "parentDeptCode": None, "_head": "병원장"},
           {"deptCode": "GN100", "name": "진료부", "parentDeptCode": "GN000", "_head": "진료부장"},
           {"deptCode": "GN200", "name": "간호부", "parentDeptCode": "GN000", "_head": "간호부장"}]
    org += [{"deptCode": "GN2%02d" % (n * 10), "name": w, "parentDeptCode": "GN200", "_head": "수간호사"} for n, w in enumerate(WARDS, 1)]
    org += [{"deptCode": "GN300", "name": "진료지원부", "parentDeptCode": "GN000", "_head": None}]
    org += [{"deptCode": "GN3%02d" % (n * 10), "name": d, "parentDeptCode": "GN300", "_head": None} for n, d in enumerate(["재활치료실", "약제과", "영양팀"], 1)]
    org += [{"deptCode": "GN400", "name": "행정부", "parentDeptCode": "GN000", "_head": None}]
    org += [{"deptCode": "GN4%02d" % (n * 10), "name": d, "parentDeptCode": "GN400", "_head": None} for n, d in enumerate(["원무과", "행정지원팀"], 1)]
    cols = [("deptCode", "부서코드"), ("name", "부서명"), ("parentDeptCode", "상위부서코드"), ("_head", "부서장 직위")]
    B.write_org(cdir / "조직.csv", org, cols, "utf-8-sig")
    return org, cols


def build(df, used, out_dir, seed):
    company = {"id": CID, "name": NAME, "industry": "의료(요양병원)",
               "profile": "160명 요양병원, 병동 3교대(D/E/N) 근무표가 코드로, 야간전담 간호사, 면허·자격수당·나이트수당·당직, 시트마다 열 구성이 다른 급여대장"}
    cdir = out_dir / CID
    cdir.mkdir(parents=True, exist_ok=True)
    emps = people(df, used, seed)
    rng = B.rng_for(CID, "plan", seed)
    duty = plan_duty(emps, rng)
    acc = inject_duty(duty, emps, rng)
    # 근무표 사고를 일정에 반영 ('N '은 표시만 다르고 실제로는 N 근무)
    no, k = acc["overlap"]
    duty[no][k] = "D"
    no, k = acc["rest"]
    duty[no][k] = "D"
    no, ks = acc["norest"]
    for k in ks:
        duty[no][k] = "D"
    duty_key, duty_counts, _ = write_duty(cdir / DUTY_FILE, emps, duty, acc)
    prng = B.rng_for(CID, "payroll", seed)
    nurses = sorted(no for no in duty if no not in {acc["typo"][0], acc["overlap"][0], acc["rest"][0], acc["norest"][0]}
                    and next(e for e in emps if e["empNo"] == no)["shift"] == "3교대")
    typo_no = prng.choice(nurses)
    carers = sorted(e["empNo"] for e in emps if e["kind"] == "요양보호사" and e["status"] == "재직" and not e.get("newbie")
                    and e["basePay"] == MIN_MONTHLY)
    pacc = {"stale": prng.choice(carers), "hoursTypo": (typo_no, counts(duty[typo_no])["N"] + 2), "typoEmp": acc["typo"][0]}
    pay_key = write_payroll(cdir / PAY_FILE, emps, duty, duty_counts, pacc, prng)
    # 근무표 'N ' 사고가 급여대장으로 번진 칸 (급여대장 쪽 사고는 GNMD-PAY-03)
    carried = next(a for a in pay_key["accidents"] if a["type"] == "CARRIED_FROM_SOURCE")
    typo = next(a for a in duty_key["accidents"] if a["type"] == "KIND_TYPO")
    col_of = {h: xl_col_to_name(n) for n, (_k, h) in enumerate(NURSE_COLS)}
    cells = ["%s%d" % (col_of[h], carried["rowNo"]) for h in carried["values"]]
    total = next(r["rowNo"] for r in carried["affected"] if r["rowNo"] != carried["rowNo"])
    typo["crossFileAffected"] = [
        {"file": PAY_FILE, "sheet": carried["sheet"], "rowNo": carried["rowNo"], "accident": carried["id"], "cells": cells, "values": carried["values"]},
        {"file": PAY_FILE, "sheet": carried["sheet"], "rowNo": total, "accident": carried["id"], "note": "합계 행 (SUM)",
         "cells": ["%s%d" % (col_of[h], total) for h in carried["values"]]}]
    roster = write_roster(cdir, emps, seed)
    org, org_cols = write_org(cdir)
    return B.answer_key(company, "직원명부.xlsx", "조직.csv", roster["mapping"], org_cols, roster["errors"],
                        {"employees": len(emps), "rosterRows": roster["rows"], "orgs": len(org),
                         "shiftWorkers": len(duty)}, {DUTY_FILE: duty_key, PAY_FILE: pay_key}, {"rosterLayout": roster["layout"]})
