"""
저처콜 — 200명 콜센터. 직원명부·조직·급여는 회계 프로그램(더존·세무사랑 같은 것)에서 내려받은 CSV, 인센티브는 팀마다 엑셀로 산정.

파일: 직원명부.csv(CP949, 프로그램 내보내기: 코드 열과 이름 열이 짝(부서코드·부서명, 직급코드·직급명, 직책코드·직책명),
              사원코드 6자리 앞자리 0, 성명·부서명 뒤 공백 채움(고정폭 느낌), 재직구분 코드 0/1, 내외국인 코드),
     조직.csv(CP949, 부서코드표 내보내기: 사용여부·정렬순서),
     급여지급내역_2026-12.csv(CP949, 한 줄 = 사원 × 급여 항목 (세로로 긴 항목코드 형식), 금액 앞 공백 채움, 0원 항목은 줄 없음),
     인센티브산정_2026-12.xlsx(팀마다 시트: 콜수·근무일수·품질·출근율 → 종합점수 → VLOOKUP 등급·인센티브, 등급표 시트).
급여 규칙: 통상시급 = (기본급+직책수당)/209 원 미만 버림, 연장 1.5, 식대 20만 비과세, 인센티브 = 산정표 금액(지급일 재직자만), 4대보험 10원 미만 버림.
          달 중간 입사자는 기본급·직책수당·식대를 재직 달력일/그달 일수로 일할(10원 미만 버림), 입사 월 국민연금·건강보험(장기요양)은 없음
          (1일 입사가 아니면 다음 달부터), 고용보험은 그달 보수로 공제.
"""
import datetime as dt

from hrfiles.common import Accidents
from hrfiles.xl import ROUND, ROUNDDOWN, XlErr, Book, vlookup_approx

from . import base as B

CID, NAME = "jeocheocall", "저처콜"
PAY_FILE, INC_FILE = "급여지급내역_2026-12.csv", "인센티브산정_2026-12.xlsx"
TEAMS = [("1100", "운영지원팀", 6), ("1200", "QA팀", 5), ("1300", "교육팀", 4), ("2100", "인바운드1팀", 70), ("2200", "인바운드2팀", 64),
         ("2300", "아웃바운드팀", 50)]
CALL_TEAMS = ["인바운드1팀", "인바운드2팀", "아웃바운드팀"]
GRADES = {"900": "센터장", "330": "과장", "320": "대리", "310": "사원", "220": "팀장", "210": "파트장", "120": "선임상담사", "110": "상담사"}
DUTIES = {"01": "센터장", "02": "팀장", "03": "파트장", "00": ""}
PAYS = {"900": (5_500_000, 500_000), "330": (3_500_000, 0), "320": (3_000_000, 0), "310": (2_500_000, 0), "220": (3_300_000, 300_000),
        "210": (2_600_000, 100_000), "120": (2_300_000, 0), "110": (B.MIN_WAGE[2026] * 209, 0)}
DEPT_CODE = {t: c for c, t, _n in TEAMS}
# 항목코드표
ITEMS = [("1", "P01", "기본급", "basePay"), ("1", "P02", "직책수당", "dutyAlw"), ("1", "P05", "식대", "meal"), ("1", "P11", "연장수당", "otPay"),
         ("1", "P21", "인센티브", "incentive"), ("2", "D01", "국민연금", "np"), ("2", "D02", "건강보험", "hi"), ("2", "D03", "장기요양보험", "ltc"),
         ("2", "D04", "고용보험", "ei"), ("2", "D11", "소득세", "tax"), ("2", "D12", "지방소득세", "ltax")]
GRADE_TABLE = [(0, "D", 0), (60, "C", 50_000), (70, "B", 150_000), (80, "A", 300_000), (90, "S", 500_000)]


