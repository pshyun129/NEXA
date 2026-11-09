"""
더러건설 — 상용직 30명 + 건설 일용직 110명, 현장 3곳.

파일: 직원명부.xlsx(상용·일용이 한 시트, 구분 열, 마스킹 주민번호, 일용 사번 'D26-0012', 일용 입사일 = 최초 출역일),
     조직.csv(CP949, 본사 팀 + 현장),
     출역부_2026-12.xlsx(현장마다 시트 '일용노무비 지급명세서', 2줄 병합 헤더, 1~31일 칸에 공수 1·0.5·1.5, 기준 시트),
     근로내용확인신고_2026-12.csv(CP949, 근로복지공단 신고 양식 흉내: 헤더 위 사업장 정보 3줄, 사번 없이 성명·주민번호, 1~31일 '○').
노무비 규칙: 노무비 = 총공수 × 일당, 일용 소득세 = (노무비 − 15만 × 출역일수) × 2.7%(= 6% × (1−55%)) 10원 미만 버림, 1,000원 미만 소액부징수,
           지방소득세 10%, 고용보험 0.9%, 월 8일 이상 출역자만 국민연금·건강보험(가정: 건설일용 가입 기준을 월 8일로 둠).
"""
import datetime as dt

from xlsxwriter.utility import xl_col_to_name

from hrfiles.common import Accidents
from hrfiles.facts import HOLIDAYS_2026
from hrfiles.xl import ROUNDDOWN, Book

from . import base as B

CID, NAME = "deoreocon", "더러건설"
GONG_FILE, REPORT_FILE = "출역부_2026-12.xlsx", "근로내용확인신고_2026-12.csv"
DAYS = B.days_of(2026, 12)
# (현장코드, 공사명, 시트 이름, 일용 인원, 사업장관리번호)
SITES = [("S2401", "가람지구 공동주택 신축공사", "가람지구", 50, "214-81-55501-1"),
         ("S2502", "나루 물류센터 신축공사", "나루물류", 38, "214-81-55502-2"),
         ("S2603", "다솜교 보수보강공사", "다솜교", 22, "214-81-55503-3")]
TRADES = {"형틀목공": (250_000, "7192"), "철근공": (240_000, "7193"), "콘크리트공": (220_000, "7194"), "조적공": (230_000, "7195"),
          "미장공": (230_000, "7196"), "용접공": (260_000, "7431"), "보통인부": (170_000, "9101"), "신호수": (160_000, "9109")}
SITE_TRADES = {"S2401": ["형틀목공", "형틀목공", "철근공", "철근공", "콘크리트공", "조적공", "미장공", "보통인부", "보통인부", "신호수"],
               "S2502": ["형틀목공", "철근공", "콘크리트공", "용접공", "용접공", "보통인부", "보통인부", "신호수"],
               "S2603": ["용접공", "콘크리트공", "보통인부", "보통인부", "신호수"]}
HQ = [("경영지원팀", 5), ("공무팀", 4), ("안전환경팀", 3)]
SITE_STAFF = ["현장소장", "공무", "안전관리자", "공사", "공사", "공사"]


def people(df, used, seed):
    rng = B.rng_for(CID, "people", seed)
    n_reg = sum(n for _, n in HQ) + 6 * len(SITES)
    n_day = sum(s[3] for s in SITES)
    w = lambda o: 4.0 if B.has(o, "건설", "목공", "철근", "콘크리트", "미장", "조적", "용접", "인부", "건축", "토목", "현장", "설비") else 1.0  # noqa: E731
    picked = B.pick(df, used, n_reg + n_day, rng, CID, w)
    picked.sort(key=lambda p: p["uuid"])
    rng.shuffle(picked)
    regs, days = sorted(picked[:n_reg], key=lambda p: -p["age"]), picked[n_reg:]
    emps = []
    # 상용직: 나이 많은 순으로 현장소장·팀장
    slots = [(s[0], s[2], role) for s in SITES for role in SITE_STAFF] + [(None, team, None) for team, n in HQ for _ in range(n)]
    heads = [x for x in slots if x[2] == "현장소장"]
    rest = [x for x in slots if x[2] != "현장소장"]
    rng.shuffle(rest)
    for p, (site, dept, role) in zip(regs, heads + rest):
        age = int(p["age"])
        birth = B.birth_of(age, rng)
        hire = min(dt.date(birth.year + rng.randint(25, max(25, min(age, 40))), rng.randint(1, 12), 1), dt.date(2026, 6, 1))
        pos = "부장" if role == "현장소장" else "과장" if age >= 42 else "대리" if age >= 33 else "사원"
        emps.append({"kind": "상용", "name": p["name"], "birthDate": birth, "sex": p["sex"], "site": site,
                     "dept": dept if site is None else next(s[1] for s in SITES if s[0] == site), "jobKind": role or "사무",
                     "position": pos, "duty": "현장소장" if role == "현장소장" else None, "hireDate": hire, "resignDate": None,
                     "empType": "정규직" if role != "안전관리자" else "계약직", "mobile": B.phone(rng)})
    for dept, _ in HQ:
        members = sorted([e for e in emps if e["dept"] == dept], key=lambda e: e["birthDate"])
        members[0]["duty"] = "팀장"
    k = 0
    for code, title, _sheet, n, _mgmt in SITES:
        for p in days[k:k + n]:
            age = int(p["age"])
            trade = rng.choice(SITE_TRADES[code])
            first = dt.date(2026, rng.randint(3, 11), rng.randint(1, 28)) if rng.random() < 0.8 else dt.date(2026, 12, rng.randint(1, 10))
            emps.append({"kind": "일용", "name": p["name"], "birthDate": B.birth_of(age, rng), "sex": p["sex"], "site": code, "dept": title,
                         "jobKind": trade, "position": None, "duty": None, "hireDate": first, "resignDate": None, "empType": "일용직",
                         "mobile": B.phone(rng), "wage": TRADES[trade][0] + rng.choice([0, 0, 0, 10_000, -10_000]),
                         "core": rng.random() < 0.55})
        k += n
    emps.sort(key=lambda e: (e["kind"] != "상용", e["hireDate"], e["name"]))
    n_r = n_d = 0
    for e in emps:
        if e["kind"] == "상용":
            n_r += 1
            e["empNo"] = "DR%04d" % n_r
        else:
            n_d += 1
            e["empNo"] = "D26-%04d" % n_d
        e["rrn"] = B.masked_rrn(e["birthDate"], e["sex"])
    return emps


