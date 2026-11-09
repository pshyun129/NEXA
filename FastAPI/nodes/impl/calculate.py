"""계산 노드: 앞 노드의 값으로 사칙연산·1인당 금액·날짜 차이·비율을 계산한다 (LLM 없음).

"숫자는 코드" 원칙을 노드로: 금액·일수 계산을 LLM에 맡기지 않고 여기서 정확히 계산해서 다음 노드로 넘김.
예) {{n3.fields.total}} 1인당 {{n0.headcount}}  →  15000 → 조건 분기 "초과 (>) 12000"
    {{n3.fields.date}} 날짜 차이 오늘          →  38   → 조건 분기 "초과 (>) 30" (정산 기한)
"""
import math
import re
from datetime import date

from nodes.base import BaseNode, NodeContext, NodeResult
from nodes.impl.condition import _to_number

OPERATIONS = [
    "더하기 (A + B)",
    "빼기 (A - B)",
    "곱하기 (A × B)",
    "나누기 (A ÷ B)",
    "1인당 (A ÷ B, 원 단위 내림)",
    "비율 (A ÷ B × 100, %)",
    "날짜 차이 (B - A, 일)",
    "합계 (A 목록)",
]
_DATE = re.compile(r"(\d{4})\D?(\d{1,2})\D?(\d{1,2})")


def _num(value, name: str) -> float:
    n = _to_number(value)
    if n is None:
        raise ValueError(f"{name} 값이 숫자가 아닙니다: {value!r}")
    return n


def _date(value, name: str) -> date:
    """'2026-11-04', '2026.11.04', '20261104', '오늘' → date"""
    if isinstance(value, date):
        return value
    text = str(value or "").strip()
    if text in ("오늘", "today"):
        return date.today()
    m = _DATE.search(text)
    if not m:
        raise ValueError(f"{name} 값이 날짜가 아닙니다: {value!r}")
    return date(int(m.group(1)), int(m.group(2)), int(m.group(3)))


def _items(value) -> list:
    """합계용 목록: 리스트 그대로, 글자면 줄바꿈 또는 '+'로 나눔 (쉼표는 1,000 같은 금액에 쓰이므로 구분자로 안 씀)"""
    if isinstance(value, list):
        return value
    return [x for x in re.split(r"[\n+]", str(value or "")) if x.strip()]


def _clean(x: float):
    """정수로 떨어지면 int (15000.0 → 15000), 아니면 소수 둘째 자리까지"""
    return int(x) if float(x).is_integer() else round(x, 2)


def calculate(operation: str, a, b=None):
    """(결과, 계산식 문자열). 노드 밖에서도 쓸 수 있게 분리"""
    if operation == "합계 (A 목록)":
        values = [_num(v, "A 목록의") for v in _items(a)]
        if not values:
            raise ValueError("합계할 값이 없습니다")
        result = _clean(sum(values))
        return result, f"{' + '.join(f'{_clean(v):,}' for v in values)} = {result:,}"

    if operation == "날짜 차이 (B - A, 일)":
        da, db = _date(a, "A"), _date(b if b not in (None, "") else "오늘", "B")
        days = (db - da).days
        return days, f"{db.isoformat()} - {da.isoformat()} = {days}일"

    x, y = _num(a, "A"), _num(b, "B")
    if operation in ("나누기 (A ÷ B)", "1인당 (A ÷ B, 원 단위 내림)", "비율 (A ÷ B × 100, %)") and y == 0:
        raise ValueError("0으로 나눌 수 없습니다 (B가 0)")
    if operation == "더하기 (A + B)":
        r, sym = x + y, "+"
    elif operation == "빼기 (A - B)":
        r, sym = x - y, "-"
    elif operation == "곱하기 (A × B)":
        r, sym = x * y, "×"
    elif operation == "나누기 (A ÷ B)":
        r, sym = x / y, "÷"
    elif operation == "1인당 (A ÷ B, 원 단위 내림)":
        r, sym = math.floor(x / y), "÷"       # 경비 규정 제8조④: 결제 금액 ÷ 참석 인원 (원 미만 버림)
    elif operation == "비율 (A ÷ B × 100, %)":
        r = x / y * 100
        result = _clean(r)
        return result, f"{_clean(x):,} ÷ {_clean(y):,} × 100 = {result}%"
    else:
        raise ValueError(f"지원하지 않는 계산입니다: {operation}")
    result = _clean(r)
    return result, f"{_clean(x):,} {sym} {_clean(y):,} = {result:,}"


class CalculateNode(BaseNode):
    node_type = "calculate"
    name = "계산"
    category = "rule"
    description = "앞 노드 값으로 사칙연산·1인당 금액·날짜 차이·비율 계산 (LLM 없이 정확히)"
    config_fields = [
        {"key": "operation", "label": "계산", "type": "select", "options": OPERATIONS, "required": True},
        {"key": "a", "label": "A (예: {{n3.fields.total}}, 합계면 목록)", "type": "ref", "required": True},
        {"key": "b", "label": "B (예: {{n0.headcount}}, 날짜 차이에서 비우면 오늘)", "type": "text", "required": False},
    ]
    branches = []
    output_vars = [
        {"key": "result", "label": "계산 결과", "type": "number"},
        {"key": "expression", "label": "계산식", "type": "text"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        operation = ctx.config["operation"]
        result, expression = calculate(operation, ctx.config.get("a"), ctx.config.get("b"))
        return NodeResult(output={"result": result, "expression": expression})