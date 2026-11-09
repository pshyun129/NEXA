"""
사아랩스 — 15명 IT 스타트업. 엑셀 프로그램 없이 구글 시트로 관리하다 CSV로 내려받은 파일만 있다 (수식 없음).

파일: 직원명부.csv(UTF-8, BOM 없음, 이름이 첫 열, 날짜 '24-03-02'·'1994.03.02'·'19940302' 혼재, 연봉(만원) '4,800'),
     조직.csv(UTF-8), 급여_2026하반기.csv(UTF-8 BOM, 6개월 세로 배치, 금액 표기 변형), 연차현황.csv(UTF-8 BOM, 사고 없음).
급여 규칙: 포괄임금제(월 고정연장 20시간 포함), 대표이사 고용보험 제외, 입사·퇴사 월은 달력일 기준 일할, 10원 미만 버림.
"""
import datetime as dt

from hrfiles.common import Accidents
from hrfiles.facts import statutory_leave
from hrfiles.payroll import TAX_ROWS
from hrfiles.xl import ROUNDDOWN, vlookup_approx

from . import base as B

CID, NAME = "saalabs", "사아랩스"
PAY_FILE, LEAVE_FILE = "급여_2026하반기.csv", "연차현황.csv"
MONTHS = ["2026-07", "2026-08", "2026-09", "2026-10", "2026-11", "2026-12"]
FIXED_OT_H = 20

# (직급, 직책, 팀, 연봉 만원 범위)
ROLES = [("대표", "CEO", "경영", (9000, 9000)), ("이사", "CTO", "개발팀", (8000, 8000)),
         ("시니어", "팀리드", "개발팀", (6200, 7000)), ("시니어", "팀원", "개발팀", (5200, 6000)),
         ("시니어", "팀원", "개발팀", (5000, 5800)), ("주니어", "팀원", "개발팀", (3800, 4600)),
         ("주니어", "팀원", "개발팀", (3600, 4200)), ("시니어", "팀리드", "디자인팀", (5000, 5600)),
         ("주니어", "팀원", "디자인팀", (3400, 3900)), ("시니어", "팀리드", "비즈팀", (5200, 6000)),
         ("주니어", "팀원", "비즈팀", (3500, 4000)), ("주니어", "팀원", "비즈팀", (2600, 2600)),
         ("시니어", "팀리드", "운영팀", (4600, 5200)), ("주니어", "팀원", "운영팀", (3300, 3700)),
         ("주니어", "팀원", "개발팀", (3900, 4300))]
TEAMS = ["경영", "개발팀", "디자인팀", "비즈팀", "운영팀"]


def people(df, used, seed):
    rng = B.rng_for(CID, "people", seed)
    w = lambda o: 4.0 if B.has(o, "소프트웨어", "개발", "웹", "데이터", "디자인", "마케팅", "기획") else 1.0  # noqa: E731
    picked = sorted(B.pick(df, used, len(ROLES), rng, CID, w, ages=(24, 46)), key=lambda p: (-p["age"], p["uuid"]))
    emps = []
    for n, (p, (grade, duty, team, sal)) in enumerate(zip(picked, ROLES)):
        hire = dt.date(2022, 3, 2) if n < 2 else dt.date(2022, 3, 2) + dt.timedelta(days=rng.randint(60, 1650))
        emps.append({"name": p["name"], "birthDate": B.birth_of(p["age"], rng), "sex": p["sex"], "team": team, "grade": grade,
                     "duty": duty, "hireDate": min(hire, dt.date(2026, 6, 1)), "resignDate": None, "empType": "정규직",
                     "annual": rng.randint(*sal) // 100 * 100 if sal[0] != sal[1] else sal[0],
                     "mobile": B.phone(rng), "_familyType": p["family_type"], "age": int(p["age"])})
    # 사번은 입사 순서
    emps.sort(key=lambda e: e["hireDate"])
    for n, e in enumerate(emps, 1):
        e["empNo"] = "SA%03d" % n
        e["email"] = "%s@saalabs.io" % e["empNo"].lower()
    # 2026 이벤트: 7/31 퇴사 1명(정상), 9/15 퇴사 1명(퇴사 처리 누락), 11/17 입사 1명(일할 누락)
    late = sorted([e for e in emps if e["grade"] == "주니어" and e["annual"] != 2600 and e["team"] != "디자인팀"], key=lambda e: e["empNo"])
    late[0]["resignDate"] = dt.date(2026, 7, 31)
    late[1]["resignDate"] = dt.date(2026, 9, 15)
    late[-1]["hireDate"] = dt.date(2026, 11, 17)
    emps.sort(key=lambda e: e["hireDate"])
    for n, e in enumerate(emps, 1):  # 입사일이 바뀌었으니 사번 다시
        e["empNo"] = "SA%03d" % n
        e["email"] = "%s@saalabs.io" % e["empNo"].lower()
        e["status"] = "퇴사" if e["resignDate"] else "재직"
    r2 = B.rng_for(CID, "family", seed)
    for e in emps:
        e["carAlw"] = 200_000 if r2.random() < 0.35 and e["grade"] != "대표" else 0
        e["childAlw"] = 100_000 if "자녀" in (e["_familyType"] or "") and 29 <= e["age"] <= 42 else 0
        e["taxFamily"] = 1 + ("배우자" in (e["_familyType"] or "")) + (1 if e["childAlw"] else 0)
    return emps


