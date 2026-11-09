"""
엑셀 수식과 같은 결과를 파이썬으로 계산하는 함수 + '수식과 계산 결과'를 함께 쓰는 시트 도우미.

openpyxl은 수식의 계산 결과를 저장하지 못하고, 이관 서비스는 저장된 계산 결과만 읽는다.
그래서 xlsxwriter로 수식과 파이썬 계산값을 같이 저장한다. 엑셀이 같은 값을 내는지는 check_formulas.py로 확인한다.
"""
import datetime as dt
from decimal import ROUND_DOWN, ROUND_HALF_UP, ROUND_UP, Decimal

from xlsxwriter.utility import xl_col_to_name


class XlErr(str):
    """엑셀 오류값. 계산 중 하나라도 오류면 결과도 그 오류 (엑셀과 같음)"""


REF = XlErr("#REF!")
NA = XlErr("#N/A")


def first_err(*xs):
    return next((x for x in xs if isinstance(x, XlErr)), None)


def xl(fn):
    """인자 중 오류가 있으면 그 오류를 돌려주고, 빈 셀(None)은 0으로 본다"""
    def wrapped(*xs):
        e = first_err(*xs)
        if e is not None:
            return e
        return fn(*[0 if x is None else x for x in xs])
    return wrapped


def _dec(x):
    # 엑셀은 15자리 유효숫자로 계산한다. 2500000*0.0475 = 118749.99999999999 같은 부동소수 꼬리를 없앤다
    return Decimal(format(x, ".15g"))


def _round(x, digits, mode):
    q = Decimal(1).scaleb(-digits)
    r = _dec(x).quantize(q, rounding=mode) if digits >= 0 else (_dec(x) / q).quantize(Decimal(1), rounding=mode) * q
    return int(r) if digits <= 0 else float(r)


ROUND = xl(lambda x, d: _round(x, d, ROUND_HALF_UP))  # 엑셀 ROUND는 0에서 먼 쪽으로 반올림 (파이썬 round와 다름)
ROUNDDOWN = xl(lambda x, d: _round(x, d, ROUND_DOWN))
ROUNDUP = xl(lambda x, d: _round(x, d, ROUND_UP))
TRUNC = ROUNDDOWN
SUM = xl(lambda *xs: sum(xs))
MIN = xl(lambda *xs: min(xs))
MAX = xl(lambda *xs: max(xs))
MUL = xl(lambda *xs: _prod(xs))
SUB = xl(lambda a, b: a - b)
DIV = xl(lambda a, b: a / b)


def _prod(xs):
    r = 1
    for x in xs:
        r *= x
    return r


def datedif_y(a, b):
    """DATEDIF(a, b, "Y"): 꽉 찬 햇수"""
    return b.year - a.year - ((b.month, b.day) < (a.month, a.day))


def datedif_m(a, b):
    """DATEDIF(a, b, "M"): 꽉 찬 개월 수"""
    return (b.year - a.year) * 12 + b.month - a.month - (b.day < a.day)


def num(x):
    """정수로 떨어지는 실수는 정수로 (엑셀 표시와 같게)"""
    if isinstance(x, float) and x.is_integer():
        return int(x)
    return x


def vlookup_approx(x, table, col):
    """VLOOKUP(x, table, col, TRUE): 첫 열이 x 이하인 마지막 행. 첫 키보다 작으면 #N/A"""
    if isinstance(x, XlErr):
        return x
    x = 0 if x is None else x  # 빈 셀은 0
    hit = None
    for row in table:
        if row[0] <= x:
            hit = row
        else:
            break
    return NA if hit is None else hit[col - 1]


def col_letters(keys, start=0):
    """컬럼 키 목록 → {키: 'A'}"""
    return {k: xl_col_to_name(start + i) for i, k in enumerate(keys)}


class Book:
    """xlsxwriter 통합 문서 + 자주 쓰는 서식"""

    def __init__(self, path):
        import xlsxwriter
        self.wb = xlsxwriter.Workbook(str(path))
        add = self.wb.add_format
        self.f = {
            "title": add({"bold": True, "font_size": 14}),
            "head": add({"bold": True, "bg_color": "#D9E1F2", "border": 1, "align": "center", "valign": "vcenter", "text_wrap": True}),
            "won": add({"num_format": "#,##0"}),
            "num": add({"num_format": "0.##"}),
            "pct": add({"num_format": "0.000%"}),
            "date": add({"num_format": "yyyy-mm-dd"}),
            "time": add({"num_format": "hh:mm"}),
            "text": add({"num_format": "@"}),
            "total": add({"bold": True, "num_format": "#,##0", "top": 1}),
            "totalLabel": add({"bold": True, "top": 1}),
        }

    def sheet(self, name):
        return Sheet(self, name)

    def close(self):
        self.wb.close()


class Sheet:
    """행 번호는 엑셀과 같은 1부터, 열은 'A' 같은 문자로 쓴다. 수식 셀 위치는 formulas에 남긴다"""

    def __init__(self, book, name):
        self.book = book
        self.name = name
        self.ws = book.wb.add_worksheet(name)
        self.formula_cells = 0

    def _rc(self, col, row):
        from xlsxwriter.utility import xl_cell_to_rowcol
        return xl_cell_to_rowcol(f"{col}{row}")

    def put(self, col, row, value, fmt=None):
        r, c = self._rc(col, row)
        f = self.book.f.get(fmt) if isinstance(fmt, str) else fmt
        if value is None:
            if f is not None:
                self.ws.write_blank(r, c, None, f)
            return
        if isinstance(value, dt.datetime):
            self.ws.write_datetime(r, c, value, f or self.book.f["date"])
        elif isinstance(value, dt.date):
            self.ws.write_datetime(r, c, dt.datetime(value.year, value.month, value.day), f or self.book.f["date"])
        elif isinstance(value, dt.time):
            self.ws.write_datetime(r, c, value, f or self.book.f["time"])
        elif isinstance(value, str):
            self.ws.write_string(r, c, value, f)
        else:
            self.ws.write_number(r, c, value, f)

    def formula(self, col, row, formula, value, fmt=None):
        r, c = self._rc(col, row)
        f = self.book.f.get(fmt) if isinstance(fmt, str) else fmt
        if isinstance(value, dt.date) and not isinstance(value, dt.datetime):
            value = (value - dt.date(1899, 12, 30)).days
        self.ws.write_formula(r, c, formula, f, num(value) if not isinstance(value, XlErr) else str(value))
        self.formula_cells += 1

    def merge(self, rng, value, fmt="head"):
        self.ws.merge_range(rng, value, self.book.f[fmt])

    def widths(self, spec):
        for col, w in spec.items():
            self.ws.set_column(f"{col}:{col}", w)