# ================================================================ 출역 계획

def plan_days(emps, rng):
    """사번 → {날짜: 공수}"""
    plan = {}
    for e in emps:
        if e["kind"] != "일용":
            continue
        out = {}
        p = 0.92 if e["core"] else 0.35
        for d in DAYS:
            if d < e["hireDate"] or d.weekday() == 6 or d in HOLIDAYS_2026:
                continue
            if d.weekday() == 5 and rng.random() < 0.5:
                continue
            if rng.random() < p:
                out[d] = rng.choices([1, 0.5, 1.5], [86, 8, 6])[0]
        if not out:  # 12월에 한 번은 출역
            d = rng.choice([d for d in DAYS if d.weekday() < 5 and d >= e["hireDate"] and d not in HOLIDAYS_2026])
            out[d] = 1
        plan[e["empNo"]] = out
    return plan


# ================================================================ 출역부 (노무비 지급명세서)

SETS = [("deduct", "일용 근로소득공제(일)", 150_000, "won"), ("taxRate", "일용 소득세 실효세율 6%×(1−55%)", 0.027, "pct"),
        ("minTax", "소액부징수 기준", 1_000, "won"), ("ei", "고용보험(일용)", B.RATES_2026["ei"], "pct"),
        ("np", "국민연금", B.RATES_2026["np"], "pct"), ("hi", "건강보험", B.RATES_2026["hi"], "pct"),
        ("ltc", "장기요양(건보료 대비)", B.RATES_2026["ltc"], "pct"), ("npFloor", "국민연금 하한", B.NP_FLOOR, "won"),
        ("npCap", "국민연금 상한", B.NP_CAP, "won"), ("days", "국민연금·건강보험 가입 월 출역일수", 8, None)]
FIRST_DAY_COL = 4  # E열 = 1일
DAYC = {k: xl_col_to_name(FIRST_DAY_COL + k) for k in range(31)}
TAIL = [("days", "출역일수"), ("gongsu", "총공수"), ("wage", "일당"), ("pay", "노무비"), ("tax", "소득세"), ("ltax", "지방소득세"),
        ("ei", "고용보험"), ("np", "국민연금"), ("hi", "건강보험"), ("ltc", "장기요양"), ("ded", "공제계"), ("net", "차감지급액"), ("memo", "비고")]
TL = {k: xl_col_to_name(FIRST_DAY_COL + 31 + i) for i, (k, _) in enumerate(TAIL)}
HEAD = [("no", "번호"), ("name", "성명"), ("empNo", "사번"), ("trade", "직종")]


def calc(days, gongsu, wage, S, deduct=None, zero_ins=False):
    pay = ROUNDDOWN(gongsu * wage, 0)
    base = max(0, pay - (deduct or S["deduct"]) * days)
    t = ROUNDDOWN(base * S["taxRate"], -1)
    tax = 0 if t < S["minTax"] else t
    v = {"days": days, "gongsu": gongsu, "wage": wage, "pay": pay, "tax": tax, "ltax": ROUNDDOWN(tax * 0.1, -1),
         "ei": ROUNDDOWN(pay * S["ei"], -1)}
    ins = days >= S["days"] and not zero_ins
    v["np"] = ROUNDDOWN(min(max(ROUNDDOWN(pay, -3), S["npFloor"]), S["npCap"]) * S["np"], -1) if ins else 0
    v["hi"] = ROUNDDOWN(pay * S["hi"], -1) if ins else 0
    v["ltc"] = ROUNDDOWN(v["hi"] * S["ltc"], -1)
    v["ded"] = v["tax"] + v["ltax"] + v["ei"] + v["np"] + v["hi"] + v["ltc"]
    v["net"] = pay - v["ded"]
    v["_fb"] = [k for k, x in (("tax", base * S["taxRate"]), ("ei", pay * S["ei"]), ("hi", pay * S["hi"] if ins else 0),
                                ("np", min(max(ROUNDDOWN(pay, -3), S["npFloor"]), S["npCap"]) * S["np"] if ins else 0),
                                ("ltc", v["hi"] * S["ltc"]), ("ltax", tax * 0.1)) if B.float_boundary(x)]
    return v


def formulas(r, st, deduct_literal=None):
    R = lambda k: "%s%d" % (TL[k], r)  # noqa: E731
    span = "%s%d:%s%d" % (DAYC[0], r, DAYC[30], r)
    ded = str(deduct_literal) if deduct_literal else st.ref("deduct")
    t = "ROUNDDOWN(MAX(0,%s-%s*%s)*%s,-1)" % (R("pay"), ded, R("days"), st.ref("taxRate"))
    ins = "%s>=%s" % (R("days"), st.ref("days"))
    return {"days": "=COUNT(%s)" % span, "gongsu": "=SUM(%s)" % span, "pay": "=ROUNDDOWN(%s*%s,0)" % (R("gongsu"), R("wage")),
            "tax": "=IF(%s<%s,0,%s)" % (t, st.ref("minTax"), t), "ltax": "=ROUNDDOWN(%s*0.1,-1)" % R("tax"),
            "ei": "=ROUNDDOWN(%s*%s,-1)" % (R("pay"), st.ref("ei")),
            "np": "=IF(%s,ROUNDDOWN(MIN(MAX(ROUNDDOWN(%s,-3),%s),%s)*%s,-1),0)" % (ins, R("pay"), st.ref("npFloor"), st.ref("npCap"), st.ref("np")),
            "hi": "=IF(%s,ROUNDDOWN(%s*%s,-1),0)" % (ins, R("pay"), st.ref("hi")),
            "ltc": "=ROUNDDOWN(%s*%s,-1)" % (R("hi"), st.ref("ltc")),
            "ded": "=SUM(%s:%s)" % (R("tax"), R("ltc")), "net": "=%s-%s" % (R("pay"), R("ded"))}