# ================================================================ 급여 (포괄임금)

def split_pay(e, car=None):
    """월 급여 = 연봉/12 (10원 미만 버림) = 기본급 + 고정연장수당 + 식대 + 자가운전 + 육아"""
    monthly = ROUNDDOWN(e["annual"] * 10_000 / 12, -1)
    car = e["carAlw"] if car is None else car
    rest = monthly - 200_000 - car - e["childAlw"]
    basic = ROUNDDOWN(rest * 209 / (209 + FIXED_OT_H * 1.5), -1)
    return {"basePay": basic, "fixedOt": rest - basic, "meal": 200_000, "car": car, "child": e["childAlw"]}


def prorate(x, days, month_days):
    return ROUNDDOWN(x * days / month_days, -1)


def deductions(e, gross, taxable, ym, np_cap=True, other=0):
    np_base = ROUNDDOWN(taxable, -3)
    floor, cap = B.np_limits(ym)
    npv = ROUNDDOWN((min(max(np_base, floor), cap) if np_cap else np_base) * B.RATES_2026["np"], -1)
    hi = ROUNDDOWN(taxable * B.RATES_2026["hi"], -1)
    ltc = ROUNDDOWN(hi * B.RATES_2026["ltc"], -1)
    ei = 0 if e["grade"] == "대표" else ROUNDDOWN(taxable * B.RATES_2026["ei"], -1)
    tax = vlookup_approx(taxable, TAX_ROWS, min(e["taxFamily"], 5) + 2) if taxable >= TAX_ROWS[0][0] else 0
    ltax = ROUNDDOWN(tax * 0.1, -1)
    d = {"np": npv, "hi": hi, "ltc": ltc, "ei": ei, "tax": tax, "ltax": ltax, "other": other}
    d["ded"] = sum(d.values())
    d["net"] = gross - d["ded"]
    return d


