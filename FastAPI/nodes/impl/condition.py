"""조건 분기 노드: 앞 노드의 값을 기준 값과 비교해서 참/거짓으로 나눈다.

예) {{n3.fields.total}} 이상 (>=) 1000000  →  참: 본부장 결재 흐름 / 거짓: 바로 상신
"""
from nodes.base import BaseNode, NodeContext, NodeResult

OPERATORS = ["이상 (>=)", "초과 (>)", "이하 (<=)", "미만 (<)", "같음 (==)", "다름 (!=)", "포함", "비어 있음"]
_ORDER = {
    "이상 (>=)": lambda a, b: a >= b,
    "초과 (>)": lambda a, b: a > b,
    "이하 (<=)": lambda a, b: a <= b,
    "미만 (<)": lambda a, b: a < b,
}


def _to_number(value):
    """17800, "17800", "17,800" → 숫자. 숫자가 아니면 None (True/False는 숫자로 안 봄)"""
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return value
    try:
        return float(str(value).replace(",", "").strip())
    except ValueError:
        return None


def compare(left, operator: str, right) -> bool:
    """left (연산자) right 를 계산. 크기 비교는 둘 다 숫자일 때만 가능"""
    if operator == "비어 있음":
        return left is None or left == "" or left == [] or left == {}
    if operator == "포함":
        if isinstance(left, list):
            return right in left
        return str(right) in str(left)

    ln, rn = _to_number(left), _to_number(right)
    if operator in _ORDER:
        if ln is None or rn is None:
            raise ValueError(f"숫자로 비교할 수 없습니다: {left!r} {operator} {right!r}")
        return _ORDER[operator](ln, rn)

    # 같음/다름: 둘 다 숫자면 숫자로 (100 == "100.0"), 아니면 글자로 비교
    same = ln == rn if (ln is not None and rn is not None) else str(left).strip() == str(right).strip()
    return same if operator == "같음 (==)" else not same


class ConditionNode(BaseNode):
    node_type = "condition"
    name = "조건 분기"
    category = "rule"
    description = "앞 노드의 값을 기준과 비교해서 참/거짓으로 나눔"
    config_fields = [
        {"key": "value", "label": "비교할 값 (예: {{n3.fields.total}})", "type": "ref", "required": True},
        {"key": "operator", "label": "조건", "type": "select", "options": OPERATORS, "required": True},
        {"key": "target", "label": "기준 값 (예: 1000000, '비어 있음'이면 비움)", "type": "text", "required": False},
    ]
    branches = ["참", "거짓"]
    output_vars = [
        {"key": "result", "label": "조건 결과", "type": "boolean"},
        {"key": "value", "label": "비교한 값", "type": "text"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        operator = ctx.config["operator"]
        left = ctx.config.get("value")      # 바인딩이 이미 실제 값으로 바뀌어서 들어옴
        right = ctx.config.get("target")
        if operator != "비어 있음" and right in (None, ""):
            raise ValueError("기준 값이 비어 있습니다")

        result = compare(left, operator, right)
        return NodeResult(output={"result": result, "value": left}, branch="참" if result else "거짓")