def write_gongsu(path, emps, plan, acc):
    A = Accidents("DERO-GONG", path.name)
    book = Book(path)
    sheets = {s[0]: book.sheet(s[2]) for s in SITES}
    st = B.Settings2(book, "기준", SETS)
    S = st.val
    meta, vals, shown_plan, info = {}, {}, {}, {}
    by_no = {e["empNo"]: e for e in emps}
    for code, title, sname, _n, mgmt in SITES:
        sh = sheets[code]
        boss = next(e for e in emps if e["site"] == code and e["jobKind"] == "현장소장")
        sh.put("A", 1, "%s  2026년 12월 일용노무비 지급명세서" % title, "title")
        sh.put("A", 2, "현장코드 %s / 고용·산재 사업장관리번호 %s / 현장대리인 %s" % (code, mgmt, boss["name"]))
        sh.merge("A3:D3", "인적사항")
        sh.merge("%s3:%s3" % (DAYC[0], DAYC[30]), "출역 (공수)")
        sh.merge("%s3:%s3" % (TL["days"], TL["pay"]), "노무비")
        sh.merge("%s3:%s3" % (TL["tax"], TL["ded"]), "공제내역")
        sh.put(TL["net"], 3, "", "head")
        sh.put(TL["memo"], 3, "", "head")
        for i, (k, h) in enumerate(HEAD):
            sh.put(xl_col_to_name(i), 4, h, "head")
        for k in range(31):
            sh.put(DAYC[k], 4, k + 1, "head")  # 날짜 머리글이 숫자 1~31
        for k, h in TAIL:
            sh.put(TL[k], 4, h, "head")
        rows = [e for e in emps if e["kind"] == "일용" and e["site"] == code] + [x["emp"] for x in acc["twoSites"] if x["site"] == code]
        r, row_of, fb = 5, {}, []
        for n, e in enumerate(rows, 1):
            two = next((x for x in acc["twoSites"] if x["emp"] is e and x["site"] == code), None)
            days = {d: 1 for d in two["days"]} if two else dict(plan[e["empNo"]])
            shown = dict(days)
            text_day = acc["text"][1] if acc["text"][0] == (code, e["empNo"]) else None
            big_day = acc["big"][1] if acc["big"][0] == (code, e["empNo"]) else None
            if big_day:
                shown[big_day] = 10
            sh.put("A", r, n)
            sh.put("B", r, e["name"])
            sh.put("C", r, e["empNo"], "text")
            sh.put("D", r, e["jobKind"])
            for k, d in enumerate(DAYS):
                if d in shown:
                    sh.put(DAYC[k], r, "반" if d == text_day else shown[d], None)
            num = {d: g for d, g in shown.items() if d != text_day}
            zero_ins = acc["zeroIns"] == (code, e["empNo"])
            deduct = 100_000 if code == acc["staleSite"] else None
            actual = calc(len(num), sum(num.values()), e["wage"], S, deduct=deduct, zero_ins=zero_ins)
            ideal = calc(len(days), sum(days.values()), e["wage"], S)
            actual["memo"] = "8일 미만" if zero_ins else ("타 현장 지원" if two else None)
            fs = formulas(r, st, deduct_literal=deduct)
            for k, _ in TAIL:
                if fs.get(k) and not (zero_ins and k in ("np", "hi")):
                    sh.formula(TL[k], r, fs[k], actual[k], B.money(actual[k]) or ("num" if k == "gongsu" else None))
                else:
                    sh.put(TL[k], r, actual[k], B.money(actual[k]))
            key = e["empNo"] + ("@" + code if two else "")
            fb += ["%s%d" % (TL[k], r) for k in actual["_fb"]]
            row_of[e["empNo"]] = r  # 시트 안에서는 사번이 한 번씩
            vals[(code, key)] = (actual, ideal)
            info[(code, key)] = {"row": r, "days": days, "num": num, "wage": e["wage"], "deduct": deduct, "zeroIns": zero_ins,
                                 "big": big_day, "text": text_day, "empNo": e["empNo"]}
            shown_plan[(code, e["empNo"])] = days
            r += 1
        last = r - 1
        sh.put("B", r, "계", "totalLabel")
        for k, _ in TAIL[:-1]:
            if k == "wage":
                continue
            tot = sum(vals[ck][0][k] for ck in vals if ck[0] == code)
            sh.formula(TL[k], r, "=SUM(%s5:%s%d)" % (TL[k], TL[k], last), round(tot, 6) if isinstance(tot, float) else tot,
                       "total" if k not in ("days", "gongsu") else "num")
        sh.put("B", r + 2, "위 금액을 정히 영수함.   현장대리인 %s (인)" % boss["name"])
        sh.ws.freeze_panes(4, 4)
        sh.widths({"A": 5, "B": 8, "C": 10, "D": 9})
        for k in range(31):
            sh.ws.set_column(FIRST_DAY_COL + k, FIRST_DAY_COL + k, 4)
        meta[sname] = {"role": "dailyLaborWide", "siteCode": code, "headerRows": [3, 4], "firstDataRow": 5, "lastDataRow": last,
                       "totalRow": r, "titleRows": [1, 2], "signRows": [r + 2], "dayColumns": "%s:%s (1~31일, 머리글 숫자)" % (DAYC[0], DAYC[30]),
                       "rowOf": row_of, "floatBoundaryCells": fb}
    book.close()

    name_of = {s[0]: s[2] for s in SITES}
    head_of = {k: h for k, h in TAIL}
    NUM = [k for k, _ in TAIL if k != "memo"]

    def fixed(ck, formula=False, text=False, big=False, zero=False):
        """그 행에서 고른 사고만 고쳤을 때의 값 (다른 사고는 그대로)"""
        x = info[ck]
        num = dict(x["num"])
        if text and x["text"]:
            num[x["text"]] = x["days"][x["text"]]
        if big and x["big"]:
            num[x["big"]] = x["days"][x["big"]]
        return calc(len(num), sum(num.values()), x["wage"], S, deduct=None if formula else x["deduct"],
                    zero_ins=x["zeroIns"] and not zero)

    def diff(ck, exp):
        a = vals[ck][0]
        return {head_of[k]: {"actual": a[k], "expected": exp[k]} for k in NUM if a[k] != exp[k]}

    def cells_of(r, vs):
        return ["%s%d" % (TL[k], r) for k, h in TAIL if h in vs]

    def total_entry(sname, code, fixes):
        """계 행: 사고 행을 고친 값으로 바꿨을 때의 합계와 비교. fixes: {(code, key): 고친 값}"""
        tr = meta[sname]["totalRow"]
        out = {}
        for k in NUM:
            if k == "wage":
                continue
            a = sum(vals[ck][0][k] for ck in vals if ck[0] == code)
            b = sum((fixes[ck] if ck in fixes else vals[ck][0])[k] for ck in vals if ck[0] == code)
            a, b = round(a, 6), round(b, 6)
            if a != b:
                out[head_of[k]] = {"actual": a, "expected": b}
        return {"sheet": sname, "rowNo": tr, "cells": cells_of(tr, out), "values": out}

    who = {}  # 사고 id → ([(code, key)], 신고 파일에서 달라지는 항목) — 신고 파일로 번진 값은 build에서 붙인다
    # ---- 정답지
    code = acc["staleSite"]
    sname = name_of[code]
    fx = {ck: fixed(ck, formula=True) for ck in vals if ck[0] == code}
    diff_keys = sorted((ck for ck in fx if vals[ck][0]["tax"] != fx[ck]["tax"]), key=lambda ck: info[ck]["row"])
    rows = [info[ck]["row"] for ck in diff_keys]
    text_ck = acc["text"][0]
    two_ck = next((ck for ck in diff_keys if "@" in ck[1]), None)
    ovl = []
    if text_ck in fx:
        r = info[text_ck]["row"]
        ovl.append({"with": "DERO-GONG-04", "cells": ["%s%d" % (TL[k], r) for k in ("tax", "ltax", "ded", "net")],
                    "note": "%s 행은 '반' 입력(DERO-GONG-04)도 있음. 이 수식만 고치면 소득세 %s원, 둘 다 고치면 %s원"
                            % (info[text_ck]["empNo"], format(fx[text_ck]["tax"], ","), format(vals[text_ck][1]["tax"], ",")),
                    "trueValue": {"소득세": vals[text_ck][1]["tax"], "지방소득세": vals[text_ck][1]["ltax"],
                                  "공제계": vals[text_ck][1]["ded"], "차감지급액": vals[text_ck][1]["net"]}})
    if two_ck:
        r = info[two_ck]["row"]
        ovl.append({"with": "DERO-GONG-02", "cells": ["%s%d" % (TL[k], r) for k in ("tax", "ltax", "ded", "net")],
                    "note": "%s 행은 두 현장 이중 출역(DERO-GONG-02)으로 생긴 행 — 그 사고를 고치면 행 자체가 없어짐" % info[two_ck]["empNo"],
                    "trueValue": None})
    A.add("STALE_TAX_THRESHOLD", sname, "%s 시트 소득세 수식에 일용 근로소득공제가 기준 시트 참조 대신 숫자 100000 (2018년 이전 공제액, 지금은 15만원) "
          "→ 소득세 과다 공제 %d명. 지방소득세·공제계·차감지급액과 계 행도 같이 틀림 (expected는 이 수식만 고친 값)" % (sname, len(diff_keys)),
          ["FORMULA_READ", "RATE_TABLE_BY_PERIOD", "LEGAL_RULE_COMPARE"], legal=True,
          rows=rows, empNos=[info[ck]["empNo"] for ck in diff_keys],
          cells=(["%s%d:%s%d" % (TL[k], rows[0], TL[k], rows[-1]) for k in ("tax", "ltax", "ded", "net")]
                 if rows == list(range(rows[0], rows[-1] + 1)) and all(len(diff(ck, fx[ck])) == 4 for ck in diff_keys)
                 else [c for ck in diff_keys for c in cells_of(info[ck]["row"], diff(ck, fx[ck]))]),
          formula={"actual": "MAX(0,노무비-100000*출역일수)", "expected": "MAX(0,노무비-기준!$B$2*출역일수)"},
          values={info[ck]["empNo"]: diff(ck, fx[ck]) for ck in diff_keys},
          affected=[{"sheet": sname, "rowNo": r} for r in rows] + [total_entry(sname, code, fx)], overlaps=ovl)
    who[A.items[-1]["id"]] = (diff_keys, {"tax", "ltax"})
    two = acc["twoSites"][0]
    e = two["emp"]
    home = name_of[e["site"]]
    other = name_of[two["site"]]
    r1, r2 = meta[home]["rowOf"][e["empNo"]], meta[other]["rowOf"][e["empNo"]]
    home_days = shown_plan[(e["site"], e["empNo"])]
    tk = (two["site"], e["empNo"] + "@" + two["site"])
    gone = {tk: {k: 0 for k in NUM}}
    A.add("SAME_DAY_TWO_SITES", other, "같은 일용근로자가 12/%s 같은 날 두 현장에 출역으로 기록: %s(본 현장) 공수 %s, %s 행 공수 %s → %s 행 노무비 %s원 이중 지급 의심"
          % ("·".join(str(d.day) for d in two["days"]), home, "·".join(str(home_days[d]) for d in two["days"]), other,
             "·".join("1" for _d in two["days"]), other, format(vals[tk][0]["pay"], ",")),
          ["MULTI_SHEET", "DUPLICATE_DETECT", "CROSS_FIELD_RULE"], empNo=e["empNo"], rowNo=r2,
          affected=[{"sheet": home, "rowNo": r1, "cells": ["%s%d" % (DAYC[d.day - 1], r1) for d in two["days"]]},
                    {"sheet": other, "rowNo": r2}, total_entry(other, two["site"], gone)],
          cells=["%s%d" % (DAYC[d.day - 1], r2) for d in two["days"]],
          overlaps=[{"with": "DERO-GONG-01", "cells": ["%s%d" % (TL[k], r2) for k in ("tax", "ltax", "ded", "net")],
                     "note": "이중 출역 행이 %s 시트에 있어 소득세 수식(DERO-GONG-01)도 틀림" % other, "trueValue": None}])
    who[A.items[-1]["id"]] = ([tk], set())
    (code, no), d = acc["big"]
    sname = name_of[code]
    ck = (code, no)
    r = meta[sname]["rowOf"][no]
    a, i = vals[ck]
    fv = fixed(ck, big=True)
    dv = diff(ck, fv)
    A.add("GONGSU_OUT_OF_RANGE", sname, "12/%d 공수 1.0을 10으로 입력 (소수점 빠짐) → 총공수 +9, 노무비 %s원 과다. 세금·4대보험·공제계·차감지급액과 계 행도 같이 틀림"
          % (d.day, format(a["pay"] - i["pay"], ",")),
          ["RANGE_CHECK", "RECOMPUTE_CHECK"], empNo=no, rowNo=r, cells=["%s%d" % (DAYC[d.day - 1], r)] + cells_of(r, dv),
          values={"%d" % d.day: {"actual": 10, "expected": 1}, **dv},
          affected=[{"sheet": sname, "rowNo": r}, total_entry(sname, code, {ck: fv})])
    who[A.items[-1]["id"]] = ([ck], {"pay", "tax", "ltax"})
    (code, no), d = acc["text"]
    sname = name_of[code]
    ck = (code, no)
    r = meta[sname]["rowOf"][no]
    a, i = vals[ck]
    fv = fixed(ck, text=True)
    dv = diff(ck, fv)
    A.add("TEXT_NUMBER", sname, "12/%d 반공수를 숫자 0.5 대신 글자 '반'으로 입력 → COUNT·SUM이 건너뛰어 출역일수 −1, 노무비 %s원 누락. "
          "4대보험·세금·공제계·차감지급액과 계 행도 같이 틀림 (expected는 이 칸만 고친 값, 소득세는 DERO-GONG-01과 겹침)"
          % (d.day, format(i["pay"] - a["pay"], ",")), ["VALUE_NORMALIZE", "RECOMPUTE_CHECK"], empNo=no, rowNo=r,
          cells=["%s%d" % (DAYC[d.day - 1], r)] + cells_of(r, dv), values={"%d" % d.day: {"actual": "반", "expected": 0.5}, **dv},
          affected=[{"sheet": sname, "rowNo": r}, total_entry(sname, code, {ck: fv})],
          overlaps=[{"with": "DERO-GONG-01", "cells": ["%s%d" % (TL[k], r) for k in ("tax", "ltax", "ded", "net")],
                     "note": "소득세는 공제 10만원 수식(DERO-GONG-01)도 틀림. 이 칸만 고치면 소득세 %s원, 둘 다 고치면 %s원"
                             % (format(fv["tax"], ","), format(i["tax"], ",")),
                     "trueValue": {"소득세": i["tax"], "지방소득세": i["ltax"], "공제계": i["ded"], "차감지급액": i["net"]}}])
    who[A.items[-1]["id"]] = ([ck], {"days", "pay", "tax", "ltax"})
    code, no = acc["zeroIns"]
    sname = name_of[code]
    ck = (code, no)
    r = meta[sname]["rowOf"][no]
    a, i = vals[ck]
    fv = fixed(ck, zero=True)
    dv = diff(ck, fv)
    A.add("INSURANCE_EXEMPTION_WRONG", sname, "출역 %d일(8일 이상)인데 국민연금·건강보험 칸을 수식 대신 0으로 입력 (비고 '8일 미만') → 장기요양(수식)도 0, "
          "공제계·차감지급액과 계 행도 틀림" % i["days"], ["HARDCODED_IN_FORMULA_COLUMN", "FORMULA_READ", "LEGAL_RULE_COMPARE"], legal=True,
          empNo=no, rowNo=r, cells=cells_of(r, dv), values=dv, affected=[{"sheet": sname, "rowNo": r}, total_entry(sname, code, {ck: fv})])
    who[A.items[-1]["id"]] = ([ck], set())
    used_sites = {acc["staleSite"], two["site"], acc["big"][0][0], acc["text"][0][0], acc["zeroIns"][0]} | {e["site"]}
    key = {"file": path.name, "kind": "DAILY_LABOR", "closestEntity": "PAY_RESULT_HISTORY", "supported": False,
           "sheetOrder": [s[2] for s in SITES] + ["기준"], "sheets": {**meta, "기준": st.meta()},
           "expectedMapping": {"dailyLaborWide": {**{h: k for k, h in HEAD}, **{h: k for k, h in TAIL}}},
           "rules": {"pay": "노무비 = 총공수 × 일당 (공수 1 = 하루, 0.5 = 반일, 1.5 = 야간 연장)",
                     "dailyIncomeTax": "ROUNDDOWN(MAX(0, 노무비 − 15만 × 출역일수) × 2.7%, −1), 1,000원 미만이면 0 (월 합산. 법은 일급 단위 — legalDeviations)",
                     "localTax": "소득세 × 10% 10원 미만 버림", "employmentInsurance": "노무비 × 0.9%",
                     "pensionHealth": "월 출역일수 8일 이상만 (가정: 건설일용 가입 기준)", "insuranceRounding": "floor10"},
           "legalDeviations": [{"rule": "dailyIncomeTaxBasis", "company": "월 노무비와 출역일수로 합산 계산",
                                "statutory": "일급 단위로 (일급 − 15만) × 2.7% (일급 15만 이하 날은 0)",
                                "assumption": True,
                                "direction": "행마다 다름. 반공수(일급 15만 미만) 날이 있으면 회사가 적게 떼고, 소액부징수(1,000원 미만)를 일 단위로 "
                                             "보면 일당이 낮은 행은 회사가 더 뗌. 소액부징수를 일 단위·지급 건 단위 중 어느 쪽으로 보느냐에 따라 "
                                             "차이의 방향과 크기가 달라져 확정하지 않음 (생성기 가정)"},
                               {"rule": "dailyIncomeTaxDeduction", "sheet": name_of[acc["staleSite"]], "company": "일 공제 10만원", "statutory": "15만원"}],
           "accidents": A.items, "cleanSheets": [s[2] for s in SITES if s[0] not in used_sites],
           "valueNoise": [{"type": "NUMERIC_DAY_HEADERS", "detail": "날짜 열 머리글이 숫자 1~31 (연·월은 제목에만)"},
                          {"type": "SIGN_ROW_BELOW_TOTAL", "detail": "계 행 아래 한 줄 띄고 '위 금액을 정히 영수함' 서명 행"},
                          {"type": "NO_EMPNO_FOR_SOME", "detail": "번호(1,2,3…)는 시트 안 순번이지 사번이 아님"}],
           "crossFile": {"report": {"file": REPORT_FILE, "detail": "현장·사람별 출역일 = 신고 '○' 날짜, 노무비 = 보수총액. 출역부 사고로 두 파일 값이 달라진 "
                                                                    "신고 행은 그 사고의 crossFileAffected에 있음 (신고 값이 맞고 출역부 값이 틀림)"}}}
    return key, meta, vals, shown_plan, who


