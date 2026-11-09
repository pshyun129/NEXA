"""
추가 가상 회사(newco) 공통 도우미: 인물 뽑기, 날짜·금액 표기, 직원명부·조직 파일 쓰기, 정답지 직원명부 오류.

기존 3개 회사(generate.py + hrfiles/)와 코드·난수를 공유하지 않는다. 회사마다 `random.Random("<id>:<용도>:<seed>")`를 따로 써서
기존 회사 출력이 바뀌지 않고, 회사 하나를 고쳐도 다른 회사 출력이 바뀌지 않는다.
"""
import csv
import datetime as dt
import random
import re

from hrfiles.common import clean
from hrfiles.xl import Book

CUTOVER = dt.date(2027, 1, 1)
BASE_DATE = dt.date(2026, 12, 31)
EXCEL_EPOCH = dt.date(1899, 12, 30)
ISO = re.compile(r"^\d{4}-\d{2}-\d{2}$")

# 2026 법정 기준 (README '사실 기준'과 같은 값) + 국민연금 기준소득월액 상·하한
RATES_2026 = {"np": 0.0475, "hi": 0.03595, "ltc": 0.1314, "ei": 0.009}
RATES_2025 = {"np": 0.045, "hi": 0.03545, "ltc": 0.1295, "ei": 0.009}
# 국민연금 기준소득월액 하한·상한은 매년 7월에 바뀐다 (귀속월 기준)
NP_LIMITS = [("2024-07", 390_000, 6_170_000),  # 2024-07 ~ 2025-06
             ("2025-07", 400_000, 6_370_000),  # 2025-07 ~ 2026-06
             ("2026-07", 410_000, 6_590_000)]  # 2026-07 ~ 2027-06 (보건복지부고시 제2026-31호)


def np_limits(ym):
    """귀속월 'YYYY-MM' → (하한, 상한)"""
    return next((lo, hi) for start, lo, hi in reversed(NP_LIMITS) if ym >= start)


# 추가 회사의 급여는 모두 2026-07 이후 귀속이라 이 값을 쓴다 (귀속월이 그 전이면 np_limits로)
NP_FLOOR, NP_CAP = np_limits("2026-07")
NP_CAP_OLD = np_limits("2024-07")[1]           # 6,170,000: 2024-07 ~ 2025-06 상한 ('지난 상한' 사고용)
MIN_WAGE = {2025: 10_030, 2026: 10_320}
NONTAX_LIMIT = 200_000                         # 식대·자가운전보조금·출산보육수당 각각 월 20만원


def rng_for(cid, part, seed):
    return random.Random("%s:%s:%d" % (cid, part, seed))


PARTS = ["saalabs", "jachaprec", "katamart", "pahagongdan"]
# 2차 추가 회사. 1차 4곳의 조각(uuid 앞 8자리 % 4)을 건드리지 않으려고 따로 둔다:
# 1차 4곳이 실제로 뽑은 인물(used에 더해짐)을 빼고, uuid 뒤 8자리로 나눈 조각에서 뽑는다
PARTS2 = ["geoneomed", "deoreocon", "meobeologis", "seoeoedu", "jeocheocall", "peoheogroup"]


def pick(df, used, n, rng, cid, weight=None, ages=(24, 59)):
    """기존 회사가 쓴 인물(used)을 빼고 n명. 추가 회사끼리는 uuid로 나눈 조각만 써서 서로 겹치지 않고,
    한 회사만 다시 만들어도 다른 회사의 인물이 바뀌지 않는다. weight(occupation) → 가중치.
    뽑은 인물은 used에 더한다 (1차 4곳은 조각이 서로 달라 used가 늘어도 뽑기 결과가 같다)"""
    if cid in PARTS:
        part = df.uuid.map(lambda u: int(u[:8], 16) % len(PARTS)) == PARTS.index(cid)
    else:
        part = df.uuid.map(lambda u: int(u[-8:], 16) % len(PARTS2)) == PARTS2.index(cid)
    pool = df[~df.uuid.isin(used) & df.age.between(*ages) & part]
    w = pool.occupation.map(weight) if weight else None
    picked = pool.sample(n=n, weights=w, random_state=rng.randint(0, 10_000))
    used.update(picked.uuid)
    return picked.to_dict("records")


def has(occ, *keys):
    return any(k in occ for k in keys)


def birth_of(age, rng):
    return dt.date(CUTOVER.year - 1 - int(age), rng.randint(1, 12), rng.randint(1, 28))


def phone(rng):
    return "010-%04d-%04d" % (rng.randint(2000, 9999), rng.randint(0, 9999))


def masked_rrn(birth, sex):
    """주민등록번호 형태의 마스킹 값만 만든다 (뒷자리 첫 숫자 = 성별·세기). 13자리 전체는 절대 만들지 않는다"""
    g = (1 if sex == "남자" else 2) + (2 if birth.year >= 2000 else 0)
    return "%s-%d******" % (birth.strftime("%y%m%d"), g)


# ---------------------------------------------------------------- 날짜·금액 표기

