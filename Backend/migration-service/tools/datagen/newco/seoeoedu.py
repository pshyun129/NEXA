"""
서어에듀 — 50명 입시학원 (초·중·고등부). 상근강사(근로·월급), 시간강사(근로·시급제), 위촉강사(사업소득 3.3%)가 한 명부에.

파일: 직원명부.xlsx(한글(HWP) 표를 복사해 붙인 모양: 'Sheet1', 논리 열 하나가 엑셀 2칸 병합, 구분·부서는 세로 병합(아래 행 빈 칸),
              날짜는 '2019. 3. 4.' 문자와 날짜 셀 혼재),
     조직.csv(UTF-8, BOM 없음),
     강사료지급_2026-11_12.csv(CP949, 학원관리 프로그램 내보내기, 11·12월 세로, 근로소득·사업소득 행이 한 파일에, 수식 없음),
     강의시수표_2026-12.xlsx(부마다 시트, 강사·반별 행, 1~5주 열, 사번 없이 강사 이름만, 강사별집계 시트는 이름으로 SUMIF).
규칙: 상근 = 기본급 + 초과수당(월 80시간 초과 × 2.5만) + 식대, 시간강사 = 시수 × 시간당 단가(월 60시간 이상이면 4대보험),
     위촉 = 시수 × 단가, 소득세 3%·지방소득세 = 소득세 × 10% (각각 10원 미만 버림), 4대보험 없음.
"""
import datetime as dt

from hrfiles.common import Accidents
from hrfiles.xl import ROUNDDOWN, Book

from . import base as B

CID, NAME = "seoeoedu", "서어에듀"
PAY_FILE, HOURS_FILE = "강사료지급_2026-11_12.csv", "강의시수표_2026-12.xlsx"
DIVS = {"초등부": ["영어", "수학", "논술"], "중등부": ["수학", "영어", "과학", "국어"], "고등부": ["수학", "영어", "국어", "물리", "화학"]}
INSTR = {"초등부": (4, 3, 3), "중등부": (6, 5, 5), "고등부": (6, 4, 6)}  # 상근, 시간, 위촉
ADMIN = [("행정실", "원장", "정규직"), ("행정실", "부원장", "정규직"), ("행정실", "상담실장", "정규직"), ("행정실", "행정", "정규직"),
         ("행정실", "행정", "계약직"), ("행정실", "행정", "계약직"), ("운영지원", "차량기사", "계약직"), ("운영지원", "차량기사", "계약직")]
KIND_LABEL = {"상근": "상근강사", "시간": "시간강사", "위촉": "위촉강사", "행정": "행정"}
EMP_TYPE = {"상근": "정규직", "시간": "시간제", "위촉": "위촉(사업소득)"}
WEEKS = ["1주(12/1~6)", "2주(12/7~13)", "3주(12/14~20)", "4주(12/21~27)", "5주(12/28~31)"]
BASE_HOURS, OVER_RATE = 80, 25_000


def people(df, used, seed):
    rng = B.rng_for(CID, "people", seed)
    n = len(ADMIN) + sum(sum(v) for v in INSTR.values())
    w = lambda o: 4.0 if B.has(o, "강사", "교사", "교육", "학원", "튜터", "상담") else 1.0  # noqa: E731
    picked = sorted(B.pick(df, used, n, rng, CID, w, ages=(25, 59)), key=lambda p: (-p["age"], p["uuid"]))
    admin, rest = picked[:2], picked[2:]
    rng.shuffle(rest)
    admin += rest[:len(ADMIN) - 2]
    rest = rest[len(ADMIN) - 2:]
    emps = []
    for p, (dept, pos, et) in zip(admin, ADMIN):
        emps.append({"kind": "행정", "dept": dept, "position": pos, "empType": et, "subject": None, "p": p})
    k = 0
    for div, (a, b, c) in INSTR.items():
        for kind, cnt in (("상근", a), ("시간", b), ("위촉", c)):
            for _ in range(cnt):
                emps.append({"kind": kind, "dept": div, "position": "강사" if kind != "상근" else "전임강사", "empType": EMP_TYPE[kind],
                             "subject": rng.choice(DIVS[div]), "p": rest[k]})
                k += 1
    for e in emps:
        p = e.pop("p")
        age = int(p["age"])
        e.update(name=p["name"], birthDate=B.birth_of(age, rng), sex=p["sex"], age=age, mobile=B.phone(rng),
                 hireDate=min(dt.date(max(2014, 2026 - rng.randint(0, 10)), rng.choice([1, 3, 3, 6, 9]), rng.choice([1, 2, 15])), dt.date(2026, 9, 1)),
                 resignDate=None, status="재직")
        # 강사·직원은 대학 졸업(만 22세 무렵) 뒤 입사 (16세 입사 같은 값이 나오지 않게).
        # 직원번호 순서는 처음 뽑은 날짜로 매겨 두어 다른 사람의 번호·시수 난수가 바뀌지 않게 한다
        e["_hire0"] = e["hireDate"]
        e["hireDate"] = min(max(e["hireDate"], dt.date(e["birthDate"].year + 22, 3, 2)), dt.date(2026, 9, 1))
        e["basePay"] = {"원장": 6_500_000, "부원장": 5_000_000, "상담실장": 3_400_000, "행정": 2_500_000, "차량기사": 2_400_000}.get(e["position"]) \
            if e["kind"] == "행정" else (rng.randint(26, 42) * 100_000 if e["kind"] == "상근" else None)
        e["unit"] = rng.choice([35_000, 40_000, 40_000, 45_000, 50_000]) if e["kind"] == "시간" else \
            (rng.choice([45_000, 50_000, 60_000, 70_000]) if e["kind"] == "위촉" else None)
        e["family"] = rng.choice([1, 1, 2, 3, 4])
    # 부원장이 상근 수학을 겸하는 일은 없게. 각 부 상근 최연장자는 부장
    for div in INSTR:
        lead = max((e for e in emps if e["dept"] == div and e["kind"] == "상근"), key=lambda e: (e["age"], e["name"]))
        lead["position"] = "부장"
    emps.sort(key=lambda e: (e["kind"] != "행정", e["_hire0"], e["name"]))
    na = nt = 0
    for e in emps:
        if e["kind"] == "행정":
            na += 1
            e["empNo"] = "A%02d" % na
        else:
            nt += 1
            e["empNo"] = "T%03d" % (100 + nt)
        e["email"] = "%s@seoeo-edu.com" % e["empNo"].lower() if e["kind"] in ("행정", "상근") else None
    return emps