# ================================================================ 근로내용확인신고 (CSV)

REP_COLS = [("mgmt", "사업장관리번호"), ("site", "현장명"), ("name", "성명"), ("rrn", "주민등록번호"), ("nation", "국적"), ("job", "직종부호")] + \
    [("d%d" % k, str(k)) for k in range(1, 32)] + \
    [("days", "근로일수"), ("avgH", "일평균근로시간"), ("pay", "보수총액"), ("wageTotal", "임금총액"), ("tax", "소득세"), ("ltax", "지방소득세")]


def write_report(path, emps, gong_vals, shown_plan, acc):
    A = Accidents("DERO-RPT", path.name)
    rows, row_meta = [], []
    for code, title, sname, _n, mgmt in SITES:
        site_emps = [e for e in emps if e["kind"] == "일용" and e["site"] == code] + [x["emp"] for x in acc["twoSites"] if x["site"] == code]
        for e in site_emps:
            two = any(x["emp"] is e and x["site"] == code for x in acc["twoSites"])
            k = e["empNo"] + ("@" + code if two else "")
            if acc["missing"] == (code, e["empNo"]):
                continue
            days = shown_plan[(code, e["empNo"])]
            report_days = sorted(days)
            if acc["fewer"][0] == (code, e["empNo"]):
                report_days = [d for d in report_days if d not in acc["fewer"][1]]
            a = gong_vals[(code, k)][1]  # 신고는 실제 출역 기준 (출역부 사고와 무관)
            row = {"mgmt": mgmt, "site": title, "name": e["name"], "rrn": e["rrn"], "nation": "100", "job": TRADES[e["jobKind"]][1],
                   "days": len(report_days), "avgH": 8, "pay": a["pay"], "wageTotal": a["pay"], "tax": a["tax"], "ltax": a["ltax"]}
            for d in report_days:
                row["d%d" % d.day] = "○"
            rows.append(row)
            row_meta.append((code, e["empNo"], two))
    pre = [["사업장명", "더러건설(주)"], ["신고연월", "2026-12"], ["작성", "경영지원팀 (근로복지공단 EDI 업로드용)"], []]
    first = B.write_csv(path, [h for _, h in REP_COLS], [[r.get(k) for k, _ in REP_COLS] for r in rows], "cp949", preamble=pre)
    sheet = path.stem
    row_of = {(c, no, two): first + n for n, (c, no, two) in enumerate(row_meta)}
    (code, no), ds = acc["fewer"]
    r = row_of[(code, no, False)]
    true_days = len(shown_plan[(code, no)])
    A.add("DAYS_MISMATCH_CROSS_FILE", sheet, "출역부에는 %d일 출역인데 신고는 %d일 (12/%s 출역 누락, 보수총액은 맞음) → 근로일수·일자 불일치"
          % (true_days, true_days - len(ds), "·".join(str(d.day) for d in sorted(ds))), ["CROSS_FILE_MATCH", "NAME_MATCH"], empNo=no, rowNo=r,
          cells=["%s%d" % (xl_col_to_name([k for k, _ in REP_COLS].index("days")), r)],
          values={"근로일수": {"actual": true_days - len(ds), "expected": true_days}})
    code, no = acc["missing"]
    e = next(x for x in emps if x["empNo"] == no)
    A.add("REPORT_MISSING", sheet, "출역부(%s)에 %d일 출역한 일용근로자가 신고 파일에 없음 (근로내용확인신고 누락)"
          % (next(s[2] for s in SITES if s[0] == code), len(shown_plan[(code, no)])), ["CROSS_FILE_MATCH", "NAME_MATCH"], empNo=no,
          name=e["name"], crossFileLocation={"file": GONG_FILE, "sheet": next(s[2] for s in SITES if s[0] == code)})
    two = acc["twoSites"][0]
    e = two["emp"]
    rr = sorted(v for (c, no2, _t), v in row_of.items() if no2 == e["empNo"])
    A.add("CARRIED_FROM_SOURCE", sheet, "출역부의 두 현장 이중 출역(DERO-GONG-02)을 그대로 신고 → 같은 사람이 같은 날 두 사업장에서 근로로 신고됨",
          ["DUPLICATE_DETECT", "CROSS_FIELD_RULE"], empNo=e["empNo"], rows=rr, source="DERO-GONG-02")
    report_rows = {(c, no2, t): (row_of[(c, no2, t)], rows[n]) for n, (c, no2, t) in enumerate(row_meta)}
    return report_rows, {"file": path.name, "kind": "DAILY_LABOR_REPORT", "closestEntity": None, "supported": False, "format": "csv", "encoding": "cp949",
            "sheetOrder": [sheet],
            "sheets": {sheet: {"role": "laborReport", "headerRows": [5], "firstDataRow": first, "lastDataRow": first + len(rows) - 1,
                               "titleRows": [1, 2, 3], "note": "CSV라 시트가 없음. 헤더 위 3줄은 사업장 정보, 4줄은 빈 줄"}},
            "expectedMapping": {"laborReport": {h: k for k, h in REP_COLS if not k.startswith("d") or k == "days"}},
            "rules": {"key": "사번 없음 — 성명 + 주민등록번호(마스킹)로 출역부와 맞춰야 함",
                      "days": "근로일수 = '○' 개수, 보수총액 = 출역부 노무비(과세), 국적 100 = 대한민국"},
            "accidents": A.items,
            "valueNoise": [{"type": "PREAMBLE_ROWS", "detail": "헤더 위에 '사업장명,…' 같은 2칸짜리 줄 3개 + 빈 줄"},
                           {"type": "DAY_MARKS", "detail": "1~31일 열에 '○' 표시"},
                           {"type": "MASKED_RRN", "detail": "주민등록번호 뒤 6자리 마스킹 (실제 신고 파일은 전체 번호)"}],
            "crossFile": {"gongsu": {"file": GONG_FILE, "detail": "현장·성명·주민번호로 출역부 행과 대응. 신고는 현장 출역확인서로 따로 작성해 "
                                                                  "출역부 사고(공제액 10만·공수 10·'반'·이중 출역 제외)의 영향을 받지 않음 → 그 행들은 두 파일 값이 다르고 원인은 출역부 쪽"}}}