def people(df, used, seed):
    rng = B.rng_for(CID, "people", seed)
    total = 1 + sum(n for *_x, n in TEAMS)
    w = lambda o: 4.0 if B.has(o, "상담", "고객", "콜", "텔레마케", "안내", "판매", "사무") else 1.0  # noqa: E731
    picked = sorted(B.pick(df, used, total, rng, CID, w, ages=(22, 59)), key=lambda p: (-p["age"], p["uuid"]))
    head, rest = picked[0], picked[1:]
    rng.shuffle(rest)
    emps, k = [], 0
    emps.append({"p": head, "dept": "운영지원팀", "grade": "900", "duty": "01"})
    for code, team, n in TEAMS:
        group = sorted(rest[k:k + n], key=lambda p: (-p["age"], p["uuid"]))
        k += n
        for j, p in enumerate(group):
            if team in CALL_TEAMS:
                g, d = ("220", "02") if j == 0 else ("210", "03") if j <= n // 15 else ("120", "00") if p["age"] >= 33 else ("110", "00")
            else:
                g, d = ("330", "02") if j == 0 else ("320", "00") if p["age"] >= 33 else ("310", "00")
            emps.append({"p": p, "dept": team, "grade": g, "duty": d})
    for e in emps:
        p = e.pop("p")
        age = int(p["age"])
        e.update(name=p["name"], birthDate=B.birth_of(age, rng), sex=p["sex"], age=age, mobile=B.phone(rng),
                 hireDate=min(dt.date(2026, 12, 1) - dt.timedelta(days=rng.randint(40, 2500 if e["grade"] in ("110", "120") else 4000)),
                              dt.date(2026, 11, 2)),
                 resignDate=None, empType="정규직" if e["grade"] not in ("110",) or rng.random() < 0.6 else "계약직",
                 family=rng.choice([1, 1, 1, 2, 3]))
        e["basePay"], e["dutyAlw"] = PAYS[e["grade"]]
        if e["duty"] == "02" and e["grade"] == "330":  # 지원부서 팀장(과장)도 직책수당
            e["dutyAlw"] = 200_000
        if e["grade"] in ("110", "120", "310", "320"):
            e["basePay"] += rng.randint(0, 5) * 10_000 if e["grade"] != "110" else 0
    # 콜센터는 이직이 많다: 올해 퇴직 14명 (11/30 퇴직 3명 포함)
    agents = sorted([e for e in emps if e["grade"] == "110"], key=lambda e: (e["name"], e["mobile"]))
    rng.shuffle(agents)
    for n, e in enumerate(agents[:14]):
        e["resignDate"] = dt.date(2026, 11, 30) if n < 3 else dt.date(2026, rng.randint(2, 10), rng.randint(1, 28))
        e["hireDate"] = min(e["hireDate"], e["resignDate"] - dt.timedelta(days=60))
    newbie = agents[14]
    newbie["hireDate"] = dt.date(2026, 12, 28)  # 교육 입과, 콜 0
    emps.sort(key=lambda e: (e["hireDate"], e["name"]))
    for n, e in enumerate(emps, 1):
        e["empNo"] = "%06d" % (n + 300)
        e["email"] = "%s@jeocheo-call.co.kr" % e["empNo"] if e["grade"] not in ("110", "120") or n % 3 == 0 else None
        e["status"] = "1" if e["resignDate"] else "0"
    return emps


# ================================================================ 인센티브 산정

def score_of(calls, days, quality, att):
    """종합점수 = ROUND(MIN(콜/일 ÷ 100, 1.2) × 35 + 품질 × 0.3 + 출근율 × 20, 1). 근무일수 0이면 #DIV/0!"""
    if days == 0:
        return XlErr("#DIV/0!")
    per = calls / days
    return ROUND(min(per / 100, 1.2) * 35 + quality * 0.3 + att * 20, 1), per


def _on_half(calls, days, quality, att):
    from fractions import Fraction as F
    v = min(F(calls, days) / 100, F(6, 5)) * 35 + F(quality) * F(3, 10) + F(str(att)) * 20
    return (v * 100).denominator == 1 and (v * 100) % 10 == 5