# ================================================================ 시수

def plan_classes(emps, rng):
    """사번 → [(반, [주차별 시수 5개])], 11월 시수"""
    out, nov = {}, {}
    for e in emps:
        if e["kind"] == "행정":
            continue
        n_cls = {"상근": rng.randint(4, 6), "시간": rng.randint(2, 4), "위촉": rng.randint(2, 4)}[e["kind"]]
        classes = []
        for c in range(n_cls):
            wk = rng.choice([3, 4, 4, 5, 6]) if e["kind"] == "상근" else rng.choice([3, 4, 5, 6, 6])
            weeks = [wk, wk, wk, max(0, wk - rng.choice([0, 1, 2])), max(1, round(wk * 0.6))]
            grade = {"초등부": "초", "중등부": "중", "고등부": "고"}[e["dept"]]
            classes.append(("%s%d-%s%s반" % (grade, rng.randint(1, 3), e["subject"], "ABCDEF"[c]), weeks))
        out[e["empNo"]] = classes
        nov[e["empNo"]] = sum(sum(w[:4]) for _, w in classes) + rng.choice([-2, 0, 0, 2, 4])
    return out, nov


def write_hours(path, emps, classes, acc):
    """반환: (정답지, 강사별집계 이름→시수(사고 반영), 사람별 실제 시수)"""
    A = Accidents("SEOE-HRS", path.name)
    book = Book(path)
    meta = {}
    truth, shown_total = {}, {}
    sums = {}  # 시트·이름 → 엑셀이 계산하는 합 (문자 칸 제외)
    ranges = {}
    emps = [e for e in emps if e["status"] == "재직"]  # 퇴사한 위촉강사는 12월 시수표에 없음
    for div in INSTR:
        sh = book.sheet(div)
        sh.put("A", 1, "2026년 12월 강의시수표 (%s)" % div, "title")
        for c, h in zip("ABCDEFGHI", ["강사", "과목", "반"] + WEEKS + ["계"]):
            sh.put(c, 3, h, "head")
        r, row_of = 4, {}
        members = sorted([e for e in emps if e["dept"] == div and e["kind"] != "행정"], key=lambda e: (e["subject"], e["name"], e["empNo"]))
        for e in members:
            for cname, weeks in classes[e["empNo"]]:
                sh.put("A", r, e["name"])
                sh.put("B", r, e["subject"])
                sh.put("C", r, cname)
                s = 0
                for i, h in enumerate(weeks):
                    col = "DEFGH"[i]
                    if acc["text"] == (e["empNo"], cname, i):
                        sh.put(col, r, "%d(보강)" % h)
                        continue
                    sh.put(col, r, h)
                    s += h
                sh.formula("I", r, "=SUM(D%d:H%d)" % (r, r), s)
                sums[(div, e["name"])] = sums.get((div, e["name"]), 0) + s
                truth[e["empNo"]] = truth.get(e["empNo"], 0) + sum(weeks)
                row_of.setdefault(e["empNo"], []).append(r)
                r += 1
        last = r - 1
        ranges[div] = last
        sh.widths({"A": 8, "C": 14})
        sh.ws.freeze_panes(3, 1)
        meta[div] = {"role": "lectureHoursByClass", "headerRows": [3], "firstDataRow": 4, "lastDataRow": last, "titleRows": [1],
                     "rowsOf": row_of, "note": "사번 없음. 한 강사가 반마다 여러 행"}
    # 강사별집계: 이름 하나에 한 행 (먼저 나온 부 기준), 시트마다 SUMIF(이름)
    sh = book.sheet("강사별집계")
    sh.put("A", 1, "강사별 12월 시수 (강사료 지급 기준)", "title")
    for c, h in zip("ABCD", ["강사", "부", "구분", "12월 시수"]):
        sh.put(c, 3, h, "head")
    r, seen, row_sum = 4, set(), {}
    for div in INSTR:
        for e in sorted([e for e in emps if e["dept"] == div and e["kind"] != "행정"], key=lambda e: e["empNo"]):
            if e["name"] in seen:
                continue
            seen.add(e["name"])
            f = "=" + "+".join("SUMIF(%s!$A$4:$A$%d,A%d,%s!$I$4:$I$%d)" % (d, ranges[d], r, d, ranges[d]) for d in INSTR)
            v = sum(sums.get((d, e["name"]), 0) for d in INSTR)
            sh.put("A", r, e["name"])
            sh.put("B", r, div)
            sh.put("C", r, KIND_LABEL[e["kind"]])
            sh.formula("D", r, f, v)
            shown_total[e["name"]] = v
            row_sum[e["name"]] = r
            r += 1
    meta["강사별집계"] = {"role": "lectureHoursSummary", "headerRows": [3], "firstDataRow": 4, "lastDataRow": r - 1, "titleRows": [1],
                      "rowOfName": row_sum}
    sh.widths({"A": 8, "D": 10})
    book.close()

    emp_of = {e["empNo"]: e for e in emps}
    e1, e2 = (emp_of[x] for x in acc["homonym"])
    rr = row_sum[e1["name"]]
    A.add("NAME_KEY_COLLISION", "강사별집계", "동명이인 '%s'(%s %s, %s %s)을 이름 SUMIF로 합쳐 한 행 %s시간 — 두 사람 시수가 섞이고 %s 강사 행은 집계에 없음"
          % (e1["name"], e1["dept"], e1["empNo"], e2["dept"], e2["empNo"], shown_total[e1["name"]], e2["empNo"]),
          ["NAME_MATCH", "DUPLICATE_DETECT", "CROSS_SHEET_LOOKUP"], rowNo=rr, empNos=[e1["empNo"], e2["empNo"]], cells=["D%d" % rr],
          values={"12월 시수": {"actual": shown_total[e1["name"]], "expected": truth[e1["empNo"]]}},
          missingRows=[{"name": e2["name"], "dept": e2["dept"], "empNo": e2["empNo"], "12월 시수": truth[e2["empNo"]],
                        "detail": "이 행(%s %s)의 시수만 남기고 %s %s 행을 따로 두어야 함" % (e1["dept"], e1["empNo"], e2["dept"], e2["empNo"])}])
    no, cname, wi = acc["text"]
    e = emp_of[no]
    rows = meta[e["dept"]]["rowsOf"][no]
    r = rows[[c for c, _w in classes[no]].index(cname)]
    h = dict(classes[no])[cname][wi]
    rs = row_sum[e["name"]]
    A.add("TEXT_NUMBER", e["dept"], "%s %s 보강 시수를 '%d(보강)' 글자로 입력 → 계(SUM)·강사별집계에서 %d시간 빠짐" % (cname, WEEKS[wi], h, h),
          ["VALUE_NORMALIZE", "RECOMPUTE_CHECK"], empNo=no, rowNo=r, cells=["%s%d" % ("DEFGH"[wi], r)],
          cascade=["I%d" % r, "강사별집계!D%d" % rs],
          affected=[{"sheet": e["dept"], "rowNo": r}, {"sheet": "강사별집계", "rowNo": rs}],
          values={WEEKS[wi]: {"actual": "%d(보강)" % h, "expected": h},
                  "계": {"actual": sum(dict(classes[no])[cname]) - h, "expected": sum(dict(classes[no])[cname])}},
          cascadeValues={"강사별집계!D%d" % rs: {"header": "12월 시수", "actual": shown_total[e["name"]], "expected": shown_total[e["name"]] + h}})
    key = {"file": path.name, "kind": "LECTURE_HOURS", "closestEntity": None, "supported": False, "period": "2026-12",
           "sheetOrder": list(INSTR) + ["강사별집계"], "sheets": meta,
           "expectedMapping": {"lectureHoursByClass": {"강사": "name", "과목": "subject", "반": "className", **{w: "week%d" % (i + 1) for i, w in enumerate(WEEKS)},
                                                       "계": "totalHours"},
                               "lectureHoursSummary": {"강사": "name", "부": "dept", "구분": "instructorType", "12월 시수": "totalHours"}},
           "rules": {"key": "사번 없음 — 강사 이름으로만 연결 (강사료 파일과도 이름으로 맞춤)", "summary": "강사별집계 = 부 시트마다 SUMIF(이름)의 합"},
           "accidents": A.items, "cleanSheets": [d for d in INSTR if d not in (e1["dept"], e2["dept"], e["dept"])],
           "legitUnusual": ["같은 강사가 반마다 여러 행 (이름 반복은 정상)", "5주차는 12/28~31 4일뿐이라 시수가 적음", "%s 동명이인 두 명은 다른 사람 (생년월일·과목 다름)" % e1["name"]],
           "crossFile": {"pay": {"file": PAY_FILE, "detail": "12월 강의시수 = 강사별집계 (이름으로 옮겨 적음)"}}}
    return key, shown_total, truth