def pay_row(e, ym, opts=None):
    """opts: noProrate, carOverLimitFull, noNpCap, leaveUnpaid(무급휴직), other(기타공제)"""
    o = opts or {}
    y, m = map(int, ym.split("-"))
    mdays = (dt.date(y + (m == 12), m % 12 + 1, 1) - dt.timedelta(days=1)).day
    start, end = dt.date(y, m, 1), dt.date(y, m, mdays)
    p = split_pay(e, o.get("car"))
    worked = (min(end, e["resignDate"] or end) - max(start, e["hireDate"])).days + 1
    if worked < mdays and not o.get("noProrate"):
        p = {k: prorate(v, worked, mdays) for k, v in p.items()}
    if o.get("leaveUnpaid"):
        p = {k: 0 for k in p}
    gross = sum(p.values())
    nontax = min(p["meal"], B.NONTAX_LIMIT) + (p["car"] if o.get("carOverLimitFull") else min(p["car"], B.NONTAX_LIMIT)) + \
        min(p["child"], B.NONTAX_LIMIT)
    taxable = gross - nontax
    if o.get("leaveUnpaid"):  # 무급휴직: 보험료는 휴직 전 보수월액 기준으로 계속 공제 (납부예외 신청 안 함)
        before = pay_row(e, "2026-11")
        d = deductions(e, 0, before["_taxable"], ym, other=o.get("other", 0))
        d.update(tax=0, ltax=0)
        d["ded"] = sum(d[k] for k in ("np", "hi", "ltc", "ei", "tax", "ltax", "other"))
        d["net"] = 0 - d["ded"]
    else:
        d = deductions(e, gross, taxable, ym, np_cap=not o.get("noNpCap"), other=o.get("other", 0))
    return {"ym": ym, "empNo": e["empNo"], "name": e["name"], **p, "gross": gross, **d, "_taxable": taxable, "memo": o.get("memo")}


PAY_COLS = [("ym", "귀속월"), ("empNo", "사번"), ("name", "이름"), ("basePay", "기본급"), ("fixedOt", "고정연장수당"), ("meal", "식대"),
            ("car", "자가운전보조금"), ("child", "육아수당"), ("gross", "지급합계"), ("np", "국민연금"), ("hi", "건강보험"),
            ("ltc", "장기요양"), ("ei", "고용보험"), ("tax", "소득세"), ("ltax", "지방소득세"), ("other", "기타공제"),
            ("ded", "공제합계"), ("net", "실지급액"), ("memo", "비고")]
COL = {k: chr(ord("A") + i) for i, (k, _) in enumerate(PAY_COLS)}
AMOUNTS = [k for k, _ in PAY_COLS[3:-1]]