def write_incentive(path, emps, acc, rng):
    A = Accidents("JEOC-INC", path.name)
    book = Book(path)
    sheets = {t: book.sheet(t) for t in CALL_TEAMS}
    gt = book.sheet("등급표")
    gt.put("A", 1, "종합점수 등급·인센티브표 (2026 하반기)", "title")
    for c, h in zip("ABC", ["점수 이상", "등급", "인센티브"]):
        gt.put(c, 2, h, "head")
    for n, (lo, g, amt) in enumerate(GRADE_TABLE):
        gt.put("A", 3 + n, lo)
        gt.put("B", 3 + n, g)
        gt.put("C", 3 + n, amt, "won")
    gt.put("A", 9, "※ 상한 50만원. 팀장 재량 가산 불가")
    full, short = "등급표!$A$3:$C$7", "등급표!$A$3:$C$6"
    cols = ["사원코드", "성명", "콜수", "근무일수", "콜/일", "품질점수", "출근율", "종합점수", "등급", "인센티브", "비고"]
    meta, result = {}, {}
    for team in CALL_TEAMS:
        sh = sheets[team]
        sh.put("A", 1, "%s 12월 인센티브 산정" % team, "title")
        sh.put("A", 2, "평가기간 2026-12-01 ~ 12-31 / 지급 2027-01-10 급여 / 대상: 상담사·선임상담사 (지급일 재직자)")
        for c, h in zip("ABCDEFGHIJK", cols):
            sh.put(c, 3, h, "head")
        members = sorted([e for e in emps if e["dept"] == team and e["grade"] in ("110", "120") and not e["resignDate"]], key=lambda e: e["empNo"])
        r, row_of = 4, {}
        for e in members:
            days = 0 if e["hireDate"] >= dt.date(2026, 12, 28) else rng.randint(17, 22)
            calls = days * rng.randint(70, 125) if team != "아웃바운드팀" else days * rng.randint(60, 110)
            quality = rng.randint(68, 100)
            att = round(rng.uniform(0.85, 1.0), 2)
            if e["empNo"] in acc["stars"]:  # 우수 상담사 (S등급)
                calls, quality, att = days * rng.randint(118, 130), rng.randint(97, 100), 1.0
            while days and _on_half(calls, days, quality, att):  # ROUND(…,1) 경계(정확히 x.x5)는 계산 도구마다 결과가 달라 피한다
                calls += 1  # 품질·출근율을 바꿔서는 끝자리 5가 그대로라 콜수를 바꾼다
            sc = score_of(calls, days, quality, att)
            rng_ = short if team == acc["shortTeam"] else full
            if isinstance(sc, XlErr):
                score, per = sc, sc
                grade = amt = sc
            else:
                score, per = sc
                grade = vlookup_approx(score, [(lo, g) for lo, g, _a in (GRADE_TABLE if rng_ == full else GRADE_TABLE[:4])], 2)
                amt = vlookup_approx(score, [(lo, a) for lo, _g, a in (GRADE_TABLE if rng_ == full else GRADE_TABLE[:4])], 2)
            ideal_amt = None if isinstance(score, XlErr) else vlookup_approx(score, [(lo, a) for lo, _g, a in GRADE_TABLE], 2)
            sh.put("A", r, e["empNo"], "text")
            sh.put("B", r, e["name"])
            sh.put("C", r, calls)
            sh.put("D", r, days)
            sh.formula("E", r, "=C%d/D%d" % (r, r), per if isinstance(per, XlErr) else per, "num")
            sh.put("F", r, quality)
            sh.put("G", r, att, "num")
            sh.formula("H", r, "=ROUND(MIN(E%d/100,1.2)*35+F%d*0.3+G%d*20,1)" % (r, r, r), score, "num")
            sh.formula("I", r, "=VLOOKUP(H%d,%s,2,TRUE)" % (r, rng_), grade)
            memo = None
            if acc["cap"] == e["empNo"]:
                amt = 650_000
                memo = "팀장 재량 +15만"
                sh.put("J", r, amt, "won")
            else:
                sh.formula("J", r, "=VLOOKUP(H%d,%s,3,TRUE)" % (r, rng_), amt, "won")
            if days == 0:
                memo = "12/28 입사 (교육)"
            if memo:
                sh.put("K", r, memo)
            row_of[e["empNo"]] = r
            ideal_grade = None if isinstance(score, XlErr) else vlookup_approx(score, [(lo, g) for lo, g, _a in GRADE_TABLE], 2)
            result[e["empNo"]] = {"actual": amt, "ideal": ideal_amt, "grade": grade, "idealGrade": ideal_grade, "score": score, "team": team}
            r += 1
        sh.ws.freeze_panes(3, 2)
        sh.widths({"B": 8, "K": 18})
        meta[team] = {"role": "incentiveCalc", "headerRows": [3], "firstDataRow": 4, "lastDataRow": r - 1, "titleRows": [1, 2], "rowOf": row_of}
    book.close()
    t = acc["shortTeam"]
    hit = sorted((no for no, v in result.items() if v["team"] == t and v["actual"] != v["ideal"]), key=lambda x: meta[t]["rowOf"][x])
    A.add("LOOKUP_RANGE_SHORT", t, "%s 시트 등급·인센티브 VLOOKUP 범위가 등급표!$A$3:$C$6 — S등급(90점 이상) 행이 빠져 %d명이 A등급 30만원으로 계산"
          % (t, len(hit)), ["FORMULA_READ", "CROSS_SHEET_LOOKUP", "RECOMPUTE_CHECK"], empNos=hit, rows=[meta[t]["rowOf"][x] for x in hit],
          cells=[c % meta[t]["rowOf"][x] for x in hit for c in ("I%d", "J%d")], formulaCells=["I4:J%d" % meta[t]["lastDataRow"]],
          formula={"actual": "=VLOOKUP(H4,등급표!$A$3:$C$6,3,TRUE)", "expected": "=VLOOKUP(H4,등급표!$A$3:$C$7,3,TRUE)"},
          values={x: {"등급": {"actual": result[x]["grade"], "expected": result[x]["idealGrade"]},
                      "인센티브": {"actual": result[x]["actual"], "expected": result[x]["ideal"]}} for x in hit})
    nb = acc["newbie"]
    tn = result[nb]["team"]
    rn = meta[tn]["rowOf"][nb]
    A.add("DIV_ZERO_ERROR", tn, "12/28 입사 교육생 근무일수 0 → 콜/일 =C/D가 #DIV/0!, 종합점수·등급·인센티브까지 #DIV/0! (IFERROR 없음). "
          "규정상 산정 대상이 아니므로 0 또는 빈 칸이어야 함",
          ["ERROR_VALUE"], empNo=nb, rowNo=rn, cells=["%s%d" % (c, rn) for c in "EHIJ"],
          values={h: {"actual": "#DIV/0!", "expected": None} for h in ("콜/일", "종합점수", "등급", "인센티브")})
    cp = acc["cap"]
    tc = result[cp]["team"]
    A.add("CAP_EXCEEDED", tc, "인센티브 칸에 수식 대신 650,000 입력 (비고 '팀장 재량 +15만') — 등급표 상한 50만원 초과",
          ["HARDCODED_IN_FORMULA_COLUMN", "CROSS_FIELD_RULE"], empNo=cp, rowNo=meta[tc]["rowOf"][cp], cells=["J%d" % meta[tc]["rowOf"][cp]],
          values={"인센티브": {"actual": 650_000, "expected": result[cp]["ideal"], "cap": 500_000}})
    used = {t, tn, tc}
    key = {"file": path.name, "kind": "INCENTIVE", "closestEntity": None, "supported": False, "period": "2026-12",
           "sheetOrder": CALL_TEAMS + ["등급표"],
           "sheets": {**meta, "등급표": {"role": "lookupTable", "range": "A3:C7", "values": [list(x) for x in GRADE_TABLE]}},
           "expectedMapping": {"incentiveCalc": {"사원코드": "empNo", "성명": "name", "콜수": "calls", "근무일수": "workDays", "콜/일": "callsPerDay",
                                                 "품질점수": "qaScore", "출근율": "attendanceRate", "종합점수": "score", "등급": "grade",
                                                 "인센티브": "incentive", "비고": "memo"}},
           "rules": {"score": "ROUND(MIN(콜/일÷100,1.2)×35 + 품질×0.3 + 출근율×20, 1)", "grade": "VLOOKUP(점수, 등급표 A3:C7, TRUE)",
                     "cap": 500_000, "eligibility": "상담사·선임상담사, 지급일 재직자"},
           "accidents": A.items, "cleanSheets": [x for x in CALL_TEAMS if x not in used],
           "crossFile": {"pay": {"file": PAY_FILE, "detail": "P21 인센티브 = 인센티브 열 (오류값이면 지급 안 함)"}}}
    return key, result