# ================================================================ 강사료 (CSV, 수식 없음)

PAY_COLS = [("ym", "지급월"), ("empNo", "직원번호"), ("name", "성명"), ("kindLabel", "구분"), ("dept", "소속"), ("subject", "과목"),
            ("hours", "강의시수"), ("unit", "시간당단가"), ("lecture", "강의료"), ("basePay", "기본급"), ("over", "초과수당"), ("meal", "식대"),
            ("gross", "지급총액"), ("incomeType", "소득구분"), ("tax", "소득세"), ("ltax", "지방소득세"), ("np", "국민연금"), ("hi", "건강보험"),
            ("ltc", "장기요양"), ("ei", "고용보험"), ("ded", "공제합계"), ("net", "실지급액"), ("memo", "비고")]


def pay_row(e, ym, hours, opts=None):
    o = opts or {}
    R = B.RATES_2026
    v = {"ym": ym, "empNo": e["empNo"], "name": e["name"], "kindLabel": KIND_LABEL[e["kind"]], "dept": e["dept"], "subject": e["subject"],
         "hours": hours, "unit": e["unit"], "lecture": 0, "basePay": 0, "over": 0, "meal": 0, "memo": o.get("memo")}
    if e["kind"] in ("행정", "상근"):
        v["basePay"], v["meal"] = e["basePay"], 200_000
        if e["kind"] == "상근":
            v["over"] = max(0, hours - BASE_HOURS) * OVER_RATE
        else:
            v["hours"] = None
    else:
        v["lecture"] = ROUNDDOWN((o.get("unitUsed") or e["unit"]) * hours, 0)
    if o.get("fixed"):
        v.update(lecture=o["fixed"], hours=None, unit=None)
    v["gross"] = v["lecture"] + v["basePay"] + v["over"] + v["meal"]
    if e["kind"] == "위촉":
        v["incomeType"] = "사업"
        v["tax"] = ROUNDDOWN(v["gross"] * (0.033 if o.get("combined") else 0.03), -1)
        v["ltax"] = 0 if o.get("combined") else ROUNDDOWN(v["tax"] * 0.1, -1)
        v["np"] = v["hi"] = v["ltc"] = v["ei"] = 0
        if o.get("insurance"):
            v["hi"] = ROUNDDOWN(v["gross"] * R["hi"], -1)
            v["ltc"] = ROUNDDOWN(v["hi"] * R["ltc"], -1)
            v["ei"] = ROUNDDOWN(v["gross"] * R["ei"], -1)
    else:
        v["incomeType"] = "근로"
        taxable = v["gross"] - v["meal"]
        covered = e["kind"] != "시간" or (hours >= 60 and not o.get("noIns"))
        v["np"] = ROUNDDOWN(min(max(ROUNDDOWN(taxable, -3), B.NP_FLOOR), B.NP_CAP) * R["np"], -1) if covered else 0
        v["hi"] = ROUNDDOWN(taxable * R["hi"], -1) if covered else 0
        v["ltc"] = ROUNDDOWN(v["hi"] * R["ltc"], -1)
        v["ei"] = ROUNDDOWN(taxable * R["ei"], -1) if covered else 0
        v["tax"] = B.tax_of(taxable, e["family"])
        v["ltax"] = ROUNDDOWN(v["tax"] * 0.1, -1)
    v["ded"] = v["tax"] + v["ltax"] + v["np"] + v["hi"] + v["ltc"] + v["ei"]
    v["net"] = v["gross"] - v["ded"]
    return v


