"""
퇴직금추계표.xlsx — 2026-12-31 기준 재직자의 예상 퇴직금.

퇴직금 = 1일 평균임금 × 30 × 근속일수 / 365 (1년 미만은 대상 아님, 평균임금이 통상임금보다 적으면 통상임금).
1일 평균임금 = 최근 3개월(10~12월) 임금 총액 / 그 기간 일수(92일). 3개월 임금은 담당자가 급여대장 지급계를 옮겨 적은 값이라
급여대장 사고(SUM 범위 누락 등)가 그대로 따라온다 (정답지에 기록).
"""
import datetime as dt

from .common import Accidents, clean
from .facts import LEDGER_DATE
from .xl import MAX, ROUND, ROUNDDOWN, TRUNC, Book, XlErr

MONTHS = ["2026-10", "2026-11", "2026-12"]
PERIOD_DAYS = (LEDGER_DATE - dt.date(2026, 10, 1)).days + 1  # 92


def _service_days(hire, ref=LEDGER_DATE):
    return (ref - hire).days + 1


def build(company, roster, facts, out_dir, rng):
    cid = company["id"]
    A = Accidents("%s-SV" % cid[:4].upper(), "퇴직금추계표.xlsx")
    members = sorted([e for e in roster if e["resignDate"] is None and e["hireDate"] <= LEDGER_DATE], key=lambda e: e["empNo"])
    path = out_dir / "퇴직금추계표.xlsx"
    book = Book(path)
    truth, carried, row_of = {}, [], {}

    def wages(e):
        pay = facts[e["empNo"]].get("pay", {})
        act = [pay.get(m, {}).get("grossActual") for m in MONTHS]
        ideal = [pay.get(m, {}).get("gross") for m in MONTHS]
        return act, ideal

    def statutory(e):
        _, ideal = wages(e)
        days = _service_days(e["hireDate"])
        period = min(PERIOD_DAYS, _service_days(e["hireDate"]) if e["hireDate"] > dt.date(2026, 10, 1) else PERIOD_DAYS)
        avg = sum(x or 0 for x in ideal) / period
        hourly = facts[e["empNo"]].get("pay", {}).get("2026-12", {}).get("hourly") or 0
        daily = max(avg, hourly * 8)
        return {"serviceDays": days, "avgDailyWage": round(avg, 2), "ordinaryDailyWage": hourly * 8,
                "severance": 0 if days < 365 else int(daily * 30 * days / 365)}

    under1 = [e for e in members if _service_days(e["hireDate"]) < 365]

    if cid == "ganatech":
        sh = book.sheet("퇴직금추계")
        sh.put("A", 1, "퇴직금 추계표 (기준일 2026-12-31, 단위: 원)", "title")
        heads = ("사번", "성명", "부서", "입사일", "근속일수", "10월 임금", "11월 임금", "12월 임금", "3개월 임금총액", "산정일수", "1일 평균임금", "추계액", "비고")
        fields = ("empNo", "name", "dept", "hireDate", "serviceDays", "wageOct", "wageNov", "wageDec", "wage3m", "periodDays", "avgDailyWage", "severance", "memo")
        for i, h in enumerate(heads):
            sh.put("ABCDEFGHIJKLM"[i], 3, h, "head")
        old_base = set(x["empNo"] for x in rng.sample([e for e in members if e not in under1], 3))
        paid_under = rng.choice(under1) if under1 else None
        for n, e in enumerate(members):
            r = 4 + n
            row_of[e["empNo"]] = r
            act, ideal = wages(e)
            ref = dt.date(2025, 12, 31) if e["empNo"] in old_base else LEDGER_DATE
            days = _service_days(e["hireDate"], ref)
            sh.put("A", r, e["empNo"], "text")
            sh.put("B", r, e["name"])
            sh.put("C", r, e["dept"])
            sh.put("D", r, e["hireDate"])
            sh.formula("E", r, f"=DATE({ref.year},{ref.month},{ref.day})-D{r}+1", days)
            for c, v in zip("FGH", act):
                sh.put(c, r, None if isinstance(v, XlErr) else v, "won")
            total = sum(v for v in act if isinstance(v, (int, float)))
            sh.formula("I", r, f"=SUM(F{r}:H{r})", total, "won")
            sh.formula("J", r, "=DATE(2026,12,31)-DATE(2026,10,1)+1", PERIOD_DAYS)
            avg = ROUND(total / PERIOD_DAYS, 2)
            sh.formula("K", r, f"=ROUND(I{r}/J{r},2)", avg, "num")
            amount = ROUNDDOWN(avg * 30 * days / 365, 0)
            if e is paid_under:
                sh.formula("L", r, f"=ROUNDDOWN(K{r}*30*E{r}/365,0)", amount, "won")
                A.add("UNDER_ONE_YEAR_PAID", sh.name, "근속 %d일(1년 미만)인데 IF 조건이 빠진 수식으로 추계액 %s원이 잡힘" % (days, amount),
                      ["FORMULA_READ", "LEGAL_RULE_COMPARE"], legal=True, empNo=e["empNo"], rowNo=r, cells=["L%d" % r],
                      values={"추계액": {"actual": amount, "expected": 0}})
            else:
                sh.formula("L", r, f"=IF(E{r}<365,0,ROUNDDOWN(K{r}*30*E{r}/365,0))", 0 if days < 365 else amount, "won")
            if e["empNo"] in old_base:
                A.add("BASE_DATE_WRONG", sh.name, "근속일수 수식이 작년 파일의 기준일 DATE(2025,12,31)을 그대로 씀 → 근속 365일 적게",
                      ["FORMULA_READ", "RULE_EXTRACTION", "RECOMPUTE_CHECK"], empNo=e["empNo"], rowNo=r, cells=["E%d" % r],
                      values={"근속일수": {"actual": days, "expected": _service_days(e["hireDate"])}})
            carried += _carried(e, r, act, ideal, "FGH")
            truth[e["empNo"]] = statutory(e)
        rules = {"serviceDays": "DATE(2026,12,31) - 입사일 + 1", "periodDays": 92, "avgDailyWage": "ROUND(3개월 임금/92, 2)",
                 "severance": "IF(근속<365, 0, ROUNDDOWN(평균임금×30×근속일수/365, 0))", "ordinaryWageFloor": False, "bonusIncluded": False}
        legal = [{"rule": "ordinaryWageFloor", "company": "평균임금만 사용 (통상임금 비교 없음)",
                  "statutory": "평균임금이 통상임금보다 적으면 통상임금을 평균임금으로 (근로기준법 제2조 제2항)", "favorableToEmployee": False,
                  "rows": [row_of[k] for k, t in truth.items() if t["ordinaryDailyWage"] > t["avgDailyWage"]]}]
        last = 3 + len(members)
        hr = 3
    elif cid == "daramulsan":
        sh = book.sheet("퇴직급여추계")
        heads = ("사번", "성명", "입사일", "기준일", "근속일수", "근속연수", "3개월임금총액", "평균임금(일)", "통상임금(일)", "적용일급", "추계액")
        fields = ("empNo", "name", "hireDate", "baseDate", "serviceDays", "serviceYears", "wage3m", "avgDailyWage", "ordinaryDailyWage", "appliedDailyWage", "severance")
        for i, h in enumerate(heads):
            sh.put("ABCDEFGHIJK"[i], 1, h, "head")
        old_base = set(x["empNo"] for x in rng.sample([e for e in members if e not in under1], 2))
        for n, e in enumerate(members):
            r = 2 + n
            row_of[e["empNo"]] = r
            act, ideal = wages(e)
            ref = dt.date(2025, 12, 31) if e["empNo"] in old_base else LEDGER_DATE
            days = _service_days(e["hireDate"], ref)
            years = ROUND(days / 365, 2)
            total = sum(v for v in act if isinstance(v, (int, float)))
            ordinary = facts[e["empNo"]].get("pay", {}).get("2026-12", {}).get("hourly", 0) * 8
            avg = total / 90
            applied = MAX(avg, ordinary)
            sh.put("A", r, int(e["empNo"]))
            sh.put("B", r, e["name"])
            sh.put("C", r, e["hireDate"])
            sh.put("D", r, ref)
            sh.formula("E", r, f"=D{r}-C{r}+1", days)
            sh.formula("F", r, f"=ROUND(E{r}/365,2)", years, "num")
            sh.put("G", r, total, "won")
            sh.formula("H", r, f"=G{r}/90", avg, "won")
            sh.put("I", r, ordinary, "won")
            sh.formula("J", r, f"=MAX(H{r},I{r})", applied, "won")
            sh.formula("K", r, f"=IF(E{r}<365,0,ROUND(J{r}*30*F{r},-1))", 0 if days < 365 else ROUND(applied * 30 * years, -1), "won")
            if e["empNo"] in old_base:
                A.add("BASE_DATE_WRONG", sh.name, "기준일을 2025-12-31로 입력 (작년 추계표 행을 복사) → 근속 365일 적게",
                      ["CROSS_FIELD_RULE", "RECOMPUTE_CHECK"], empNo=e["empNo"], rowNo=r, cells=["D%d" % r],
                      values={"기준일": {"actual": ref, "expected": LEDGER_DATE}})
            carried += _carried(e, r, [total], [sum(x or 0 for x in ideal)], "G")
            truth[e["empNo"]] = statutory(e)
        rules = {"serviceDays": "기준일(값 입력) - 입사일 + 1", "serviceYears": "ROUND(근속일수/365, 2)", "periodDays": "90 (고정)",
                 "avgDailyWage": "3개월 임금총액(값) / 90", "ordinaryWageFloor": True, "severance": "IF(근속<365, 0, ROUND(적용일급×30×근속연수, -1))",
                 "bonusIncluded": False}
        legal = [{"rule": "periodDays", "company": "3개월 일수를 90일로 고정", "statutory": "산정 사유 발생일 이전 3개월의 총일수 (10~12월은 92일)",
                  "favorableToEmployee": True, "note": "평균임금이 커져 근로자에게 유리, 위법은 아님", "column": "평균임금(일)"},
                 {"rule": "serviceYears", "company": "근속연수를 소수 둘째 자리로 반올림해 곱함", "statutory": "근속일수/365 그대로",
                  "favorableToEmployee": None, "note": "행마다 몇 원~몇백 원 차이", "column": "근속연수"}]
        last = 1 + len(members)
        hr = 1
    else:
        sh = book.sheet("SEVERANCE_EST")
        cfg = book.sheet("CONFIG")
        cfg.put("A", 1, "BASE_DATE")
        cfg.put("B", 1, LEDGER_DATE)
        book.wb.define_name("BASE_DATE", "=CONFIG!$B$1")
        heads = ("EMP_NO", "NAME", "HIRE_DATE", "SERVICE_DAYS", "WAGE_OCT", "WAGE_NOV", "WAGE_DEC", "WAGE_3M", "DAYS_3M", "AVG_DAILY", "ORD_DAILY", "DAILY", "SEVERANCE")
        fields = ("empNo", "name", "hireDate", "serviceDays", "wageOct", "wageNov", "wageDec", "wage3m", "periodDays", "avgDailyWage", "ordinaryDailyWage", "appliedDailyWage", "severance")
        for i, h in enumerate(heads):
            sh.put("ABCDEFGHIJKLM"[i], 1, h, "head")
        typo = rng.choice([e for e in members if e not in under1])
        dupm = rng.choice([e for e in members if e is not typo and e not in under1])
        for n, e in enumerate(members):
            r = 2 + n
            row_of[e["empNo"]] = r
            act, ideal = wages(e)
            hire = e["hireDate"].replace(year=e["hireDate"].year + 1) if e is typo else e["hireDate"]
            days = _service_days(hire)
            if e is dupm:
                act = [act[0], act[1], act[1]]
            sh.put("A", r, e["empNo"], "text")
            sh.put("B", r, e["name"])
            sh.put("C", r, hire)
            sh.formula("D", r, f"=BASE_DATE-C{r}+1", days)
            for c, v in zip("EFG", act):
                sh.put(c, r, None if isinstance(v, XlErr) else v, "won")
            total = sum(v for v in act if isinstance(v, (int, float)))
            sh.formula("H", r, f"=SUM(E{r}:G{r})", total, "won")
            sh.formula("I", r, "=DATE(2026,12,31)-DATE(2026,10,1)+1", PERIOD_DAYS)
            avg = TRUNC(total / PERIOD_DAYS, 0)
            sh.formula("J", r, f"=TRUNC(H{r}/I{r})", avg, "won")
            ordinary = TRUNC(facts[e["empNo"]].get("pay", {}).get("2026-12", {}).get("hourly", 0) * 8, 0)
            sh.put("K", r, ordinary, "won")
            daily = MAX(avg, ordinary)
            sh.formula("L", r, f"=MAX(J{r},K{r})", daily, "won")
            sh.formula("M", r, f"=IF(D{r}<365,0,TRUNC(L{r}*30*D{r}/365))", 0 if days < 365 else TRUNC(daily * 30 * days / 365, 0), "won")
            if e is typo:
                A.add("HIRE_DATE_MISMATCH", sh.name, "입사일 연도를 잘못 입력(+1년) → 직원명부 입사일과 다르고 근속 365일 적게",
                      ["CROSS_FILE_MATCH", "RECOMPUTE_CHECK"], empNo=e["empNo"], rowNo=r, cells=["C%d" % r],
                      values={"HIRE_DATE": {"actual": hire, "expected": e["hireDate"]}})
            if e is dupm:
                A.add("WRONG_MONTH_COPIED", sh.name, "WAGE_DEC에 11월 급여를 한 번 더 붙여넣음 → 급여대장 12월 지급계와 다름",
                      ["CROSS_FILE_MATCH"], empNo=e["empNo"], rowNo=r, cells=["G%d" % r])
            carried += _carried(e, r, act, ideal, "EFG") if e is not dupm else []
            truth[e["empNo"]] = statutory(e)
        rules = {"serviceDays": "BASE_DATE(이름 정의) - 입사일 + 1", "periodDays": 92, "avgDailyWage": "TRUNC(3개월 임금/92)",
                 "ordinaryWageFloor": True, "severance": "IF(근속<365, 0, TRUNC(적용일급×30×근속일수/365))", "bonusIncluded": False}
        legal = []
        last = 1 + len(members)
        hr = 1
    if carried:
        A.add("PAYROLL_ACCIDENT_CARRIED", sh.name, "급여대장의 사고(SUM 범위 누락·#N/A·하드코딩 등)가 있는 지급계를 그대로 옮겨 적음",
              ["CROSS_FILE_MATCH", "RECOMPUTE_CHECK"], rows=carried)
    book.close()
    sheets = {sh.name: {"role": "severanceEstimate", "headerRows": [hr], "firstDataRow": hr + 1, "lastDataRow": last, "rowOf": row_of}}
    return clean({
        "file": path.name, "kind": "SEVERANCE_ESTIMATE", "closestEntity": None, "supported": False,
        "sheetOrder": [sh.name] + (["CONFIG"] if cid == "mabaservice" else []), "sheets": sheets,
        "expectedMapping": {"severanceEstimate": dict(zip(heads, fields))}, "rules": rules, "legalDeviations": legal,
        "employees": truth,
        "crossFile": {"payroll": {"file": "급여대장.xlsx", "check": "10~12월 임금 = 급여대장 각 월 지급계 (사고 없는 값은 employees가 아니라 급여대장 정답지 기준)"},
                      "roster": {"file": "직원명부", "check": "입사일 = 직원명부 입사일"}},
        "accidents": A.items,
        "note": "employees의 severance는 법정 공식(근속일수/365, 92일, 통상임금 하한, 1년 미만 0)으로 계산한 값",
    })


def _carried(e, r, act, ideal, cols):
    """급여대장 사고 때문에 옮겨 적은 값이 사고 없는 값과 다른 칸"""
    out = []
    for c, a, i in zip(cols, act, ideal):
        if a != i:
            out.append({"empNo": e["empNo"], "rowNo": r, "cell": "%s%d" % (c, r),
                        "actual": None if isinstance(a, XlErr) else a, "payrollError": str(a) if isinstance(a, XlErr) else None, "expected": i})
    return out