def write_payroll(cdir, emps, seed):
    rng = B.rng_for(CID, "payroll", seed)
    A = Accidents("SAAL-PAY", PAY_FILE)
    by_no = {e["empNo"]: e for e in emps}
    ceo = next(e for e in emps if e["grade"] == "대표")
    quit_late = next(e for e in emps if e["resignDate"] == dt.date(2026, 9, 15))
    newbie = next(e for e in emps if e["hireDate"] == dt.date(2026, 11, 17))
    low = next(e for e in emps if e["annual"] == 2600)
    regular = [e for e in emps if e["resignDate"] is None and e not in (ceo, newbie, low) and e["hireDate"] < dt.date(2026, 1, 1)]
    parents = [e for e in regular if e["childAlw"]] or regular
    on_leave = rng.choice(parents)
    missing_nov, raised, car_over = rng.sample([e for e in regular if e is not on_leave], 3)
    plans = []  # (ym, e, actual row, ideal row, 사고 표시)
    for ym in MONTHS:
        y, m = map(int, ym.split("-"))
        start = dt.date(y, m, 1)
        for e in emps:
            end_ok = e["resignDate"] is None or e["resignDate"] >= start
            ideal_active = e["hireDate"] <= dt.date(y, m, 28) and end_ok
            opts, tag = {}, None
            if e is ceo:
                opts["noNpCap"], tag = True, "NP_CAP"
            if e is car_over:
                opts.update(car=300_000, carOverLimitFull=True)
                tag = "CAR"
            if e is on_leave and ym == "2026-12":
                opts.update(leaveUnpaid=True, other=300_000, memo="12/1~ 육아휴직(무급), 사내대출 상환 30만")
                tag = "NEG"
            if e is newbie and ym == "2026-11":
                opts["noProrate"], tag = True, "PRORATE"
            if e is missing_nov and ym == "2026-11":
                continue  # 11월 행 누락
            if e is quit_late and ym == "2026-10":  # 퇴사 처리 누락으로 10월 급여 지급
                actual = pay_row({**e, "resignDate": None}, ym)
                plans.append((ym, e, actual, None, "RESIGNED"))
                continue
            if not ideal_active:
                continue
            ideal_opts = {k: v for k, v in opts.items() if k in ("leaveUnpaid", "other", "memo", "car")}
            actual = pay_row(e, ym, opts)
            ideal = pay_row(e, ym, ideal_opts)
            if e is missing_nov and ym == "2026-12":  # 11월분을 12월에 소급 합산
                nov = pay_row(e, "2026-11")
                for k in AMOUNTS:
                    actual[k] += nov[k]
                actual["memo"], tag = "11월분 소급 포함", "MISSING"
            if e is raised and ym == "2026-10":  # 기본급 칸에만 일회성 소급 15만원을 더하고 합계·공제·실지급은 그대로
                actual["basePay"] += 150_000
                ideal = dict(actual)
                ideal["gross"] = actual["gross"] + 150_000
                ideal.update({k: v for k, v in deductions(e, ideal["gross"], actual["_taxable"] + 150_000, ym).items()})
                tag = "TOTAL"
            plans.append((ym, e, actual, ideal, tag))
    # 정렬: 귀속월, 사번
    plans.sort(key=lambda x: (x[0], x[1]["empNo"]))
    # 금액·귀속월 표기 (월마다 담당자·양식이 달라짐)
    style = {"2026-07": "plain", "2026-08": "plain", "2026-09": "plain", "2026-10": "comma", "2026-11": "comma", "2026-12": "won"}
    ym_text = {ym: (ym if ym < "2026-11" else ym.replace("-", ".")) for ym in MONTHS}
    out_rows = []
    for ym, e, a, _i, _t in plans:
        row = []
        for k, _ in PAY_COLS:
            v = a.get(k)
            if k == "ym":
                v = ym_text[ym]
            elif k in AMOUNTS:
                v = B.won_text(v, style[ym]) if v else (0 if style[ym] == "plain" else "0")
            row.append(v)
        out_rows.append(row)
    first = B.write_csv(cdir / PAY_FILE, [h for _, h in PAY_COLS], out_rows, "utf-8-sig")
    row_no = {(p[0], p[1]["empNo"]): first + n for n, p in enumerate(plans)}
    sheet = PAY_FILE.rsplit(".", 1)[0]

    def cells(r, keys):
        return ["%s%d" % (COL[k], r) for k in keys]

    def vals(a, i, keys):
        return {dict(PAY_COLS)[k]: {"actual": a[k], "expected": i[k]} for k in keys}

    def diff(a, i):
        return [k for k in AMOUNTS if a[k] != i[k]]

    for ym, e, a, i, tag in plans:
        r = row_no[(ym, e["empNo"])]
        if tag == "RESIGNED":
            A.add("RESIGNED_STILL_PAID", sheet, "2026-09-15 퇴사자인데 10월 급여 전액 지급 (직원명부 재직여부 '재직'으로 퇴사 처리 누락)",
                  ["CROSS_FILE_MATCH"], empNo=e["empNo"], rowNo=r, cells=cells(r, ["gross", "net"]),
                  values={"실지급액": {"actual": a["net"], "expected": 0}})
        elif tag == "MISSING":
            A.add("MONTH_ROW_MISSING", sheet, "2026-11 행이 없고 12월 행에 11월분을 더해 지급 (비고 '11월분 소급 포함') → 월별 이력·4대보험 귀속월이 틀어짐. "
                  "금액·공제 칸이 모두 두 달 치라 식대 %s원도 두 달 치(달마다 20만 한도 안)로 비과세 한도 초과가 아님" % format(a["meal"], ","),
                  ["PERIOD_CONTINUITY", "CROSS_FILE_MATCH"], empNo=e["empNo"], rowNo=r, missingMonth="2026-11",
                  cells=cells(r, diff(a, i)), values=vals(a, i, diff(a, i)),
                  note="expected는 12월 단월 값. 11월분은 따로 2026-11 행이어야 함 (금액은 12월 단월과 같음)")
        elif tag == "TOTAL" and ym == "2026-10":
            A.add("TOTAL_MISMATCH", sheet, "10월 기본급 칸에만 일회성 소급분 15만원을 더해 입력하고 지급합계·공제·실지급액은 고치지 않음 → 지급합계 ≠ 항목 합 "
                  "(연봉은 그대로라 11·12월 기본급은 원래 금액)",
                  ["RECOMPUTE_CHECK"], empNo=e["empNo"], rowNo=r, cells=cells(r, ["basePay"] + diff(a, i)),
                  values=vals(a, i, diff(a, i)),
                  note="expected는 기본급 칸의 15만원이 맞다고 보고 합계·공제·실지급을 다시 계산한 값")
        elif tag == "NEG":
            A.add("NEGATIVE_NET_PAY", sheet, "12월 무급 육아휴직인데 4대보험(납부예외 미신청)·사내대출 상환을 공제해 실지급액 %s원" % format(a["net"], ","),
                  ["RANGE_CHECK", "RECOMPUTE_CHECK"], empNo=e["empNo"], rowNo=r, cells=cells(r, ["gross", "np", "hi", "ltc", "ei", "other", "ded", "net"]),
                  values={"실지급액": {"actual": a["net"]}})
        elif tag == "PRORATE":
            A.add("PRORATION_MISSING", sheet, "11/17 입사자에게 11월 급여 전액 지급 (회사 규칙: 달력일 기준 일할 14/30, 식대·육아수당 포함 모든 항목) "
                  "→ 공제·실지급도 전액 기준",
                  ["CROSS_FILE_MATCH", "RECOMPUTE_CHECK"], empNo=e["empNo"], rowNo=r, cells=cells(r, diff(a, i)),
                  values=vals(a, i, diff(a, i)))
    ceo_rows = sorted(r for (ym, no), r in row_no.items() if no == ceo["empNo"])
    sample = next(p for p in plans if p[1] is ceo)
    assert all(p[0] >= "2026-07" for p in plans) and all(p[2]["np"] == sample[2]["np"] and p[2]["net"] == sample[2]["net"] for p in plans if p[1] is ceo)
    A.add("NP_CAP_NOT_APPLIED", sheet, "대표 국민연금을 기준소득월액 상한(2026-07~ 귀속 %s원) 없이 과세 급여 전액 × 4.75%%로 계산 "
          "(매달 %s원 → %s원이어야 함, 공제합계·실지급액도 같이 틀림)"
          % (format(B.np_limits("2026-07")[1], ","), format(sample[2]["np"], ","), format(sample[3]["np"], ",")), ["LEGAL_RULE_COMPARE", "RECOMPUTE_CHECK"],
          legal=True, empNo=ceo["empNo"], rows=ceo_rows, cells=["%s%d" % (COL[k], r) for r in ceo_rows for k in ("np", "ded", "net")],
          values=vals(sample[2], sample[3], diff(sample[2], sample[3])), note="values는 6개월 모두 같음")
    car_rows = sorted(r for (ym, no), r in row_no.items() if no == car_over["empNo"])
    sample = next(p for p in plans if p[1] is car_over)
    assert all(diff(p[2], p[3]) == diff(sample[2], sample[3]) and p[2]["net"] == sample[2]["net"] for p in plans if p[1] is car_over)
    A.add("NONTAX_OVER_LIMIT", sheet, "자가운전보조금 월 30만원을 전액 비과세로 처리 (한도 20만원) → 10만원이 과세·보험료 기준에서 빠져 "
          "4대보험·소득세·공제합계·실지급액이 틀림 (자가운전보조금 30만원 지급 자체는 맞음)",
          ["LEGAL_RULE_COMPARE", "RECOMPUTE_CHECK"], legal=True, empNo=car_over["empNo"], rows=car_rows,
          cells=["%s%d" % (COL[k], r) for r in car_rows for k in diff(sample[2], sample[3])],
          values=vals(sample[2], sample[3], diff(sample[2], sample[3])), note="values는 6개월 모두 같음")
    low_rows = sorted(r for (ym, no), r in row_no.items() if no == low["empNo"])
    p = split_pay(low)
    hourly = (p["basePay"] + p["meal"] + p["child"]) / 209
    A.add("MIN_WAGE_VIOLATION", sheet, "포괄임금 연봉 2,600만원: (기본급 %s + 식대 20만 + 육아수당 %s)/209 = 시간당 %s원 < 2026 최저임금 %s원 "
          "(고정연장수당은 최저임금 산입 안 됨, 자가운전보조금은 실비변상이라 제외)"
          % (format(p["basePay"], ","), format(p["child"], ","), format(int(hourly), ","), format(B.MIN_WAGE[2026], ",")),
          ["LEGAL_RULE_COMPARE", "RULE_EXTRACTION"], legal=True, empNo=low["empNo"], rows=low_rows,
          cells=["%s%d" % (COL["basePay"], r) for r in low_rows])
    key = {
        "file": PAY_FILE, "kind": "PAYROLL", "closestEntity": "PAY_RESULT_HISTORY", "supported": False, "format": "csv",
        "encoding": "utf-8-sig", "sheetOrder": [sheet],
        "sheets": {sheet: {"role": "monthlyPayrollLong", "headerRows": [1], "firstDataRow": first, "lastDataRow": first + len(plans) - 1,
                           "note": "CSV라 시트가 없음. 시트 이름은 파일 이름(확장자 제외)으로 둠",
                           "rowOf": {"%s|%s" % k: v for k, v in row_no.items()}}},
        "expectedMapping": {"monthlyPayrollLong": {h: (k if k != "ym" else "payMonth") for k, h in PAY_COLS}},
        "rules": {
            "payModel": "포괄임금제: 연봉/12(10원 미만 버림) − 식대 − 자가운전 − 육아 = 기본급 + 고정연장수당, 기본급 = 나머지 × 209/(209+20×1.5)",
            "proration": "입사·퇴사 월은 항목마다 × 재직일수/달력일수, 10원 미만 버림",
            "nonTaxable": "식대·자가운전보조금·육아수당 각각 월 20만원 한도",
            "nationalPension": {"base": "그달 과세 급여(천원 미만 버림)", "floor": B.np_limits("2026-07")[0], "cap": B.np_limits("2026-07")[1],
                                "note": "귀속월 기준 고시값. 이 파일은 2026-07~12라 모두 2026-07~2027-06 값"},
            "employmentInsurance": "대표이사는 고용보험 제외 (0원은 정상)",
            "insuranceRounding": "floor10", "incomeTax": "간이세액표(단순화), 가족 수 = 본인+배우자+자녀",
            "minimumWageBasis": "(기본급 + 식대 + 육아수당) / 209 ≥ 최저임금 (매월 현금으로 주는 복리후생비는 2024년부터 전액 산입, "
                                "고정연장수당·자가운전보조금(실비변상)은 제외)",
        },
        "legalDeviations": [{"rule": "nationalPensionCap", "company": "대표 행 상한 미적용",
                             "statutory": "기준소득월액 상한 %s원 (2026-07~ 귀속)" % format(B.np_limits("2026-07")[1], ",")},
                            {"rule": "nonTaxableLimit", "company": "자가운전보조금 30만원 전액 비과세", "statutory": "월 20만원"},
                            {"rule": "minimumWage", "company": "포괄임금 환산 시급 %d원" % int(hourly), "statutory": "%d원" % B.MIN_WAGE[2026]}],
        "accidents": A.items,
        "valueNoise": [
            {"type": "AMOUNT_TEXT", "detail": "10·11월은 금액이 '1,234,560' 문자, 12월은 '1,234,560원'. 7~9월은 숫자"},
            {"type": "PERIOD_FORMAT", "detail": "귀속월 '2026-07' ~ '2026-10', 11·12월은 '2026.11'"},
            {"type": "LONG_LAYOUT", "detail": "6개월이 한 파일에 세로로 (귀속월 열)"},
        ],
        "crossFile": {"roster": {"file": "직원명부.csv", "detail": "사번·이름·입사일·퇴사일과 대조 (퇴사자 지급, 일할 누락)"}},
    }
    return key


