"""
4대보험취득상실.xlsx (취득·상실일, 보수월액, 월 보험료) / 부양가족.xlsx (부양가족 명세 + 공제대상 집계·원천징수비율).

취득일 = 입사일, 상실일 = 퇴사일 다음 날. 보수월액은 2026 연봉 기준(회사별: 식대 포함 여부 = 급여대장 규칙과 같음).
부양가족은 facts의 부양가족이라 공제대상 가족 수 = 급여대장 공제가족수 (일부러 어긋나게 한 1명 제외).
"""
import datetime as dt

from .common import Accidents, clean
from .facts import TAX_YEAR
from .payroll import MEAL_LIMIT, RATES
from .xl import ROUNDDOWN, Book

GIVEN = ["민준", "서연", "도윤", "하은", "지호", "수아", "예준", "지유", "시우", "하린", "주원", "서윤", "영숙", "정희", "순자", "영호", "철수", "미경"]
SURNAMES = ["김", "이", "박", "최", "정", "강", "조", "윤", "장", "임"]


def build(company, roster, facts, out_dir, rng):
    return _insurance(company, roster, facts, out_dir, rng), _dependents(company, roster, facts, out_dir, rng)


# ---------------------------------------------------------------- 4대보험 취득·상실

def _insurance(company, roster, facts, out_dir, rng):
    cid = company["id"]
    A = Accidents("%s-IN" % cid[:4].upper(), "4대보험취득상실.xlsx")
    members = sorted(roster, key=lambda e: e["empNo"])
    path = out_dir / "4대보험취득상실.xlsx"
    book = Book(path)
    eng = cid == "mabaservice"
    sh = book.sheet("INSURANCE" if eng else ("취득상실" if cid == "ganatech" else "4대보험"))
    rt = RATES[2026]
    meal = cid == "daramulsan"  # 급여대장과 같은 규칙: 다라물산만 식대를 보험 기준에 넣음
    if eng:
        heads = ("EMP_NO", "NAME", "HIRE_DATE", "ACQ_NPS", "ACQ_NHIS", "ACQ_EIS", "ACQ_IACI", "LOSS_DATE", "LOSS_REASON",
                 "ANNUAL_2026", "MONTHLY_WAGE", "NPS", "NHIS", "LTC", "EIS")
    else:
        heads = ("사번", "성명", "입사일", "국민연금 취득일", "건강보험 취득일", "고용보험 취득일", "산재보험 취득일", "상실일", "상실사유",
                 "연봉(2026)", "보수월액", "국민연금", "건강보험", "장기요양", "고용보험")
    fields = ("empNo", "name", "hireDate", "acqNps", "acqNhis", "acqEis", "acqIaci", "lossDate", "lossReason",
              "annualSalary", "monthlyWage", "nps", "nhis", "ltc", "eis")
    # 요율은 1행 위쪽 머리글 칸에 두고 수식이 참조 (회사 양식)
    sh.put("A", 1, "2026년 4대보험 취득·상실 현황" if not eng else "SOCIAL INSURANCE 2026", "title")
    for c, label, v in (("K", "요율", None), ("L", "국민연금", rt["np"]), ("M", "건강보험", rt["hi"]), ("N", "장기요양", rt["ltc"]), ("O", "고용보험", rt["ei"])):
        if v is not None:
            sh.put(c, 2, v, "pct")
    for i, h in enumerate(heads):
        sh.put(chr(65 + i), 3, h, "head")

    act = [e for e in members if e["resignDate"] is None]
    late = set(x["empNo"] for x in rng.sample(act, 2))
    quitters = [e for e in members if e["resignDate"]]
    same_day = quitters[0] if quitters else None
    no_loss = quitters[1] if len(quitters) > 1 else None
    stale = rng.choice([e for e in act if e["empNo"] not in late and e["hireDate"].year <= 2024])
    expected = {}
    for n, e in enumerate(members):
        r = 4 + n
        hire = e["hireDate"]
        acq = hire
        if e["empNo"] in late:
            acq = hire + dt.timedelta(days=rng.randint(20, 60))
            A.add("ACQ_DATE_MISMATCH", sh.name, "취득일이 입사일보다 %d일 늦음 (지연 신고)" % (acq - hire).days, ["CROSS_FIELD_RULE", "CROSS_FILE_MATCH"],
                  empNo=e["empNo"], rowNo=r, cells=["D%d:G%d" % (r, r)], values={"취득일": {"actual": acq, "expected": hire}})
        loss = e["resignDate"] + dt.timedelta(days=1) if e["resignDate"] else None
        loss_written = loss
        if e is same_day:
            loss_written = e["resignDate"]
            A.add("LOSS_DATE_RULE", sh.name, "상실일을 퇴사일과 같은 날로 입력 (상실일은 퇴사일 다음 날)", ["CROSS_FIELD_RULE", "LEGAL_RULE_COMPARE"],
                  empNo=e["empNo"], rowNo=r, cells=["H%d" % r], values={"상실일": {"actual": loss_written, "expected": loss}})
        if e is no_loss:
            loss_written = None
            A.add("LOSS_MISSING", sh.name, "퇴사자(%s 퇴사)인데 상실일이 비어 있음" % e["resignDate"], ["REQUIRED_PAIR", "CROSS_FILE_MATCH"],
                  empNo=e["empNo"], rowNo=r, cells=["H%d" % r])
        annual = facts[e["empNo"]]["annual2026"]
        annual_written = annual
        if e is stale:
            annual_written = int(round(annual / 1.03, -4))
            A.add("WAGE_NOT_UPDATED", sh.name, "연봉(2026)이 작년 금액 → 보수월액·보험료가 급여대장과 다름", ["CROSS_FILE_MATCH"],
                  empNo=e["empNo"], rowNo=r, cells=["J%d" % r], values={"연봉": {"actual": annual_written, "expected": annual}})
        sh.put("A", r, int(e["empNo"]) if cid == "daramulsan" else e["empNo"], None if cid == "daramulsan" else "text")
        sh.put("B", r, e["name"])
        sh.put("C", r, hire)
        for c in "DEFG":
            sh.put(c, r, acq)
        sh.put("H", r, loss_written)
        sh.put("I", r, ({"퇴직": "자진퇴사"} if not eng else {"퇴직": "VOLUNTARY"})["퇴직"] if e["resignDate"] else None)
        sh.put("J", r, annual_written, "won")
        mw = ROUNDDOWN(annual_written / 12 + (MEAL_LIMIT if meal else 0), -3)
        f_mw = f"=ROUNDDOWN(J{r}/12+{MEAL_LIMIT},-3)" if meal else f"=ROUNDDOWN(J{r}/12,-3)"
        sh.formula("K", r, f_mw, mw, "won")
        np_ = ROUNDDOWN(mw * rt["np"], -1)
        hi = ROUNDDOWN(mw * rt["hi"], -1)
        sh.formula("L", r, f"=ROUNDDOWN(K{r}*$L$2,-1)", np_, "won")
        sh.formula("M", r, f"=ROUNDDOWN(K{r}*$M$2,-1)", hi, "won")
        sh.formula("N", r, f"=ROUNDDOWN(M{r}*$N$2,-1)", ROUNDDOWN(hi * rt["ltc"], -1), "won")
        sh.formula("O", r, f"=ROUNDDOWN(K{r}*$O$2,-1)", ROUNDDOWN(mw * rt["ei"], -1), "won")
        expected[e["empNo"]] = {"acquisitionDate": hire, "lossDate": loss, "monthlyWage": ROUNDDOWN(annual / 12 + (MEAL_LIMIT if meal else 0), -3)}
    book.close()
    legal = [{"rule": "mealInInsuranceBase", "company": "보수월액에 비과세 식대 20만원 포함", "statutory": "보수월액은 비과세 소득 제외",
              "favorableToEmployee": False, "column": "보수월액"}] if meal else []
    return clean({
        "file": path.name, "kind": "SOCIAL_INSURANCE", "closestEntity": "PAY_PROFILE", "supported": False,
        "sheetOrder": [sh.name], "sheets": {sh.name: {"role": "insuranceStatus", "headerRows": [3], "firstDataRow": 4, "lastDataRow": 3 + len(members),
                                                      "rateCells": {"np": "L2", "hi": "M2", "ltc": "N2", "ei": "O2"}}},
        "expectedMapping": {"insuranceStatus": dict(zip(heads, fields))},
        "rules": {"acquisitionDate": "입사일", "lossDate": "퇴사일 다음 날",
                  "monthlyWage": "ROUNDDOWN(연봉/12%s, -3) — 천원 미만 버림" % (" + 식대 200,000" if meal else ""),
                  "premiums": "ROUNDDOWN(보수월액 × 요율, -1), 장기요양 = 건강보험료 × 13.14%", "mealInInsuranceBase": meal},
        "legalDeviations": legal, "employees": expected,
        "crossFile": {"roster": {"file": "직원명부", "check": "취득일 = 입사일, 상실일 = 퇴사일 + 1"},
                      "salaryHistory": {"file": "연봉이력.xlsx", "check": "연봉(2026) = 연봉이력 2026"}},
        "accidents": A.items,
        "valueNoise": [{"type": "TITLE_ROW_LOOKS_LIKE_HEADER", "row": 2, "detail": "2행에 요율 4칸 → '값 2칸 이상인 첫 행 = 헤더' 규칙이면 헤더로 오인"}]
                      + ([{"type": "EMPNO_NUMERIC"}] if cid == "daramulsan" else []),
    })