def fmt_date(d, style):
    """회사마다 다른 날짜 표기. 'cell'은 엑셀 날짜 셀, 'serial'은 서식 없는 일련번호(숫자)"""
    if d is None:
        return None
    return {
        "iso": lambda: d.isoformat(),
        "dot": lambda: "%d.%d.%d" % (d.year, d.month, d.day),
        "dot0": lambda: d.strftime("%Y.%m.%d"),
        "yy": lambda: d.strftime("%y-%m-%d"),
        "compact": lambda: d.strftime("%Y%m%d"),
        "slash": lambda: d.strftime("%Y/%m/%d"),
        "korean": lambda: "%d년 %d월 %d일" % (d.year, d.month, d.day),
        "hwp": lambda: "%d. %d. %d." % (d.year, d.month, d.day),
        "mdy": lambda: d.strftime("%m/%d/%Y"),
        "cell": lambda: d,
        "serial": lambda: (d - EXCEL_EPOCH).days,
    }[style]()


def date_style(rng, base, others, rate):
    return rng.choice(others) if others and rng.random() < rate else base


def won_text(n, style):
    """금액 표기 변형: 'plain' 1234500 / 'comma' '1,234,500' / 'won' '1,234,500원' / 'full' 전각 숫자"""
    if n is None:
        return None
    if style == "plain":
        return n
    s = "{:,}".format(n)
    if style == "comma":
        return s
    if style == "won":
        return s + "원"
    if style == "full":
        return "".join(chr(ord(c) + 0xFEE0) if c.isdigit() else c for c in s)
    raise ValueError(style)


# ---------------------------------------------------------------- 직원명부 오류 (정답지 errors)

class RosterErrors:
    """행 순서(i, 0부터) 기준으로 모았다가 파일을 쓴 뒤 rowNo로 바꾼다"""

    def __init__(self):
        self.items = []

    def add(self, i, field, code, detail, detectable=True):
        self.items.append({"i": i, "field": field, "code": code, "detectable": detectable, "detail": detail})

    def rows(self, first_data_row):
        out = [{"rowNo": first_data_row + e["i"], "field": e["field"], "code": e["code"],
                "detectable": e["detectable"], "detail": e["detail"]} for e in self.items]
        return sorted(out, key=lambda x: (x["rowNo"], x["field"], x["code"]))


def date_format_errors(rows, date_fields, errs):
    """쓴 값이 ISO 문자열·날짜 셀이 아니면 DATE_FORMAT (기존 회사와 같은 기준, 숫자 일련번호 포함)"""
    for i, row in enumerate(rows):
        for f in date_fields:
            v = row.get(f)
            if v is None or isinstance(v, dt.date):
                continue
            if isinstance(v, str) and ISO.match(v):
                continue
            kind = "엑셀 일련번호(서식 없음)" if isinstance(v, int) else "'%s'" % v
            errs.add(i, f, "DATE_FORMAT", "날짜 표기가 ISO가 아님: %s" % kind)


# ---------------------------------------------------------------- 파일 쓰기

def write_csv(path, header, rows, encoding, preamble=()):
    """rows: 값 목록의 목록. preamble은 헤더 위에 들어가는 줄(제목 등). 첫 데이터 행 번호를 돌려준다"""
    with open(path, "w", encoding=encoding, newline="") as f:
        w = csv.writer(f)
        for p in preamble:
            w.writerow(p)
        w.writerow(header)
        for r in rows:
            w.writerow(["" if v is None else v for v in r])
    return len(preamble) + 2


def write_roster_xlsx(path, sheet_name, columns, rows, title_rows=(), groups=None, hidden=(), widths=None):
    """columns: [(field, header)], rows: [{field: 값}]. groups: [(첫 field, 끝 field, 묶음 이름)] → 헤더 위 병합 행.
    hidden: 숨길 field. 반환: (헤더 행 번호 목록, 첫 데이터 행)"""
    from xlsxwriter.utility import xl_col_to_name
    book = Book(path)
    sh = book.sheet(sheet_name)
    col = {f: xl_col_to_name(i) for i, (f, _) in enumerate(columns)}
    r = 1
    for t in title_rows:
        sh.put("A", r, t, "title" if r == 1 else None)
        r += 1
    if title_rows:
        r += 1  # 빈 행
    head_rows = []
    if groups:
        for a, b, label in groups:
            if a == b:
                sh.put(col[a], r, label, "head")
            else:
                sh.merge("%s%d:%s%d" % (col[a], r, col[b], r), label)
        head_rows.append(r)
        r += 1
    for f, h in columns:
        sh.put(col[f], r, h, "head")
    head_rows.append(r)
    first = r + 1
    for k, row in enumerate(rows):
        for f, _ in columns:
            v = row.get(f)
            fmt = "date" if isinstance(v, dt.date) else ("text" if isinstance(v, str) else None)
            sh.put(col[f], first + k, v, fmt)
    for f in hidden:
        sh.ws.set_column("%s:%s" % (col[f], col[f]), None, None, {"hidden": True})
    for f, w in (widths or {}).items():
        if f not in hidden:
            sh.widths({col[f]: w})
    sh.ws.freeze_panes(first - 1, 2)
    book.close()
    return head_rows, first


