"""
머버로지스 — 115명 물류센터 (내국인 65 + 고용허가제(E-9) 외국인 50). 주간조·야간조, 기숙사·식비 공제.

외국인 근로자 이름은 인물 데이터셋(한국인)에서 뽑지 않고 국가별 흔한 이름 조각을 섞어 만든 가상 이름이다 (실존 인물 아님).

파일: 직원명부.csv(UTF-8 BOM, 한·영 병기 헤더 '성명(Name)', 외국인은 여권 영문 이름 대문자, 체류자격·체류만료일, 마스킹 외국인등록번호,
              체류만료일은 'MM/DD/YYYY'로 입력된 행 섞임, 기숙사 호실),
     조직.csv(UTF-8),
     급여대장_2026-12.xlsx(주간조·야간조 시트, 헤더가 '사번\\nID' 줄바꿈 한·영 병기, 외국인 성명은 한글 표기, 기숙사비·식비 공제, SETTING 시트),
     출퇴근기록_2026-12.csv(지문인식기 내보내기, UTF-8 BOM 없음, 한 줄 = 한 번 찍은 기록, 영문 헤더, 이름 10자에서 잘림, 야간조 퇴근은 다음 날).
급여 규칙: 통상시급 = ROUND(기본급/209), 연장 1.5·야간 0.5·휴일 1.5 가산, 토요일(휴무일) 근무는 연장, 12/25는 시작일 기준 휴일,
         기숙사 거주자 식대 현금 없음 + 기숙사비·식비 공제(합계 통상임금 월액의 20% 이내, 회사 적용값·가정: 지침은 숙소 유형별 8~20%),
         외국인도 4대보험 내국인과 같게(단순화), 주 52시간 안(근무 계획에서 넘는 주의 연장을 줄임).
"""
import datetime as dt

from xlsxwriter.utility import xl_col_to_name

from hrfiles.common import Accidents
from hrfiles.facts import HOLIDAYS_2026
from hrfiles.xl import ROUND, ROUNDDOWN, Book

from . import base as B

CID, NAME = "meobeologis", "머버로지스"
PAY_FILE, PUNCH_FILE = "급여대장_2026-12.xlsx", "출퇴근기록_2026-12.csv"
DAYS = B.days_of(2026, 12)
XMAS = dt.date(2026, 12, 25)
TEAMS = [("운영지원팀", 6, "주간"), ("입고팀", 20, None), ("출고팀", 26, None), ("재고관리팀", 12, None)]
SHIFT = {"주간": (dt.time(8, 0), 9, 0), "야간": (dt.time(22, 0), 9, 7)}  # 시작, 근무+휴게 시간, 야간시간(22~06, 휴게 1시간 뺌)
MIN_MONTHLY = B.MIN_WAGE[2026] * 209
DORM_FEE, MEAL_FEE = 200_000, 150_000

# 국적: (국가, 이름 순서, 성 [(영문, 한글)], 가운데, 이름)
NATIONS = [
    ("베트남", "FMG", [("NGUYEN", "응우옌"), ("TRAN", "쩐"), ("LE", "레"), ("PHAM", "팜"), ("HOANG", "호앙"), ("VU", "부"), ("BUI", "부이")],
     [("VAN", "반"), ("DUC", "득"), ("MINH", "민"), ("HUU", "흐우"), ("QUANG", "꽝")],
     [("AN", "안"), ("BINH", "빈"), ("CUONG", "끄엉"), ("HUNG", "훙"), ("KHANH", "카인"), ("LONG", "롱"), ("NAM", "남"), ("PHUC", "푹"),
      ("SON", "선"), ("THANH", "타인"), ("TUAN", "뚜언"), ("VIET", "비엣")]),
    ("네팔", "GF", [("GURUNG", "구룽"), ("TAMANG", "타망"), ("RAI", "라이"), ("MAGAR", "마가르"), ("SHRESTHA", "쉬레스타"), ("THAPA", "타파")], [],
     [("RAM", "람"), ("HARI", "하리"), ("BISHNU", "비슈누"), ("KRISHNA", "크리슈나"), ("SURESH", "수레시"), ("DIPAK", "디팍"), ("ANIL", "아닐")]),
    ("캄보디아", "FG", [("SOK", "속"), ("CHAN", "찬"), ("HENG", "행"), ("LIM", "림"), ("CHEA", "치어")], [],
     [("DARA", "다라"), ("VANNAK", "완낙"), ("RITHY", "리티"), ("VIBOL", "위볼"), ("PISETH", "피셋")]),
    ("우즈베키스탄", "FG", [("KARIMOV", "카리모프"), ("YUSUPOV", "유수포프"), ("RAKHIMOV", "라히모프"), ("TOSHMATOV", "토시마토프")], [],
     [("AZIZ", "아지즈"), ("BOBUR", "보부르"), ("JASUR", "자수르"), ("SARDOR", "사르도르"), ("OTABEK", "오타벡")]),
    ("인도네시아", "GF", [("SETIAWAN", "스티아완"), ("SANTOSO", "산토소"), ("KURNIAWAN", "쿠르니아완"), ("PRASETYO", "프라스티오"), ("HIDAYAT", "히다얏")], [],
     [("AGUS", "아구스"), ("BUDI", "부디"), ("DEDI", "데디"), ("EKO", "에코"), ("RUDI", "루디"), ("WAHYU", "와휴")]),
]


def foreign_name(rng, nation):
    _n, order, fam, mid, given = nation
    parts = {"F": rng.choice(fam), "M": rng.choice(mid) if mid else None, "G": rng.choice(given)}
    seq = [parts[c] for c in order if parts[c]]
    return " ".join(p[0] for p in seq), " ".join(p[1] for p in seq)


def masked_frn(birth, sex):
    """외국인등록번호 형태의 마스킹 값 (뒷자리 첫 숫자 5·6 = 1900년대, 7·8 = 2000년대)"""
    g = (5 if sex == "남자" else 6) + (2 if birth.year >= 2000 else 0)
    return "%s-%d******" % (birth.strftime("%y%m%d"), g)


