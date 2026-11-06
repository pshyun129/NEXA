"""
파하공단 — 250명 공공기관형 조직. 호봉제(직급×호봉 봉급표), 공무원 보수·복무 규정을 따라 만든 내부 규정.

파일: 직원명부.xlsx(1행 헤더, 마스킹된 주민등록번호 열, 임용일에 '2015년 3월 2일' 같은 문자 혼재, 직원번호는 앞자리 0이 있는 문자),
     조직.csv(UTF-8 BOM, 3단계: 이사장 > 본부·실 > 팀), 급여대장_2026.xlsx(10·11·12월 + 봉급표 2025·2026 + 수당기준),
     연가관리.xlsx(공무원 연가 일수표를 쓴 연가현황 + 사용내역).
급여 규칙: 봉급 = INDEX/MATCH(봉급표, 직급, 호봉), 정기승급은 임용 월에 1호봉, 4급 이상은 관리업무수당(봉급 9%)·시간외 없음,
         시간외 단가 = 봉급 × 55% / 209 × 1.5 (공무원 방식), 10원 미만 절사(TRUNC).
"""
import datetime as dt

from xlsxwriter.utility import xl_col_to_name

from hrfiles.common import Accidents
from hrfiles.facts import _dependents, draw_days, is_holiday, shift_workday, statutory_leave
from hrfiles.payroll import TAX_ROWS
from hrfiles.xl import NA, ROUNDDOWN, Book, XlErr, datedif_y, vlookup_approx

from . import base as B

CID, NAME = "pahagongdan", "파하공단"
PAY_FILE, LEAVE_FILE = "급여대장_2026.xlsx", "연가관리.xlsx"
SIZE = 250
GRADES = ["1급", "2급", "3급", "4급", "5급", "6급"]
LADDER = [(29, "6급", "주임"), (35, "5급", "대리"), (42, "4급", "과장"), (49, "3급", "차장"), (55, "2급", "부장"), (99, "1급", "실장")]
TEAMS = [("기획조정실", "기획예산팀"), ("기획조정실", "감사팀"), ("경영지원본부", "인사팀"), ("경영지원본부", "재무회계팀"),
         ("경영지원본부", "총무팀"), ("경영지원본부", "정보화팀"), ("사업본부", "사업1팀"), ("사업본부", "사업2팀"),
         ("사업본부", "사업3팀"), ("사업본부", "고객지원팀")]
MONTHS = ["2026.10", "2026.11", "2026.12"]
MAX_STEP = 32
# 봉급표: 1호봉 금액, 호봉당 증가액 (2025년). 2026년은 3.5% 인상 후 100원 단위 버림
PAY_2025 = {"1급": (3_350_000, 118_000), "2급": (3_020_000, 108_000), "3급": (2_720_000, 99_000),
            "4급": (2_450_000, 90_000), "5급": (2_210_000, 80_000), "6급": (1_990_000, 72_000)}
GRADE_ALW = {"1급": 500_000, "2급": 400_000, "3급": 300_000, "4급": 250_000, "5급": 175_000, "6급": 155_000}
MEAL = 140_000
LEAVE_TABLE = [(0, 11), (1, 12), (2, 14), (3, 15), (4, 17), (5, 20), (6, 21)]  # 국가공무원 복무규정 연가일수 (재직기간)


def table(year):
    out = {}
    for g, (first, inc) in PAY_2025.items():
        row = [first + inc * s for s in range(MAX_STEP)]
        out[g] = row if year == 2025 else [ROUNDDOWN(x * 1.035, -2) for x in row]
    return out


TABLE = {2025: table(2025), 2026: table(2026)}


def _team_of(occ, rng):
    for keys, team in [(("회계", "경리", "재무", "세무"), "재무회계팀"), (("인사", "노무", "교육"), "인사팀"),
                       (("소프트웨어", "정보", "시스템", "데이터", "네트워크"), "정보화팀"), (("감사", "법무"), "감사팀"),
                       (("기획", "예산", "정책"), "기획예산팀"), (("상담", "고객", "안내"), "고객지원팀"), (("사무", "총무", "비서"), "총무팀")]:
        if B.has(occ, *keys):
            return team
    return rng.choice(["사업1팀", "사업2팀", "사업3팀", "사업1팀", "사업2팀", "고객지원팀", "총무팀"])