# ================================================================ 급여지급내역 (세로 항목 형식)

MONTH_DAYS = 31


def hire_ratio(e):
    """12월 중간 입사자의 (재직 달력일, 그달 일수). 1일 이전 입사면 None (일할 없음)"""
    h = e["hireDate"]
    return (MONTH_DAYS - h.day + 1, MONTH_DAYS) if h.year == 2026 and h.month == 12 and h.day > 1 else None


def pay_calc(e, ot_h, incentive, ratio=None):
    v = {"basePay": e["basePay"], "dutyAlw": e["dutyAlw"], "meal": 200_000}
    if ratio:  # 입사 월 일할 (10원 미만 버림)
        v = {k: ROUNDDOWN(x * ratio[0] / ratio[1], -1) for k, x in v.items()}
    hourly = ROUNDDOWN((e["basePay"] + e["dutyAlw"]) / 209, 0)
    v["otPay"] = ROUND(hourly * ot_h * 1.5, 0)
    v["incentive"] = incentive or 0
    gross = sum(v.values())
    taxable = gross - v["meal"]
    R = B.RATES_2026
    v["np"] = 0 if ratio else ROUNDDOWN(min(max(ROUNDDOWN(taxable, -3), B.NP_FLOOR), B.NP_CAP) * R["np"], -1)
    v["hi"] = 0 if ratio else ROUNDDOWN(taxable * R["hi"], -1)
    v["ltc"] = ROUNDDOWN(v["hi"] * R["ltc"], -1)
    v["ei"] = ROUNDDOWN(taxable * R["ei"], -1)
    v["tax"] = B.tax_of(taxable, e["family"])
    v["ltax"] = ROUNDDOWN(v["tax"] * 0.1, -1)
    v["_gross"], v["_hourly"], v["_taxable"] = gross, hourly, taxable
    return v


PAY_HEAD = ["귀속연월", "지급연월일", "사원코드", "사원명", "부서코드", "지급공제구분", "항목코드", "항목명", "금액"]