def people(df, used, seed):
    rng = B.rng_for(CID, "people", seed)
    n_kr = 1 + sum(n for _, n, _s in TEAMS)
    w = lambda o: 3.0 if B.has(o, "물류", "운송", "하역", "창고", "배송", "포장", "운전", "지게차") else 1.0  # noqa: E731
    picked = sorted(B.pick(df, used, n_kr, rng, CID, w), key=lambda p: (-p["age"], p["uuid"]))
    emps = []
    head, rest = picked[0], picked[1:]
    rng.shuffle(rest)
    slots = [(t, s) for t, n, s in TEAMS for _ in range(n)]
    for p, (team, shift) in [(head, ("운영지원팀", "주간"))] + list(zip(rest, slots)):
        age = int(p["age"])
        birth = B.birth_of(age, rng)
        hire = min(dt.date(max(2016, birth.year + 22 + rng.randint(0, 10)), rng.randint(1, 12), rng.randint(1, 28)), dt.date(2026, 9, 1))
        emps.append({"name": p["name"], "nameKo": p["name"], "foreign": False, "nation": "대한민국", "birthDate": birth, "sex": p["sex"],
                     "dept": team, "shift": shift or rng.choice(["주간", "주간", "야간"]), "hireDate": hire, "resignDate": None,
                     "empType": rng.choices(["정규직", "계약직"], [70, 30])[0], "mobile": B.phone(rng), "visa": None, "visaEnd": None,
                     "dorm": None, "age": age, "isHead": p is head})
    frng = B.rng_for(CID, "foreign", seed)
    names = set()
    for k in range(50):
        nation = frng.choices(NATIONS, [45, 20, 15, 10, 10])[0]
        while True:
            en, ko = foreign_name(frng, nation)
            if en not in names:
                names.add(en)
                break
        sex = "남자" if frng.random() < 0.85 or nation[0] != "베트남" else "여자"
        if nation[0] == "베트남":
            en = en.replace(" VAN ", " THI ") if sex == "여자" else en
            ko = ko.replace(" 반 ", " 티 ") if sex == "여자" else ko
        age = frng.randint(22, 38)
        hire = dt.date(2026, 10, 31) - dt.timedelta(days=frng.randint(60, 1500))
        emps.append({"name": en, "nameKo": ko, "foreign": True, "nation": nation[0], "birthDate": B.birth_of(age, frng), "sex": sex,
                     "dept": frng.choice(["입고팀", "출고팀", "출고팀", "재고관리팀"]), "shift": frng.choice(["주간", "야간", "야간"]),
                     "hireDate": hire, "resignDate": None, "empType": "계약직", "mobile": B.phone(frng), "visa": "E-9",
                     "visaEnd": hire.replace(year=hire.year + 3) - dt.timedelta(days=1) if hire.replace(year=hire.year + 3) > dt.date(2027, 1, 1)
                     else dt.date(2027, frng.randint(2, 12), 1) + dt.timedelta(days=frng.randint(0, 27)),
                     "dorm": "%d0%d호" % (frng.randint(2, 4), frng.randint(1, 8)) if frng.random() < 0.8 else None, "age": age, "isHead": False})
    emps.sort(key=lambda e: (e["hireDate"], e["name"]))
    for n, e in enumerate(emps, 1):
        e["empNo"] = "%04d" % (1000 + n)
        e["email"] = None if e["foreign"] else "%s@meobeo-logis.co.kr" % e["empNo"]
        e["frn"] = masked_frn(e["birthDate"], e["sex"]) if e["foreign"] else None
        if e["isHead"]:
            e["position"], e["basePay"] = "센터장", 5_200_000
        elif e["foreign"]:
            e["position"], e["basePay"] = "사원", MIN_MONTHLY
        else:
            e["position"] = "과장" if e["age"] >= 42 else "대리" if e["age"] >= 34 else "주임" if e["age"] >= 29 else "사원"
            e["basePay"] = {"과장": 3_600_000, "대리": 3_000_000, "주임": 2_550_000, "사원": 2_300_000}[e["position"]] + rng.randint(0, 10) * 10_000
        e["family"] = 1 if e["foreign"] else rng.choice([1, 1, 2, 3, 4])
    for team, _n, _s in TEAMS:
        lead = max((e for e in emps if e["dept"] == team and not e["foreign"]), key=lambda e: (e["age"], e["empNo"]))
        if not lead["isHead"]:
            lead["position"] = "팀장"
            lead["basePay"] = 4_000_000
    return emps


# ================================================================ 근무·출퇴근

def plan_work(emps, rng):
    """사번 → [(날짜, 조, 연장시간, 토요일?)]"""
    plan = {}
    for e in emps:
        leave = set(rng.sample([d for d in DAYS if d.weekday() < 5 and d != XMAS], rng.choice([0, 0, 1, 2])))
        rows = []
        for d in DAYS:
            if d < e["hireDate"] or d in leave or d.weekday() == 6:
                continue
            if d.weekday() == 5 and (e["dept"] == "운영지원팀" or rng.random() > 0.3):
                continue
            if d in HOLIDAYS_2026 and (e["dept"] == "운영지원팀" or rng.random() > 0.5):
                continue
            ot = rng.choices([0, 1, 1.5, 2], [70, 12, 6, 12])[0] if e["dept"] != "운영지원팀" and d.weekday() < 5 else 0
            rows.append((d, e["shift"], ot))
        cap_week(rows)
        plan[e["empNo"]] = rows
    return plan


def cap_week(rows):
    """주(월~일, 근무 시작일 기준) 근로시간 = 근무일 × 8 + 연장이 52시간을 넘지 않게, 넘는 주는 뒤쪽 날 연장부터 줄인다.
    창고·물류는 근로시간 특례 업종이 아니라 주 52시간이 그대로 적용된다 (난수는 쓰지 않아 다른 값은 그대로)"""
    weeks = {}
    for n, (d, _s, _o) in enumerate(rows):
        weeks.setdefault(d.isocalendar()[:2], []).append(n)
    for ns in weeks.values():
        over = sum(8 + rows[n][2] for n in ns) - 52
        for n in reversed(ns):
            if over <= 0:
                break
            d, s, o = rows[n]
            cut = min(o, over)
            rows[n] = (d, s, o - cut)
            over -= cut