def write_pay(path, emps, nov, dec_shown, truth, acc):
    A = Accidents("SEOE-PAY", path.name)
    plans = []
    for ym in ("2026-11", "2026-12"):
        for e in emps:
            if e["status"] != "재직":  # 2026-08 이전 퇴사자(위촉강사)는 11·12월 강사료 없음
                continue
            no = e["empNo"]
            if e["kind"] == "행정":
                h = None
            elif ym == "2026-11":
                h = nov[no]
            else:
                h = dec_shown.get(e["name"], 0) if no != acc["homonym"][1] else dec_shown[e["name"]]
            opts, ideal_opts, tag = {}, {}, None
            ideal_h = truth.get(no) if ym == "2026-12" else h
            if no == acc["misclass"]:
                opts = {"fixed": 2_800_000, "memo": "담임·상담 포함, 주5일"}
                ideal_opts = dict(opts)
                tag = "MISCLASS"
            if ym == "2026-12":
                if no == acc["combined"]:
                    opts["combined"], tag = True, "COMBINED"
                if no == acc["insBiz"]:
                    opts["insurance"], tag = True, "INSBIZ"
                if no == acc["unit"]:
                    opts["unitUsed"], tag = e["unit"] - 5_000, "UNIT"
                if no == acc["noIns"]:
                    opts.update(noIns=True, memo="단시간")
                    tag = "NOINS"
                if no in acc["homonym"]:
                    tag = "HOMONYM"
                if no == acc["typo"][0]:
                    h, tag = acc["typo"][1], "TYPO"
                if no == acc["textCarry"]:
                    tag = "TEXTCARRY"
            actual = pay_row(e, ym, h, opts)
            ideal = pay_row(e, ym, ideal_h, ideal_opts)
            plans.append((ym, e, actual, ideal, tag))
    rows = [[p[2].get(k) for k, _ in PAY_COLS] for p in plans]
    first = B.write_csv(path, [h for _, h in PAY_COLS], rows, "cp949")
    sheet = path.stem
    col = {k: B_col(i) for i, (k, _) in enumerate(PAY_COLS)}
    row_no = {(p[0], p[1]["empNo"]): first + n for n, p in enumerate(plans)}

    def cells(r, ks):
        return ["%s%d" % (col[k], r) for k in ks]

    hdr = dict(PAY_COLS)
    amount_keys = [k for k, _ in PAY_COLS if k not in ("ym", "empNo", "name", "kindLabel", "dept", "subject", "incomeType", "memo")]

    def full(r, a, i, primary):
        """사고 행에서 회사 규칙 값과 다른 칸 전부: cells(원인 칸) + cascade(그 때문에 같이 틀린 칸) + values(헤더별 실제·기대)"""
        diff = [k for k in amount_keys if a.get(k) != i.get(k) and k not in primary]
        return {"cells": cells(r, primary), "cascade": cells(r, diff),
                "values": {hdr[k]: {"actual": a[k], "expected": i[k]} for k in primary + diff if a.get(k) != i.get(k)}}

    for ym, e, a, i, tag in plans:
        r = row_no[(ym, e["empNo"])]
        if tag == "MISCLASS" and ym == "2026-12":
            rr = [row_no[("2026-11", e["empNo"])], r]
            A.add("MISCLASSIFIED_CONTRACTOR", sheet, "위촉강사(사업소득 3.3%%)인데 시수·단가 없이 매달 고정 %s원, 비고 '담임·상담 포함, 주5일' — 근로자성이 강해 "
                  "근로소득·4대보험 대상일 가능성 (위장 프리랜서)" % format(a["gross"], ","), ["CROSS_FIELD_RULE", "LEGAL_RULE_COMPARE"], legal=True,
                  empNo=e["empNo"], rows=rr, cells=cells(r, ["incomeType", "hours", "unit"]))
        elif tag == "COMBINED":
            A.add("WITHHOLDING_COMBINED_RATE", sheet, "사업소득 원천징수를 소득세 칸에 3.3%%(%s원) 한꺼번에, 지방소득세 0 → 소득세 3%%·지방소득세 0.3%% 따로 신고해야 함"
                  % format(a["tax"], ","), ["LEGAL_RULE_COMPARE", "RECOMPUTE_CHECK"], legal=True, empNo=e["empNo"], rowNo=r, **full(r, a, i, ["tax", "ltax"]))
        elif tag == "INSBIZ":
            A.add("INSURANCE_ON_BUSINESS_INCOME", sheet, "소득구분 '사업'(3.3%)인데 건강보험·장기요양·고용보험을 공제 — 사업소득자는 사업장 4대보험 대상 아님 (둘 중 하나가 틀림)",
                  ["CROSS_FIELD_RULE", "LEGAL_RULE_COMPARE"], legal=True, empNo=e["empNo"], rowNo=r, **full(r, a, i, ["hi", "ltc", "ei"]))
        elif tag == "UNIT":
            A.add("UNIT_PRICE_MISMATCH", sheet, "12월 강의료를 시간당단가 열(%s원, 11월 %d행과 같은 단가)이 아니라 %s원 × %s시간으로 계산 → 강의료 ≠ 시수 × 단가. "
                  "4대보험·소득세도 줄어든 강의료 기준 (cascade)"
                  % (format(e["unit"], ","), row_no[("2026-11", e["empNo"])], format(e["unit"] - 5_000, ","), a["hours"]), ["RECOMPUTE_CHECK"],
                  empNo=e["empNo"], rowNo=r, **full(r, a, i, ["unit", "lecture"]))
        elif tag == "NOINS":
            A.add("INSURANCE_EXEMPTION_WRONG", sheet, "시간강사 12월 %s시간(60시간 이상)인데 4대보험 0, 비고 '단시간'" % a["hours"],
                  ["LEGAL_RULE_COMPARE", "RECOMPUTE_CHECK"], legal=True, empNo=e["empNo"], rowNo=r, **full(r, a, i, ["np", "hi", "ltc", "ei"]))
        elif tag == "HOMONYM":
            under = e["kind"] == "시간" and i["hours"] < 60 <= a["hours"]
            A.add("CARRIED_FROM_SOURCE", sheet, "강의시수표 강사별집계의 동명이인 합산(SEOE-HRS-01)을 그대로 옮김 → 강의시수 %s (실제 %s)%s" % (
                a["hours"], i["hours"], ". 실제 시수로는 월 60시간 미만이라 회사 규칙상 4대보험 0, 소득세도 다시 계산 (cascade)" if under else ""),
                  ["CROSS_FILE_MATCH", "NAME_MATCH"], empNo=e["empNo"], rowNo=r, source="SEOE-HRS-01", **full(r, a, i, ["hours", "lecture"]))
        elif tag == "TEXTCARRY":
            A.add("CARRIED_FROM_SOURCE", sheet, "강의시수표의 '보강' 글자 사고(SEOE-HRS-02)로 빠진 시수를 그대로 옮김 → 강의시수 %s (실제 %s), 80시간 미만이라 금액 영향은 없음"
                  % (a["hours"], i["hours"]), ["CROSS_FILE_MATCH"], empNo=e["empNo"], rowNo=r, source="SEOE-HRS-02", **full(r, a, i, ["hours"]))
        elif tag == "TYPO":
            A.add("HOURS_MISMATCH_CROSS_FILE", sheet, "강사별집계 %s시간을 %s시간으로 옮겨 적음 → 강의료와 4대보험·소득세도 같이 틀림 (cascade)" % (i["hours"], a["hours"]),
                  ["CROSS_FILE_MATCH"], empNo=e["empNo"], rowNo=r, **full(r, a, i, ["hours", "lecture"]))
    # 월 60시간 미만이라 4대보험 0인 시간강사 행 중 그달 1일에 3개월 이상 근무한 행 (고용보험은 법정 적용 대상)
    short_rows = sorted(row_no[(ym, e["empNo"])] for ym, e, a, _i, _t in plans
                        if e["kind"] == "시간" and a["hours"] is not None and a["hours"] < 60 and a["ei"] == 0
                        and e["hireDate"] <= dt.date(int(ym[:4]), int(ym[5:]) - 3, 1))
    return {"file": path.name, "kind": "PAYROLL", "closestEntity": "PAY_RESULT_HISTORY", "supported": False, "format": "csv", "encoding": "cp949",
            "sheetOrder": [sheet],
            "sheets": {sheet: {"role": "lecturePayLong", "headerRows": [1], "firstDataRow": first, "lastDataRow": first + len(plans) - 1,
                               "note": "CSV라 시트가 없음", "rowOf": {"%s|%s" % k: v for k, v in row_no.items()}}},
            "expectedMapping": {"lecturePayLong": {h: ("payMonth" if k == "ym" else k) for k, h in PAY_COLS}},
            "rules": {"fullTime": "상근강사: 기본급 + 초과수당 MAX(0, 시수−80) × 25,000 + 식대 20만 (근로소득)",
                      "hourly": "시간강사: 강의료 = 시수 × 시간당단가 (근로소득), 월 시수 60시간 이상만 4대보험 (회사 규칙. 고용보험은 법과 다름 → legalDeviations)",
                      "director": "원장은 법인에 고용된 원장(대표자 아님)으로 보고 다른 직원처럼 4대보험 공제 (생성기 가정)",
                      "contractor": "위촉강사: 강의료 = 시수 × 단가 (사업소득), 소득세 3%·지방소득세 = 소득세×10% (각각 10원 미만 버림), 4대보험 없음",
                      "insuranceRounding": "floor10", "incomeTax": "근로소득은 간이세액표(단순화)"},
            "legalDeviations": [{"rule": "businessIncomeWithholding", "company": "3.3%를 소득세 칸에 한꺼번에 1건", "statutory": "소득세 3% + 지방소득세 0.3%"},
                                {"rule": "shortHoursEmploymentInsurance", "company": "월 60시간 미만 시간강사는 4대보험 전부 0 (고용보험 포함)",
                                 "statutory": "고용보험은 월 60시간 미만이라도 3개월 이상 계속 근로하면 적용 (고용보험법 시행령 제3조). "
                                              "국민연금·건강보험의 월 60시간 미만 제외는 생성기의 단순화 가정 (실제 가입 요건은 법마다 다름)",
                                 "rows": short_rows}],
            "accidents": A.items,
            "legitUnusual": ["근로소득·사업소득 행이 한 파일 (소득구분 열)", "행정 직원은 강의시수·단가 빈 칸", "위촉강사는 4대보험 0이 정상",
                             "명부의 퇴사자 위촉강사 2명(2026년 8월 이전 퇴사)은 11·12월 강사료·시수표에 없음",
                             "원장도 4대보험 공제: 법인에 고용된 원장(근로자)으로 가정"],
            "valueNoise": [{"type": "LONG_TWO_MONTHS", "detail": "11월·12월이 한 파일에 세로로 (지급월 열)"}],
            "crossFile": {"hours": {"file": HOURS_FILE, "detail": "12월 강의시수 = 강의시수표 강사별집계 (이름으로 옮겨 적음)"}}}