def write_org(path, org, columns, encoding):
    with open(path, "w", encoding=encoding, newline="") as f:
        w = csv.writer(f)
        w.writerow([h for _, h in columns])
        for o in org:
            w.writerow(["" if o.get(k) is None else o[k] for k, _ in columns])


def answer_key(company, emp_file, org_file, emp_mapping, org_columns, errors, counts, hr_files, extra=None):
    key = {
        "company": company["name"], "industry": company["industry"], "cutoverDate": CUTOVER.isoformat(),
        "profile": company["profile"],
        "files": {"HR_EMPLOYEE": emp_file, "HR_ORG": org_file},
        "expectedMapping": {"HR_EMPLOYEE": emp_mapping, "HR_ORG": {h: (None if f.startswith("_") else f) for f, h in org_columns}},
        "errors": errors,
        "counts": counts,
        "source": "NVIDIA Nemotron-Personas-Korea (CC BY 4.0) 의 합성 인물을 사용",
        "hrFiles": hr_files,
    }
    if extra:
        key.update(extra)
    return clean(key)


def summary_of(key):
    by_code, by_type = {}, {}
    for e in key["errors"]:
        by_code[e["code"]] = by_code.get(e["code"], 0) + 1
    for f in key["hrFiles"].values():
        for a in f["accidents"]:
            by_type[a["type"]] = by_type.get(a["type"], 0) + 1
    return {"company": key["company"], **key["counts"], "errors": by_code, "accidents": by_type}


# ---------------------------------------------------------------- 2차 추가 회사 공통 (1차 4곳은 쓰지 않음)

DOW = "월화수목금토일"


def days_of(y, m):
    d, out = dt.date(y, m, 1), []
    while d.month == m:
        out.append(d)
        d += dt.timedelta(days=1)
    return out


def tax_of(taxable, family):
    """단순화한 간이세액표(hrfiles.payroll.TAX_ROWS) VLOOKUP(…, TRUE). 첫 구간 미만은 0. family는 1~5"""
    from hrfiles.payroll import TAX_ROWS
    from hrfiles.xl import vlookup_approx
    return 0 if taxable < TAX_ROWS[0][0] else vlookup_approx(taxable, TAX_ROWS, min(max(family, 1), 5) + 2)


def tax_formula(base_expr, family_cell, sheet="세액표"):
    from hrfiles.payroll import TAX_ROWS
    return "IF(%s<%d,0,VLOOKUP(%s,%s!$A$2:$G$%d,MIN(MAX(%s,1),5)+2,TRUE))" % (
        base_expr, TAX_ROWS[0][0], base_expr, sheet, 1 + len(TAX_ROWS), family_cell)


def write_tax_sheet(book, name="세액표"):
    from hrfiles.payroll import TAX_ROWS
    sh = book.sheet(name)
    for c, h in zip("ABCDEFG", ["이상", "미만", "1인", "2인", "3인", "4인", "5인↑"]):
        sh.put(c, 1, h, "head")
    for n, row in enumerate(TAX_ROWS):
        for c, v in zip("ABCDEFG", row):
            sh.put(c, 2 + n, v, "won")
    return {"role": "lookupTable", "note": "단순화한 간이세액표 (실제 국세청 표 아님)"}


class Settings2:
    """설정 시트: 1행 머리글, A열 항목, B열 값. items: [(키, 항목, 값, 서식)]"""

    def __init__(self, book, name, items):
        self.name, self.items = name, items
        sh = book.sheet(name)
        sh.put("A", 1, "항목", "head")
        sh.put("B", 1, "값", "head")
        self.row = {k: 2 + i for i, (k, *_r) in enumerate(items)}
        self.val = {k: v for k, _l, v, _f in items}
        for k, label, v, fmt in items:
            sh.put("A", self.row[k], label)
            sh.put("B", self.row[k], v, fmt)
        sh.widths({"A": 28, "B": 12})

    def ref(self, k):
        return "%s!$B$%d" % (self.name, self.row[k])

    def meta(self):
        return {"role": "settings", "values": self.val, "cells": {k: "%s!B%d" % (self.name, r) for k, r in self.row.items()}}


def float_boundary(x, digits=-1):
    """ROUNDDOWN(x, digits)에서 이진 부동소수 꼬리(24569.999999999996) 때문에 15자리 기준 결과와 이진 버림 결과가 다른 경우.
    생성기(hrfiles.xl)는 엑셀 ROUND 계열처럼 15자리 기준으로 계산한다 — 이 셀은 검산 도구에 따라 10원 차이가 날 수 있다"""
    import math
    from hrfiles.xl import ROUNDDOWN
    q = 10 ** (-digits)
    return ROUNDDOWN(x, digits) != math.floor(x / q) * q if x >= 0 else False


def money(v):
    return "won" if isinstance(v, (int, float)) and not isinstance(v, bool) and abs(v) >= 1000 else None