def month_hours(rows):
    """회사 집계: 연장 = 평일 연장 + 토요일 근무 8시간, 야간 = 야간조 근무일 × 7, 휴일 = 12/25 시작 근무 8시간"""
    ot = sum(o for d, s, o in rows) + 8 * sum(1 for d, s, o in rows if d.weekday() == 5)
    night = sum(SHIFT[s][2] for d, s, o in rows)
    hol = 8 * sum(1 for d, s, o in rows if d in HOLIDAYS_2026)
    return {"otH": ot, "nightH": night, "holH": hol}


def punches(e, rows, rng):
    out = []
    for d, s, ot in rows:
        start, span, _n = SHIFT[s]
        t_in = dt.datetime.combine(d, start) - dt.timedelta(minutes=rng.randint(3, 15), seconds=rng.randint(0, 59))
        t_out = dt.datetime.combine(d, start) + dt.timedelta(hours=span + ot, minutes=rng.randint(1, 12), seconds=rng.randint(0, 59))
        out.append((t_in, "C/In", d))
        out.append((t_out, "C/Out", d))
    return out


def write_punch(path, emps, plan, acc, rng):
    A = Accidents("MEOB-PUNCH", path.name)
    by_no = {e["empNo"]: e for e in emps}
    recs = []
    for e in emps:
        for t, kind, d in punches(e, plan[e["empNo"]], rng):
            if acc["outMissing"] == (e["empNo"], d) and kind == "C/Out":
                continue
            verify = "FP" if rng.random() < 0.97 else "Card"
            recs.append({"t": t, "id": e["empNo"], "kind": kind, "verify": verify, "dev": "GATE-%d" % (1 if e["shift"] == "주간" else 2), "d": d})
    # 대리 체크: 같은 기계·같은 초에 두 사람, 한 사람은 비밀번호(PW)
    pa, pb, pd = acc["proxy"]
    a_rec = next(r for r in recs if r["id"] == pa and r["d"] == pd and r["kind"] == "C/In")
    b_rec = next(r for r in recs if r["id"] == pb and r["d"] == pd and r["kind"] == "C/In")
    b_rec.update(t=a_rec["t"] + dt.timedelta(seconds=4), verify="PW", dev=a_rec["dev"])
    # 두 번 찍힘(정상 소음): 1분 안에 같은 기록 한 번 더
    doubles = []
    for r in rng.sample([r for r in recs if r["kind"] == "C/In" and r["id"] not in (pa, pb)], 6):
        dup = dict(r)
        dup["t"] = r["t"] + dt.timedelta(seconds=rng.randint(2, 40))
        doubles.append(dup)
    recs += doubles
    recs.sort(key=lambda r: (r["t"], r["id"]))
    rows = []
    for n, r in enumerate(recs, 1):
        e = by_no[r["id"]]
        rows.append([n, r["id"], e["name"][:10], e["dept"], r["t"].strftime("%Y-%m-%d %H:%M:%S"), r["kind"], r["dev"], r["verify"]])
    heads = ["No", "UserID", "Name", "Dept", "DateTime", "InOut", "Device", "Verify"]
    first = B.write_csv(path, heads, rows, "utf-8")
    sheet = path.stem
    row_no = {id(r): first + n for n, r in enumerate(recs)}
    eid, d = acc["outMissing"]
    rin = next(row_no[id(r)] for r in recs if r["id"] == eid and r["d"] == d and r["kind"] == "C/In")
    A.add("OUT_MISSING", sheet, "%s 출근(C/In)만 있고 퇴근(C/Out) 기록 없음 → 그날 근무·연장 시간을 알 수 없음 (급여는 8시간으로 처리)" % d.isoformat(),
          ["REQUIRED_PAIR"], empNo=eid, rowNo=rin)
    A.add("PROXY_PUNCH_SUSPECTED", sheet, "%s %s: 같은 기계에서 다른 사람 출근 4초 뒤 비밀번호(PW)로 출근 — 지문이 아닌 대리 체크 의심" % (pd.isoformat(), b_rec["dev"]),
          ["CROSS_FIELD_RULE", "DUPLICATE_DETECT"], empNo=pb, rowNo=row_no[id(b_rec)], related={"empNo": pa, "rowNo": row_no[id(a_rec)]})
    ve = acc["visaEmp"]
    vrows = sorted(row_no[id(r)] for r in recs if r["id"] == ve["empNo"])
    A.add("WORK_AFTER_VISA_EXPIRY", sheet, "직원명부 체류만료일 %s 이후 12월 내내 출퇴근 기록 (%d건) — 명부에 연장 허가 기록이 없어 확인 대상 "
          "(만료 전 연장 신청 중이면 심사 중 취업 가능, 확정 위반 아님. MEOB-PAY-05와 같은 사람)"
          % (ve["visaEnd"].isoformat(), len(vrows)), ["CROSS_FILE_MATCH", "CROSS_FIELD_RULE"], basis="회사 점검 기준 (확인 필요)", empNo=ve["empNo"], rows=vrows[:1],
          rowCount=len(vrows), firstRow=vrows[0], lastRow=vrows[-1])
    return {"file": path.name, "kind": "ATTENDANCE", "closestEntity": None, "supported": False, "format": "csv", "encoding": "utf-8 (BOM 없음)",
            "period": "2026-12", "sheetOrder": [sheet],
            "sheets": {sheet: {"role": "punchLog", "headerRows": [1], "firstDataRow": first, "lastDataRow": first + len(recs) - 1,
                               "note": "CSV라 시트가 없음. 시트 이름은 파일 이름(확장자 제외)"}},
            "expectedMapping": {"punchLog": {"No": None, "UserID": "empNo", "Name": "name", "Dept": "dept", "DateTime": "punchTime",
                                             "InOut": "punchType", "Device": "device", "Verify": "verifyType"}},
            "rules": {"layout": "한 줄 = 한 번 찍은 기록 (세로로 긴 원시 로그), 출근·퇴근 짝을 맞춰야 근무시간이 나옴",
                      "shifts": {"주간": "08:00~17:00 (휴게 12~13)", "야간": "22:00~익일 07:00 (휴게 02~03, 야간 7시간)"},
                      "overtime": "퇴근 − 종료시각을 30분 단위 버림 (급여대장 연장시간과 맞음, 토요일은 8시간 전부 연장)"},
            "accidents": A.items,
            "legitUnusual": ["야간조 퇴근은 다음 날 날짜 (12/31 야간 퇴근은 2027-01-01)", "1분 안에 같은 사람이 두 번 찍은 기록 %d건 (손가락 재인식, 오류 아님)" % len(doubles),
                             "Verify 'Card'는 지문 인식 실패 시 카드 (정상)"],
            "valueNoise": [{"type": "NAME_TRUNCATED", "detail": "Name은 기계 등록 이름 10자에서 잘림 (외국인 영문 이름)"},
                           {"type": "DATETIME_TEXT", "detail": "'2026-12-03 07:52:41' 문자 (날짜·시각 한 칸)"}],
            "crossFile": {"payroll": {"file": PAY_FILE, "detail": "연장·야간·휴일 시간 = 이 기록에서 회사 규칙으로 집계"},
                          "roster": {"file": "직원명부.csv", "detail": "체류만료일 대조"}}}