# ---------------------------------------------------------------- 부양가족

def _phone(rng):
    a, b = rng.randint(1000, 9999), rng.randint(0, 9999)
    style = rng.choices(range(5), [50, 20, 15, 10, 5])[0]
    return ["010-%04d-%04d", "010%04d%04d", "010 %04d %04d", "+82-10-%04d-%04d", "010.%04d.%04d"][style] % (a, b), style


def _dependents(company, roster, facts, out_dir, rng):
    cid = company["id"]
    A = Accidents("%s-DP" % cid[:4].upper(), "부양가족.xlsx")
    members = sorted([e for e in roster if e["resignDate"] is None], key=lambda e: e["empNo"])
    path = out_dir / "부양가족.xlsx"
    book = Book(path)
    eng = cid == "mabaservice"
    s_dep = book.sheet("DEPENDENTS" if eng else "부양가족명세")
    s_sum = book.sheet("TAX_FAMILY" if eng else "공제대상집계")
    if eng:
        heads = ("EMP_NO", "EMP_NAME", "RELATION", "DEP_NAME", "BIRTH_DATE", "AGE", "BASIC_DED", "SENIOR", "CONTACT")
        rel = {"배우자": "SPOUSE", "자녀": "CHILD", "부": "FATHER", "모": "MOTHER"}
        yes = "Y"
    else:
        heads = ("사번", "직원명", "관계", "성명", "생년월일", "나이", "기본공제", "경로우대", "비상연락처")
        rel = {"배우자": "배우자", "자녀": "자녀", "부": "부", "모": "모"}
        yes = "Y" if cid == "ganatech" else "O"
    fields = ("empNo", "empName", "relation", "dependentName", "birthDate", "age", "basicDeduction", "seniorDeduction", "emergencyContact")
    for i, h in enumerate(heads):
        s_dep.put("ABCDEFGHI"[i], 1, h, "head")

    rows = []
    for e in members:
        for d in facts[e["empNo"]]["dependents"]:
            rows.append({"emp": e, **d})
    with_child = [e for e in members if any(d["relation"] == "자녀" for d in facts[e["empNo"]]["dependents"])]
    newborn_e = rng.choice(with_child)
    rows.append({"emp": newborn_e, "relation": "자녀", "birthDate": dt.date(2026, 11, rng.randint(1, 28)), "basic": True, "newborn": True})
    kids = [x for x in rows if x["relation"] == "자녀" and not x.get("newborn") and not x["basic"]]
    old_kid = rng.choice(kids) if kids else None
    parents = [x for x in rows if x["relation"] in ("부", "모") and not x["basic"]]
    young_parent = rng.choice(parents) if parents else None
    rows.sort(key=lambda x: (x["emp"]["empNo"], ["배우자", "부", "모", "자녀"].index(x["relation"]), x["birthDate"]))

    phone_styles = {}
    yes_count = {e["empNo"]: 0 for e in members}
    last = 1 + len(rows)
    for n, x in enumerate(rows):
        r = 2 + n
        x["row"] = r
        e = x["emp"]
        surname = e["name"][0] if x["relation"] in ("자녀", "부") else rng.choice(SURNAMES)
        basic = x["basic"]
        if x is old_kid or x is young_parent:
            basic = True  # 요건이 안 되는데 공제 Y
        age = TAX_YEAR - x["birthDate"].year
        phone, style = _phone(rng)
        phone_styles[style] = phone_styles.get(style, 0) + 1
        s_dep.put("A", r, int(e["empNo"]) if cid == "daramulsan" else e["empNo"], None if cid == "daramulsan" else "text")
        s_dep.put("B", r, e["name"])
        s_dep.put("C", r, rel[x["relation"]])
        s_dep.put("D", r, surname + rng.choice(GIVEN))
        s_dep.put("E", r, x["birthDate"])
        s_dep.formula("F", r, f"={TAX_YEAR}-YEAR(E{r})", age)
        s_dep.put("G", r, yes if basic else ("N" if cid != "daramulsan" else "X"))
        s_dep.formula("H", r, f'=IF(F{r}>=70,"{yes}","")', yes if age >= 70 else "")
        s_dep.put("I", r, phone)
        if basic:
            yes_count[e["empNo"]] += 1
    if old_kid:
        A.add("DEDUCTION_AGE_RULE", s_dep.name, "자녀 나이 %d세인데 기본공제 대상 표시 (자녀는 20세 이하). 집계 가족수도 급여대장과 달라짐" % (TAX_YEAR - old_kid["birthDate"].year),
              ["LEGAL_RULE_COMPARE", "CROSS_FIELD_RULE"], legal=True, empNo=old_kid["emp"]["empNo"], rowNo=old_kid["row"], cells=["G%d" % old_kid["row"]])
    if young_parent:
        A.add("DEDUCTION_AGE_RULE", s_dep.name, "직계존속 나이 %d세인데 기본공제 대상 표시 (60세 이상). 집계 가족수도 급여대장과 달라짐" % (TAX_YEAR - young_parent["birthDate"].year),
              ["LEGAL_RULE_COMPARE", "CROSS_FIELD_RULE"], legal=True, empNo=young_parent["emp"]["empNo"], rowNo=young_parent["row"], cells=["G%d" % young_parent["row"]])
    nb = next(x for x in rows if x.get("newborn"))
    A.add("FAMILY_COUNT_MISMATCH", s_dep.name, "11월 출생 자녀를 부양가족에 추가했지만 급여대장 공제가족수는 그대로 → 집계 가족수 ≠ 급여대장",
          ["CROSS_FILE_MATCH"], empNo=nb["emp"]["empNo"], rowNo=nb["row"])

    # 공제대상 집계
    sum_heads = ("EMP_NO", "NAME", "TAX_FAMILY", "WHT_RATE") if eng else ("사번", "성명", "공제대상가족수(본인포함)", "원천징수비율(%)")
    for i, h in enumerate(sum_heads):
        s_sum.put("ABCD"[i], 1, h, "head")
    bad_ratio = rng.choice(members)
    expected = {}
    for n, e in enumerate(members):
        r = 2 + n
        f = facts[e["empNo"]]
        s_sum.put("A", r, int(e["empNo"]) if cid == "daramulsan" else e["empNo"], None if cid == "daramulsan" else "text")
        s_sum.put("B", r, e["name"])
        s_sum.formula("C", r, f'=1+COUNTIFS({s_dep.name}!$A$2:$A${last},A{r},{s_dep.name}!$G$2:$G${last},"{yes}")', 1 + yes_count[e["empNo"]])
        ratio = 90 if e is bad_ratio else f["withholdRatio"]
        s_sum.put("D", r, ratio)
        if e is bad_ratio:
            A.add("RATIO_INVALID", s_sum.name, "원천징수비율 90% (선택 가능한 값은 80·100·120%)", ["ENUM_CHECK", "LEGAL_RULE_COMPARE"],
                  empNo=e["empNo"], rowNo=r, cells=["D%d" % r], values={"원천징수비율": {"actual": 90, "expected": f["withholdRatio"]}})
        expected[e["empNo"]] = {"taxFamily": f["taxFamily"], "withholdRatio": f["withholdRatio"]}
    book.close()
    return clean({
        "file": path.name, "kind": "DEPENDENTS", "closestEntity": "PAY_PROFILE", "supported": False,
        "sheetOrder": [s_dep.name, s_sum.name],
        "sheets": {s_dep.name: {"role": "dependents", "headerRows": [1], "firstDataRow": 2, "lastDataRow": last},
                   s_sum.name: {"role": "taxFamilySummary", "headerRows": [1], "firstDataRow": 2, "lastDataRow": 1 + len(members)}},
        "expectedMapping": {"dependents": dict(zip(heads, fields)), "taxFamilySummary": dict(zip(sum_heads, ("empNo", "name", "taxFamily", "withholdRatio")))},
        "rules": {"taxFamily": "1(본인) + COUNTIFS(사번, 기본공제=%s)" % yes, "basicDeductionAge": {"child": "≤ 20", "parent": "≥ 60", "taxYear": TAX_YEAR},
                  "withholdingRatio": [80, 100, 120], "yesMark": yes},
        "employees": expected,
        "crossFile": {"payroll": {"file": "급여대장.xlsx", "check": "공제대상가족수 = 급여대장 공제가족수, 원천징수비율 = 마바서비스 급여대장 WHT_RATE"}},
        "accidents": A.items,
        "valueNoise": [{"type": "PHONE_FORMAT_MIXED", "column": heads[8], "counts": {["010-0000-0000", "01000000000", "010 0000 0000", "+82-10-0000-0000", "010.0000.0000"][k]: v for k, v in sorted(phone_styles.items())}}]
                      + ([{"type": "EMPNO_NUMERIC"}, {"type": "YES_NO_MARK", "detail": "기본공제를 O/X로 표시"}] if cid == "daramulsan" else []),
    })
