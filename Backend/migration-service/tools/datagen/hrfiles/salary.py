"""
연봉이력.xlsx — 연도별 연봉, 인상률, 월 기본급 (2021년 또는 입사 연도부터 2026년까지).

2026년 연봉 = facts의 연봉이고, 월 기본급 = 연봉 / 12 (회사별 끝수 처리) = 급여대장 기본급.
이전 연도는 2026년에서 거꾸로 만든다. 다라물산은 '연봉 = 전년 × (1+인상률)' 수식이라 그 수식이 정확히 2026년 연봉이 되게 거꾸로 찾는다.
"""
from .common import Accidents, clean
from .facts import MONTHLY_BASE_ROUNDING
from .xl import ROUND, ROUNDDOWN, ROUNDUP, Book

YEARS = list(range(2021, 2027))
BASE_RAISE = {2022: 0.035, 2023: 0.04, 2024: 0.03, 2025: 0.025, 2026: 0.03}


def _years(e):
    last = 2026 if e["resignDate"] is None else min(2026, e["resignDate"].year)
    return [y for y in YEARS if max(e["hireDate"].year, 2021) <= y <= last]


def _backward(e, annual2026, rng, unit):
    """2026년 연봉에서 거꾸로: 연도 → 연봉 (unit 단위 반올림)"""
    ys = _years(e)
    amt = {}
    cur = annual2026
    for y in reversed(ys):
        amt[y] = cur
        if y > ys[0]:
            r = BASE_RAISE[y] + rng.choice([-0.005, 0, 0, 0.005, 0.01, 0.02])
            cur = int(ROUND(cur / (1 + r), -len(str(unit)) + 1))
    return amt


def _backward_exact(annual2026, ys, rng):
    """다라물산: ROUND(전년 × (1+인상률), -3) = 올해 연봉이 정확히 맞는 (전년, 인상률)을 거꾸로 찾는다"""
    amt, rate = {ys[-1]: annual2026}, {}
    cur = annual2026
    for y in reversed(ys[1:]):
        base = BASE_RAISE[y]
        cands = [base + m for m in rng.sample([-0.005, 0, 0.005, 0.01, 0.015, 0.02], 6)]
        cands += [base + m / 1000 for m in range(-10, 30)]  # 0.1%p 단위까지 넓혀 찾기
        found = None
        for r in cands:
            r = round(r, 4)
            guess = int(round(cur / (1 + r) / 1000)) * 1000
            for prev in (guess, guess - 1000, guess + 1000):
                if ROUND(prev * (1 + r), -3) == cur:
                    found = (prev, r)
                    break
            if found:
                break
        assert found, "인상률을 찾지 못함"
        cur, rate[y] = found
        amt[y - 1] = cur
    return amt, rate