def B_col(i):
    from xlsxwriter.utility import xl_col_to_name
    return xl_col_to_name(i)


# ================================================================ 직원명부 (한글 표 붙여넣기)

ROSTER_COLS = [("_kind", "구분"), ("dept", "부서"), ("empNo", "직원번호"), ("name", "성명"), ("jobRole", "담당과목"), ("position", "직위"),
               ("empType", "고용형태"), ("hireDate", "입사일"), ("birthDate", "생년월일"), ("mobile", "휴대폰"), ("email", "이메일"),
               ("status", "재직"), ("resignDate", "퇴사일"), ("_memo", "비고")]
MERGE_DOWN = ("_kind", "dept")


def write_roster(cdir, emps, acc, seed):
    rng = B.rng_for(CID, "roster", seed)
    errs = B.RosterErrors()
    rows = [{"_kind": KIND_LABEL[e["kind"]], "dept": e["dept"], "empNo": e["empNo"], "name": e["name"], "jobRole": e["subject"],
             "position": e["position"], "empType": e["empType"], "hireDate": e["hireDate"], "birthDate": e["birthDate"], "mobile": e["mobile"],
             "email": e["email"], "status": e["status"], "resignDate": e["resignDate"], "_memo": None, "_e": e} for e in emps]
    order = {"행정실": 0, "운영지원": 1, "초등부": 2, "중등부": 3, "고등부": 4}
    rows.sort(key=lambda r: (order[r["dept"]], ["행정", "상근강사", "시간강사", "위촉강사"].index(r["_kind"]), r["empNo"]))
    idx = list(range(len(rows)))
    rng.shuffle(idx)
    reserved = set(acc["reserved"])
    take = lambda k, cond=lambda r: True: [idx.pop(idx.index(i)) for i in [j for j in idx if cond(rows[j]) and rows[j]["empNo"] not in reserved][:k]]  # noqa: E731
    mi = next(n for n, r in enumerate(rows) if r["empNo"] == acc["misclass"])
    rows[mi]["_memo"] = "주5일 상근·담임"
    errs.add(mi, "empType", "MISCLASSIFIED_CONTRACTOR", "고용형태 '위촉(사업소득)'인데 비고 '주5일 상근·담임' — 근로자로 봐야 할 가능성", detectable=False)
    for i in take(1, lambda r: r["_kind"] != "행정"):
        rows[i]["hireDate"] = None
        errs.add(i, "hireDate", "REQUIRED", "입사일 빈 칸")
    for i in take(1, lambda r: r["email"]):
        rows[i]["email"] = rows[i]["email"].replace("@", "@@")
        errs.add(i, "email", "INVALID_EMAIL", "이메일 '@@': %s" % rows[i]["email"])
    i, j = take(2, lambda r: r["_kind"] == "시간강사")
    rows[j]["empNo"] = rows[i]["empNo"]
    errs.add(i, "empNo", "DUPLICATE_KEY", "직원번호 중복 (다른 사람)")
    errs.add(j, "empNo", "DUPLICATE_KEY", "직원번호 중복 — 이 행의 실제 번호는 %s" % rows[j]["_e"]["empNo"])
    for i in take(1, lambda r: r["_kind"] == "시간강사"):
        rows[i]["empType"] = "알바"
        errs.add(i, "empType", "INVALID_ENUM", "고용형태 '알바' (코드표: 정규직·계약직·시간제·위촉(사업소득))")
    # 퇴사자 2명(위촉강사, 강사료·시수표에 없음) 중 1명은 퇴사일을 입사일보다 앞선 날짜로 잘못 적음. 재직구분 '퇴사'는 맞는 값
    bad = next(n for n, r in enumerate(rows) if r["_e"]["empNo"] == acc["retBad"])
    true_resign = rows[bad]["resignDate"]
    rows[bad]["resignDate"] = rows[bad]["hireDate"] - dt.timedelta(days=rng.randint(30, 200))
    errs.add(bad, "resignDate", "DATE_ORDER", "퇴사일 < 입사일 (실제 퇴사일 %s)" % true_resign.isoformat())
    # 시간강사 → 상근 전환하며 새 번호, 예전 시간강사 행 '재직' 그대로
    src = take(1, lambda r: r["_kind"] == "상근강사" and r["hireDate"])[0]
    old = dict(rows[src])
    old.update(_kind="시간강사", empNo="T%03d" % (190 + rng.randint(1, 9)), empType="시간제", position="강사", email=None,
               hireDate=rows[src]["hireDate"] - dt.timedelta(days=400), _memo="상근 전환 전")
    rows.insert(src + 1, old)
    errs.items = [{**x, "i": x["i"] + (1 if x["i"] > src else 0)} for x in errs.items]
    errs.add(src, "name", "DUPLICATE_SUSPECTED", "같은 사람(성명·생년월일 같음)이 시간강사 번호 %s로도 재직" % old["empNo"])
    errs.add(src + 1, "name", "DUPLICATE_SUSPECTED", "상근 전환 전 시간강사 행이 닫히지 않음 (새 번호 %s)" % rows[src]["empNo"])
    for r in rows:
        for f in ("hireDate", "birthDate", "resignDate"):
            if r[f] is not None:
                r[f] = B.fmt_date(r[f], B.date_style(rng, "hwp", ["cell"], 0.3))
    B.date_format_errors(rows, ("hireDate", "birthDate", "resignDate"), errs)
    # ---- 쓰기: 논리 열 하나 = 엑셀 2칸, 구분·부서는 같은 값이 이어지면 세로 병합
    book = Book(cdir / "직원명부.xlsx")
    sh = book.sheet("Sheet1")
    cell = book.wb.add_format({"border": 1, "align": "center", "valign": "vcenter", "text_wrap": True})
    dcell = book.wb.add_format({"border": 1, "align": "center", "valign": "vcenter", "num_format": "yyyy-mm-dd"})
    ncols = len(ROSTER_COLS) * 2
    from xlsxwriter.utility import xl_col_to_name as cn
    sh.ws.merge_range(0, 0, 0, ncols - 1, "서어에듀 교직원 현황", book.wb.add_format({"bold": True, "font_size": 16, "align": "center"}))
    sh.ws.merge_range(1, 0, 1, ncols - 1, "(2026. 12. 1. 현재)", book.wb.add_format({"align": "right"}))
    for c, (_f, h) in enumerate(ROSTER_COLS):
        sh.ws.merge_range(2, 2 * c, 2, 2 * c + 1, h, book.f["head"])
    first = 4
    n = len(rows)
    for c, (f, _h) in enumerate(ROSTER_COLS):
        k = 0
        while k < n:
            v = rows[k].get(f)
            span = 1
            if f in MERGE_DOWN:
                while k + span < n and rows[k + span].get(f) == v:
                    span += 1
            r0 = first - 1 + k
            if isinstance(v, dt.date):
                v = dt.datetime(v.year, v.month, v.day)
            fmt = dcell if isinstance(v, dt.datetime) else cell
            if v is None:
                sh.ws.merge_range(r0, 2 * c, r0 + span - 1, 2 * c + 1, "", fmt)
            else:
                sh.ws.merge_range(r0, 2 * c, r0 + span - 1, 2 * c + 1, None, fmt)
                if isinstance(v, dt.datetime):
                    sh.ws.write_datetime(r0, 2 * c, v, fmt)
                elif isinstance(v, str):
                    sh.ws.write_string(r0, 2 * c, v, fmt)
                else:
                    sh.ws.write_number(r0, 2 * c, v, fmt)
            k += span
    for c in range(ncols):
        sh.ws.set_column(c, c, 6)
    book.close()
    mapping = {h: (None if f.startswith("_") else f) for f, h in ROSTER_COLS}
    return {"mapping": mapping, "errors": errs.rows(first), "rows": len(rows),
            "layout": {"sheet": "Sheet1", "titleRows": [1, 2], "headerRows": [3], "firstDataRow": first,
                       "mergedColumns": "논리 열 하나가 엑셀 2칸 병합 (A:B 구분, C:D 부서, E:F 직원번호 …). 값은 왼쪽 칸",
                       "mergedDown": {"구분": "같은 값이 이어지면 세로 병합 → 아래 행은 빈 칸 (위 값과 같음, 오류 아님)",
                                      "부서": "같은 값이 이어지면 세로 병합 → 아래 행은 빈 칸 (위 값과 같음, 오류 아님)"},
                       "notes": ["한글(HWP) 표를 복사해 붙인 모양 (시트 이름 'Sheet1')", "날짜는 '2019. 3. 4.' 문자 70% + 날짜 셀 30%",
                                 "위촉강사(사업소득) 14명은 근로자가 아니지만 학원 교직원 명부에 함께 관리 (이관 대상 여부는 회사 결정, 오류로 세지 않음. 그중 주5일 상근 1명만 MISCLASSIFIED_CONTRACTOR)",
                                 "시간강사·위촉강사는 이메일 빈 칸 (오류 아님)",
                                 "동명이인 강사 2명은 다른 사람 (생년월일 다름, 오류 아님)"]}}