# ================================================================ 연차현황 (사고 없음)

def write_leave(cdir, emps, seed):
    rng = B.rng_for(CID, "leave", seed)
    rows, truth = [], {}
    members = [e for e in emps if e["resignDate"] is None]
    for e in members:
        g = statutory_leave(e["hireDate"])
        used = min(g, rng.randint(0, 2 * max(g, 1)) / 2)
        used = int(used) if float(used).is_integer() else used
        rows.append([e["empNo"], e["name"], e["hireDate"].isoformat(), g, used, g - used])
        truth[e["empNo"]] = {"granted": g, "used": used, "remain": g - used}
    heads = ["사번", "이름", "입사일", "발생", "사용", "잔여"]
    first = B.write_csv(cdir / LEAVE_FILE, heads, rows, "utf-8-sig")
    sheet = LEAVE_FILE.rsplit(".", 1)[0]
    return {"file": LEAVE_FILE, "kind": "LEAVE", "closestEntity": "LEAVE_BALANCE", "supported": False, "format": "csv",
            "encoding": "utf-8-sig", "clean": True, "sheetOrder": [sheet],
            "sheets": {sheet: {"role": "leaveSummary", "headerRows": [1], "firstDataRow": first, "lastDataRow": first + len(rows) - 1}},
            "expectedMapping": {"leaveSummary": dict(zip(heads, ["empNo", "name", "hireDate", "granted", "used", "remain"]))},
            "rules": {"granted": "근로기준법 제60조 그대로 (입사일 기준, 2026-12-31)", "remain": "발생 − 사용"},
            "employees": truth, "accidents": [],
            "note": "오탐 측정용: 일부러 넣은 사고가 없는 파일. 1년 미만 입사자의 발생은 월 단위(최대 11)"}