# ================================================================ 급여대장

SETS = [("np", "National Pension 국민연금", B.RATES_2026["np"], "pct"), ("hi", "Health 건강보험", B.RATES_2026["hi"], "pct"),
        ("ltc", "LTC 장기요양", B.RATES_2026["ltc"], "pct"), ("ei", "Employment 고용보험", B.RATES_2026["ei"], "pct"),
        ("meal", "Meal non-tax 식대 비과세 한도", B.NONTAX_LIMIT, "won"), ("npFloor", "NP floor 하한", B.NP_FLOOR, "won"),
        ("npCap", "NP cap 상한", B.NP_CAP, "won"), ("dormCap", "숙식비 공제 상한 (통상임금 월액 대비, 회사 적용값)", 0.2, "pct")]
COLS = [("empNo", "사번\nID"), ("name", "성명\nName"), ("nation", "국적\nNation"), ("dept", "팀\nTeam"), ("family", "공제가족\nDep."),
        ("basePay", "기본급\nBasic"), ("hourly", "통상시급\nHourly"), ("otH", "연장(h)\nOT"), ("nightH", "야간(h)\nNight"),
        ("holH", "휴일(h)\nHoliday"), ("otPay", "연장수당\nOT Pay"), ("nightPay", "야간수당\nNight Pay"), ("holPay", "휴일수당\nHoliday Pay"),
        ("meal", "식대\nMeal"), ("gross", "지급계\nGross"), ("np", "국민연금\nNP"), ("hi", "건강보험\nNHI"), ("ltc", "장기요양\nLTC"),
        ("ei", "고용보험\nEI"), ("tax", "소득세\nTax"), ("ltax", "지방세\nLocal"), ("dorm", "기숙사비\nDorm"), ("food", "식비\nFood"),
        ("ded", "공제계\nDeduct"), ("net", "실지급\nNet"), ("memo", "비고\nNote")]
L = {k: xl_col_to_name(i) for i, (k, _) in enumerate(COLS)}
HOURS = ("otH", "nightH", "holH", "family")


def calc(v, S):
    v = dict(v)
    v["hourly"] = ROUND(v["basePay"] / 209, 0)
    v["otPay"] = ROUND(v["hourly"] * v["otH"] * 1.5, 0)
    v["nightPay"] = ROUND(v["hourly"] * v["nightH"] * 0.5, 0)
    v["holPay"] = ROUND(v["hourly"] * v["holH"] * 1.5, 0)
    v["gross"] = v["basePay"] + v["otPay"] + v["nightPay"] + v["holPay"] + v["meal"]
    taxable = v["gross"] - min(v["meal"], S["meal"])
    v["np"] = ROUNDDOWN(min(max(ROUNDDOWN(taxable, -3), S["npFloor"]), S["npCap"]) * S["np"], -1)
    v["hi"] = ROUNDDOWN(taxable * S["hi"], -1)
    v["ltc"] = ROUNDDOWN(v["hi"] * S["ltc"], -1)
    v["ei"] = ROUNDDOWN(taxable * S["ei"], -1)
    v["tax"] = B.tax_of(taxable, v["family"])
    v["ltax"] = ROUNDDOWN(v["tax"] * 0.1, -1)
    v["ded"] = v["np"] + v["hi"] + v["ltc"] + v["ei"] + v["tax"] + v["ltax"] + v["dorm"] + v["food"]
    v["net"] = v["gross"] - v["ded"]
    return v


def formulas(r, st):
    R = lambda k: "%s%d" % (L[k], r)  # noqa: E731
    taxable = "(%s-MIN(%s,%s))" % (R("gross"), R("meal"), st.ref("meal"))
    return {"hourly": "=ROUND(%s/209,0)" % R("basePay"), "otPay": "=ROUND(%s*%s*1.5,0)" % (R("hourly"), R("otH")),
            "nightPay": "=ROUND(%s*%s*0.5,0)" % (R("hourly"), R("nightH")), "holPay": "=ROUND(%s*%s*1.5,0)" % (R("hourly"), R("holH")),
            "gross": "=%s+SUM(%s:%s)" % (R("basePay"), R("otPay"), R("meal")),
            "np": "=ROUNDDOWN(MIN(MAX(ROUNDDOWN(%s,-3),%s),%s)*%s,-1)" % (taxable, st.ref("npFloor"), st.ref("npCap"), st.ref("np")),
            "hi": "=ROUNDDOWN(%s*%s,-1)" % (taxable, st.ref("hi")), "ltc": "=ROUNDDOWN(%s*%s,-1)" % (R("hi"), st.ref("ltc")),
            "ei": "=ROUNDDOWN(%s*%s,-1)" % (taxable, st.ref("ei")), "tax": "=" + B.tax_formula(taxable, R("family")),
            "ltax": "=ROUNDDOWN(%s*0.1,-1)" % R("tax"), "ded": "=SUM(%s:%s)" % (R("np"), R("food")), "net": "=%s-%s" % (R("gross"), R("ded"))}