def write_pay(path, emps, inc, acc, rng):
    A = Accidents("JEOC-PAY", path.name)
    lines, tags = [], []
    line_of, calc_of = {}, {}  # (사번, 항목) → 줄 위치 / 사번 → (사원, 연장시간, 일할, 계산값)
    active = [e for e in emps if not e["resignDate"] and e["hireDate"] <= dt.date(2026, 12, 31)]
    for e in active + [x for x in emps if x["empNo"] == acc["resigned"]]:
        resigned = e["empNo"] == acc["resigned"]
        if resigned:
            v = {"incentive": 300_000}
            items = [x for x in ITEMS if x[3] == "incentive"]
        else:
            ot_h = rng.choice([0, 0, 0, 2, 4, 6, 8, 10]) if e["dept"] in CALL_TEAMS else rng.choice([0, 0, 4])
            ratio = hire_ratio(e)
            if ratio:
                ot_h = 0  # 12/28 입사 교육생: 연장 없음 (난수는 그대로 소비)
            i = inc.get(e["empNo"])
            amt = i["actual"] if i else 0
            if isinstance(amt, XlErr):
                amt = 0
            if e["empNo"] == acc["amountTypo"][0]:
                amt = acc["amountTypo"][1]
            v = pay_calc(e, ot_h, amt, ratio)
            calc_of[e["empNo"]] = (e, ot_h, ratio, v)
            items = ITEMS
        for kind, code, name, k in items:
            amount = v[k]
            if not amount:
                continue  # 0원 항목은 줄 없음
            tag = None
            if k == "ei" and e["empNo"] == acc["eiMissing"]:
                tags.append(("EIMISS", e, None, v))
                continue
            if k == "incentive" and e["dept"] == acc["legacyTeam"]:
                code, name, tag = "P31", "인센티브(구)", "LEGACY"
            if resigned:
                tag = "RESIGNED"
            if k == "incentive" and e["empNo"] == acc["amountTypo"][0]:
                tag = "AMOUNT"
            lines.append(["202612", "20270110", e["empNo"], e["name"].ljust(10), DEPT_CODE[e["dept"]], kind, code, name, "%12d" % amount])
            line_of.setdefault((e["empNo"], k), len(lines) - 1)
            tags.append((tag, e, len(lines) - 1, v))
            if k == "incentive" and e["empNo"] == acc["dup"]:
                lines.append(list(lines[-1]))
                tags.append(("DUP", e, len(lines) - 1, v))
    first = B.write_csv(path, PAY_HEAD, lines, "cp949")
    sheet = path.stem

    def diff_lines(no, incentive):
        """사원 no의 인센티브가 incentive였다면 금액이 달라지는 줄 (인센티브 줄 + 4대보험·세금 줄). 금액 열 = I"""
        e, ot_h, ratio, v = calc_of[no]
        w = pay_calc(e, ot_h, incentive, ratio)
        out = []
        for _kd, _code, _nm, k in ITEMS:
            if v[k] == w[k]:
                continue
            n = line_of.get((no, k))
            assert n is not None, "금액이 바뀌는 항목의 줄이 없음"
            out.append({"sheet": sheet, "rowNo": first + n, "cells": ["I%d" % (first + n)],
                        "values": {"금액": {"actual": v[k], "expected": w[k], "항목코드": lines[n][6]}}})
        return out

    legacy = [(e, first + n) for t, e, n, _v in tags if t == "LEGACY"]
    A.add("LEGACY_ITEM_CODE", sheet, "%s 인센티브 %d줄이 항목코드 개편 전 코드 'P31 인센티브(구)' (코드표는 P21) — 팀 담당자가 옛 업로드 양식 사용"
          % (acc["legacyTeam"], len(legacy)), ["ENUM_CHECK", "CROSS_FIELD_RULE"], empNos=[e["empNo"] for e, _r in legacy], rows=[r for _e, r in legacy],
          cells=["G%d" % r for _e, r in legacy], values={"항목코드": {"actual": "P31", "expected": "P21"}})
    for t, e, n, v in tags:
        if t == "DUP":
            A.add("DUPLICATE_ROW", sheet, "같은 사원·같은 항목(P21 인센티브) 줄이 두 번 → 인센티브 이중 지급 (공제 줄은 인센티브 한 번 기준이라 그대로)",
                  ["DUPLICATE_DETECT"], empNo=e["empNo"],
                  rowNo=first + n, affected=[{"sheet": sheet, "rowNo": first + n - 1}, {"sheet": sheet, "rowNo": first + n}])
        elif t == "AMOUNT":
            cas = diff_lines(e["empNo"], inc[e["empNo"]]["actual"])
            A.add("AMOUNT_MISMATCH_CROSS_FILE", sheet, "P21 인센티브 %s원 — 인센티브산정 시트는 %s원. 공제(국민연금·건강·장기요양·고용·소득세·지방세) 줄도 %s원 기준으로 계산돼 %d줄이 같이 틀림"
                  % (format(acc["amountTypo"][1], ","), format(inc[e["empNo"]]["actual"], ","), format(acc["amountTypo"][1], ","), len(cas) - 1),
                  ["CROSS_FILE_MATCH"], empNo=e["empNo"], rowNo=first + n, cells=["I%d" % (first + n)],
                  values={"금액": {"actual": acc["amountTypo"][1], "expected": inc[e["empNo"]]["actual"], "항목코드": "P21"}},
                  affected=cas)
        elif t == "RESIGNED":
            A.add("RESIGNED_STILL_PAID", sheet, "11/30 퇴직자(직원명부 재직구분 1)에게 12월 귀속 인센티브 30만원 — 회사 규정은 지급일 재직자만",
                  ["CROSS_FILE_MATCH"], empNo=e["empNo"], rowNo=first + n, cells=["I%d" % (first + n)],
                  values={"금액": {"actual": 300_000, "expected": 0, "항목코드": "P21"}})
        elif t == "EIMISS":
            near = [first + m for tt, ee, m, _v in tags if ee is e and m is not None]
            A.add("ITEM_ROW_MISSING", sheet, "고용보험(D04) 줄이 없음 — 과세 급여 %s원이면 %s원 공제돼야 함 (다른 공제 줄은 있음)"
                  % (format(v["_taxable"], ","), format(v["ei"], ",")), ["ITEM_COMPLETENESS", "RECOMPUTE_CHECK"], empNo=e["empNo"],
                  rows=near, values={"고용보험": {"actual": None, "expected": v["ei"], "항목코드": "D04"}})
    # 인센티브산정 파일 사고(산정 금액 자체가 틀림)가 이 파일 줄로 옮겨진 것: 산정표 값 그대로 지급·공제됐고 참값은 등급표 기준
    cross = {no: [{"file": path.name, **x} for x in diff_lines(no, r["ideal"])]
             for no, r in inc.items() if no in calc_of and r["ideal"] is not None and r["actual"] != r["ideal"]}
    newbie = next((e for e, _o, ratio, _v in calc_of.values() if ratio), None)
    return {"file": path.name, "kind": "PAYROLL", "closestEntity": "PAY_RESULT_HISTORY", "supported": False, "format": "csv", "encoding": "cp949",
            "sheetOrder": [sheet],
            "sheets": {sheet: {"role": "payItemLong", "headerRows": [1], "firstDataRow": first, "lastDataRow": first + len(lines) - 1,
                               "note": "CSV라 시트가 없음. 한 줄 = 사원 × 항목"}},
            "expectedMapping": {"payItemLong": {"귀속연월": "payMonth", "지급연월일": "payDate", "사원코드": "empNo", "사원명": "name", "부서코드": "deptCode",
                                                "지급공제구분": "itemKind", "항목코드": "itemCode", "항목명": "itemName", "금액": "amount"}},
            "codeTable": {code: {"kind": "지급" if kd == "1" else "공제", "name": nm, "field": k} for kd, code, nm, k in ITEMS},
            "rules": {"layout": "세로 항목 형식 (사원마다 항목 수만큼 줄), 0원 항목은 줄을 만들지 않음",
                      "ordinaryHourlyWage": {"expr": "floor((basePay+dutyAllowance)/209)"}, "overtime": "ROUND(통상시급 × 연장 × 1.5)",
                      "incentive": "인센티브산정 파일 금액, 지급일(2027-01-10) 재직자만", "insuranceRounding": "floor10", "mealInInsuranceBase": False,
                      "hireMonthProration": "달 중간 입사: 기본급·직책수당·식대 × 재직 달력일 / 그달 일수, 10원 미만 버림",
                      "hireMonthInsurance": "1일 입사가 아니면 입사 월 국민연금·건강보험(장기요양) 없음 (다음 달부터), 고용보험은 그달 보수 × 0.9%"},
            "accidents": A.items,
            "legitUnusual": ["연장수당·직책수당이 0인 사원은 그 항목 줄이 없음 (정상)", "교육생(12/28 입사)은 인센티브 줄 없음 (산정표가 #DIV/0!)",
                             "%s(12/28 입사): 기본급·식대 4/31 일할, 국민연금·건강보험·장기요양 줄 없음, 소득세 0이라 소득세·지방세 줄 없음 (회사 규칙대로)"
                             % (newbie["empNo"] if newbie else "-")],
            "valueNoise": [{"type": "FIXED_WIDTH_PADDING", "detail": "사원명 뒤 공백 채움 10자, 금액 앞 공백 채움 12자 ('     2156880')"},
                           {"type": "CODE_DATES", "detail": "귀속연월 '202612', 지급연월일 '20270110' (구분자 없는 숫자 문자)"}],
            "crossFile": {"incentive": {"file": INC_FILE, "detail": "P21 = 인센티브 열"}, "roster": {"file": "직원명부.csv", "detail": "재직구분·퇴사일자"}}}, cross