# ================================================================ 직원명부·조직

ROSTER_COLS = [("_kind", "구분"), ("empNo", "사번"), ("name", "성명"), ("_rrn", "주민등록번호"), ("dept", "소속"), ("jobKind", "직종"),
               ("position", "직위"), ("duty", "직책"), ("empType", "고용형태"), ("hireDate", "입사일"), ("birthDate", "생년월일"),
               ("mobile", "연락처"), ("status", "재직상태"), ("resignDate", "퇴사일"), ("_wage", "일당"), ("_memo", "비고")]


def write_roster(cdir, emps, plan, seed):
    rng = B.rng_for(CID, "roster", seed)
    errs = B.RosterErrors()
    rows = []
    for e in emps:
        last = max(plan[e["empNo"]]) if e["kind"] == "일용" else None
        resign = last if e["kind"] == "일용" and last and last < dt.date(2026, 12, 20) else None
        rows.append({"_kind": e["kind"], "empNo": e["empNo"], "name": e["name"], "_rrn": e["rrn"], "dept": e["dept"], "jobKind": e["jobKind"],
                     "position": e["position"], "duty": e["duty"], "empType": e["empType"], "hireDate": e["hireDate"], "birthDate": e["birthDate"],
                     "mobile": e["mobile"], "status": "재직" if not resign else "퇴사", "resignDate": resign,
                     "_wage": e.get("wage"), "_memo": None, "_e": e})
    idx = list(range(len(rows)))
    rng.shuffle(idx)
    take = lambda k, cond=lambda r: True: [idx.pop(idx.index(i)) for i in [j for j in idx if cond(rows[j])][:k]]  # noqa: E731
    for i in take(2, lambda r: r["_kind"] == "일용"):
        rows[i]["hireDate"] = None
        errs.add(i, "hireDate", "REQUIRED", "입사일(최초 출역일) 빈 칸")
    i, j = take(2, lambda r: r["_kind"] == "일용")
    rows[j]["empNo"] = rows[i]["empNo"]
    errs.add(i, "empNo", "DUPLICATE_KEY", "사번 중복 (다른 사람)")
    errs.add(j, "empNo", "DUPLICATE_KEY", "사번 중복 — 이 행의 실제 사번은 %s" % rows[j]["_e"]["empNo"])
    for i in take(1, lambda r: r["_kind"] == "일용"):
        rows[i]["empType"] = "일당"
        errs.add(i, "empType", "INVALID_ENUM", "고용형태 '일당' (코드표: 정규직·계약직·일용직)")
    for i in take(1, lambda r: r["_kind"] == "일용" and r["resignDate"] and r["hireDate"]):
        rows[i]["resignDate"] = rows[i]["hireDate"] - dt.timedelta(days=rng.randint(3, 40))
        errs.add(i, "resignDate", "DATE_ORDER", "퇴사일 < 입사일")
    for i in take(1, lambda r: r["_kind"] == "상용" and r["position"] == "대리"):
        rows[i]["position"] = "대라"
        errs.add(i, "position", "POSITION_TYPO", "직위 오타 '대라'", detectable=False)
    for i in take(1, lambda r: r["_kind"] == "일용"):
        b = rows[i]["birthDate"]
        rows[i]["_rrn"] = B.masked_rrn(b.replace(year=b.year - 1), rows[i]["_e"]["sex"])
        errs.add(i, "주민등록번호", "RRN_BIRTH_MISMATCH", "마스킹 주민번호 앞 6자리 연도가 생년월일과 1년 다름", detectable=False)
    # 같은 사람을 다른 현장에서 일용 사번을 새로 받아 다시 등록 (현장마다 따로 등록하는 관행)
    src = take(1, lambda r: r["_kind"] == "일용" and r["status"] == "재직" and r["hireDate"])[0]
    dup = dict(rows[src])
    other = next(s for s in SITES if s[0] != rows[src]["_e"]["site"])
    dup.update(empNo="D26-%04d" % (900 + rng.randint(1, 99)), dept=other[1], hireDate=dt.date(2026, 4, 6), status="재직", resignDate=None,
               _memo="%s 현장 등록" % other[2])
    rows.append(dup)
    errs.add(src, "name", "DUPLICATE_SUSPECTED", "같은 사람(성명·생년월일·주민번호 같음)이 다른 현장 일용 사번 %s로도 등록" % dup["empNo"])
    errs.add(len(rows) - 1, "name", "DUPLICATE_SUSPECTED", "현장마다 새로 등록한 일용 사번 (원래 사번 %s)" % rows[src]["empNo"])
    for r in rows:
        for f in ("hireDate", "birthDate", "resignDate"):
            if r[f] is not None:
                r[f] = B.fmt_date(r[f], "cell" if r["_kind"] == "상용" else B.date_style(rng, "cell", ["slash", "compact"], 0.25))
    B.date_format_errors(rows, ("hireDate", "birthDate", "resignDate"), errs)
    head_rows, first = B.write_roster_xlsx(
        cdir / "직원명부.xlsx", "인력현황", ROSTER_COLS, rows,
        title_rows=["※ 일용직은 현장 투입 시 등록. 입사일 = 최초 출역일, 퇴사일 = 마지막 출역일 (12/20 이후 출역자는 빈 칸)"],
        widths={"name": 8, "dept": 24, "_rrn": 15, "mobile": 14})
    mapping = {h: (None if f.startswith("_") else f) for f, h in ROSTER_COLS}
    return {"mapping": mapping, "errors": errs.rows(first), "rows": len(rows),
            "layout": {"sheet": "인력현황", "titleRows": [1], "headerRows": head_rows, "firstDataRow": first,
                       "notes": ["상용(DR0001)·일용(D26-0001)이 한 시트, 구분 열", "일용은 직위·직책·이메일 없음 (빈 칸 정상)",
                                 "일용 입사일·퇴사일은 출역일 기준 — 퇴사 후 다시 출역해도 같은 사번 (정상)",
                                 "소속이 팀 이름(상용 본사) 또는 공사명(현장)", "주민등록번호는 마스킹 값"]}}