def build(company, roster, facts, out_dir, rng):
    cid = company["id"]
    A = Accidents("%s-SA" % cid[:4].upper(), "연봉이력.xlsx")
    path = out_dir / "연봉이력.xlsx"
    book = Book(path)
    monthly = MONTHLY_BASE_ROUNDING[cid][1]
    members = sorted([e for e in roster if _years(e)], key=lambda e: e["empNo"])
    active = [e for e in members if e["resignDate"] is None]
    truth = {}

    if cid == "ganatech":
        sh = book.sheet("연봉이력")
        heads = ("사번", "성명", "적용연도", "연봉", "전년 연봉", "인상률", "월 기본급")
        fields = ("empNo", "name", "year", "annualSalary", "prevAnnualSalary", "raiseRate", "monthlyBase")
        for i, h in enumerate(heads):
            sh.put("ABCDEFG"[i], 1, h, "head")
        rate_bad = rng.choice([e for e in active if len(_years(e)) >= 3])
        stale = rng.choice([e for e in active if len(_years(e)) >= 2 and e is not rate_bad])
        r = 2
        for e in members:
            amt = _backward(e, facts[e["empNo"]]["annual2026"], rng, 10_000)
            prev = None
            for y in _years(e):
                a = amt[y]
                typed_old = e is stale and y == 2026
                if typed_old:
                    a = amt[2025]
                sh.put("A", r, e["empNo"], "text")
                sh.put("B", r, e["name"])
                sh.put("C", r, y)
                sh.put("D", r, a, "won")
                sh.formula("E", r, f'=IF(A{r}=A{r - 1},D{r - 1},"")', prev if prev is not None else "", "won")
                rate = ROUND(a / prev - 1, 4) if prev else ""
                if e is rate_bad and y == _years(e)[-2]:
                    sh.put("F", r, 0.05, "pct")
                    A.add("RATE_AMOUNT_MISMATCH", sh.name, "인상률을 수식 대신 5%%로 입력, 금액으로 계산하면 %.2f%%" % (rate * 100),
                          ["FORMULA_READ", "HARDCODED_IN_FORMULA_COLUMN", "RECOMPUTE_CHECK"], empNo=e["empNo"], rowNo=r, cells=["F%d" % r],
                          values={"인상률": {"actual": 0.05, "expected": rate}})
                else:
                    sh.formula("F", r, f'=IF(E{r}="","",ROUND(D{r}/E{r}-1,4))', rate, "pct")
                sh.formula("G", r, f"=ROUNDDOWN(D{r}/12,-1)", ROUNDDOWN(a / 12, -1), "won")
                if typed_old:
                    A.add("SALARY_NOT_UPDATED", sh.name, "2026년 연봉을 2025년 금액 그대로 입력 → 월 기본급 ≠ 급여대장 기본급, 인상률 0%",
                          ["CROSS_FILE_MATCH"], empNo=e["empNo"], rowNo=r, cells=["D%d" % r],
                          values={"연봉": {"actual": a, "expected": amt[2026]}, "월 기본급": {"actual": ROUNDDOWN(a / 12, -1), "payroll": monthly(amt[2026])}})
                prev = a
                r += 1
            truth[e["empNo"]] = {str(y): amt[y] for y in _years(e)}
        last = r - 1
        rules = {"layout": "long (사람·연도별 1행)", "annualSalary": "값 입력", "raiseRate": "ROUND(연봉/전년-1, 4)",
                 "prevAnnualSalary": "IF(위 행과 사번이 같으면 위 행 연봉)", "monthlyBase": "ROUNDDOWN(연봉/12, -1) — 10원 미만 버림",
                 "monthlyBaseRounding": "floor10"}
    elif cid == "daramulsan":
        sh = book.sheet("연봉")
        heads = ("사번", "성명", "연도", "인상률", "연봉", "월급여(기본)")
        fields = ("empNo", "name", "year", "raiseRate", "annualSalary", "monthlyBase")
        for i, h in enumerate(heads):
            sh.put("ABCDEF"[i], 1, h, "head")
        multi = [e for e in active if len(_years(e)) >= 4]
        typed, pct = rng.sample(multi, 2)
        r = 2
        for e in members:
            ys = _years(e)
            amt, rate = _backward_exact(facts[e["empNo"]]["annual2026"], ys, rng)
            actual_prev = None
            cascade = {}
            for i, y in enumerate(ys):
                sh.put("A", r, int(e["empNo"]))
                sh.put("B", r, e["name"])
                sh.put("C", r, y)
                if i == 0:
                    sh.put("D", r, None)
                    sh.put("E", r, amt[y], "won")
                    actual = amt[y]
                else:
                    rr = rate[y]
                    if e is pct and y == ys[1]:
                        rr = round(rate[y] * 100, 2)  # 3.5%를 3.5로 입력
                        A.add("PERCENT_AS_NUMBER", sh.name, "인상률 %.1f%%를 %s로 입력 → 연봉이 %.1f배가 되고 이후 연도 모두 틀림" % (rate[y] * 100, rr, 1 + rr),
                              ["VALUE_NORMALIZE", "RECOMPUTE_CHECK", "CROSS_FILE_MATCH"], empNo=e["empNo"], rowNo=r, cells=["D%d" % r])
                    sh.put("D", r, rr, "pct" if rr < 1 else None)
                    if e is typed and y == ys[-3]:
                        actual = amt[y] + 500_000
                        sh.put("E", r, actual, "won")
                        A.add("AMOUNT_HARDCODED", sh.name, "연봉을 수식 대신 협상 금액(+50만원)으로 입력 → 다음 해부터 수식이 이 값을 이어받아 2026 월급여 ≠ 급여대장 기본급",
                              ["FORMULA_READ", "HARDCODED_IN_FORMULA_COLUMN", "CROSS_FILE_MATCH"], empNo=e["empNo"], rowNo=r, cells=["E%d" % r],
                              values={"연봉": {"actual": actual, "expected": amt[y]}})
                    else:
                        actual = ROUND(actual_prev * (1 + rr), -3)
                        sh.formula("E", r, f"=ROUND(E{r - 1}*(1+D{r}),-3)", actual, "won")
                sh.formula("F", r, f"=ROUND(E{r}/12,0)", ROUND(actual / 12, 0), "won")
                cascade[y] = actual
                actual_prev = actual
                r += 1
            if e in (typed, pct):
                A.items[-1]["cascade"] = {str(y): {"actual": cascade[y], "expected": amt[y]} for y in ys if cascade[y] != amt[y]}
            truth[e["empNo"]] = {str(y): amt[y] for y in ys}
        last = r - 1
        rules = {"layout": "long (사람·연도별 1행)", "raiseRate": "값 입력 (회사 기본 인상률 ± 개인)", "annualSalary": "첫해 값 입력, 이후 ROUND(전년 × (1+인상률), -3)",
                 "monthlyBase": "ROUND(연봉/12, 0)", "monthlyBaseRounding": "round1", "baseRaise": BASE_RAISE}
    else:
        sh = book.sheet("SALARY_HISTORY")
        sal = {y: "DEFGHI"[i] for i, y in enumerate(YEARS)}
        rai = {y: "JKLMN"[i] for i, y in enumerate(YEARS[1:])}
        heads = ["EMP_NO", "NAME", "HIRE_DATE"] + ["SAL_%d" % y for y in YEARS] + ["RAISE_%d" % y for y in YEARS[1:]] + ["MONTHLY_BASE_2026"]
        fields = ["empNo", "name", "hireDate"] + ["annualSalary%d" % y for y in YEARS] + ["raiseRate%d" % y for y in YEARS[1:]] + ["monthlyBase2026"]
        for i, h in enumerate(heads):
            sh.put(chr(65 + i), 1, h, "head")
        bad_raise = rng.choice([e for e in active if len(_years(e)) >= 3])
        missing = rng.choice([e for e in active if e is not bad_raise and len(_years(e)) >= 2])
        for n, e in enumerate(members):
            r = 2 + n
            amt = _backward(e, facts[e["empNo"]]["annual2026"], rng, 100_000)
            sh.put("A", r, e["empNo"], "text")
            sh.put("B", r, e["name"])
            sh.put("C", r, e["hireDate"])
            vals = {y: amt.get(y) for y in YEARS}
            if e is missing:
                vals[2026] = None
                A.add("VALUE_MISSING", sh.name, "재직자인데 SAL_2026이 비어 있음 → MONTHLY_BASE_2026 = 0, 급여대장 기본급과 불일치",
                      ["REQUIRED", "CROSS_FILE_MATCH"], empNo=e["empNo"], rowNo=r, cells=["I%d" % r], values={"SAL_2026": {"expected": amt[2026]}})
            for y in YEARS:
                sh.put(sal[y], r, vals[y], "won")
            for y in YEARS[1:]:
                p_, c_ = vals[y - 1], vals[y]
                v = "" if p_ is None or c_ is None else ROUND(c_ / p_ - 1, 4)
                if e is bad_raise and y == _years(e)[-1] and v != "":
                    sh.put(rai[y], r, 0.03, "pct")
                    A.add("RATE_AMOUNT_MISMATCH", sh.name, "RAISE_%d를 수식 대신 3%%로 입력, 금액으로 계산하면 %.2f%%" % (y, v * 100),
                          ["FORMULA_READ", "HARDCODED_IN_FORMULA_COLUMN", "RECOMPUTE_CHECK"], empNo=e["empNo"], rowNo=r, cells=["%s%d" % (rai[y], r)],
                          values={"RAISE": {"actual": 0.03, "expected": v}})
                    continue
                sh.formula(rai[y], r, f'=IF(OR({sal[y - 1]}{r}="",{sal[y]}{r}=""),"",ROUND({sal[y]}{r}/{sal[y - 1]}{r}-1,4))', v, "pct")
            mb = ROUNDUP((vals[2026] or 0) / 12, -2)
            sh.formula("O", r, f"=ROUNDUP(I{r}/12,-2)", mb, "won")
            truth[e["empNo"]] = {str(y): amt[y] for y in _years(e)}
        last = 1 + len(members)
        rules = {"layout": "wide (사람 1행, 연도별 열)", "annualSalary": "값 입력 (10만원 단위)", "raiseRate": "ROUND(올해/전년-1, 4), 한쪽이 비면 빈 값",
                 "monthlyBase": "ROUNDUP(SAL_2026/12, -2) — 100원 미만 올림", "monthlyBaseRounding": "ceil100"}
    book.close()
    return clean({
        "file": path.name, "kind": "SALARY_HISTORY", "closestEntity": "PAY_PROFILE", "supported": False,
        "sheetOrder": [sh.name], "sheets": {sh.name: {"role": "salaryHistory", "headerRows": [1], "firstDataRow": 2, "lastDataRow": last}},
        "expectedMapping": {"salaryHistory": dict(zip(heads, fields))},
        "rules": rules, "annualSalary": truth,
        "crossFile": {"payroll": {"file": "급여대장.xlsx", "check": "2026년 월 기본급 = 급여대장 기본급 (재직자)", "rounding": MONTHLY_BASE_ROUNDING[cid][0]},
                      "roster": {"file": "직원명부", "check": "가나테크 직원명부의 '기본급' 열 = 2026 월 기본급"} if cid == "ganatech" else None},
        "accidents": A.items, "valueNoise": [{"type": "EMPNO_NUMERIC", "scope": "모든 시트"}] if cid == "daramulsan" else [],
    })