# ================================================================ 직원명부·조직

ROSTER_COLS = [("name", "이름"), ("empNo", "사번"), ("dept", "팀"), ("grade", "직급"), ("duty", "직책"), ("hireDate", "입사일"),
               ("birthDate", "생일"), ("email", "이메일"), ("mobile", "연락처"), ("empType", "고용형태"), ("status", "재직여부"),
               ("resignDate", "퇴사일"), ("_annual", "연봉(만원)"), ("_memo", "비고")]


def write_roster(cdir, emps, seed):
    rng = B.rng_for(CID, "roster", seed)
    errs = B.RosterErrors()
    rows = []
    for e in emps:
        rows.append({"name": e["name"], "empNo": e["empNo"], "dept": e["team"], "grade": e["grade"], "duty": e["duty"],
                     "hireDate": e["hireDate"], "birthDate": e["birthDate"], "email": e["email"], "mobile": e["mobile"],
                     "empType": e["empType"], "status": e["status"], "resignDate": e["resignDate"],
                     "_annual": B.won_text(e["annual"], "comma"), "_memo": None, "_e": e})
    quit_late = next(r for r in rows if r["resignDate"] == dt.date(2026, 9, 15))
    i = rows.index(quit_late)
    quit_late["status"] = "재직"
    quit_late["_memo"] = "9월 중순 퇴사"
    errs.add(i, "status", "STATUS_CONFLICT", "재직여부 '재직'인데 퇴사일 2026-09-15 (퇴사 처리 누락 → 10월 급여 지급)", detectable=False)
    pool = [n for n, r in enumerate(rows) if r["status"] == "재직" and r["grade"] == "주니어" and r is not quit_late and r["dept"] != "디자인팀"]
    rng.shuffle(pool)
    a, b, c, d = pool[:4]
    rows[a]["hireDate"] = None
    errs.add(a, "hireDate", "REQUIRED", "입사일 빈 칸")
    rows[b]["email"] = rows[b]["email"].replace("@", ".")
    errs.add(b, "email", "INVALID_EMAIL", "이메일에 @ 없음: %s" % rows[b]["email"])
    rows[c]["empType"] = "정규직(수습)"
    errs.add(c, "empType", "INVALID_ENUM", "고용형태 '정규직(수습)' (수습은 고용형태가 아니라 별도 기간)")
    rows[d]["empNo"] = "SA%d" % int(rows[d]["empNo"][2:])
    errs.add(d, "empNo", "EMPNO_FORMAT", "사번 자릿수 다름: '%s' (다른 파일은 '%s')" % (rows[d]["empNo"], rows[d]["_e"]["empNo"]), detectable=False)
    # 프리랜서로 일하다 정규직 전환된 디자이너: 예전 프리랜서 행이 그대로 '재직'
    des = next(n for n, r in enumerate(rows) if r["dept"] == "디자인팀" and r["grade"] == "주니어")
    old = dict(rows[des])
    old.update(empNo="SAF02", empType="프리랜서(3.3%)", hireDate=rows[des]["hireDate"] - dt.timedelta(days=240), email=None,
               _annual=None, _memo="사업소득 3.3%")
    rows.append(old)
    j = len(rows) - 1
    errs.add(des, "name", "DUPLICATE_SUSPECTED", "같은 사람(이름·생일 같음)이 프리랜서 행 SAF02로도 '재직'")
    errs.add(j, "name", "DUPLICATE_SUSPECTED", "정규직 전환 전 프리랜서 행이 닫히지 않음 (정규직 사번 %s)" % rows[des]["empNo"])
    errs.add(j, "empType", "NOT_EMPLOYEE", "사업소득자(프리랜서 3.3%)는 근로자 명부·급여 이관 대상 아님", detectable=False)
    for r in rows:
        r["hireDate"] = B.fmt_date(r["hireDate"], B.date_style(rng, "yy", ["dot", "iso"], 0.3))
        r["birthDate"] = B.fmt_date(r["birthDate"], B.date_style(rng, "compact", ["dot0"], 0.4))
        r["resignDate"] = B.fmt_date(r["resignDate"], "yy")
        r["mobile"] = rng.choice([r["mobile"], r["mobile"].replace("-", ""), r["mobile"].replace("-", " ")])
    B.date_format_errors(rows, ("hireDate", "birthDate", "resignDate"), errs)
    first = B.write_csv(cdir / "직원명부.csv", [h for _, h in ROSTER_COLS], [[r.get(f) for f, _ in ROSTER_COLS] for r in rows], "utf-8")
    mapping = {h: (None if f.startswith("_") else f) for f, h in ROSTER_COLS}
    return {"mapping": mapping, "errors": errs.rows(first), "rows": len(rows),
            "layout": {"encoding": "utf-8 (BOM 없음)", "firstDataRow": first,
                       "notes": ["이름이 첫 열, 사번이 둘째 열", "연봉(만원)은 '4,800' 같은 쉼표 문자",
                                 "직급 대표·이사·시니어·주니어, 직책 CEO·CTO·팀리드·팀원 (회사 고유 값, 오류 아님)",
                                 "휴대폰 표기 3가지 혼재 (값 변형)"]}}