# ================================================================ 직원명부·조직 (프로그램 내보내기)

ROSTER_COLS = [("empNo", "사원코드"), ("name", "사원명"), ("_deptCode", "부서코드"), ("dept", "부서명"), ("grade", "직급코드"), ("position", "직급명"),
               ("_dutyCode", "직책코드"), ("duty", "직책명"), ("hireDate", "입사일자"), ("resignDate", "퇴사일자"), ("birthDate", "생년월일"),
               ("_foreign", "내외국인"), ("_rrn", "주민번호"), ("mobile", "휴대폰"), ("email", "E-Mail"), ("empType", "고용구분"), ("status", "재직구분")]


def write_roster(cdir, emps, reserved, seed):
    rng = B.rng_for(CID, "roster", seed)
    errs = B.RosterErrors()
    rows = [{"empNo": e["empNo"], "name": e["name"], "_deptCode": DEPT_CODE[e["dept"]], "dept": e["dept"], "grade": e["grade"],
             "position": GRADES[e["grade"]], "_dutyCode": e["duty"], "duty": DUTIES[e["duty"]], "hireDate": e["hireDate"],
             "resignDate": e["resignDate"], "birthDate": e["birthDate"], "_foreign": "0", "_rrn": B.masked_rrn(e["birthDate"], e["sex"]),
             "mobile": e["mobile"], "email": e["email"], "empType": e["empType"], "status": e["status"], "_e": e} for e in emps]
    idx = list(range(len(rows)))
    rng.shuffle(idx)
    take = lambda k, cond=lambda r: True: [idx.pop(idx.index(i)) for i in [j for j in idx if cond(rows[j]) and rows[j]["empNo"] not in reserved][:k]]  # noqa: E731
    for i in take(2, lambda r: r["status"] == "0"):
        rows[i]["hireDate"] = None
        errs.add(i, "hireDate", "REQUIRED", "입사일자 빈 칸")
    for i in take(2, lambda r: r["email"]):
        rows[i]["email"] = rows[i]["email"].split("@")[0] + "@"
        errs.add(i, "email", "INVALID_EMAIL", "이메일 도메인 없음: %s" % rows[i]["email"])
    i, j = take(2, lambda r: r["status"] == "0" and r["dept"] not in CALL_TEAMS)
    rows[j]["empNo"] = rows[i]["empNo"]
    errs.add(i, "empNo", "DUPLICATE_KEY", "사원코드 중복 (다른 사람)")
    errs.add(j, "empNo", "DUPLICATE_KEY", "사원코드 중복 — 이 행의 실제 사원코드는 %s" % rows[j]["_e"]["empNo"])
    for i in take(1, lambda r: r["status"] == "0"):
        rows[i]["status"] = "2"
        errs.add(i, "status", "INVALID_ENUM", "재직구분 '2' (코드: 0 재직, 1 퇴직)")
    for i in take(1, lambda r: r["status"] == "1" and r["hireDate"]):
        rows[i]["resignDate"] = rows[i]["hireDate"] - dt.timedelta(days=rng.randint(5, 90))
        errs.add(i, "resignDate", "DATE_ORDER", "퇴사일자 < 입사일자")
    for i in take(1, lambda r: r["grade"] == "110"):
        rows[i]["position"] = "선임상담사"
        errs.add(i, "position", "CODE_NAME_MISMATCH", "직급코드 110(상담사)인데 직급명 '선임상담사' — 코드·이름 짝이 안 맞음", detectable=False)
    for i in take(1, lambda r: r["grade"] == "120"):
        rows[i]["position"] = "선임상담시"
        errs.add(i, "position", "POSITION_TYPO", "직급명 오타 '선임상담시'", detectable=False)
    # 재입사: 예전 사원코드 행이 퇴직 처리 안 됨(오류) + 퇴직 처리된 예전 행(정상 이력) 1명씩
    src = take(2, lambda r: r["status"] == "0" and r["grade"] == "110" and r["hireDate"])
    old = dict(rows[src[0]])
    old.update(empNo="%06d" % (100 + rng.randint(1, 99)), hireDate=dt.date(2023, 3, 6), resignDate=None, status="0")
    rows.append(old)
    errs.add(src[0], "name", "DUPLICATE_SUSPECTED", "같은 사람(성명·생년월일·주민번호 같음)이 예전 사원코드 %s로도 재직(0)" % old["empNo"])
    errs.add(len(rows) - 1, "name", "DUPLICATE_SUSPECTED", "재입사 전 행이 퇴직 처리되지 않음 (새 사원코드 %s)" % rows[src[0]]["empNo"])
    hist = dict(rows[src[1]])
    hist.update(empNo="%06d" % (200 + rng.randint(1, 99)), hireDate=dt.date(2022, 9, 5), resignDate=dt.date(2023, 8, 31), status="1")
    rows.append(hist)
    legit = len(rows) - 1
    for r in rows:
        for f in ("hireDate", "birthDate", "resignDate"):
            r[f] = B.fmt_date(r[f], "iso")
        r["name"] = r["name"].ljust(10)
        r["dept"] = r["dept"].ljust(12)
    B.date_format_errors(rows, ("hireDate", "birthDate", "resignDate"), errs)
    first = B.write_csv(cdir / "직원명부.csv", [h for _, h in ROSTER_COLS], [[r.get(f) for f, _ in ROSTER_COLS] for r in rows], "cp949")
    return {"mapping": {h: (None if f.startswith("_") else f) for f, h in ROSTER_COLS}, "errors": errs.rows(first), "rows": len(rows),
            "layout": {"encoding": "cp949", "firstDataRow": first,
                       "codeColumns": {"부서코드": "조직.csv 부서코드", "직급코드": {k: v for k, v in GRADES.items()},
                                       "직책코드": {k: (v or "(없음)") for k, v in DUTIES.items()}, "재직구분": {"0": "재직", "1": "퇴직"},
                                       "내외국인": {"0": "내국인", "1": "외국인"}},
                       "notes": ["사원코드 6자리 앞자리 0 ('000301') — 엑셀로 열면 0이 사라짐", "사원명·부서명 뒤 공백 채움 (고정폭 내보내기)",
                                 "재직구분이 코드 0/1", "상담사 다수는 이메일 빈 칸 (오류 아님)"],
                       "legitUnusual": [{"rowNo": first + legit, "detail": "재입사자의 예전 사원코드 행 — 퇴직(1) 처리돼 있어 이력으로 정상"}]}}


