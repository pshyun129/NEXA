"""
급여대장.xlsx — 회사마다 다른 계산 규칙을 진짜 엑셀 수식으로 넣고, 실무에서 생기는 사고를 일부러 섞는다.

시트: 설정(요율·한도) / 간이세액표(단순화) / 근태(월별 연장·야간·휴일 시간) / 월별 급여 시트.
월별 시트의 모든 계산 셀은 수식이고, 이관 서비스가 읽을 수 있게 계산 결과도 함께 저장한다.
"""
import datetime as dt

from .facts import active_in, month_bounds
from .xl import (DIV, MIN, MUL, NA, REF, ROUND, ROUNDDOWN, SUB, SUM, TRUNC, Book, XlErr, col_letters,
                 first_err, vlookup_approx)

RATES = {
    2026: {"np": 0.0475, "hi": 0.03595, "ltc": 0.1314, "ei": 0.009},
    2025: {"np": 0.045, "hi": 0.03545, "ltc": 0.1295, "ei": 0.009},  # '한 달만 작년 요율' 사고용
}
MEAL_LIMIT = 200_000
MIN_WAGE_2026 = 10_320


# ---------------------------------------------------------------- 간이세액표 (단순화)

def simple_tax(monthly, family):
    """국세청 간이세액표를 흉내 낸 단순 계산. 실제 표와 금액이 다르다 (README 참고)"""
    annual = monthly * 12
    if annual <= 5_000_000:
        ded = annual * 0.7
    elif annual <= 15_000_000:
        ded = 3_500_000 + (annual - 5_000_000) * 0.4
    elif annual <= 45_000_000:
        ded = 7_500_000 + (annual - 15_000_000) * 0.15
    elif annual <= 100_000_000:
        ded = 12_000_000 + (annual - 45_000_000) * 0.05
    else:
        ded = 14_750_000 + (annual - 100_000_000) * 0.02
    base = max(0, annual - ded - 1_500_000 * family - annual * 0.0475)
    tax = 0
    for limit, rate, acc in [(14e6, .06, 0), (50e6, .15, 840_000), (88e6, .24, 6_240_000), (1.5e8, .35, 15_360_000), (9e99, .38, 37_060_000)]:
        if base <= limit:
            prev = {0.06: 0, 0.15: 14e6, 0.24: 50e6, 0.35: 88e6, 0.38: 1.5e8}[rate]
            tax = acc + (base - prev) * rate
            break
    credit = tax * 0.55 if tax <= 1_300_000 else 715_000 + (tax - 1_300_000) * 0.3
    credit = min(credit, 740_000 if annual <= 33_000_000 else 660_000)
    return max(0, int((tax - credit) / 12 // 10 * 10))


TAX_ROWS = [[lo, lo + 50_000] + [simple_tax(lo + 25_000, n) for n in range(1, 6)]
            for lo in range(1_000_000, 10_000_000, 50_000)]


# ---------------------------------------------------------------- 설정 시트

class Settings:
    def __init__(self, sheet, items, value_col, first_row, names=None):
        self.sheet, self.items, self.value_col, self.first_row = sheet, items, value_col, first_row
        self.names = names or {}
        self.row = {k: first_row + i for i, (k, *_rest) in enumerate(items)}

    def v(self, k):
        return next(it[2] for it in self.items if it[0] == k)

    def ref(self, k):
        return self.names.get(k) or f"{self.sheet}!${self.value_col}${self.row[k]}"


# ---------------------------------------------------------------- 회사별 양식·규칙

def _items(rates, extra):
    return [("np", "국민연금(근로자)", rates["np"], "pct"), ("hi", "건강보험(근로자)", rates["hi"], "pct"),
            ("ltc", "장기요양(건강보험료 대비)", rates["ltc"], "pct"), ("ei", "고용보험(근로자)", rates["ei"], "pct"),
            ("meal", "식대 비과세 한도", MEAL_LIMIT, "won")] + extra


def _gana():
    cols = [("empNo", "사번"), ("name", "성명"), ("dept", "부서"), ("position", "직위"), ("family", "공제\n가족수"),
            ("basePay", "기본급"), ("posAlw", "직책수당"), ("meal", "식대"),
            ("otH", "연장"), ("nightH", "야간"), ("holH", "휴일"), ("hol8H", "휴일\n8h초과"),
            ("hourly", "통상시급"), ("otPay", "연장수당"), ("nightPay", "야간수당"), ("holPay", "휴일수당"),
            ("gross", "지급계"), ("taxable", "과세대상"),
            ("np", "국민연금"), ("hi", "건강보험"), ("ltc", "장기요양"), ("ei", "고용보험"),
            ("tax", "소득세"), ("ltax", "지방소득세"), ("ded", "공제계"), ("net", "실지급액")]
    S = Settings("설정", _items(RATES[2026], [
        ("hours", "월 소정근로시간", 209, None), ("posAlw", "직책수당(팀장)", 200_000, "won"),
        ("local", "지방소득세율", 0.1, "pct"), ("minWage", "최저시급(참고)", MIN_WAGE_2026, "won")]), "B", 4)
    L = col_letters([k for k, _ in cols])

    def calc(ym, T, S):
        R = lambda k: "%s{r}" % L[k]  # noqa: E731
        c = {}
        for k in ("otH", "nightH", "holH", "hol8H"):
            c[k] = (f"=VLOOKUP($A{{r}},{T.range},{{tcol_{k}}},FALSE)", None)  # 근태 시트 조회
        c["hourly"] = (f"=ROUNDDOWN(({R('basePay')}+{R('posAlw')})/{S.ref('hours')},0)",
                       lambda v: ROUNDDOWN(DIV(SUM(v["basePay"], v["posAlw"]), S.v("hours")), 0))
        c["otPay"] = (f"=ROUND({R('hourly')}*{R('otH')}*1.5,0)", lambda v: ROUND(MUL(v["hourly"], v["otH"], 1.5), 0))
        c["nightPay"] = (f"=ROUND({R('hourly')}*{R('nightH')}*0.5,0)", lambda v: ROUND(MUL(v["hourly"], v["nightH"], 0.5), 0))
        c["holPay"] = (f"=ROUND({R('hourly')}*({R('holH')}*1.5+{R('hol8H')}*2),0)",
                       lambda v: ROUND(MUL(v["hourly"], SUM(MUL(v["holH"], 1.5), MUL(v["hol8H"], 2))), 0))
        c["gross"] = (f"=SUM({L['basePay']}{{r}}:{L['meal']}{{r}},{L['otPay']}{{r}}:{L['holPay']}{{r}})",
                      lambda v: SUM(v["basePay"], v["posAlw"], v["meal"], v["otPay"], v["nightPay"], v["holPay"]))
        c["taxable"] = (f"={R('gross')}-MIN({R('meal')},{S.ref('meal')})",
                        lambda v: SUB(v["gross"], MIN(v["meal"], S.v("meal"))))
        for k in ("np", "hi", "ei"):
            c[k] = (f"=ROUNDDOWN({R('taxable')}*{S.ref(k)},-1)", (lambda k: lambda v: ROUNDDOWN(MUL(v["taxable"], S.v(k)), -1))(k))
        c["ltc"] = (f"=ROUNDDOWN({R('hi')}*{S.ref('ltc')},-1)", lambda v: ROUNDDOWN(MUL(v["hi"], S.v("ltc")), -1))
        c["tax"] = (f"=VLOOKUP({R('taxable')},간이세액표!$A$4:$G${3 + len(TAX_ROWS)},MIN({R('family')},5)+2,TRUE)",
                    lambda v: vlookup_approx(v["taxable"], TAX_ROWS, min(v["family"], 5) + 2))
        c["ltax"] = (f"=ROUNDDOWN({R('tax')}*{S.ref('local')},-1)", lambda v: ROUNDDOWN(MUL(v["tax"], S.v("local")), -1))
        c["ded"] = (f"=SUM({L['np']}{{r}}:{L['ltax']}{{r}})",
                    lambda v: SUM(v["np"], v["hi"], v["ltc"], v["ei"], v["tax"], v["ltax"]))
        c["net"] = (f"={R('gross')}-{R('ded')}", lambda v: SUB(v["gross"], v["ded"]))
        return c

    def inputs(e, f, ym, S):
        return {"family": f["taxFamily"], "basePay": f["basePay"],
                "posAlw": S.v("posAlw") if e["duty"] == "팀장" else 0, "meal": MEAL_LIMIT}

    return {
        "id": "ganatech", "months": ["2026-07", "2026-08", "2026-09", "2026-10", "2026-11", "2026-12"],
        "order": lambda months: ["설정", "간이세액표", "근태"] + months,
        "columns": cols, "L": L, "settings": S, "calc": calc, "inputs": inputs,
        "title": lambda ym: [("A", 1, "(주)가나테크 %s년 %s월 급여대장" % tuple(ym.split("-")), "title"),
                             ("A", 2, "지급일 %s-25 / 단위: 원" % ym, None)],
        "groups": [("empNo", "family", "인적사항"), ("basePay", "meal", "기본 지급"), ("otH", "hol8H", "근태(시간)"),
                   ("hourly", "holPay", "법정수당"), ("gross", "taxable", "합계"), ("np", "ded", "공제항목"), ("net", "net", "실지급")],
        "headerRow": 4, "firstRow": 5, "totals": ("name", "합계"), "time": "wide_vlookup", "timeSheet": "근태",
        "taxSheet": "간이세액표", "empNoNumeric": False,
        "rules": {
            "ordinaryHourlyWage": {"expr": "floor((basePay+positionAllowance)/209)", "basis": ["basePay", "positionAllowance"], "divisor": 209, "rounding": "floor1", "divisorFrom": "설정!B" },
            "overtimePremium": {"rate": 1.5, "rateFrom": "literal", "rounding": "round1"},
            "nightPremium": {"rate": 0.5, "rateFrom": "literal", "rounding": "round1"},
            "holidayPremium": {"within8h": 1.5, "over8h": 2.0, "rateFrom": "literal", "rounding": "round1"},
            "grossPay": "SUM(basePay, positionAllowance, meal, overtimePay, nightPay, holidayPay)",
            "nonTaxableMeal": {"limit": MEAL_LIMIT},
            "mealInInsuranceBase": False,
            "insuranceBase": "grossPay - min(meal, 200000)",
            "insuranceRates": RATES[2026], "rateFrom": "설정 시트 셀 참조",
            "insuranceRounding": "floor10",
            "longTermCareBase": "healthInsurancePremium",
            "incomeTax": {"method": "VLOOKUP(과세대상, 간이세액표, 가족수 열, TRUE)", "withholdingRatio": False, "rounding": "table"},
            "localIncomeTax": {"rate": 0.1, "rounding": "floor10"},
            "netPay": "grossPay - deductionTotal",
        },
    }


def _dara():
    cols = [("empNo", "사번"), ("name", "성명"), ("dept", "부서"), ("position", "직위"), ("hire", "입사일"), ("family", "가족수"),
            ("basePay", "기본급"), ("tenureAlw", "근속수당"), ("jobAlw", "직무수당"), ("meal", "식대"),
            ("otH", "연장\n시간"), ("nightH", "야간\n시간"), ("holH", "휴일\n시간"), ("hol8H", "휴일8h\n초과시간"),
            ("hourly", "시급"), ("otPay", "연장\n근로수당"), ("nightPay", "야간\n근로수당"), ("holPay", "휴일\n근로수당"),
            ("gross", "지급합계"), ("np", "국민연금"), ("hi", "건강보험"), ("ltc", "요양보험"), ("ei", "고용보험"),
            ("tax", "갑근세"), ("ltax", "주민세"), ("ded", "공제합계"), ("net", "차인지급액")]
    S = Settings("설정", _items(RATES[2026], [
        ("tenureUnit", "근속수당 (1년당)", 10_000, "won"), ("tenureCap", "근속수당 상한 (년)", 10, None),
        ("jobAlw", "직무수당 (생산본부)", 50_000, "won")]), "C", 3)
    L = col_letters([k for k, _ in cols])

    def calc(ym, T, S):
        R = lambda k: "%s{r}" % L[k]  # noqa: E731
        c = {}
        c["tenureAlw"] = (f"=MIN(DATEDIF({R('hire')},$F$2,\"Y\"),{S.ref('tenureCap')})*{S.ref('tenureUnit')}",
                          lambda v: MUL(MIN(_datedif_y(v["hire"], month_bounds(ym)[1]), S.v("tenureCap")), S.v("tenureUnit")))
        for k in ("otH", "nightH", "holH", "hol8H"):
            c[k] = (f"=INDEX({{tcolrange_{k}}},MATCH($A{{r}},{T.keyRange},0))", None)
        c["hourly"] = (f"=ROUND(({R('basePay')}+{R('tenureAlw')}+{R('jobAlw')})/209,0)",
                       lambda v: ROUND(DIV(SUM(v["basePay"], v["tenureAlw"], v["jobAlw"]), 209), 0))
        c["otPay"] = (f"=ROUNDDOWN({R('hourly')}*{R('otH')}*1.5,-1)", lambda v: ROUNDDOWN(MUL(v["hourly"], v["otH"], 1.5), -1))
        c["nightPay"] = (f"=ROUNDDOWN({R('hourly')}*{R('nightH')}*0.5,-1)", lambda v: ROUNDDOWN(MUL(v["hourly"], v["nightH"], 0.5), -1))
        # 법정 기준(8시간 초과 2.0배)과 다르게 휴일근로 전체를 1.5배로 계산한다
        c["holPay"] = (f"=ROUNDDOWN({R('hourly')}*({R('holH')}+{R('hol8H')})*1.5,-1)",
                       lambda v: ROUNDDOWN(MUL(v["hourly"], SUM(v["holH"], v["hol8H"]), 1.5), -1))
        c["gross"] = (f"=SUM({L['basePay']}{{r}}:{L['meal']}{{r}},{L['otPay']}{{r}}:{L['holPay']}{{r}})",
                      lambda v: SUM(v["basePay"], v["tenureAlw"], v["jobAlw"], v["meal"], v["otPay"], v["nightPay"], v["holPay"]))
        # 4대보험 기준에 식대를 포함한다 (비과세 식대는 보수에서 빼는 것이 원칙)
        for k in ("np", "hi", "ei"):
            c[k] = (f"=ROUND({R('gross')}*{S.ref(k)},-1)", (lambda k: lambda v: ROUND(MUL(v["gross"], S.v(k)), -1))(k))
        c["ltc"] = (f"=ROUND({R('hi')}*{S.ref('ltc')},-1)", lambda v: ROUND(MUL(v["hi"], S.v("ltc")), -1))
        c["tax"] = (f"=VLOOKUP({R('gross')}-MIN({R('meal')},{S.ref('meal')}),간이세액표!$A$3:$G${2 + len(TAX_ROWS)},MIN({R('family')},5)+2,TRUE)",
                    lambda v: vlookup_approx(SUB(v["gross"], MIN(v["meal"], S.v("meal"))), TAX_ROWS, min(v["family"], 5) + 2))
        c["ltax"] = (f"=ROUNDDOWN({R('tax')}*0.1,-1)", lambda v: ROUNDDOWN(MUL(v["tax"], 0.1), -1))
        c["ded"] = (f"=SUM({L['np']}{{r}}:{L['ltax']}{{r}})",
                    lambda v: SUM(v["np"], v["hi"], v["ltc"], v["ei"], v["tax"], v["ltax"]))
        c["net"] = (f"={R('gross')}-{R('ded')}", lambda v: SUB(v["gross"], v["ded"]))
        return c

    def inputs(e, f, ym, S):
        return {"hire": e["hireDate"], "family": f["taxFamily"], "basePay": f["basePay"],
                "jobAlw": S.v("jobAlw") if e["_hq"] == "생산본부" else 0,
                "meal": MEAL_LIMIT}

    return {
        "id": "daramulsan", "months": ["2026-09", "2026-10", "2026-11", "2026-12"],
        "order": lambda months: list(reversed(months)) + ["근태", "설정", "간이세액표"],
        "columns": cols, "L": L, "settings": S, "calc": calc, "inputs": inputs,
        "title": lambda ym: [("A", 1, "다라물산 급여대장", "title"), ("A", 2, "귀속: %s년 %s월" % tuple(ym.split("-")), None),
                             ("E", 2, "근속 기준일", None), ("F", 2, month_bounds(ym)[1], "date")],
        "groups": None, "headerRow": 4, "firstRow": 5, "totals": ("empNo", "합 계"), "time": "wide_index", "timeSheet": "근태",
        "taxSheet": "간이세액표", "empNoNumeric": True,
        "rules": {
            "ordinaryHourlyWage": {"expr": "round((basePay+tenureAllowance+jobAllowance)/209)", "basis": ["basePay", "tenureAllowance", "jobAllowance"], "divisor": 209, "rounding": "round1", "divisorFrom": "literal"},
            "tenureAllowance": {"expr": "min(DATEDIF(hireDate, payMonthEnd, 'Y'), 10) * 10000"},
            "overtimePremium": {"rate": 1.5, "rateFrom": "literal", "rounding": "floor10"},
            "nightPremium": {"rate": 0.5, "rateFrom": "literal", "rounding": "floor10"},
            "holidayPremium": {"within8h": 1.5, "over8h": 1.5, "rateFrom": "literal", "rounding": "floor10"},
            "grossPay": "SUM(basePay, tenureAllowance, jobAllowance, meal, overtimePay, nightPay, holidayPay)",
            "nonTaxableMeal": {"limit": MEAL_LIMIT},
            "mealInInsuranceBase": True,
            "insuranceBase": "grossPay",
            "insuranceRates": RATES[2026], "rateFrom": "설정 시트 셀 참조",
            "insuranceRounding": "round10",
            "longTermCareBase": "healthInsurancePremium",
            "incomeTax": {"method": "VLOOKUP(지급합계-min(식대,한도), 간이세액표, 가족수 열, TRUE)", "withholdingRatio": False, "rounding": "table"},
            "localIncomeTax": {"rate": 0.1, "rounding": "floor10"},
            "netPay": "grossPay - deductionTotal",
        },
        "legalDeviations": [
            {"rule": "holidayPremium", "company": "8시간 초과분도 1.5배", "statutory": "8시간 이내 1.5배, 초과 2.0배 (근로기준법 제56조)", "cells": "휴일근로수당 열 전체"},
            {"rule": "mealInInsuranceBase", "company": "비과세 식대를 4대보험 기준에 포함", "statutory": "보수월액은 비과세 소득 제외", "cells": "국민연금·건강·고용보험 열 전체"},
        ],
    }


def _maba():
    cols = [("empNo", "EMP_NO"), ("name", "NAME"), ("dept", "DEPT"), ("family", "FAMILY_CNT"), ("ratio", "WHT_RATE"),
            ("basePay", "BASE_PAY"), ("posAlw", "POS_ALW"), ("meal", "MEAL"),
            ("otH", "OT_HRS"), ("nightH", "NIGHT_HRS"), ("holH", "HOL_HRS"), ("hol8H", "HOL_OVER8_HRS"),
            ("hourly", "HOURLY"), ("otPay", "OT_PAY"), ("nightPay", "NIGHT_PAY"), ("holPay", "HOL_PAY"),
            ("gross", "GROSS"), ("taxable", "TAXABLE"), ("np", "NPS"), ("hi", "NHIS"), ("ltc", "LTC"), ("ei", "EIS"),
            ("tax", "INC_TAX"), ("ltax", "LOCAL_TAX"), ("ded", "DED_TOTAL"), ("net", "NET_PAY")]
    names = {"np": "NPS_RATE", "hi": "NHIS_RATE", "ltc": "LTC_RATE", "ei": "EIS_RATE", "meal": "MEAL_LIMIT",
             "hours": "MONTH_HOURS", "ot": "OT_RATE", "night": "NIGHT_RATE", "hol": "HOL_RATE", "hol8": "HOL8_RATE",
             "local": "LOCAL_RATE", "posAlw": "POS_ALW"}
    S = Settings("SETTINGS", _items(RATES[2026], [
        ("hours", "MONTH_HOURS", 209, None), ("ot", "OT_RATE", 1.5, None), ("night", "NIGHT_RATE", 0.5, None),
        ("hol", "HOL_RATE", 1.5, None), ("hol8", "HOL8_RATE", 2.0, None), ("local", "LOCAL_RATE", 0.1, "pct"),
        ("posAlw", "POS_ALW", 100_000, "won")]), "B", 2, names)
    L = col_letters([k for k, _ in cols])

    def calc(ym, T, S):
        R = lambda k: "%s{r}" % L[k]  # noqa: E731
        n = lambda k: S.ref(k)  # noqa: E731
        c = {}
        for k in ("otH", "nightH", "holH", "hol8H"):
            c[k] = (f"=SUMIFS({{tcolrange_{k}}},{T.keyRange},$A{{r}},{T.monthRange},\"{ym}\")", None)
        c["hourly"] = (f"=({R('basePay')}+{R('posAlw')})/{n('hours')}", lambda v: DIV(SUM(v["basePay"], v["posAlw"]), S.v("hours")))
        c["otPay"] = (f"=ROUNDDOWN({R('hourly')}*{R('otH')}*{n('ot')},0)", lambda v: ROUNDDOWN(MUL(v["hourly"], v["otH"], S.v("ot")), 0))
        c["nightPay"] = (f"=ROUNDDOWN({R('hourly')}*{R('nightH')}*{n('night')},0)", lambda v: ROUNDDOWN(MUL(v["hourly"], v["nightH"], S.v("night")), 0))
        c["holPay"] = (f"=ROUNDDOWN({R('hourly')}*({R('holH')}*{n('hol')}+{R('hol8H')}*{n('hol8')}),0)",
                       lambda v: ROUNDDOWN(MUL(v["hourly"], SUM(MUL(v["holH"], S.v("hol")), MUL(v["hol8H"], S.v("hol8")))), 0))
        c["gross"] = (f"=SUM({L['basePay']}{{r}}:{L['meal']}{{r}})+SUM({L['otPay']}{{r}}:{L['holPay']}{{r}})",
                      lambda v: SUM(v["basePay"], v["posAlw"], v["meal"], v["otPay"], v["nightPay"], v["holPay"]))
        c["taxable"] = (f"={R('gross')}-MIN({R('meal')},{n('meal')})", lambda v: SUB(v["gross"], MIN(v["meal"], S.v("meal"))))
        for k in ("np", "hi", "ei"):
            c[k] = (f"=TRUNC({R('taxable')}*{n(k)})", (lambda k: lambda v: TRUNC(MUL(v["taxable"], S.v(k)), 0))(k))
        c["ltc"] = (f"=TRUNC({R('hi')}*{n('ltc')})", lambda v: TRUNC(MUL(v["hi"], S.v("ltc")), 0))
        c["tax"] = (f"=ROUNDDOWN(VLOOKUP({R('taxable')},TAX_TBL,MIN({R('family')},5)+2,TRUE)*{R('ratio')}/100,-1)",
                    lambda v: ROUNDDOWN(DIV(MUL(vlookup_approx(v["taxable"], TAX_ROWS, min(v["family"], 5) + 2), v["ratio"]), 100), -1))
        c["ltax"] = (f"=ROUNDDOWN({R('tax')}*{n('local')},-1)", lambda v: ROUNDDOWN(MUL(v["tax"], S.v("local")), -1))
        c["ded"] = (f"=SUM({L['np']}{{r}}:{L['ltax']}{{r}})",
                    lambda v: SUM(v["np"], v["hi"], v["ltc"], v["ei"], v["tax"], v["ltax"]))
        c["net"] = (f"={R('gross')}-{R('ded')}", lambda v: SUB(v["gross"], v["ded"]))
        return c

    def inputs(e, f, ym, S):
        return {"family": f["taxFamily"], "ratio": f["withholdRatio"], "basePay": f["basePay"],
                "posAlw": S.v("posAlw") if e["duty"] == "팀장" else 0, "meal": MEAL_LIMIT}

    return {
        "id": "mabaservice", "months": ["2026-10", "2026-11", "2026-12"],
        "order": lambda months: months + ["TIME", "SETTINGS", "TAX_TABLE"],
        "columns": cols, "L": L, "settings": S, "calc": calc, "inputs": inputs,
        "title": lambda ym: [], "groups": None, "headerRow": 1, "firstRow": 2, "totals": None,
        "time": "long_sumifs", "timeSheet": "TIME", "taxSheet": "TAX_TABLE", "empNoNumeric": False,
        "rules": {
            "ordinaryHourlyWage": {"expr": "(basePay+positionAllowance)/209", "basis": ["basePay", "positionAllowance"], "divisor": 209, "rounding": "none", "divisorFrom": "이름 정의 MONTH_HOURS"},
            "overtimePremium": {"rate": 1.5, "rateFrom": "이름 정의 OT_RATE", "rounding": "floor1"},
            "nightPremium": {"rate": 0.5, "rateFrom": "이름 정의 NIGHT_RATE", "rounding": "floor1"},
            "holidayPremium": {"within8h": 1.5, "over8h": 2.0, "rateFrom": "이름 정의 HOL_RATE·HOL8_RATE", "rounding": "floor1"},
            "grossPay": "SUM(basePay, positionAllowance, meal) + SUM(overtimePay, nightPay, holidayPay)",
            "nonTaxableMeal": {"limit": MEAL_LIMIT},
            "mealInInsuranceBase": False,
            "insuranceBase": "grossPay - min(meal, 200000)",
            "insuranceRates": RATES[2026], "rateFrom": "이름 정의 (NPS_RATE 등)",
            "insuranceRounding": "floor1",
            "longTermCareBase": "healthInsurancePremium",
            "incomeTax": {"method": "VLOOKUP(TAXABLE, TAX_TBL(이름 정의), 가족수 열, TRUE) × 원천징수비율/100", "withholdingRatio": True, "rounding": "floor10"},
            "localIncomeTax": {"rate": 0.1, "rounding": "floor10"},
            "netPay": "grossPay - deductionTotal",
        },
    }


def _datedif_y(a, b):
    return b.year - a.year - ((b.month, b.day) < (a.month, a.day))


SPECS = {"ganatech": _gana, "daramulsan": _dara, "mabaservice": _maba}


# ---------------------------------------------------------------- 근태 시트

class TimeSheet:
    """근태 시트에 실제로 쓴 내용을 그대로 기억해 두고, 엑셀의 VLOOKUP/INDEX-MATCH/SUMIFS처럼 찾는다"""

    def __init__(self, spec, roster, facts, months, numeric_keys=(), drop=()):
        self.kind, self.name, self.months = spec["time"], spec["timeSheet"], months
        self.rows = []  # (사번 셀 값, 이름, 부서, {월: 시간})
        for e in roster:
            if not any(active_in(e, ym) for ym in months):
                continue
            key = int(e["empNo"]) if spec["empNoNumeric"] or e["empNo"] in numeric_keys else e["empNo"]
            hrs = {ym: (facts[e["empNo"]]["hours"][ym] if active_in(e, ym) else {"ot": 0, "night": 0, "hol": 0, "hol8": 0})
                   for ym in months}
            self.rows.append((key, e["name"], e["dept"], hrs))
        self.drop = set(drop)  # (사번, 월): long 형식에서 빠뜨린 행
        if self.kind == "wide_vlookup":
            self.first, self.keycol = 5, "A"
            self.last = self.first + len(self.rows) - 1
            self.lastcol = col_letters(range(3 + 4 * len(months)))[2 + 4 * len(months)]
            self.range = f"{self.name}!$A${self.first}:${self.lastcol}${self.last}"
        elif self.kind == "wide_index":
            self.first = 2
            self.last = self.first + len(self.rows) - 1
            self.keyRange = f"{self.name}!$A${self.first}:$A${self.last}"
        else:
            self.first = 2
            self.long = [(k, ym, h[ym]) for ym in months for k, _, _, h in self.rows if (k, ym) not in self.drop]
            self.last = self.first + len(self.long) - 1
            self.keyRange = f"{self.name}!$A${self.first}:$A${self.last}"
            self.monthRange = f"{self.name}!$B${self.first}:$B${self.last}"

    KINDS = ("ot", "night", "hol", "hol8")

    def placeholders(self, ym):
        mi = self.months.index(ym)
        out = {}
        for i, k in enumerate(self.KINDS):
            key = k + "H"
            if self.kind == "wide_vlookup":
                out["tcol_" + key] = 4 + 4 * mi + i
            elif self.kind == "wide_index":
                c = col_letters(range(3 + 4 * len(self.months)))[2 + 4 * mi + i]
                out["tcolrange_" + key] = f"{self.name}!${c}${self.first}:${c}${self.last}"
            else:
                c = "CDEF"[i]
                out["tcolrange_" + key] = f"{self.name}!${c}${self.first}:${c}${self.last}"
        return out

    def lookup(self, key, ym, kind):
        if self.kind == "long_sumifs":
            return sum(h[kind] for k, m, h in self.long if k == key and m == ym)  # 없으면 0 (SUMIFS)
        for k, _, _, h in self.rows:
            if k == key and type(k) is type(key):
                return h[ym][kind]
        return NA  # 정확히 일치(FALSE/0) 조회 실패

    def write(self, book):
        sh = book.sheet(self.name)
        if self.kind == "wide_vlookup":
            sh.put("A", 1, "월별 근태 집계 (연장·야간·휴일, 단위: 시간)", "title")
            for c, h in zip("ABC", ("사번", "성명", "부서")):
                sh.merge(f"{c}3:{c}4", h)
            L = col_letters(range(3 + 4 * len(self.months)))
            for mi, ym in enumerate(self.months):
                a, b = L[3 + 4 * mi], L[6 + 4 * mi]
                sh.merge(f"{a}3:{b}3", "%s월" % ym[-2:])
                for i, h in enumerate(("연장", "야간", "휴일", "휴일8h초과")):
                    sh.put(L[3 + 4 * mi + i], 4, h, "head")
            for n, (key, name, dept, hrs) in enumerate(self.rows):
                r = self.first + n
                sh.put("A", r, key, None if isinstance(key, int) else "text")
                sh.put("B", r, name)
                sh.put("C", r, dept)
                for mi, ym in enumerate(self.months):
                    for i, k in enumerate(self.KINDS):
                        sh.put(L[3 + 4 * mi + i], r, hrs[ym][k])
        elif self.kind == "wide_index":
            L = col_letters(range(2 + 4 * len(self.months)))
            sh.put("A", 1, "사번", "head")
            sh.put("B", 1, "성명", "head")
            for mi, ym in enumerate(self.months):
                for i, h in enumerate(("연장", "야간", "휴일", "휴일8h초과")):
                    sh.put(L[2 + 4 * mi + i], 1, "%s월 %s" % (ym[-2:], h), "head")
            for n, (key, name, _, hrs) in enumerate(self.rows):
                r = self.first + n
                sh.put("A", r, key)
                sh.put("B", r, name)
                for mi, ym in enumerate(self.months):
                    for i, k in enumerate(self.KINDS):
                        sh.put(L[2 + 4 * mi + i], r, hrs[ym][k])
        else:
            for c, h in zip("ABCDEF", ("EMP_NO", "YM", "OT_HRS", "NIGHT_HRS", "HOL_HRS", "HOL_OVER8_HRS")):
                sh.put(c, 1, h, "head")
            for n, (key, ym, h) in enumerate(self.long):
                r = self.first + n
                sh.put("A", r, key, "text")
                sh.put("B", r, ym, "text")
                for c, k in zip("CDEF", self.KINDS):
                    sh.put(c, r, h[k])


# ---------------------------------------------------------------- 사고 계획

def _plan(spec, roster, facts, rng):
    """회사별로 일부러 넣을 사고. 시트 단위(sheet) / 행 단위(row) 덮어쓰기와 정답지 기록용 설명을 만든다"""
    months = spec["months"]
    L = spec["L"]
    S = spec["settings"]
    active = lambda ym: [e for e in roster if active_in(e, ym)]  # noqa: E731
    used = set()

    def pick(ym, cond=lambda e: True):
        pool = [e for e in active(ym) if e["empNo"] not in used and cond(e)]
        e = rng.choice(pool)
        used.add(e["empNo"])
        return e

    def newest(ym, after):
        pool = [e for e in active(ym) if e["hireDate"] >= after and e["empNo"] not in used]
        if not pool:
            return None
        e = max(pool, key=lambda x: x["hireDate"])
        used.add(e["empNo"])
        return e

    sheet_over, row_over, plan = {}, {}, {"numericKeys": set(), "drop": set(), "bottom": {}, "afterTotal": {}, "notes": [],
                                          "spaceNames": set()}
    stale = RATES[2025]
    R = lambda k: "%s{r}" % L[k]  # noqa: E731

    def note(**kw):
        plan["notes"].append(kw)

    sid = spec["id"]
    if sid == "ganatech":
        ym = months[0]  # 작년 양식을 복사해서 만든 7월 시트: 요율이 숫자로 박혀 있음
        sheet_over[ym] = {
            "np": (f"=ROUNDDOWN({R('taxable')}*{stale['np']},-1)", lambda v: ROUNDDOWN(MUL(v["taxable"], stale["np"]), -1)),
            "hi": (f"=ROUNDDOWN({R('taxable')}*{stale['hi']},-1)", lambda v: ROUNDDOWN(MUL(v["taxable"], stale["hi"]), -1)),
            "ltc": (f"=ROUNDDOWN({R('hi')}*{stale['ltc']},-1)", lambda v: ROUNDDOWN(MUL(v["hi"], stale["ltc"]), -1)),
        }
        note(type="STALE_RATE_LITERAL", sheet=ym, columns=["np", "hi", "ltc"], scope="sheet", legalDeviation=True,
             detail="7월 시트만 작년(2025) 요율을 수식에 숫자로 넣음: 국민연금 4.5%, 건강보험 3.545%, 장기요양 12.95%. 설정 시트(2026 요율)가 정답",
             needs=["FORMULA_READ", "RULE_EXTRACTION", "RATE_TABLE_BY_PERIOD", "LEGAL_RULE_COMPARE"])
        for _ in range(2):
            e = pick(months[1])
            row_over[(months[1], e["empNo"])] = {"tax": ("hard", None)}
            note(type="HARDCODED_EQUAL", sheet=months[1], empNo=e["empNo"], columns=["tax"],
                 detail="소득세 수식 대신 같은 금액을 숫자로 입력", needs=["FORMULA_READ", "HARDCODED_IN_FORMULA_COLUMN"])
        e = pick(months[2], lambda e: active_in(e, months[1])
                 and facts[e["empNo"]]["hours"][months[2]]["ot"] != facts[e["empNo"]]["hours"][months[1]]["ot"])
        row_over[(months[2], e["empNo"])] = {"otPay": ("hard_prev", months[1])}
        note(type="HARDCODED_STALE", sheet=months[2], empNo=e["empNo"], columns=["otPay"],
             detail="연장수당 수식 대신 지난달 금액을 숫자로 입력 (연장시간이 바뀌었는데 금액은 그대로)",
             needs=["FORMULA_READ", "HARDCODED_IN_FORMULA_COLUMN", "RECOMPUTE_CHECK"])
        ym = months[3]
        sheet_over[ym] = {"gross": (f"=SUM({L['basePay']}{{r}}:{L['meal']}{{r}},{L['otPay']}{{r}}:{L['nightPay']}{{r}})",
                                    lambda v: SUM(v["basePay"], v["posAlw"], v["meal"], v["otPay"], v["nightPay"]))}
        note(type="SUM_RANGE_MISSING", sheet=ym, columns=["gross"], scope="sheet",
             detail="지급계 SUM 범위에서 휴일수당 열이 빠짐 (휴일근로가 있는 행만 금액이 틀림)",
             needs=["FORMULA_READ", "RECOMPUTE_CHECK", "RULE_EXTRACTION"])
        e = newest(months[4], month_bounds(months[0])[0])
        if e:
            blank = ["otH", "nightH", "holH", "hol8H", "hourly", "otPay", "nightPay", "holPay", "taxable", "np", "hi", "ltc", "ei", "tax", "ltax", "ded"]
            row_over[(months[4], e["empNo"])] = {k: ("blank",) for k in blank}
            plan["bottom"][months[4]] = e["empNo"]
            note(type="NEW_ROW_FORMULA_MISSING", sheet=months[4], empNo=e["empNo"], columns=blank,
                 detail="신규 입사자 행을 맨 아래에 추가하면서 지급계·실지급 수식만 복사함. 근태·수당·공제가 비어 실지급 = 지급계",
                 needs=["FORMULA_READ", "HARDCODED_IN_FORMULA_COLUMN", "RECOMPUTE_CHECK"])
        for _ in range(2):
            plan["spaceNames"].add(pick(months[-1])["empNo"])
        e = newest(months[-1], month_bounds(months[0])[0]) or pick(months[-1])
        plan["numericKeys"].add(e["empNo"])
        note(type="LOOKUP_NA", sheet="근태", empNo=e["empNo"], columns=["otH", "nightH", "holH", "hol8H"], scope="allMonths",
             detail="최근 입사자를 근태 시트에 추가하면서 사번을 숫자로 입력해 앞자리 0이 사라짐 → 그 사람이 있는 월 시트의 VLOOKUP이 #N/A, 수당·공제·실지급·합계 행까지 #N/A",
             needs=["ERROR_VALUE", "CROSS_SHEET_LOOKUP", "MULTI_SHEET"])
    elif sid == "daramulsan":
        ym = months[0]
        sheet_over[ym] = {"ltc": (f"=ROUND({R('hi')}*{stale['ltc']},-1)", lambda v: ROUND(MUL(v["hi"], stale["ltc"]), -1))}
        note(type="STALE_RATE_LITERAL", sheet=ym, columns=["ltc"], scope="sheet", legalDeviation=True,
             detail="9월 시트 요양보험만 작년 비율 12.95%를 숫자로 넣음 (설정은 13.14%)",
             needs=["FORMULA_READ", "RULE_EXTRACTION", "RATE_TABLE_BY_PERIOD", "LEGAL_RULE_COMPARE"])
        e = pick(months[1], lambda e: facts[e["empNo"]]["hours"][months[1]]["night"] > 0)
        row_over[(months[1], e["empNo"])] = {"nightPay": ("calc", f"=ROUNDDOWN({R('hourly')}*#REF!*0.5,-1)", lambda v: REF)}
        note(type="REF_ERROR", sheet=months[1], empNo=e["empNo"], columns=["nightPay"],
             detail="참조하던 열을 지워 야간근로수당이 #REF! → 지급합계·공제·차인지급액·합계 행까지 #REF!",
             needs=["ERROR_VALUE", "FORMULA_READ"])
        for _ in range(2):
            e = pick(months[2])
            row_over[(months[2], e["empNo"])] = {"net": ("hard", None)}
            note(type="HARDCODED_EQUAL", sheet=months[2], empNo=e["empNo"], columns=["net"],
                 detail="차인지급액을 수식 대신 같은 금액 숫자로 입력", needs=["FORMULA_READ", "HARDCODED_IN_FORMULA_COLUMN"])
        for _ in range(3):
            e = pick(months[3], lambda e: active_in(e, months[2])
                     and facts[e["empNo"]]["hours"][months[3]] != facts[e["empNo"]]["hours"][months[2]])
            row_over[(months[3], e["empNo"])] = {"np": ("hard_prev", months[2])}
            note(type="HARDCODED_STALE", sheet=months[3], empNo=e["empNo"], columns=["np"],
                 detail="국민연금을 지난달 값으로 붙여넣음 (지급합계가 바뀌었는데 그대로)",
                 needs=["FORMULA_READ", "HARDCODED_IN_FORMULA_COLUMN", "RECOMPUTE_CHECK"])
        e = newest(months[3], month_bounds(months[0])[0])
        if e:
            row_over[(months[3], e["empNo"])] = {k: ("hard", None) for k in spec["calcKeys"]}
            plan["afterTotal"][months[3]] = e["empNo"]
            note(type="ROW_AFTER_TOTAL", sheet=months[3], empNo=e["empNo"], columns=spec["calcKeys"],
                 detail="합계 행 아래에 신규 입사자를 추가하고 계산 결과를 숫자로만 입력 → 합계에 빠지고 수식도 없음",
                 needs=["TOTAL_ROW_SKIP", "HARDCODED_IN_FORMULA_COLUMN", "FORMULA_READ"])
    else:
        ym = months[0]
        sheet_over[ym] = {"np": (f"=TRUNC({R('taxable')}*0.045)", lambda v: TRUNC(MUL(v["taxable"], stale["np"]), 0))}
        note(type="STALE_RATE_LITERAL", sheet=ym, columns=["np"], scope="sheet", legalDeviation=True,
             detail="10월 시트 NPS만 이름 정의(NPS_RATE) 대신 작년 요율 0.045를 숫자로 넣음",
             needs=["FORMULA_READ", "RULE_EXTRACTION", "RATE_TABLE_BY_PERIOD", "LEGAL_RULE_COMPARE"])
        e = pick(months[1], lambda e: sum(facts[e["empNo"]]["hours"][months[1]].values()) > 0)
        plan["drop"].add((e["empNo"], months[1]))
        note(type="TIME_ROW_MISSING", sheet="TIME", empNo=e["empNo"], columns=["otH", "nightH", "holH", "hol8H"],
             detail="TIME 시트에 이 사람의 11월 행이 없음 → SUMIFS가 오류 없이 0을 돌려줘 수당이 조용히 빠짐",
             needs=["CROSS_SHEET_LOOKUP", "CROSS_FILE_MATCH", "RECOMPUTE_CHECK"])
        for _ in range(2):
            e = pick(months[1])
            row_over[(months[1], e["empNo"])] = {"tax": ("hard", None)}
            note(type="HARDCODED_EQUAL", sheet=months[1], empNo=e["empNo"], columns=["tax"],
                 detail="INC_TAX를 수식 대신 같은 금액 숫자로 입력", needs=["FORMULA_READ", "HARDCODED_IN_FORMULA_COLUMN"])
        ym = months[2]
        rows = sorted(active(ym), key=lambda e: (e["dept"], e["empNo"]))
        half = {e["empNo"] for e in rows[len(rows) // 2:]}
        for emp in half:
            row_over.setdefault((ym, emp), {})["ded"] = (
                "calc", f"=SUM({L['np']}{{r}}:{L['tax']}{{r}})", lambda v: SUM(v["np"], v["hi"], v["ltc"], v["ei"], v["tax"]))
        note(type="SUM_RANGE_MISSING", sheet=ym, empNos=sorted(half), columns=["ded"],
             detail="12월 시트 아래쪽 절반 행의 DED_TOTAL 범위에서 LOCAL_TAX가 빠짐 (위쪽 절반은 정상)",
             needs=["FORMULA_READ", "RECOMPUTE_CHECK", "RULE_EXTRACTION"])
    return sheet_over, row_over, plan


# ---------------------------------------------------------------- 쓰기

def build(company, roster, facts, out_dir, rng):
    spec = SPECS[company["id"]]()
    spec["calcKeys"] = [k for k in spec["calc"](spec["months"][0], _Dummy(), spec["settings"])]
    months, L, S = spec["months"], spec["L"], spec["settings"]
    sheet_over, row_over, plan = _plan(spec, roster, facts, rng)
    T = TimeSheet(spec, roster, facts, months, plan["numericKeys"], plan["drop"])

    path = out_dir / "급여대장.xlsx"
    book = Book(path)
    sheets = {name: None for name in spec["order"](months)}
    # xlsxwriter는 만든 순서대로 시트가 놓이므로 순서대로 만들고 내용은 나중에 채운다
    for name in sheets:
        if name == S.sheet:
            sheets[name] = _write_settings(book, S, spec)
        elif name == spec["taxSheet"]:
            sheets[name] = _write_tax(book, spec)
        elif name == T.name:
            T.write(book)
        else:
            sheets[name] = book.sheet(name)
    if S.names:
        for k, nm in S.names.items():
            book.wb.define_name(nm, f"={S.sheet}!${S.value_col}${S.row[k]}")
        book.wb.define_name("TAX_TBL", f"={spec['taxSheet']}!$A$2:$G${1 + len(TAX_ROWS)}")

    sheet_meta, values = {}, {}
    for ym in months:
        sheet_meta[ym], values[ym] = _write_month(sheets[ym], book, spec, ym, roster, facts, T, sheet_over.get(ym, {}),
                                                  row_over, plan, values)
    book.close()

    accidents = _accidents(spec, plan, sheet_meta, values)
    header_map = {h: k for k, h in spec["columns"]}
    key = {
        "file": path.name, "kind": "PAYROLL", "closestEntity": "PAY_RESULT_HISTORY", "supported": False,
        "sheetOrder": list(sheets),
        "sheets": {**{ym: {"role": "monthlyPayroll", **m} for ym, m in sheet_meta.items()},
                   S.sheet: {"role": "settings", "values": {k: v for k, _, v, *_ in S.items},
                             "cells": {k: S.ref(k) for k, *_ in S.items}},
                   spec["taxSheet"]: {"role": "lookupTable", "note": "단순화한 간이세액표 (실제 국세청 표 아님)"},
                   T.name: {"role": "timeInput", "layout": T.kind, "firstDataRow": T.first, "lastDataRow": T.last}},
        "expectedMapping": {"monthlyPayroll": {h.replace("\n", " "): k for h, k in header_map.items()}},
        "rules": spec["rules"],
        "legalDeviations": spec.get("legalDeviations", []) + [
            {"rule": "insuranceRates", "company": a["detail"], "statutory": "2026 요율", "cells": a["sheet"]}
            for a in accidents if a["type"] == "STALE_RATE_LITERAL"],
        "accidents": accidents,
        "valueNoise": ([{"type": "EMPNO_NUMERIC", "scope": "모든 시트", "detail": "사번을 숫자 셀로 입력해 앞자리 0이 사라짐 (00123 → 123)"}]
                       if spec["empNoNumeric"] else []) + [
            {"type": "MULTILINE_HEADER", "detail": "헤더에 줄바꿈 포함 (예: '연장\\n시간'). 매핑에서는 공백 하나로 본다"}],
    }
    if spec["groups"]:
        key["valueNoise"].append({"type": "TWO_ROW_HEADER", "detail": "월 시트 3행에 병합된 묶음 헤더, 4행에 컬럼 헤더"})
    if plan["spaceNames"]:
        key["valueNoise"].append({"type": "SURROUNDING_SPACES", "column": "성명", "empNos": sorted(plan["spaceNames"]),
                                  "detail": "모든 월 시트에서 이름 앞뒤에 공백"})
    if spec["id"] == "daramulsan":
        key["valueNoise"].append({"type": "TITLE_ROW_LOOKS_LIKE_HEADER", "row": 2,
                                  "detail": "2행(귀속월·근속 기준일)에 값이 3칸 있어 '값 2칸 이상인 첫 행 = 헤더' 규칙이면 헤더로 오인"})
    return key


class _Dummy:
    range = keyRange = monthRange = ""


def _write_settings(book, S, spec):
    sh = book.sheet(S.sheet)
    if S.names:
        for c, h in zip("ABC", ("KEY", "VALUE", "DESC")):
            sh.put(c, 1, h, "head")
        for k, label, v, fmt in S.items:
            r = S.row[k]
            sh.put("A", r, S.names.get(k, k.upper()))
            sh.put("B", r, v, fmt)
            sh.put("C", r, label)
    else:
        label_col = chr(ord(S.value_col) - 1)
        sh.put("A", 1, "급여 계산 기준 (2026년)", "title")
        sh.put(label_col, S.first_row - 1, "항목", "head")
        sh.put(S.value_col, S.first_row - 1, "값", "head")
        for k, label, v, fmt in S.items:
            sh.put(label_col, S.row[k], label)
            sh.put(S.value_col, S.row[k], v, fmt)
    sh.widths({"A": 26, "B": 26, "C": 16})
    return sh


def _write_tax(book, spec):
    sh = book.sheet(spec["taxSheet"])
    heads = ["월급여(이상)", "월급여(미만)", "1명", "2명", "3명", "4명", "5명 이상"]
    first = 4 if spec["id"] == "ganatech" else (3 if spec["id"] == "daramulsan" else 2)
    if first > 2:
        sh.put("A", 1, "근로소득 간이세액표 (단순화, 공제대상 가족 수별, 단위: 원)", "title")
    for c, h in zip("ABCDEFG", heads):
        sh.put(c, first - 1, h, "head")
    for n, row in enumerate(TAX_ROWS):
        for c, v in zip("ABCDEFG", row):
            sh.put(c, first + n, v, "won")
    return sh


def _write_month(sh, book, spec, ym, roster, facts, T, sheet_over, row_over, plan, values):
    L, S = spec["L"], spec["settings"]
    cols = spec["columns"]
    base = spec["calc"](ym, T, S)
    calc = {**base, **sheet_over}
    ph = T.placeholders(ym)

    for col, row, v, fmt in spec["title"](ym):
        sh.put(col, row, v, fmt)
    hr = spec["headerRow"]
    for k, h in cols:
        sh.put(L[k], hr, h, "head")
    if spec["groups"]:
        for a, b, label in spec["groups"]:
            if a == b:
                sh.put(L[a], hr - 1, label, "head")
            else:
                sh.merge(f"{L[a]}{hr - 1}:{L[b]}{hr - 1}", label)
    sh.ws.freeze_panes(hr, 2)
    sh.widths({L["name"]: 10, L["dept"]: 14})

    emps = sorted([e for e in roster if active_in(e, ym)], key=lambda e: (e["dept"], e["empNo"]))
    bottom = plan["bottom"].get(ym)
    after = plan["afterTotal"].get(ym)
    order = [e for e in emps if e["empNo"] not in (bottom, after)]
    order += [e for e in emps if e["empNo"] == bottom]
    row_of, vals = {}, {}
    r = spec["firstRow"]
    for e in order:
        row_of[e["empNo"]] = r
        vals[e["empNo"]] = _write_row(sh, spec, ym, r, e, facts, T, calc, base, ph, row_over.get((ym, e["empNo"]), {}), values, plan)
        r += 1
    last = r - 1
    total_row = None
    if spec["totals"]:
        total_row = r
        lk, label = spec["totals"]
        sh.put(L[lk], r, label, "totalLabel")
        start = [k for k, _ in cols].index("basePay")
        for k, _ in cols[start:]:
            if k == "hourly":
                continue
            col_vals = [vals[x]["actual"][k] for x in vals]
            sh.formula(L[k], r, f"=SUM({L[k]}{spec['firstRow']}:{L[k]}{last})",
                       first_err(*col_vals) or sum(v or 0 for v in col_vals), "total")
        r += 1
    if after:
        e = next(x for x in emps if x["empNo"] == after)
        row_of[after] = r
        vals[after] = _write_row(sh, spec, ym, r, e, facts, T, calc, base, ph, row_over.get((ym, after), {}), values, plan)
    for emp, v in vals.items():  # 퇴직금추계표가 쓴다: 담당자가 옮겨 적는 값(actual)과 사고 없는 값(ideal)
        facts[emp].setdefault("pay", {})[ym] = {"gross": v["ideal"]["gross"], "grossActual": v["actual"]["gross"],
                                                "hourly": v["ideal"]["hourly"]}
    meta = {"payMonth": ym, "headerRows": [hr - 1, hr] if spec["groups"] else [hr], "firstDataRow": spec["firstRow"],
            "lastDataRow": last, "totalRow": total_row, "rowOf": row_of,
            "titleRows": [row for _, row, _, _ in spec["title"](ym)],
            "formulas": {k: calc[k][0].format(r=spec["firstRow"], **ph) for k in calc}}
    if after:
        meta["rowsAfterTotal"] = [row_of[after]]
    return meta, vals


HOUR_KEYS = ("otH", "nightH", "holH", "hol8H")


def _write_row(sh, spec, ym, r, e, facts, T, calc, base, ph, over, values, plan):
    """한 행을 쓴다. actual = 파일에 들어간 값(사고 포함), ideal = 사고가 없었다면 나왔을 값"""
    L, S = spec["L"], spec["settings"]
    f = facts[e["empNo"]]
    actual = {"empNo": int(e["empNo"]) if spec["empNoNumeric"] else e["empNo"], "name": e["name"], "dept": e["dept"],
              "position": e["position"]}
    actual.update(spec["inputs"](e, f, ym, S))
    ideal = dict(actual)
    if e["empNo"] in plan["spaceNames"]:
        actual["name"] = " " + e["name"] + "  "
    for k, _ in spec["columns"]:
        if k in calc:
            continue
        x = actual.get(k)
        fmt = "text" if k == "empNo" and isinstance(x, str) else ("date" if k == "hire" else ("won" if isinstance(x, int) and x >= 1000 else None))
        sh.put(L[k], r, x, fmt)
    for k, (tmpl, fn) in calc.items():
        hour = k in HOUR_KEYS
        ideal[k] = f["hours"][ym][k[:-1]] if hour else base[k][1](ideal)
        formula_value = T.lookup(actual["empNo"], ym, k[:-1]) if hour else fn(actual)
        mode = over.get(k)
        if mode is None:
            actual[k] = formula_value
            sh.formula(L[k], r, tmpl.format(r=r, **ph), formula_value, _fmt(k))
        elif mode[0] == "blank":
            actual[k] = None
        elif mode[0] == "hard":
            actual[k] = formula_value
            sh.put(L[k], r, _plain(formula_value), _fmt(k))
        elif mode[0] == "hard_prev":
            actual[k] = values[mode[1]][e["empNo"]]["actual"][k]
            sh.put(L[k], r, _plain(actual[k]), _fmt(k))
        elif mode[0] == "calc":
            actual[k] = mode[2](actual)
            sh.formula(L[k], r, mode[1].format(r=r, **ph), actual[k], _fmt(k))
    return {"actual": actual, "ideal": ideal}


def _plain(x):
    return int(x) if isinstance(x, float) and x.is_integer() else x


def _fmt(k):
    return None if k.endswith("H") or k in ("family", "ratio") else ("num" if k == "hourly" else "won")


def _accidents(spec, plan, meta, values):
    """계획한 사고를 셀 주소와 실제값·기대값이 있는 정답지 항목으로 바꾼다"""
    L = spec["L"]
    header = {k: h.replace("\n", " ") for k, h in spec["columns"]}
    involved = {a.get("empNo") for a in plan["notes"]} | {x for a in plan["notes"] for x in a.get("empNos", [])}

    def diff_cols(sheet, emp):
        v = values[sheet][emp]
        return [header[k] for k, _ in spec["columns"] if v["actual"].get(k) != v["ideal"].get(k) and k != "name"]

    out = []
    for n, a in enumerate(plan["notes"], 1):
        item = {"id": "%s-PAY-%02d" % (spec["id"][:4].upper(), n), "file": "급여대장.xlsx", "type": a["type"],
                "sheet": a["sheet"], "columns": [header[c] for c in a["columns"]],
                "legalDeviation": a.get("legalDeviation", False), "detectable": False, "needs": a["needs"]}
        sheet, emp = a["sheet"], a.get("empNo")
        if emp and sheet in meta:
            r = meta[sheet]["rowOf"][emp]
            v = values[sheet][emp]
            item.update(empNo=emp, rowNo=r, cells=["%s%d" % (L[c], r) for c in a["columns"]],
                        values={header[c]: {"actual": v["actual"].get(c), "expected": v["ideal"].get(c)} for c in a["columns"]},
                        cascade=diff_cols(sheet, emp))
        elif a.get("scope") == "sheet":
            m = meta[sheet]
            item["cells"] = ["%s%d:%s%d" % (L[c], m["firstDataRow"], L[c], m["lastDataRow"]) for c in a["columns"]]
            item["formula"] = {header[c]: m["formulas"][c] for c in a["columns"]}
            item["rowsWithDifferentValue"] = sorted(
                m["rowOf"][x] for x, v in values[sheet].items()
                if x not in involved and any(v["actual"][c] != v["ideal"][c] for c in a["columns"]))
        elif a.get("empNos"):
            m = meta[sheet]
            item["rows"] = sorted(m["rowOf"][x] for x in a["empNos"])
            item["rowsWithDifferentValue"] = sorted(
                m["rowOf"][x] for x in a["empNos"] if any(values[sheet][x]["actual"][c] != values[sheet][x]["ideal"][c] for c in a["columns"]))
        else:  # 근태·TIME 시트의 사고 → 영향을 받은 월 시트의 행
            item["empNo"] = emp
            item["affected"] = [{"sheet": ym, "rowNo": m["rowOf"][emp], "cascade": diff_cols(ym, emp)}
                                for ym, m in meta.items() if emp in m["rowOf"]
                                and any(values[ym][emp]["actual"][c] != values[ym][emp]["ideal"][c] for c in a["columns"])]
        item["detail"] = a["detail"]
        out.append(_clean(item))
    return out


def _clean(x):
    if isinstance(x, dict):
        return {k: _clean(v) for k, v in x.items() if not k.startswith("_")}
    if isinstance(x, (list, tuple, set)):
        return [_clean(v) for v in x]
    if isinstance(x, XlErr):
        return str(x)
    if isinstance(x, (dt.date,)):
        return x.isoformat()
    return x
