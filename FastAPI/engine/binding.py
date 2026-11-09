"""입력 바인딩: 노드 설정값 안의 {{노드id.출력키}} 참조를 실행 시점의 실제 값으로 바꾼다.

문법
  {{n1.text}}                  n1 노드 출력의 text
  {{n2.fields.total}}          점(.)으로 안쪽까지 (dict 키)
  {{n2}}                       n2 노드 출력 전체 (dict)
  {{n4.fields || n3.fields}}   앞에서부터 값이 있는 것 (분기로 둘 중 하나만 실행된 경우)

치환 규칙
  - 설정값 전체가 참조 하나면 → 원래 타입 그대로 (dict, int 등)
  - 글자 사이에 섞여 있으면 → 문자열로 끼워 넣음 ("[경비] {{n2.fields.store}}")
  - 참조한 노드가 실행되지 않았거나 키가 없으면 → None (글자 사이면 빈 문자열)
"""
import re

# {{ ... }} 안: 참조(노드id + .키...) 하나 이상을 || 로 연결
_REF = r"[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_]+)*"
REF_PATTERN = re.compile(r"\{\{\s*(" + _REF + r"(?:\s*\|\|\s*" + _REF + r")*)\s*\}\}")


def parse_refs(expr: str) -> list[tuple[str, list[str]]]:
    """'n4.fields || n3.fields' → [('n4', ['fields']), ('n3', ['fields'])]"""
    refs = []
    for part in expr.split("||"):
        node_id, *path = part.strip().split(".")
        refs.append((node_id, path))
    return refs


def _lookup(outputs: dict, node_id: str, path: list[str]):
    """outputs[node_id][path...] 를 따라 내려감. 중간에 없으면 None"""
    value = outputs.get(node_id)
    for key in path:
        if not isinstance(value, dict):
            return None
        value = value.get(key)
    return value


def _evaluate(expr: str, outputs: dict):
    """|| 로 이어진 참조 중 처음으로 값이 있는 것"""
    for node_id, path in parse_refs(expr):
        value = _lookup(outputs, node_id, path)
        if value is not None:
            return value
    return None


def _to_text(value) -> str:
    return "" if value is None else str(value)


def resolve(value, outputs: dict):
    """설정값(문자열·dict·list 어디든)의 참조를 실제 값으로 바꾼 사본을 돌려준다"""
    if isinstance(value, str):
        whole = REF_PATTERN.fullmatch(value.strip())
        if whole:  # 설정값 전체가 참조 하나 → 타입 유지
            return _evaluate(whole.group(1), outputs)
        return REF_PATTERN.sub(lambda m: _to_text(_evaluate(m.group(1), outputs)), value)
    if isinstance(value, dict):
        return {k: resolve(v, outputs) for k, v in value.items()}
    if isinstance(value, list):
        return [resolve(v, outputs) for v in value]
    return value


def find_refs(value) -> list[tuple[str, list[str]]]:
    """설정값 안의 모든 참조 목록 (흐름 검사용)"""
    if isinstance(value, str):
        return [ref for m in REF_PATTERN.finditer(value) for ref in parse_refs(m.group(1))]
    if isinstance(value, dict):
        return [ref for v in value.values() for ref in find_refs(v)]
    if isinstance(value, list):
        return [ref for v in value for ref in find_refs(v)]
    return []