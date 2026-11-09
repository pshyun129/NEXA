"""인사 파일 생성기들이 같이 쓰는 정답지 도우미"""
import datetime as dt

from .xl import XlErr


class Accidents:
    """일부러 넣은 사고 목록. id는 회사·파일 접두어 + 순번"""

    def __init__(self, prefix, file):
        self.prefix, self.file, self.items = prefix, file, []

    def add(self, type, sheet, detail, needs, legal=False, **kw):
        self.items.append({"id": "%s-%02d" % (self.prefix, len(self.items) + 1), "file": self.file, "type": type,
                           "sheet": sheet, **kw, "legalDeviation": legal, "detectable": False, "needs": needs,
                           "detail": detail})


def clean(x):
    """정답지 JSON으로 쓸 수 있게: 날짜 → ISO, 엑셀 오류 → 문자열, '_'로 시작하는 키는 버림"""
    if isinstance(x, dict):
        return {k: clean(v) for k, v in x.items() if not str(k).startswith("_")}
    if isinstance(x, (list, tuple, set)):
        return [clean(v) for v in x]
    if isinstance(x, XlErr):
        return str(x)
    if isinstance(x, dt.date):
        return x.isoformat()
    if isinstance(x, float) and x.is_integer():
        return int(x)
    return x


def to_fullwidth(s):
    """'12' → '１２' (전각 숫자)"""
    return "".join(chr(ord(c) + 0xFEE0) if c.isdigit() else c for c in str(s))