def write_org(cdir):
    org = [{"deptCode": "DR000", "name": "더러건설(주)", "parentDeptCode": None, "deptType": "회사"},
           {"deptCode": "DR100", "name": "본사", "parentDeptCode": "DR000", "deptType": "본부"},
           {"deptCode": "DR200", "name": "공사부", "parentDeptCode": "DR000", "deptType": "본부"}]
    org += [{"deptCode": "DR1%02d" % (n * 10), "name": t, "parentDeptCode": "DR100", "deptType": "팀"} for n, (t, _) in enumerate(HQ, 1)]
    org += [{"deptCode": s[0], "name": s[1], "parentDeptCode": "DR200", "deptType": "현장"} for s in SITES]
    cols = [("deptCode", "조직코드"), ("name", "조직명"), ("parentDeptCode", "상위조직"), ("deptType", "조직유형")]
    B.write_org(cdir / "조직.csv", org, cols, "cp949")
    return org, cols


def build(df, used, out_dir, seed):
    company = {"id": CID, "name": NAME, "industry": "건설",
               "profile": "상용 30 + 일용 110, 현장 3곳. 현장별 시트 출역부(공수 1·0.5·1.5), 일용 소득세·8일 기준 4대보험, 사번 없는 근로내용확인신고 CSV"}
    cdir = out_dir / CID
    cdir.mkdir(parents=True, exist_ok=True)
    emps = people(df, used, seed)
    rng = B.rng_for(CID, "plan", seed)
    plan = plan_days(emps, rng)
    for e in emps:  # 명부 규칙: 일용 입사일 = 최초 출역일. 12월 첫 출역자는 계획된 첫 출역일로 맞춘다 (난수 소비는 그대로)
        if e["kind"] == "일용" and e["hireDate"] >= dt.date(2026, 12, 1):
            e["hireDate"] = min(plan[e["empNo"]])
    day = [e for e in emps if e["kind"] == "일용"]
    by_site = {s[0]: [e for e in day if e["site"] == s[0]] for s in SITES}
    core_ga = [e for e in by_site["S2401"] if len(plan[e["empNo"]]) >= 12]
    two_e = rng.choice(core_ga)
    two_days = rng.sample(sorted(plan[two_e["empNo"]]), 2)
    left = [e for e in core_ga if e is not two_e]
    big_e = rng.choice(left)
    left.remove(big_e)
    big_d = rng.choice(sorted(d for d, g in plan[big_e["empNo"]].items() if g == 1))
    zero_e = rng.choice(left)
    left.remove(zero_e)
    text_e = rng.choice([e for e in by_site["S2502"] if len(plan[e["empNo"]]) >= 5])
    text_d = rng.choice(sorted(plan[text_e["empNo"]]))
    plan[text_e["empNo"]][text_d] = 0.5
    acc = {"staleSite": "S2502", "twoSites": [{"emp": two_e, "site": "S2502", "days": sorted(two_days)}],
           "big": (("S2401", big_e["empNo"]), big_d), "text": (("S2502", text_e["empNo"]), text_d), "zeroIns": ("S2401", zero_e["empNo"])}
    gong_key, gmeta, vals, shown_plan, who = write_gongsu(cdir / GONG_FILE, emps, plan, acc)
    fewer_e = rng.choice([e for e in left if len(plan[e["empNo"]]) >= 10])
    missing_e = rng.choice([e for e in by_site["S2603"] if 2 <= len(plan[e["empNo"]]) <= 6])
    racc = {"twoSites": acc["twoSites"], "missing": ("S2603", missing_e["empNo"]),
            "fewer": (("S2401", fewer_e["empNo"]), rng.sample(sorted(plan[fewer_e["empNo"]]), 2))}
    report_rows, rep_key = write_report(cdir / REPORT_FILE, emps, vals, shown_plan, racc)
    # 출역부 사고 → 신고 파일과 값이 달라진 행 (신고는 실제 출역 기준이라 신고 값이 맞다)
    rsheet = REPORT_FILE.rsplit(".", 1)[0]
    rcol = {k: xl_col_to_name(n) for n, (k, _h) in enumerate(REP_COLS)}
    rhead = dict(REP_COLS)
    fields = {"days": ["days"], "pay": ["pay", "wageTotal"], "tax": ["tax"], "ltax": ["ltax"]}
    rpt03 = next(a for a in rep_key["accidents"] if a["type"] == "CARRIED_FROM_SOURCE")
    for a in gong_key["accidents"]:
        cks, fs = who[a["id"]]
        cf = []
        for code, key in cks:
            no, two = key.split("@")[0], "@" in key
            hit = report_rows.get((code, no, two))
            if not hit:
                continue
            r, row = hit
            g = vals[(code, key)][0]
            vs = {rhead[c]: {"value": row[c], "gongsuValue": g[k]} for k in fields if k in fs for c in fields[k] if row[c] != g[k]}
            if vs:
                cf.append({"file": REPORT_FILE, "sheet": rsheet, "rowNo": r, "cells": ["%s%d" % (rcol[c], r) for k in fields if k in fs for c in fields[k]
                                                                                      if row[c] != g[k]], "values": vs})
        if a["type"] == "SAME_DAY_TWO_SITES":
            cf = [{"file": REPORT_FILE, "sheet": rsheet, "rowNo": r, "note": "이중 출역을 그대로 신고 — %s로 따로 등록" % rpt03["id"]}
                  for r in rpt03["rows"]]
        if cf:
            a["crossFileAffected"] = cf
            if a["type"] != "SAME_DAY_TWO_SITES":
                a["crossFileNote"] = "신고 파일은 실제 출역 기준이라 신고 값(value)이 맞고 출역부 값(gongsuValue)이 이 사고로 틀림 → 두 파일 대조 시 불일치"
    rpt02 = next(a for a in rep_key["accidents"] if a["type"] == "REPORT_MISSING")
    rpt02["crossFileLocation"]["rowNo"] = gmeta[rpt02["crossFileLocation"]["sheet"]]["rowOf"][rpt02["empNo"]]
    roster = write_roster(cdir, emps, plan, seed)
    org, org_cols = write_org(cdir)
    return B.answer_key(company, "직원명부.xlsx", "조직.csv", roster["mapping"], org_cols, roster["errors"],
                        {"employees": len(emps), "rosterRows": roster["rows"], "orgs": len(org), "dailyWorkers": len(day)},
                        {GONG_FILE: gong_key, REPORT_FILE: rep_key}, {"rosterLayout": roster["layout"]})