def write_payroll(path, emps, plan, acc):
    A = Accidents("MEOB-PAY", path.name)
    book = Book(path)
    sheets = {s: book.sheet(s + "조") for s in ("주간", "야간")}
    st = B.Settings2(book, "SETTING", SETS)
    tax_meta = B.write_tax_sheet(book)
    S = st.val
    meta, vals = {}, {}
    for s, sh in sheets.items():
        sh.put("A", 1, "MEOBEO LOGISTICS  2026.12 PAYROLL  급여대장 (%s조)" % s, "title")
        for k, h in COLS:
            sh.put(L[k], 2, h, "head")
        sh.ws.set_row(1, 30)
        members = sorted([e for e in emps if e["shift"] == s and e["status"] == "재직"], key=lambda e: (e["dept"], e["empNo"]))
        r, row_of = 3, {}
        for e in members:
            h = month_hours(plan[e["empNo"]])
            dorm = e["dorm"] is not None
            inp = {"empNo": e["empNo"], "name": e["nameKo"], "nation": e["nation"], "dept": e["dept"], "family": e["family"],
                   "basePay": e["basePay"], **h, "meal": 0 if dorm else 200_000, "dorm": DORM_FEE if dorm else 0, "food": MEAL_FEE if dorm else 0,
                   "memo": None}
            ideal_inp = dict(inp)
            if e["empNo"] == acc["dormOver"]:
                inp["dorm"] = 300_000
                inp["memo"] = "1인실"
            if e["empNo"] == acc["movedOut"]:
                ideal_inp.update(dorm=0, food=0, meal=200_000)
                inp.update(dorm=DORM_FEE, food=MEAL_FEE, meal=0)
            if e["empNo"] == acc["probation"]:
                inp["basePay"] = ROUNDDOWN(MIN_MONTHLY * 0.9, -1)
                inp["memo"] = "수습 90%"
            if e["empNo"] == acc["otTypo"][0]:
                inp["otH"] = acc["otTypo"][1]
            if e["empNo"] == acc["visa"]:
                inp["memo"] = "체류연장 신청 중"
                ideal_inp = None
            a = calc(inp, S)
            i = calc(ideal_inp, S) if ideal_inp else None
            fs = formulas(r, st)
            for k, _ in COLS:
                v = a[k]
                if k == "empNo" and e["empNo"] == acc["keyMissing"]:
                    continue  # 사번 칸 빈 칸
                if fs.get(k):
                    sh.formula(L[k], r, fs[k], v, B.money(v) or ("num" if k in HOURS else None))
                else:
                    sh.put(L[k], r, v, "text" if k == "empNo" else (B.money(v) if k not in HOURS else "num"))
            row_of[e["empNo"]] = r
            vals[e["empNo"]] = (a, i)
            r += 1
        sh.put(L["name"], r, "TOTAL 합계", "totalLabel")
        for k, _ in COLS[COLS.index(("basePay", "기본급\nBasic")):-1]:
            tot = sum(vals[e["empNo"]][0][k] for e in members)
            sh.formula(L[k], r, "=SUM(%s3:%s%d)" % (L[k], L[k], r - 1), round(tot, 6) if isinstance(tot, float) else tot,
                       "total" if k not in HOURS else "num")
        sh.ws.freeze_panes(2, 2)
        sh.widths({L["name"]: 18})
        meta[s + "조"] = {"role": "monthlyPayrollBilingual", "payMonth": "2026-12", "headerRows": [2], "firstDataRow": 3, "lastDataRow": r - 1,
                         "totalRow": r, "titleRows": [1], "rowOf": row_of}
    book.close()

    sheet_of = {e["empNo"]: e["shift"] + "조" for e in emps}

    def at(no):
        return {"sheet": sheet_of[no], "rowNo": meta[sheet_of[no]]["rowOf"][no], "empNo": no}

    def loc(no):
        return {"rowNo": meta[sheet_of[no]]["rowOf"][no], "empNo": no}

    H = dict(COLS)

    def changed(no):
        a, i = vals[no]
        return [k for k, _ in COLS if k not in ("empNo", "name", "memo") and a.get(k) != i.get(k)]

    def diff_vals(no):
        a, i = vals[no]
        return {H[k]: {"actual": a[k], "expected": i[k]} for k in changed(no)}

    def where(no):
        """값이 달라진 행 + 그 시트 합계 행 (SUM)"""
        sh = sheet_of[no]
        return [{"sheet": sh, "rowNo": meta[sh]["rowOf"][no]}, {"sheet": sh, "rowNo": meta[sh]["totalRow"]}]

    no = acc["dormOver"]
    a, i = vals[no]
    cap = ROUNDDOWN(a["hourly"] * 209 * S["dormCap"], 0)
    v = diff_vals(no)
    v[H["dorm"]]["note"] = "기대값은 회사 표준 기숙사비. 1인실 30만원이면 식비 15만원과 합쳐 상한을 넘으므로 기숙사비는 %s원 이하여야 함"         % format(cap - a["food"], ",")
    A.add("DORM_DEDUCTION_OVER_LIMIT", at(no)["sheet"], "기숙사비 30만(1인실) + 식비 15만 = 45만원 공제 > 통상임금 월액의 20%% (%s원) — 회사 숙식비 공제 상한 초과. "
          "상한 20%%는 회사 적용값(가정, rules.dormitory): 지침의 숙소 유형별 상한(8~20%%) 중 가장 높은 값이라 어느 유형이어도 초과"
          % format(cap, ","), ["LEGAL_RULE_COMPARE", "CROSS_FIELD_RULE"], legal=True, **loc(no),
          cells=["%s%d" % (L[k], at(no)["rowNo"]) for k in ("dorm", "food", "ded", "net")], values=v, limit={"숙식비 공제 합계": {"actual": a["dorm"] + a["food"], "limit": cap, "basis": "통상임금 월액(통상시급 × 209) × 20%"}},
          cascade=[H[k] for k in changed(no) if k != "dorm"], affected=where(no))
    no = acc["movedOut"]
    a, i = vals[no]
    A.add("DORM_CHARGED_AFTER_MOVEOUT", at(no)["sheet"], "11/30 기숙사 퇴실(직원명부 기숙사 '퇴실 11/30')인데 12월에도 기숙사비·식비 공제, 식대 현금 0",
          ["CROSS_FILE_MATCH"], **loc(no), cells=["%s%d" % (L[k], at(no)["rowNo"]) for k in changed(no)],
          values=diff_vals(no), cascade=[H[k] for k in changed(no) if k not in ("meal", "dorm", "food")], affected=where(no))
    no = acc["probation"]
    a, i = vals[no]
    A.add("PROBATION_REDUCTION_ILLEGAL", at(no)["sheet"], "11월 입사 물류(단순노무) 근로자 기본급을 수습 90%%(%s원)로 지급 — 단순노무업무는 수습 감액 불가, "
          "1년 미만 계약도 불가 (최저임금법 제5조)" % format(a["basePay"], ","), ["LEGAL_RULE_COMPARE", "CROSS_FILE_MATCH"], legal=True, **loc(no),
          cells=["%s%d" % (L[k], at(no)["rowNo"]) for k in changed(no)], values=diff_vals(no), cascade=[H[k] for k in changed(no) if k != "basePay"],
          affected=where(no))
    no, typed = acc["otTypo"]
    a, i = vals[no]
    A.add("HOURS_MISMATCH_CROSS_FILE", at(no)["sheet"], "출퇴근기록 집계 연장 %s시간을 %s시간으로 입력" % (i["otH"], typed), ["CROSS_FILE_MATCH"], **loc(no),
          cells=["%s%d" % (L[k], at(no)["rowNo"]) for k in changed(no)], values=diff_vals(no), cascade=[H[k] for k in changed(no) if k != "otH"], affected=where(no))
    no = acc["visa"]
    A.add("VISA_EXPIRED_STILL_PAID", at(no)["sheet"], "직원명부 체류만료일(2026-11-20)이 지났는데 12월 급여 전액 지급, 비고 '체류연장 신청 중' — 명부에 연장 허가 기록이 없어 "
          "회사 점검 기준(만료일 지난 재직자는 확인 대상)에 걸림. 만료 전에 연장을 신청했으면 심사 중에도 취업할 수 있고 위반 책임은 고용 쪽이라, "
          "법 위반인지는 신청·허가 서류로 확인해야 함 (확정 위반 아님)",
          ["CROSS_FILE_MATCH", "CROSS_FIELD_RULE"], basis="회사 점검 기준 (확인 필요)", **loc(no))
    no = acc["keyMissing"]
    A.add("KEY_MISSING", at(no)["sheet"], "사번 칸이 비어 있고 성명은 한글 표기 '%s' (직원명부는 여권 영문 '%s') → 이름으로도 바로 못 찾음"
          % (vals[no][0]["name"], next(e["name"] for e in emps if e["empNo"] == no)), ["NAME_MATCH", "REQUIRED"], **loc(no),
          cells=["%s%d" % (L["empNo"], at(no)["rowNo"])])
    used = {at(x)["sheet"] for x in (acc["dormOver"], acc["movedOut"], acc["probation"], acc["otTypo"][0], acc["visa"], acc["keyMissing"])}
    # 법정 기준과 다른 회사 규칙: 12/25 주간 근무의 정시 뒤 연장을 연장 1.5로 (휴일 8시간 초과분은 2배)
    hol_ot = {no: sum(o for d, sft, o in plan[no] if d in HOLIDAYS_2026 and sft == "주간") for no in vals if sheet_of[no] == "주간조"}
    hol_ot = {no: h for no, h in hol_ot.items() if h}
    hol_rows = sorted(meta["주간조"]["rowOf"][no] for no in hol_ot)
    eve = sorted(no for no in vals if sheet_of[no] == "야간조" and any(d == XMAS - dt.timedelta(days=1) for d, *_x in plan[no]))
    legal = [{"rule": "holidayWorkBasis", "sheet": "야간조", "company": "야간조 12/24 밤 근무의 12/25 0~7시는 휴일로 안 봄 (시작일 기준)",
              "statutory": "휴일(0~24시)에 걸친 시간은 휴일근로", "rows": sorted(meta["야간조"]["rowOf"][x] for x in eve), "empNos": eve}]
    if hol_ot:
        legal.append({"rule": "holidayPremium", "sheet": "주간조",
                      "company": "12/25 휴일근무 8시간을 넘긴 연장 %d명을 연장(1.5배)으로 지급" % len(hol_ot),
                      "statutory": "휴일근로 8시간 초과분은 통상임금의 100% 가산 = 2배 (근로기준법 제56조 제2항)",
                      "rows": hol_rows, "empNos": sorted(hol_ot, key=lambda x: meta["주간조"]["rowOf"][x]),
                      "values": {no: {"휴일 8시간 초과(h)": h, "부족액": ROUND(vals[no][0]["hourly"] * h * 0.5, 0)} for no, h in hol_ot.items()}})
    # 비교군 = 사고 없는 시트. 회사 규칙의 법정 편차(legalDeviations, 행 목록 포함)는 비교군에도 있을 수 있다
    clean = [s for s in ("주간조", "야간조") if s not in used]
    return {"file": path.name, "kind": "PAYROLL", "closestEntity": "PAY_RESULT_HISTORY", "supported": False,
            "sheetOrder": ["주간조", "야간조", "SETTING", "세액표"], "sheets": {**meta, "SETTING": st.meta(), "세액표": tax_meta},
            "expectedMapping": {"monthlyPayrollBilingual": {h: k for k, h in COLS}},
            "rules": {"ordinaryHourlyWage": {"expr": "round(basePay/209)"},
                      "overtime": "연장 1.5 (토요일 휴무일 근무 8시간 포함). 12/25 휴일근무 날 정시 뒤 연장도 연장 1.5 (법정은 휴일 8시간 초과분 2배 — legalDeviations)",
                      "weeklyLimit": "주 52시간 (월~일, 근무 시작일 기준. 물류·창고는 근로시간 특례 업종 아님) — 넘는 주 없음",
                      "nightPremium": "야간 0.5 (야간조 하루 7시간)", "holidayWork": "12/25 시작 근무 8시간 × 1.5 (시작일 기준, 회사 단순화)",
                      "dormitory": "기숙사 거주자: 식대 현금 0, 기숙사비 20만·식비 15만 공제. 공제 합계 상한 = 통상임금 월액의 20% (회사 적용값, 가정: "
                                   "고용노동부 외국인근로자 숙식비 징수 지침은 제공 숙식·숙소 유형별로 8~20%. 이 회사 기숙사를 숙식 모두 제공·주택형으로 보고 가장 높은 20%를 씀)",
                      "foreignWorkers": "외국인도 4대보험·간이세액표 내국인과 같게 (단순화. 국민연금은 실제로 국가별 상호주의)",
                      "insuranceRounding": "floor10", "mealInInsuranceBase": False},
            "legalDeviations": legal,
            "accidents": A.items, "cleanSheets": clean,
            "valueNoise": [{"type": "BILINGUAL_HEADER", "detail": "헤더 칸 안에 줄바꿈으로 한글·영문 ('사번\\nID')"},
                           {"type": "NAME_SCRIPT_DIFFERS", "detail": "외국인 성명이 급여대장은 한글 표기, 직원명부·출퇴근기록은 여권 영문 — 사번으로 맞춰야 함"}],
            "crossFile": {"punch": {"file": PUNCH_FILE, "detail": "연장·야간·휴일 시간"},
                          "roster": {"file": "직원명부.csv", "detail": "기숙사·체류만료일·입사일(수습)"}}}