def write_org(cdir):
    org = [{"deptCode": "SA00", "name": "사아랩스", "parentDeptCode": None}]
    for n, t in enumerate(TEAMS, 1):
        org.append({"deptCode": "SA%02d" % (n * 10), "name": t, "parentDeptCode": "SA00"})
    cols = [("deptCode", "팀코드"), ("name", "팀명"), ("parentDeptCode", "상위")]
    B.write_org(cdir / "조직.csv", org, cols, "utf-8")
    return org, cols


def build(df, used, out_dir, seed):
    company = {"id": CID, "name": NAME, "industry": "IT",
               "profile": "15명 스타트업, 구글 시트에서 내려받은 CSV만 (수식 없음), 포괄임금제, 6개월 세로 배치 급여"}
    cdir = out_dir / CID
    cdir.mkdir(parents=True, exist_ok=True)
    emps = people(df, used, seed)
    for e in emps:
        e["dept"] = e["team"]
    roster = write_roster(cdir, emps, seed)
    org, org_cols = write_org(cdir)
    hr = {PAY_FILE: write_payroll(cdir, emps, seed), LEAVE_FILE: write_leave(cdir, emps, seed)}
    return B.answer_key(company, "직원명부.csv", "조직.csv", roster["mapping"], org_cols, roster["errors"],
                        {"employees": len(emps), "rosterRows": roster["rows"], "orgs": len(org)}, hr,
                        {"rosterLayout": roster["layout"]})