def write_org(cdir):
    org = [{"deptCode": "1000", "name": "저처콜 고객센터", "parentDeptCode": None, "_use": "Y", "_sort": 1},
           {"deptCode": "1900", "name": "지원본부", "parentDeptCode": "1000", "_use": "Y", "_sort": 2},
           {"deptCode": "2000", "name": "상담본부", "parentDeptCode": "1000", "_use": "Y", "_sort": 3}]
    for n, (code, team, _c) in enumerate(TEAMS):
        org.append({"deptCode": code, "name": team, "parentDeptCode": "1900" if code < "2000" else "2000", "_use": "Y", "_sort": 10 + n})
    org.append({"deptCode": "2400", "name": "해피콜팀", "parentDeptCode": "2000", "_use": "N", "_sort": 99})
    cols = [("deptCode", "부서코드"), ("name", "부서명"), ("parentDeptCode", "상위부서코드"), ("_use", "사용여부"), ("_sort", "정렬순서")]
    B.write_org(cdir / "조직.csv", org, cols, "cp949")
    return org, cols


def build(df, used, out_dir, seed):
    company = {"id": CID, "name": NAME, "industry": "콜센터",
               "profile": "200명 콜센터, 회계 프로그램 내보내기 CSV(코드 열 짝·고정폭 공백·앞자리 0 사원코드), 세로 항목코드 급여내역, 팀별 인센티브 산정 엑셀(VLOOKUP 등급표)"}
    cdir = out_dir / CID
    cdir.mkdir(parents=True, exist_ok=True)
    emps = people(df, used, seed)
    rng = B.rng_for(CID, "plan", seed)
    agents = sorted([e for e in emps if e["grade"] in ("110", "120") and not e["resignDate"] and e["hireDate"] < dt.date(2026, 12, 1)],
                    key=lambda e: e["empNo"])
    newbie = next(e for e in emps if e["hireDate"] == dt.date(2026, 12, 28))
    cap_e = rng.choice([e for e in agents if e["dept"] == "인바운드1팀"])
    stars = [e["empNo"] for e in rng.sample([e for e in agents if e["dept"] == "인바운드2팀"], 3)] +         [e["empNo"] for e in rng.sample([e for e in agents if e["dept"] == "아웃바운드팀"], 1)]
    acc_i = {"shortTeam": "인바운드2팀", "newbie": newbie["empNo"], "cap": cap_e["empNo"], "stars": stars}
    inc_key, inc = write_incentive(cdir / INC_FILE, emps, acc_i, B.rng_for(CID, "kpi", seed))
    out_team = [e for e in agents if e["dept"] == "아웃바운드팀" and inc[e["empNo"]]["actual"] and not isinstance(inc[e["empNo"]]["actual"], XlErr)]
    typo_e, dup_e, ei_e = rng.sample(out_team, 3)
    resigned = sorted(e["empNo"] for e in emps if e["resignDate"] == dt.date(2026, 11, 30))[0]
    acc_p = {"legacyTeam": "인바운드2팀", "dup": dup_e["empNo"], "amountTypo": (typo_e["empNo"], inc[typo_e["empNo"]]["actual"] * 2),
             "resigned": resigned, "eiMissing": ei_e["empNo"]}
    pay_key, cross = write_pay(cdir / PAY_FILE, emps, inc, acc_p, B.rng_for(CID, "pay", seed))
    # 산정표 사고가 급여지급내역으로 옮겨진 줄 (인센티브 줄 + 그 금액으로 계산된 공제 줄)
    for a in inc_key["accidents"]:
        nos = a.get("empNos") or [a.get("empNo")]
        cf = [x for no in nos for x in cross.get(no, [])]
        if cf:
            a["crossFileAffected"] = cf
    inc01 = next(a for a in inc_key["accidents"] if a["type"] == "LOOKUP_RANGE_SHORT")
    pay01 = next(a for a in pay_key["accidents"] if a["type"] == "LEGACY_ITEM_CODE")
    both = [x for x in inc01["crossFileAffected"] if x["values"]["금액"]["항목코드"] == "P31"]
    sheet = PAY_FILE.rsplit(".", 1)[0]
    inc01["overlaps"] = [{"with": pay01["id"], "cells": ["%s!I%d" % (sheet, x["rowNo"]) for x in both],
                          "note": "%s(VLOOKUP 범위) 대상 %d명의 급여 인센티브 줄은 항목코드 P31(%s)이기도 함" % (inc01["id"], len(both), pay01["id"]),
                          "trueValue": {"항목코드": "P21", "금액": both[0]["values"]["금액"]["expected"]}}]
    pay01["overlaps"] = [{"with": inc01["id"], "cells": ["%s!G%d" % (sheet, x["rowNo"]) for x in both] + ["%s!I%d" % (sheet, x["rowNo"]) for x in both],
                          "note": "P31 줄 중 %d줄은 인센티브산정 %s 대상이라 금액도 30만원 → 50만원이어야 함 (공제 줄도 같이 바뀜)" % (len(both), inc01["id"]),
                          "trueValue": {"항목코드": "P21", "금액": both[0]["values"]["금액"]["expected"]}}]
    reserved = {newbie["empNo"], cap_e["empNo"], typo_e["empNo"], dup_e["empNo"], ei_e["empNo"], resigned}
    roster = write_roster(cdir, emps, reserved, seed)
    org, org_cols = write_org(cdir)
    return B.answer_key(company, "직원명부.csv", "조직.csv", roster["mapping"], org_cols, roster["errors"],
                        {"employees": len(emps), "rosterRows": roster["rows"], "orgs": len(org)},
                        {PAY_FILE: pay_key, INC_FILE: inc_key}, {"rosterLayout": roster["layout"]})