def steps_since(hire, y, m):
    """임용 월 기준 정기승급 횟수: (y,m)월 1일까지 지난 임용 기념월 수"""
    return max(0, ((y * 12 + m) - (hire.year * 12 + hire.month)) // 12)


def people(df, used, seed):
    rng = B.rng_for(CID, "people", seed)
    picked = sorted(B.pick(df, used, SIZE, rng, CID), key=lambda p: (-p["age"], p["uuid"]))
    emps, seq = [], {}
    for p in picked:
        age = int(p["age"])
        birth = B.birth_of(age, rng)
        hire = dt.date(birth.year + rng.randint(23, max(23, min(age, 35))), rng.choice([1, 3, 3, 7, 9, 11]), rng.choice([1, 2]))
        hire = min(hire, dt.date(2026, 7, 1))
        _, grade, pos = next(x for x in LADDER if age <= x[0])
        seq[hire.year] = seq.get(hire.year, 0) + 1
        resign = None
        if rng.random() < 0.03:
            resign = dt.date(2026, rng.randint(1, 9), 28)
            resign = resign if resign > hire + dt.timedelta(days=100) else None
        e = {"empNo": "%02d%04d" % (hire.year % 100, seq[hire.year]), "name": p["name"], "birthDate": birth, "sex": p["sex"],
             "dept": _team_of(p["occupation"], rng), "grade": grade, "position": pos, "duty": "팀원", "hireDate": hire,
             "resignDate": resign, "status": "퇴직" if resign else "재직",
             "empType": rng.choices(["정규직", "무기계약직", "계약직"], [92, 6, 2])[0], "mobile": B.phone(rng),
             "initStep": rng.randint(1, 6), "_familyType": p["family_type"]}
        e["email"] = "%s@pahagongdan.or.kr" % e["empNo"]
        emps.append(e)
    for team in {t for _, t in TEAMS}:
        act = [e for e in emps if e["dept"] == team and e["status"] == "재직"]
        if act:
            act[0]["duty"] = "팀장"
    drng = B.rng_for(CID, "deps", seed)
    for e in emps:
        deps = _dependents(e, drng)
        kids = [d for d in deps if d["relation"] == "자녀"]
        e["taxFamily"] = 1 + sum(d["basic"] for d in deps)
        e["familyAlw"] = (40_000 if any(d["relation"] == "배우자" for d in deps) else 0) + \
            sum([50_000, 80_000, 120_000][min(i, 2)] for i in range(len(kids)))
        e["otHours"] = {ym: (drng.choice([0, 0, 4, 8, 10, 12, 15, 20]) if e["grade"] in ("5급", "6급") else 0) for ym in MONTHS}
    emps.sort(key=lambda e: (e["hireDate"], e["empNo"]))
    return emps


def step_at(e, ym):
    y, m = map(int, ym.split("."))
    return min(MAX_STEP, e["initStep"] + steps_since(e["hireDate"], y, m))


# ================================================================ 급여

COLS = [("empNo", "직원번호"), ("name", "성명"), ("dept", "소속"), ("grade", "직급"), ("step", "호봉"), ("family", "공제가족"),
        ("pay", "봉급"), ("gradeAlw", "직급보조비"), ("meal", "정액급식비"), ("familyAlw", "가족수당"), ("mgmtAlw", "관리업무수당"),
        ("otH", "시간외(h)"), ("otPay", "시간외수당"), ("gross", "지급계"), ("taxable", "과세대상"), ("np", "국민연금"),
        ("hi", "건강보험"), ("ltc", "장기요양"), ("ei", "고용보험"), ("tax", "소득세"), ("ltax", "지방소득세"), ("ded", "공제계"),
        ("net", "실지급액")]
L = {k: xl_col_to_name(i) for i, (k, _) in enumerate(COLS)}
TOTAL_KEYS = [k for k, _ in COLS[COLS.index(("pay", "봉급")):]]
SETS = {"np": B.RATES_2026["np"], "hi": B.RATES_2026["hi"], "ltc": B.RATES_2026["ltc"], "ei": B.RATES_2026["ei"],
        "mealLimit": B.NONTAX_LIMIT, "npFloor": B.NP_FLOOR, "npCap": B.NP_CAP, "otBase": 0.55, "mgmtRate": 0.09}
SET_ROW = {k: 12 + i for i, k in enumerate(SETS)}


def SR(k):
    return "수당기준!$C$%d" % SET_ROW[k]


def tbl_ref(year):
    return "'봉급표(%d)'" % year


def calc(v, year=2026):
    v = dict(v)
    g = v["grade"]
    if g not in TABLE[year]:  # MATCH 실패 → #N/A 전파 (관리업무수당은 IF가 직급 비교에서 0을 골라 오류를 안 탐)
        for k in ("pay", "gradeAlw", "otPay", "gross", "taxable", "np", "hi", "ltc", "ei", "tax", "ltax", "ded", "net"):
            v[k] = NA
        v["mgmtAlw"] = 0
        return v
    v["pay"] = TABLE[year][g][v["step"] - 1]
    v["gradeAlw"] = GRADE_ALW[g]
    v["mgmtAlw"] = ROUNDDOWN(v["pay"] * SETS["mgmtRate"], -1) if g in ("1급", "2급", "3급", "4급") else 0
    v["otPay"] = 0 if v["mgmtAlw"] > 0 else ROUNDDOWN(ROUNDDOWN(v["pay"] * SETS["otBase"] / 209 * 1.5, 0) * v["otH"], -1)
    if v.get("_otOverride") is not None:
        v["otPay"] = v["_otOverride"]
    v["gross"] = v["pay"] + v["gradeAlw"] + v["meal"] + v["familyAlw"] + v["mgmtAlw"] + v["otPay"]
    v["taxable"] = v["gross"] - min(v["meal"], SETS["mealLimit"])
    v["np"] = ROUNDDOWN(min(max(v["taxable"], SETS["npFloor"]), SETS["npCap"]) * SETS["np"], -1)
    v["hi"] = ROUNDDOWN(v["taxable"] * SETS["hi"], -1)
    v["ltc"] = ROUNDDOWN(v["hi"] * SETS["ltc"], -1)
    v["ei"] = ROUNDDOWN(v["taxable"] * SETS["ei"], -1)
    v["tax"] = vlookup_approx(v["taxable"], TAX_ROWS, min(v["family"], 5) + 2)
    v["ltax"] = ROUNDDOWN(v["tax"] * 0.1, -1)
    v["ded"] = v["np"] + v["hi"] + v["ltc"] + v["ei"] + v["tax"] + v["ltax"]
    v["net"] = v["gross"] - v["ded"]
    # TRUNC(금액×요율,-1)이 정확히 10원 경계라 이진 부동소수 검산에서 10원 낮게 나올 수 있는 칸
    ot_unit = v["pay"] * SETS["otBase"] / 209 * 1.5
    v["_fb"] = [k for k, x, dg in (("mgmtAlw", v["pay"] * SETS["mgmtRate"] if v["mgmtAlw"] else 0, -1),
                                   ("otPay", ot_unit if v["otH"] and not v["mgmtAlw"] else 0, 0),
                                   ("otPay", ROUNDDOWN(ot_unit, 0) * v["otH"] if not v["mgmtAlw"] else 0, -1),
                                   ("np", min(max(v["taxable"], SETS["npFloor"]), SETS["npCap"]) * SETS["np"], -1),
                                   ("hi", v["taxable"] * SETS["hi"], -1), ("ltc", v["hi"] * SETS["ltc"], -1),
                                   ("ei", v["taxable"] * SETS["ei"], -1), ("ltax", v["tax"] * 0.1, -1))
                if B.float_boundary(x, dg)]
    return v


def formulas(r, year):
    R = lambda k: "%s%d" % (L[k], r)  # noqa: E731
    t = tbl_ref(year)
    return {
        "pay": "=INDEX(%s!$C$3:$%s$8,MATCH(%s,%s!$B$3:$B$8,0),%s)" % (t, xl_col_to_name(1 + MAX_STEP), R("grade"), t, R("step")),
        "gradeAlw": "=VLOOKUP(%s,수당기준!$A$3:$B$8,2,FALSE)" % R("grade"),
        "mgmtAlw": '=IF(OR(%s="1급",%s="2급",%s="3급",%s="4급"),TRUNC(%s*%s,-1),0)' % (R("grade"), R("grade"), R("grade"), R("grade"), R("pay"), SR("mgmtRate")),
        "otPay": "=IF(%s>0,0,TRUNC(TRUNC(%s*%s/209*1.5,0)*%s,-1))" % (R("mgmtAlw"), R("pay"), SR("otBase"), R("otH")),
        "gross": "=SUM(%s:%s,%s)" % (R("pay"), R("mgmtAlw"), R("otPay")),
        "taxable": "=%s-MIN(%s,%s)" % (R("gross"), R("meal"), SR("mealLimit")),
        "np": "=TRUNC(MIN(MAX(%s,%s),%s)*%s,-1)" % (R("taxable"), SR("npFloor"), SR("npCap"), SR("np")),
        "hi": "=TRUNC(%s*%s,-1)" % (R("taxable"), SR("hi")),
        "ltc": "=TRUNC(%s*%s,-1)" % (R("hi"), SR("ltc")),
        "ei": "=TRUNC(%s*%s,-1)" % (R("taxable"), SR("ei")),
        "tax": "=VLOOKUP(%s,간이세액표!$A$2:$G$%d,MIN(%s,5)+2,TRUE)" % (R("taxable"), 1 + len(TAX_ROWS), R("family")),
        "ltax": "=TRUNC(%s*0.1,-1)" % R("tax"),
        "ded": "=SUM(%s:%s)" % (R("np"), R("ltax")),
        "net": "=%s-%s" % (R("gross"), R("ded")),
    }


def write_payroll(path, emps, acc):
    A = Accidents("PAHA-PAY", path.name)
    book = Book(path)
    sheets = {ym: book.sheet(ym) for ym in MONTHS}
    for year in (2026, 2025):
        sh = book.sheet("봉급표(%d)" % year)
        sh.put("A", 1, "%d년 직원 봉급표 (월지급액, 단위: 원)" % year, "title")
        sh.put("B", 2, "직급", "head")
        for s in range(MAX_STEP):
            sh.put(xl_col_to_name(2 + s), 2, s + 1, "head")
        for n, g in enumerate(GRADES):
            sh.put("B", 3 + n, g)
            for s, x in enumerate(TABLE[year][g]):
                sh.put(xl_col_to_name(2 + s), 3 + n, x, "won")
    s_set = book.sheet("수당기준")
    s_set.put("A", 1, "수당 기준 (2026년 보수규정)", "title")
    s_set.put("A", 2, "직급", "head")
    s_set.put("B", 2, "직급보조비", "head")
    for n, g in enumerate(GRADES):
        s_set.put("A", 3 + n, g)
        s_set.put("B", 3 + n, GRADE_ALW[g], "won")
    s_set.put("B", 11, "항목", "head")
    s_set.put("C", 11, "값", "head")
    labels = {"np": "국민연금", "hi": "건강보험", "ltc": "장기요양", "ei": "고용보험", "mealLimit": "식대 비과세 한도",
              "npFloor": "국민연금 하한", "npCap": "국민연금 상한", "otBase": "시간외 단가 기준(봉급의)", "mgmtRate": "관리업무수당(4급 이상, 봉급의)"}
    for k, v in SETS.items():
        s_set.put("B", SET_ROW[k], labels[k])
        s_set.put("C", SET_ROW[k], v, "pct" if v < 1 else "won")
    s_set.widths({"A": 10, "B": 30, "C": 12})
    s_tax = book.sheet("간이세액표")
    for c, h in zip("ABCDEFG", ["이상", "미만", "1인", "2인", "3인", "4인", "5인 이상"]):
        s_tax.put(c, 1, h, "head")
    for n, row in enumerate(TAX_ROWS):
        for c, v in zip("ABCDEFG", row):
            s_tax.put(c, 2 + n, v, "won")

    meta, values = {}, {}
    for ym in MONTHS:
        y, m = map(int, ym.split("."))
        sh = sheets[ym]
        year = 2025 if ym == acc["staleTable"] else 2026
        for k, h in COLS:
            sh.put(L[k], 1, h, "head")
        sh.ws.freeze_panes(1, 2)
        members = [e for e in emps if e["hireDate"] <= dt.date(y, m, 1) and (e["resignDate"] is None or e["resignDate"] >= dt.date(y, m, 1))]
        members.sort(key=lambda e: (GRADES.index(e["grade"]), e["empNo"]))
        r = 2
        row_of, vals, fb = {}, {}, []
        for e in members:
            step_true = step_at(e, ym)
            inp = {"empNo": e["empNo"], "name": e["name"], "dept": e["dept"], "grade": e["grade"], "step": step_true,
                   "family": e["taxFamily"], "meal": MEAL, "familyAlw": e["familyAlw"], "otH": e["otHours"][ym]}
            ideal = calc(inp)
            act_in = dict(inp)
            over = acc["rows"].get((ym, e["empNo"]), {})
            act_in.update(over.get("inputs", {}))
            actual = calc(act_in, year)
            F = formulas(r, year)
            for k, _ in COLS:
                if k in F and k not in over.get("hard", ()):
                    sh.formula(L[k], r, F[k], actual[k], "won")
                else:
                    x = actual[k]
                    sh.put(L[k], r, x, "text" if k == "empNo" else ("won" if isinstance(x, int) and x >= 1000 else None))
            row_of[e["empNo"]] = r
            vals[e["empNo"]] = {"actual": actual, "ideal": ideal}
            fb += ["%s%d" % (L[k], r) for k in dict.fromkeys(actual.get("_fb", [])) if k not in over.get("hard", ())]
            r += 1
        total = r
        sh.put(L["name"], r, "합계", "totalLabel")
        for k in TOTAL_KEYS:
            xs = [v["actual"][k] for v in vals.values()]
            err = next((x for x in xs if isinstance(x, XlErr)), None)
            sh.formula(L[k], r, "=SUM(%s2:%s%d)" % (L[k], L[k], r - 1), err or sum(xs), "total")
        sh.widths({L["name"]: 9, L["dept"]: 11})
        meta[ym] = {"role": "monthlyPayroll", "payMonth": ym.replace(".", "-"), "headerRows": [1], "firstDataRow": 2,
                    "lastDataRow": total - 1, "totalRow": total, "rowOf": row_of,
                    "formulas": {k: f for k, f in formulas(2, year).items()}, "floatBoundaryCells": fb}
        values[ym] = vals
    book.close()

    hdr = dict(COLS)
    ym = acc["staleTable"]
    m = meta[ym]
    A.add("STALE_PAY_TABLE", ym, "%s 시트의 봉급 수식이 '봉급표(2025)'를 참조 (11·12월은 2026) — 인상분 3.5%%가 빠진 봉급" % ym,
          ["FORMULA_READ", "RATE_TABLE_BY_PERIOD", "MULTI_SHEET"],
          cells=["%s%d:%s%d" % (L["pay"], m["firstDataRow"], L["pay"], m["lastDataRow"])],
          formula={"actual": m["formulas"]["pay"], "expected": formulas(2, 2026)["pay"]},
          rowsWithDifferentValue=sorted(m["rowOf"][x] for x, v in values[ym].items() if v["actual"]["pay"] != v["ideal"]["pay"]))
    def diff_keys(v, skip=()):
        return [k for k, _ in COLS if k not in skip and v["actual"][k] != v["ideal"][k]]

    def cascade_values(v, keys):
        return {hdr[k]: {"actual": v["actual"][k], "expected": v["ideal"][k]} for k in keys}

    row_acc = {}  # (시트, 사번) → 사고 id (합계 행 겹침 표시용)
    for (ym, emp), over in sorted(acc["rows"].items()):
        r = meta[ym]["rowOf"][emp]
        v = values[ym][emp]
        tot = {"sheet": ym, "rowNo": meta[ym]["totalRow"]}
        if over["type"] == "STEP_NOT_ADVANCED":
            casc = diff_keys(v, ("step",))
            A.add("STEP_NOT_ADVANCED", ym, "11월 임용일이 돌아와 정기승급(%d→%d호봉) 대상인데 호봉을 고치지 않음 (직원명부는 %d호봉)"
                  % (v["actual"]["step"], v["ideal"]["step"], v["ideal"]["step"]), ["CROSS_FILE_MATCH", "PERIODIC_STEP_RULE"],
                  empNo=emp, rowNo=r, cells=["%s%d" % (L[k], r) for k in ["step"] + casc],
                  values={"호봉": {"actual": v["actual"]["step"], "expected": v["ideal"]["step"]}, **cascade_values(v, casc)},
                  cascade=[hdr[k] for k in casc], affected=[{"sheet": ym, "rowNo": r}, tot])
        elif over["type"] == "ALLOWANCE_RULE_CONFLICT":
            casc = diff_keys(v, ("otH", "otPay"))
            A.add("ALLOWANCE_RULE_CONFLICT", ym, "관리업무수당을 받는 %s인데 시간외 %d시간을 적고 시간외수당 %s원을 수식 대신 값으로 입력 "
                  "(규정: 4급 이상 시간외 미지급, 같은 사람 10·12월 시간외 0시간)"
                  % (v["actual"]["grade"], v["actual"]["otH"], format(v["actual"]["otPay"], ",")),
                  ["CROSS_FIELD_RULE", "HARDCODED_IN_FORMULA_COLUMN", "RULE_EXTRACTION"],
                  empNo=emp, rowNo=r, cells=["%s%d" % (L[k], r) for k in ["otH", "otPay"] + casc],
                  values={"시간외(h)": {"actual": v["actual"]["otH"], "expected": v["ideal"]["otH"]},
                          "시간외수당": {"actual": v["actual"]["otPay"], "expected": 0}, **cascade_values(v, casc)},
                  cascade=[hdr[k] for k in casc], affected=[{"sheet": ym, "rowNo": r}, tot])
        elif over["type"] == "LOOKUP_NA":
            casc = diff_keys(v, ("grade",))
            na_cols = [L[k] for k in casc]
            A.add("LOOKUP_NA", ym, "직급을 '%s'(공백 포함)로 입력 → MATCH·VLOOKUP 실패로 #N/A, 이 행의 %d개 열과 합계 행까지 전파"
                  % (v["actual"]["grade"], len(casc)),
                  ["ERROR_VALUE", "VALUE_NORMALIZE"], empNo=emp, rowNo=r, cells=["%s%d" % (L["grade"], r)],
                  values={"직급": {"actual": v["actual"]["grade"], "expected": v["ideal"]["grade"]}},
                  affected=[{"sheet": ym, "rowNo": r}, tot],
                  cascade=[hdr[k] for k in casc],
                  cascadeCells=["%s%d" % (c, x) for x in (r, meta[ym]["totalRow"]) for c in na_cols])
        row_acc[(ym, emp)] = A.items[-1]
    # 겹침: #N/A 합계 행이 같은 시트의 다른 사고(합계 행 값 차이)를 가린다
    na_acc = [a for a in row_acc.values() if a["type"] == "LOOKUP_NA"]
    for na in na_acc:
        ym = na["sheet"]
        tr = meta[ym]["totalRow"]
        true_tot = {hdr[k]: sum(x["ideal"][k] for x in values[ym].values()) for k in ("gross", "net")}
        others = [a for (s, _e), a in sorted(row_acc.items()) if s == ym and a is not na]
        for o in others:
            note = "합계 행 %d이 #N/A라 %s의 합계 차이가 드러나지 않음" % (tr, o["id"])
            na.setdefault("overlaps", []).append({"with": o["id"], "cells": ["%s%d" % (L[k], tr) for k in ("gross", "net")],
                                                  "note": note, "trueValue": true_tot})
            o.setdefault("overlaps", []).append({"with": na["id"], "cells": ["%s%d" % (L[k], tr) for k in ("gross", "net")],
                                                 "note": note, "trueValue": true_tot})
    ot_rows = sum(1 for ym in MONTHS for v in values[ym].values() if isinstance(v["ideal"]["otPay"], int) and v["ideal"]["otPay"] > 0)
    clean = [ym for ym in MONTHS if ym != acc["staleTable"] and not any(k[0] == ym for k in acc["rows"])]
    return {
        "file": path.name, "kind": "PAYROLL", "closestEntity": "PAY_RESULT_HISTORY", "supported": False,
        "sheetOrder": MONTHS + ["봉급표(2026)", "봉급표(2025)", "수당기준", "간이세액표"],
        "sheets": {**meta, "봉급표(2026)": {"role": "lookupTable", "note": "직급(행)×호봉(열) 봉급표"},
                   "봉급표(2025)": {"role": "lookupTable", "note": "작년 봉급표가 같은 파일에 남아 있음 (참조하면 사고)"},
                   "수당기준": {"role": "settings", "values": {**SETS, "gradeAllowance": GRADE_ALW, "meal": MEAL},
                              "cells": {k: SR(k) for k in SETS}},
                   "간이세액표": {"role": "lookupTable", "note": "단순화한 간이세액표 (실제 국세청 표 아님)"}},
        "expectedMapping": {"monthlyPayroll": {h: k for k, h in COLS}},
        "rules": {
            "basePay": "INDEX(봉급표(2026), MATCH(직급), 호봉)", "stepRule": "임용 월 1일마다 1호봉 승급 (최대 32)",
            "gradeAllowance": "직급별 정액 (수당기준 VLOOKUP)", "mealAllowance": "정액급식비 14만원 (비과세 한도 안)",
            "familyAllowance": "배우자 4만, 자녀 첫째 5만·둘째 8만·셋째 이상 12만 (값 입력)",
            "managementAllowance": "1~4급: 봉급 × 9%, 10원 미만 절사, 시간외수당 없음",
            "overtime": "TRUNC(봉급×55%/209×1.5) × 시간, 10원 미만 절사 (5·6급만)",
            "insuranceRounding": "floor10 (TRUNC -1)", "nationalPension": {"base": "그달 과세대상", "floor": B.NP_FLOOR, "cap": B.NP_CAP},
        },
        "legalDeviations": [{"rule": "overtimeBasis", "company": "봉급의 55%를 209로 나눈 단가 × 1.5",
                             "statutory": "통상임금(봉급+직급보조비+정액급식비 등 정기·일률 지급분)/209 × 1.5",
                             "rows": ot_rows, "note": "공무원 보수규정 방식. 근로기준법 적용 기관이면 통상임금보다 낮은 단가"}],
        "accidents": A.items, "cleanSheets": clean,
        "valueNoise": [{"type": "EMPNO_TEXT_LEADING_ZERO", "detail": "직원번호 '050012'처럼 앞자리 0이 있는 문자 (2000년대 임용)"},
                       {"type": "SHEET_NAME_DOT_MONTH", "detail": "월 시트 이름 '2026.10'"},
                       {"type": "SORTED_BY_GRADE", "detail": "직급 순(1급→6급) 정렬, 사번 순 아님"}],
        "crossFile": {"roster": {"file": "직원명부.xlsx", "detail": "12월 호봉 = 직원명부 호봉 (2026-12 기준)"}},
    }


# ================================================================ 연가관리

def write_leave(path, emps, rng):
    A = Accidents("PAHA-LV", path.name)
    book = Book(path)
    s_sum, s_use, s_cfg = book.sheet("연가현황"), book.sheet("사용내역"), book.sheet("기준")
    s_cfg.put("A", 1, "재직기간(년 이상)", "head")
    s_cfg.put("B", 1, "연가일수", "head")
    for n, (yrs, days) in enumerate(LEAVE_TABLE):
        s_cfg.put("A", 2 + n, yrs)
        s_cfg.put("B", 2 + n, days)
    members = sorted([e for e in emps if e["status"] == "재직" and e["hireDate"] <= B.BASE_DATE], key=lambda e: e["empNo"])
    granted = {e["empNo"]: next(d for yrs, d in reversed(LEAVE_TABLE) if datedif_y(e["hireDate"], B.BASE_DATE) >= yrs) for e in members}
    uses = []
    for e in members:
        start = max(dt.date(2026, 1, 1), e["hireDate"] + dt.timedelta(days=30))
        days = [start + dt.timedelta(days=i) for i in range((B.BASE_DATE - start).days + 1)]
        days = draw_days(days)  # 첫 버전 달력으로 뽑고 7/17(제헌절)에 뽑힌 날만 다음 근무일로 (다른 날짜·난수열 유지)
        target = int(granted[e["empNo"]] * rng.uniform(0.3, 0.9))
        taken = set()
        for d in sorted(rng.sample(days, min(len(days), target))):
            d = shift_workday(d, taken, B.BASE_DATE)
            taken.add(d)
            uses.append({"emp": e, "start": d, "end": d, "days": 1, "kind": "연가"})
        if rng.random() < 0.15:
            d = rng.choice(days)
            while is_holiday(d) or d in taken:  # 같은 날 연가와 겹치거나 공휴일이면 다음 근무일 (난수는 더 쓰지 않음)
                d += dt.timedelta(days=1)
                if d > B.BASE_DATE:
                    d = start
            uses.append({"emp": e, "start": d, "end": d, "days": 1, "kind": rng.choice(["병가", "공가", "특별휴가"])})
    # 사고: 병가 5일을 '연가'로 기록 → 연가 초과 사용
    over_e = max([e for e in members if granted[e["empNo"]] <= 14], key=lambda e: (sum(u["days"] for u in uses if u["emp"] is e), e["empNo"]))
    used_now = sum(u["days"] for u in uses if u["emp"] is over_e and u["kind"] == "연가")
    sick_n = granted[over_e["empNo"]] - used_now + 3
    sick_start = dt.date(2026, 11, 16)
    sick = {"emp": over_e, "start": sick_start, "end": sick_start + dt.timedelta(days=sick_n - 1 + 2 * ((sick_n - 1) // 5)),
            "days": sick_n, "kind": "연가", "trueKind": "병가"}
    uses = [u for u in uses if not (u["emp"] is over_e and sick["start"] <= u["start"] <= sick["end"])]
    uses.append(sick)
    # 사고: 공휴일 연가, 종료일 < 시작일
    hol_u = rng.choice([u for u in uses if u["kind"] == "연가" and u["emp"] is not over_e])
    hol_u["orig"] = hol_u["start"]
    hol_u["start"] = hol_u["end"] = dt.date(2026, 10, 9)  # 한글날
    rev_u = rng.choice([u for u in uses if u["kind"] == "연가" and u["emp"] not in (over_e, hol_u["emp"])])
    rev_u["end"] = rev_u["start"] + dt.timedelta(days=1)
    rev_u["days"] = 2
    rev_u["start"], rev_u["end"] = rev_u["end"], rev_u["start"]
    rev_u["reversed"] = True
    uses.sort(key=lambda u: (u["start"], u["emp"]["empNo"]))

    heads = ["직원번호", "성명", "시작일", "종료일", "일수", "종류"]
    for c, h in zip("ABCDEF", heads):
        s_use.put(c, 1, h, "head")
    last_use = 1 + len(uses)
    for n, u in enumerate(uses):
        r = 2 + n
        u["row"] = r
        s_use.put("A", r, u["emp"]["empNo"], "text")
        s_use.put("B", r, u["emp"]["name"])
        s_use.put("C", r, u["start"])
        s_use.put("D", r, u["end"])
        s_use.put("E", r, u["days"])
        s_use.put("F", r, u["kind"])
    sum_heads = ["직원번호", "성명", "소속", "임용일", "재직기간(년)", "연가일수", "사용일수", "잔여일수"]
    for c, h in zip("ABCDEFGH", sum_heads):
        s_sum.put(c, 1, h, "head")
    row_of, truth = {}, {}
    for n, e in enumerate(members):
        r = 2 + n
        yrs = datedif_y(e["hireDate"], B.BASE_DATE)
        used = sum(u["days"] for u in uses if u["emp"] is e and u["kind"] == "연가")
        true_used = sum(u["days"] for u in uses if u["emp"] is e and u.get("trueKind", u["kind"]) == "연가")
        s_sum.put("A", r, e["empNo"], "text")
        s_sum.put("B", r, e["name"])
        s_sum.put("C", r, e["dept"])
        s_sum.put("D", r, e["hireDate"])
        s_sum.formula("E", r, '=DATEDIF(D%d,DATE(2026,12,31),"Y")' % r, yrs)
        s_sum.formula("F", r, "=VLOOKUP(E%d,기준!$A$2:$B$%d,2,TRUE)" % (r, 1 + len(LEAVE_TABLE)), granted[e["empNo"]])
        s_sum.formula("G", r, '=SUMIFS(사용내역!$E$2:$E$%d,사용내역!$A$2:$A$%d,A%d,사용내역!$F$2:$F$%d,"연가")' % (last_use, last_use, r, last_use), used)
        s_sum.formula("H", r, "=F%d-G%d" % (r, r), granted[e["empNo"]] - used)
        row_of[e["empNo"]] = r
        truth[e["empNo"]] = {"granted": granted[e["empNo"]], "statutory": statutory_leave(e["hireDate"]), "used": true_used,
                             "remain": granted[e["empNo"]] - true_used}
    s_sum.widths({"D": 11})
    s_use.widths({"C": 11, "D": 11})
    book.close()
    r = row_of[over_e["empNo"]]
    A.add("LEAVE_OVERUSED", "사용내역", "병가 %d일(11/16~)을 종류 '연가'로 기록 → 연가 사용 %d일 > 부여 %d일, 잔여 %d"
          % (sick_n, used_now + sick_n, granted[over_e["empNo"]], granted[over_e["empNo"]] - used_now - sick_n),
          ["CROSS_FIELD_RULE", "RECOMPUTE_CHECK"], empNo=over_e["empNo"], rowNo=sick["row"], cells=["F%d" % sick["row"]],
          values={"종류": {"actual": "연가", "expected": "병가"}},
          affected=[{"sheet": "사용내역", "rowNo": sick["row"]}, {"sheet": "연가현황", "rowNo": r}])
    A.add("USED_ON_HOLIDAY", "사용내역", "연가 사용일이 공휴일(10/9 한글날)", ["HOLIDAY_CALENDAR"], empNo=hol_u["emp"]["empNo"],
          rowNo=hol_u["row"], cells=["C%d" % hol_u["row"], "D%d" % hol_u["row"]],
          values={"시작일": {"actual": "2026-10-09", "expected": hol_u["orig"].isoformat(), "originalWorkday": hol_u["orig"].isoformat()},
                  "종료일": {"actual": "2026-10-09", "expected": hol_u["orig"].isoformat()}})
    A.add("DATE_ORDER", "사용내역", "종료일 < 시작일 (2일 연가의 시작·종료를 바꿔 입력)", ["CROSS_FIELD_RULE"], empNo=rev_u["emp"]["empNo"],
          rowNo=rev_u["row"], cells=["C%d" % rev_u["row"], "D%d" % rev_u["row"]])
    less = sorted(row_of[x] for x, t in truth.items() if t["granted"] < t["statutory"])
    more = sorted(row_of[x] for x, t in truth.items() if t["granted"] > t["statutory"])
    # 재직 년수별 공무원 표 vs 근로기준법 제60조 (1년 이상만; 1년 미만은 입사 월에 따라 다름)
    cmp_by_year = {}
    for n in range(1, 41):
        g = next(d for yrs, d in reversed(LEAVE_TABLE) if n >= yrs)
        st = min(15 + (n - 1) // 2, 25)
        cmp_by_year.setdefault((g > st) - (g < st), []).append(n)

    def spans(ns):
        out, a = [], ns[0]
        for p, q in zip(ns, ns[1:] + [None]):
            if q != p + 1:
                out.append("%d년" % a if a == p else ("%d~%d년" % (a, p) if p < 40 else "%d년 이상" % a))
                a = q
        return "·".join(out)
    leave_note = "재직 %s은 법보다 적고, %s은 많고, %s은 같음. 1년 미만은 공무원 표 11일 vs 법 월 1일(입사 월에 따라 많음). "                  "근로기준법 적용 기관이면 적은 쪽은 위반" % (spans(cmp_by_year[-1]), spans(cmp_by_year[1]), spans(cmp_by_year[0]))
    return {
        "file": path.name, "kind": "LEAVE", "closestEntity": "LEAVE_BALANCE", "supported": False,
        "sheetOrder": ["연가현황", "사용내역", "기준"],
        "sheets": {"연가현황": {"role": "leaveSummary", "headerRows": [1], "firstDataRow": 2, "lastDataRow": 1 + len(members), "rowOf": row_of},
                   "사용내역": {"role": "leaveUsage", "headerRows": [1], "firstDataRow": 2, "lastDataRow": last_use},
                   "기준": {"role": "settings", "values": dict(LEAVE_TABLE)}},
        "expectedMapping": {"leaveSummary": dict(zip(sum_heads, ["empNo", "name", "dept", "hireDate", "serviceYears", "granted", "used", "remain"])),
                            "leaveUsage": dict(zip(heads, ["empNo", "name", "startDate", "endDate", "days", "leaveType"]))},
        "rules": {"granted": "공무원 연가일수표 VLOOKUP(재직 년수): 1년 미만 11, 1년 12, 2년 14, 3년 15, 4년 17, 5년 20, 6년 이상 21",
                  "used": "SUMIFS(일수, 직원번호, 종류='연가') — 병가·공가·특별휴가는 빼고 셈", "remain": "연가일수 − 사용일수",
                  "baseDate": "DATE(2026,12,31) (회계연도 기준)"},
        "legalDeviations": [{"rule": "annualLeaveDays", "company": "공무원 연가일수표", "statutory": "근로기준법 제60조",
                             "rowsLessThanStatutory": less, "rowsMoreThanStatutory": more,
                             "note": leave_note}],
        "employees": truth, "accidents": A.items,
        "valueNoise": [{"type": "EMPNO_TEXT_LEADING_ZERO", "detail": "직원번호 문자 '050012'"}],
    }


# ================================================================ 직원명부·조직

ROSTER_COLS = [("empNo", "직원번호"), ("name", "성명"), ("_rrn", "주민등록번호"), ("dept", "소속"), ("grade", "직급"), ("_step", "호봉"),
               ("position", "직위"), ("duty", "보직"), ("hireDate", "임용일"), ("birthDate", "생년월일"), ("empType", "고용구분"),
               ("mobile", "휴대전화"), ("email", "이메일"), ("status", "재직구분"), ("resignDate", "퇴직일")]


def write_roster(cdir, emps, seed):
    rng = B.rng_for(CID, "roster", seed)
    errs = B.RosterErrors()
    rows = [{**{f: e.get(f) for f, _ in ROSTER_COLS}, "_rrn": B.masked_rrn(e["birthDate"], e["sex"]),
             "_step": step_at(e, "2026.12"), "_e": e} for e in sorted(emps, key=lambda e: e["empNo"])]
    idx = list(range(len(rows)))
    rng.shuffle(idx)
    take = lambda k, cond=lambda r: True: [idx.pop(idx.index(i)) for i in [j for j in idx if cond(rows[j])][:k]]  # noqa: E731
    for i in take(1, lambda r: r["status"] == "재직"):
        rows[i]["hireDate"] = None
        errs.add(i, "hireDate", "REQUIRED", "임용일 빈 칸")
    for i in take(1):
        rows[i]["email"] = rows[i]["email"].replace("@", "@@")
        errs.add(i, "email", "INVALID_EMAIL", "이메일 '@@'")
    i, j = take(2)
    rows[j]["empNo"] = rows[i]["empNo"]
    errs.add(i, "empNo", "DUPLICATE_KEY", "직원번호 중복 (다른 사람)")
    errs.add(j, "empNo", "DUPLICATE_KEY", "직원번호 중복 — 이 행의 실제 직원번호는 %s" % rows[j]["_e"]["empNo"])
    for i in take(1, lambda r: r["status"] == "퇴직" and r["hireDate"]):
        rows[i]["resignDate"] = rows[i]["hireDate"] - dt.timedelta(days=rng.randint(30, 300))
        errs.add(i, "resignDate", "DATE_ORDER", "퇴직일 < 임용일")
    for i in take(1, lambda r: r["position"] == "차장"):
        rows[i]["position"] = "치장"
        errs.add(i, "position", "POSITION_TYPO", "직위 오타 '치장'", detectable=False)
    for i in take(2):
        e = rows[i]["_e"]
        wrong = e["birthDate"].replace(year=e["birthDate"].year + rng.choice([-1, 1]))
        rows[i]["_rrn"] = B.masked_rrn(wrong, e["sex"])
        errs.add(i, "birthDate", "RRN_BIRTH_MISMATCH", "주민등록번호 앞 6자리(%s)와 생년월일(%s)이 다름 (마스킹 값만 있음)"
                 % (rows[i]["_rrn"][:6], e["birthDate"].isoformat()), detectable=False)
    for i in take(1, lambda r: r["status"] == "재직"):
        rows[i]["_step"] = 0
        errs.add(i, "_step", "STEP_OUT_OF_RANGE", "호봉 0 (봉급표는 1~32호봉)", detectable=False)
    for r in rows:
        if isinstance(r["hireDate"], dt.date):
            r["hireDate"] = B.fmt_date(r["hireDate"], B.date_style(rng, "cell", ["korean", "slash"], 0.15))
    B.date_format_errors(rows, ("hireDate", "birthDate", "resignDate"), errs)
    head_rows, first = B.write_roster_xlsx(cdir / "직원명부.xlsx", "직원명부", ROSTER_COLS, rows,
                                           widths={"name": 9, "_rrn": 16, "dept": 11, "hireDate": 16, "email": 26, "mobile": 14})
    mapping = {h: (None if f.startswith("_") else f) for f, h in ROSTER_COLS}
    mapping["호봉"] = "gradeStep"  # 이관 사원 스키마의 호봉 필드 (직급과 합쳐 '2급 27호봉'으로 적재)
    errors = errs.rows(first)
    for x in errors:  # 호봉 열은 이관 사원 스키마의 gradeStep
        if x["field"] == "_step":
            x["field"] = "gradeStep"
    return {"mapping": mapping, "errors": errors, "rows": len(rows),
            "layout": {"sheet": "직원명부", "headerRows": head_rows, "firstDataRow": first,
                       "notes": ["주민등록번호는 마스킹 값만 ('880312-1******')", "임용일 15%는 '2015년 3월 2일'·'2015/03/02' 문자",
                                 "직원번호는 앞자리 0이 있는 문자", "고용구분 '무기계약직' 있음 (오류 아님)"]}}


def write_org(cdir):
    org = [{"deptCode": "A000", "name": "이사장", "parentDeptCode": None}]
    hqs = []
    for hq, team in TEAMS:
        if hq not in hqs:
            hqs.append(hq)
            org.append({"deptCode": "A%d00" % len(hqs), "name": hq, "parentDeptCode": "A000"})
        n = sum(1 for o in org if o["parentDeptCode"] == "A%d00" % len(hqs))
        org.append({"deptCode": "A%d%02d" % (hqs.index(hq) + 1, (n + 1) * 10), "name": team, "parentDeptCode": "A%d00" % (hqs.index(hq) + 1)})
    cols = [("deptCode", "부서코드"), ("name", "부서명"), ("parentDeptCode", "상위부서코드")]
    B.write_org(cdir / "조직.csv", org, cols, "utf-8-sig")
    return org, cols


def build(df, used, out_dir, seed):
    company = {"id": CID, "name": NAME, "industry": "공공",
               "profile": "250명 공공기관형, 호봉제 봉급표(INDEX/MATCH), 작년 봉급표가 같은 파일에, 공무원 연가일수표, 마스킹된 주민번호 열"}
    cdir = out_dir / CID
    cdir.mkdir(parents=True, exist_ok=True)
    emps = people(df, used, seed)
    rng = B.rng_for(CID, "plan", seed)
    act = lambda ym: [e for e in emps if e["status"] == "재직" and e["hireDate"] < dt.date(2026, 1, 1)]  # noqa: E731
    nov_step = [e for e in act("2026.11") if e["hireDate"].month == 11 and step_at(e, "2026.11") < MAX_STEP]
    step_es = rng.sample(nov_step, 2)
    rows = {("2026.11", e["empNo"]): {"type": "STEP_NOT_ADVANCED", "inputs": {"step": step_at(e, "2026.10")}} for e in step_es}
    mgr = rng.choice([e for e in act("2026.11") if e["grade"] == "4급" and e not in step_es])
    rows[("2026.11", mgr["empNo"])] = {"type": "ALLOWANCE_RULE_CONFLICT", "hard": ("otPay",), "inputs": {"otH": 12}}
    rows[("2026.11", mgr["empNo"])]["inputs"]["_otOverride"] = ROUNDDOWN(ROUNDDOWN(TABLE[2026]["4급"][step_at(mgr, "2026.11") - 1] * 0.55 / 209 * 1.5, 0) * 12, -1)
    na = rng.choice([e for e in act("2026.11") if e["grade"] == "5급" and e not in step_es])
    rows[("2026.11", na["empNo"])] = {"type": "LOOKUP_NA", "inputs": {"grade": "5 급"}}
    pay_key = write_payroll(cdir / PAY_FILE, emps, {"staleTable": "2026.10", "rows": rows})
    leave_key = write_leave(cdir / LEAVE_FILE, emps, B.rng_for(CID, "leave", seed))
    roster = write_roster(cdir, emps, seed)
    org, org_cols = write_org(cdir)
    return B.answer_key(company, "직원명부.xlsx", "조직.csv", roster["mapping"], org_cols, roster["errors"],
                        {"employees": len(emps), "rosterRows": roster["rows"], "orgs": len(org)},
                        {PAY_FILE: pay_key, LEAVE_FILE: leave_key}, {"rosterLayout": roster["layout"]})