# ================================================================ 직원명부·조직

ROSTER_COLS = [("empNo", "사번(Emp No)"), ("name", "성명(Name)"), ("_nameKo", "한글표기"), ("_nation", "국적(Nationality)"),
               ("_visa", "체류자격(Visa)"), ("_visaEnd", "체류만료일(Visa Exp.)"), ("_frn", "외국인등록번호(ARC)"), ("dept", "부서(Dept)"),
               ("position", "직위(Title)"), ("_shift", "근무조(Shift)"), ("hireDate", "입사일(Join)"), ("birthDate", "생년월일(DOB)"),
               ("mobile", "연락처(Phone)"), ("email", "이메일(Email)"), ("empType", "고용형태(Type)"), ("status", "재직(Status)"),
               ("resignDate", "퇴사일(Leave)"), ("_dorm", "기숙사(Dorm)")]


def write_roster(cdir, emps, acc, seed):
    rng = B.rng_for(CID, "roster", seed)
    errs = B.RosterErrors()
    rows = []
    for e in emps:
        dorm = e["dorm"]
        if e["empNo"] == acc["movedOut"]:
            dorm = "퇴실 11/30"
        rows.append({"empNo": e["empNo"], "name": e["name"], "_nameKo": e["nameKo"] if e["foreign"] else None, "_nation": e["nation"],
                     "_visa": e["visa"], "_visaEnd": e["visaEnd"], "_frn": e["frn"], "dept": e["dept"], "position": e["position"],
                     "_shift": e["shift"], "hireDate": e["hireDate"], "birthDate": e["birthDate"], "mobile": e["mobile"], "email": e["email"],
                     "empType": e["empType"], "status": e["status"], "resignDate": e["resignDate"], "_dorm": dorm, "_e": e})
    idx = list(range(len(rows)))
    rng.shuffle(idx)
    reserved = {acc[k] for k in ("dormOver", "movedOut", "probation", "visa", "keyMissing")}
    take = lambda k, cond=lambda r: True: [idx.pop(idx.index(i)) for i in [j for j in idx if cond(rows[j]) and rows[j]["empNo"] not in reserved][:k]]  # noqa: E731
    vi = next(n for n, r in enumerate(rows) if r["empNo"] == acc["visa"])
    idx.remove(vi)
    errs.add(vi, "체류만료일(Visa Exp.)", "VISA_EXPIRED", "재직인데 체류만료일 %s가 기준일(2026-12-31)보다 이름 (체류기간 연장 기록 없음)"
             % rows[vi]["_visaEnd"].isoformat(), detectable=False)
    for i in take(2, lambda r: r["status"] == "재직"):
        rows[i]["hireDate"] = None
        errs.add(i, "hireDate", "REQUIRED", "입사일 빈 칸")
    for i in take(1, lambda r: r["email"]):
        rows[i]["email"] = rows[i]["email"].replace("@", " ")
        errs.add(i, "email", "INVALID_EMAIL", "이메일에 @ 대신 공백: %s" % rows[i]["email"])
    i, j = take(2, lambda r: r["_e"]["foreign"])
    rows[j]["empNo"] = rows[i]["empNo"]
    errs.add(i, "empNo", "DUPLICATE_KEY", "사번 중복 (다른 사람)")
    errs.add(j, "empNo", "DUPLICATE_KEY", "사번 중복 — 이 행의 실제 사번은 %s" % rows[j]["_e"]["empNo"])
    for i in take(1, lambda r: r["_e"]["foreign"]):
        rows[i]["empType"] = "E-9"
        errs.add(i, "empType", "INVALID_ENUM", "고용형태 칸에 체류자격 'E-9' (코드표: 정규직·계약직)")
    for i in take(1, lambda r: not r["_e"]["foreign"] and r["position"] == "주임"):
        rows[i]["position"] = "주인"
        errs.add(i, "position", "POSITION_TYPO", "직위 오타 '주인'", detectable=False)
    # 성실근로자 재입국: 새 사번으로 다시 등록했는데 예전 행 '재직' 그대로, 이름 띄어쓰기가 다름
    src = take(1, lambda r: r["_e"]["foreign"] and r["status"] == "재직" and len(r["name"].split()) == 3 and r["hireDate"])[0]
    old = dict(rows[src])
    parts = old["name"].split()
    old.update(empNo="%04d" % (900 + rng.randint(1, 99)), name="%s %s%s" % (parts[0], parts[1], parts[2]), hireDate=dt.date(2022, 5, 9),
               _visaEnd=dt.date(2025, 5, 8), _dorm=None)
    rows.append(old)
    errs.add(src, "name", "DUPLICATE_SUSPECTED", "같은 사람(생년월일·외국인등록번호 같음)이 재입국 전 사번 %s로도 재직, 이름은 '%s'로 띄어쓰기만 다름"
             % (old["empNo"], old["name"]))
    errs.add(len(rows) - 1, "name", "DUPLICATE_SUSPECTED", "재입국 전 행이 퇴사 처리되지 않음 (새 사번 %s)" % rows[src]["empNo"])
    errs.add(len(rows) - 1, "체류만료일(Visa Exp.)", "VISA_EXPIRED", "재직인데 체류만료일 2025-05-08 (재입국 전 행)", detectable=False)
    for r in rows:
        r["hireDate"] = B.fmt_date(r["hireDate"], B.date_style(rng, "iso", ["dot0"], 0.05))
        r["birthDate"] = B.fmt_date(r["birthDate"], B.date_style(rng, "iso", ["dot0"], 0.05))
        r["resignDate"] = B.fmt_date(r["resignDate"], "iso")
        v = r["_visaEnd"]
        r["_visaEnd"] = None if v is None else (v.strftime("%m/%d/%Y") if rng.random() < 0.3 else v.isoformat())
    B.date_format_errors(rows, ("hireDate", "birthDate", "resignDate"), errs)
    first = B.write_csv(cdir / "직원명부.csv", [h for _, h in ROSTER_COLS], [[r.get(f) for f, _ in ROSTER_COLS] for r in rows], "utf-8-sig")
    return {"mapping": {h: (None if f.startswith("_") else f) for f, h in ROSTER_COLS}, "errors": errs.rows(first), "rows": len(rows),
            "layout": {"encoding": "utf-8-sig", "firstDataRow": first,
                       "notes": ["헤더 한·영 병기 '성명(Name)'", "외국인 성명은 여권 영문 대문자, 한글표기 열 따로", "사번은 앞자리 0 없는 4자리 숫자 문자",
                                 "체류만료일은 ISO와 'MM/DD/YYYY'(영문 엑셀에서 입력) 혼재 — 매핑 없는 열이라 DATE_FORMAT 오류로 세지 않음",
                                 "외국인등록번호 마스킹 (뒷자리 첫 숫자 5~8)", "외국인·현장직 이메일 빈 칸 (오류 아님)"]}}