def write_org(cdir):
    org = [{"deptCode": "SE", "name": NAME, "parentDeptCode": None}]
    org += [{"deptCode": "SE%d" % (n + 1), "name": d, "parentDeptCode": "SE"} for n, d in enumerate(["행정실", "운영지원", "초등부", "중등부", "고등부"])]
    cols = [("deptCode", "코드"), ("name", "부서"), ("parentDeptCode", "상위")]
    B.write_org(cdir / "조직.csv", org, cols, "utf-8")
    return org, cols


def build(df, used, out_dir, seed):
    company = {"id": CID, "name": NAME, "industry": "교육(학원)",
               "profile": "50명 입시학원, 근로자 강사와 위촉(3.3%) 강사가 한 명부·한 강사료 파일에, 한글 표를 붙인 병합 셀 명부, 사번 없는 시수표(이름 SUMIF)"}
    cdir = out_dir / CID
    cdir.mkdir(parents=True, exist_ok=True)
    emps = people(df, used, seed)
    rng = B.rng_for(CID, "plan", seed)
    # 동명이인: 중등부·고등부 시간강사 한 명씩 같은 이름 (생년월일은 다름)
    h1 = sorted((e for e in emps if e["dept"] == "중등부" and e["kind"] == "시간"), key=lambda e: e["empNo"])[0]
    h2 = sorted((e for e in emps if e["dept"] == "고등부" and e["kind"] == "시간"), key=lambda e: e["empNo"])[0]
    h2["name"] = h1["name"]
    classes, nov = plan_classes(emps, rng)
    pick = lambda cond: sorted((e for e in emps if cond(e)), key=lambda e: e["empNo"])  # noqa: E731
    contractors = [e for e in pick(lambda e: e["kind"] == "위촉")]
    rng.shuffle(contractors)
    misclass, combined, ins_biz = contractors[:3]
    hourly = [e for e in pick(lambda e: e["kind"] == "시간") if e not in (h1, h2)]
    rng.shuffle(hourly)
    unit_e, typo_e = hourly[:2]
    week_tot = {e["empNo"]: sum(sum(w) for _, w in classes[e["empNo"]]) for e in emps if e["kind"] != "행정"}
    big = [e for e in hourly[2:] if week_tot[e["empNo"]] >= 60]
    if not big:  # 60시간 이상 시간강사가 없으면 한 명 반을 늘림
        e = hourly[2]
        cname, w = classes[e["empNo"]][0]
        add = 60 - week_tot[e["empNo"]]
        classes[e["empNo"]][0] = (cname, [w[0] + add // 4 + 1, w[1] + add // 4 + 1, w[2] + add // 4 + 1, w[3] + add // 4 + 1, w[4]])
        big = [e]
    noins_e = big[0]
    full = [e for e in pick(lambda e: e["kind"] == "상근") if week_tot[e["empNo"]] <= 76]
    text_e = rng.choice(full) if full else pick(lambda e: e["kind"] == "상근")[0]
    tc = rng.choice(classes[text_e["empNo"]])
    acc_h = {"homonym": (h1["empNo"], h2["empNo"]), "text": (text_e["empNo"], tc[0], 3 if tc[1][3] else 0)}
    # 퇴사한 위촉강사 2명 (명부에만, 11·12월 강사료·시수표에 없음): 2026-08-31 퇴사 1명, 2026-02-28 퇴사 1명(명부 퇴사일 오기 DATE_ORDER)
    busy = {misclass["empNo"], combined["empNo"], ins_biz["empNo"], unit_e["empNo"], typo_e["empNo"], noins_e["empNo"], text_e["empNo"], *acc_h["homonym"]}
    ret_ok, ret_bad = B.rng_for(CID, "retire", seed).sample(
        [e for e in pick(lambda e: e["kind"] == "위촉") if e["empNo"] not in busy and e["hireDate"] < dt.date(2026, 1, 1)], 2)
    ret_ok.update(status="퇴사", resignDate=dt.date(2026, 8, 31))
    ret_bad.update(status="퇴사", resignDate=dt.date(2026, 2, 28))
    hours_key, shown_total, truth = write_hours(cdir / HOURS_FILE, emps, classes, acc_h)
    acc_p = {"misclass": misclass["empNo"], "combined": combined["empNo"], "insBiz": ins_biz["empNo"], "unit": unit_e["empNo"],
             "noIns": noins_e["empNo"], "homonym": acc_h["homonym"], "textCarry": text_e["empNo"], "typo": (typo_e["empNo"], shown_total[typo_e["name"]] + 6)}
    pay_key = write_pay(cdir / PAY_FILE, emps, nov, shown_total, truth, acc_p)
    acc_r = {"misclass": misclass["empNo"], "retBad": ret_bad["empNo"],
             "reserved": [misclass["empNo"], *acc_h["homonym"], combined["empNo"], ins_biz["empNo"], unit_e["empNo"],
                          typo_e["empNo"], noins_e["empNo"], text_e["empNo"], ret_ok["empNo"], ret_bad["empNo"]]}
    for h in hours_key["accidents"]:
        h["crossFileAffected"] = [{"file": PAY_FILE, "sheet": a["sheet"], "rowNo": a["rowNo"], "cells": a["cells"] + a["cascade"], "values": a["values"],
                                   "accident": a["id"]} for a in pay_key["accidents"] if a.get("source") == h["id"]]
    roster = write_roster(cdir, emps, acc_r, seed)
    org, org_cols = write_org(cdir)
    return B.answer_key(company, "직원명부.xlsx", "조직.csv", roster["mapping"], org_cols, roster["errors"],
                        {"employees": len(emps), "rosterRows": roster["rows"], "orgs": len(org),
                         "contractors": sum(e["kind"] == "위촉" for e in emps)},
                        {PAY_FILE: pay_key, HOURS_FILE: hours_key}, {"rosterLayout": roster["layout"]})