def write_org(cdir):
    org = [{"deptCode": "MB00", "name": "머버로지스 이천센터", "parentDeptCode": None}]
    org += [{"deptCode": "MB%02d" % (n * 10), "name": t, "parentDeptCode": "MB00"} for n, (t, _n, _s) in enumerate(TEAMS, 1)]
    cols = [("deptCode", "Dept Code"), ("name", "Dept Name"), ("parentDeptCode", "Parent")]
    B.write_org(cdir / "조직.csv", org, cols, "utf-8")
    return org, cols


def build(df, used, out_dir, seed):
    company = {"id": CID, "name": NAME, "industry": "물류",
               "profile": "115명 물류센터, 고용허가제 외국인 50명(여권 영문·한글 표기 혼재, 체류만료), 주야 2조, 기숙사·식비 공제, 지문인식기 원시 출퇴근 로그"}
    cdir = out_dir / CID
    cdir.mkdir(parents=True, exist_ok=True)
    emps = people(df, used, seed)
    for e in emps:
        e["status"] = "재직"
    rng = B.rng_for(CID, "plan", seed)
    fn = sorted((e for e in emps if e["foreign"] and e["shift"] == "야간"), key=lambda e: e["empNo"])
    dorm_f = [e for e in fn if e["dorm"]]
    visa_e = dorm_f[0]
    visa_e["visaEnd"] = dt.date(2026, 11, 20)
    probation = next(e for e in reversed(fn) if e is not visa_e)
    probation["hireDate"] = dt.date(2026, 11, 2)
    probation["visaEnd"] = dt.date(2029, 11, 1)
    rest = [e for e in dorm_f if e not in (visa_e, probation)]
    rng.shuffle(rest)
    dorm_over, moved, key_missing = rest[:3]
    plan = plan_work(emps, rng)
    ot_e = rng.choice([e for e in emps if e["shift"] == "야간" and e not in (visa_e, probation, dorm_over, moved, key_missing)
                       and month_hours(plan[e["empNo"]])["otH"] >= 8])
    ot_true = month_hours(plan[ot_e["empNo"]])["otH"]
    acc = {"dormOver": dorm_over["empNo"], "movedOut": moved["empNo"], "probation": probation["empNo"], "visa": visa_e["empNo"],
           "keyMissing": key_missing["empNo"], "otTypo": (ot_e["empNo"], ot_true + 10)}
    pay_key = write_payroll(cdir / PAY_FILE, emps, plan, acc)
    day = [e for e in emps if e["shift"] == "주간" and len(plan[e["empNo"]]) >= 10 and e is not ot_e]
    a, b = rng.sample(day, 2)
    common = sorted({d for d, *_x in plan[a["empNo"]]} & {d for d, *_x in plan[b["empNo"]]})
    pacc = {"outMissing": (rng.choice([e for e in day if e not in (a, b)])["empNo"], None), "proxy": (a["empNo"], b["empNo"], rng.choice(common)),
            "visaEmp": visa_e}
    om = next(e for e in emps if e["empNo"] == pacc["outMissing"][0])
    pacc["outMissing"] = (om["empNo"], rng.choice([d for d, *_x in plan[om["empNo"]]]))
    prng = B.rng_for(CID, "punch", seed)
    punch_key = write_punch(cdir / PUNCH_FILE, emps, plan, pacc, prng)
    roster = write_roster(cdir, emps, acc, seed)
    org, org_cols = write_org(cdir)
    return B.answer_key(company, "직원명부.csv", "조직.csv", roster["mapping"], org_cols, roster["errors"],
                        {"employees": len(emps), "rosterRows": roster["rows"], "orgs": len(org), "foreignWorkers": sum(e["foreign"] for e in emps)},
                        {PAY_FILE: pay_key, PUNCH_FILE: punch_key}, {"rosterLayout": roster["layout"]})
