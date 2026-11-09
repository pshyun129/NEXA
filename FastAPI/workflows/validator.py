from collections import deque

from nodes.registry import catalog
from engine.binding import find_refs

NODE_DEFS = {n["node_type"]: n for n in catalog()}


def validate_graph(graph: dict) -> list[dict]:
    issues: list[dict] = []
    nodes = graph.get("nodes", [])
    edges = graph.get("edges", [])

    if not nodes:
        return [{"message": "노드가 하나도 없습니다."}]

    # 1) 노드 검사: 중복 id, 없는 종류, 빈 필수 설정
    node_ids: set[str] = set()
    for n in nodes:
        if n["id"] in node_ids:
            issues.append({"node_id": n["id"], "message": "노드 id가 중복됩니다."})
        node_ids.add(n["id"])

        definition = NODE_DEFS.get(n["type"])
        if definition is None:
            issues.append({"node_id": n["id"], "message": f"알 수 없는 노드 종류입니다: {n['type']}"})
            continue

        config = n.get("config", {})
        for field in definition.get("config_fields", []):
            if field.get("required") and not config.get(field["key"]):
                issues.append({"node_id": n["id"], "message": f"'{field['label']}' 설정이 비어 있습니다."})

    # 2) 연결선 검사: 없는 노드에 연결된 선
    outgoing: dict[str, list[dict]] = {nid: [] for nid in node_ids}
    incoming: dict[str, int] = {nid: 0 for nid in node_ids}
    for e in edges:
        if e["source"] not in node_ids or e["target"] not in node_ids:
            issues.append({"edge_id": e["id"], "message": "없는 노드에 연결된 선입니다."})
            continue
        outgoing[e["source"]].append(e)
        incoming[e["target"]] += 1

    # 3) 분기 검사
    #    분기 노드: 나가는 선마다 분기 이름이 맞아야 함 (연결 안 된 분기는 그 자리에서 종료)
    #    일반 노드: 다음 노드는 하나만
    for n in nodes:
        definition = NODE_DEFS.get(n["type"])
        if definition is None:
            continue
        branches = definition.get("branches", [])
        out_edges = outgoing.get(n["id"], [])

        if branches:
            for e in out_edges:
                if e.get("branch") not in branches:
                    issues.append({"edge_id": e["id"], "message": f"분기 결과를 선택해야 합니다. ({', '.join(branches)})"})
        elif len(out_edges) > 1:
            issues.append({"node_id": n["id"], "message": "분기가 없는 노드는 다음 노드를 하나만 연결할 수 있습니다."})

    # 4) 시작 노드는 1개
    starts = [nid for nid, count in incoming.items() if count == 0]
    if len(starts) != 1:
        issues.append({"message": f"시작 노드는 1개여야 합니다. (현재 {len(starts)}개)"})

    # 5) 순환 검사: 시작점부터 차례로 지워나갔을 때 남는 노드가 있으면 순환
    remaining = dict(incoming)
    queue = deque(starts)
    visited = 0
    while queue:
        nid = queue.popleft()
        visited += 1
        for e in outgoing[nid]:
            remaining[e["target"]] -= 1
            if remaining[e["target"]] == 0:
                queue.append(e["target"])
    if visited < len(node_ids):
        issues.append({"message": "순환 연결이 있습니다."})

    # 입력 바인딩 검사 : {{노드id.키}}가 앞쪽(상류)에 연결된 노드의 실제 출력을 가리키는지
    parents: dict[str, set[str]] = {nid: set() for nid in node_ids}
    for e in edges:
        if e["source"] in node_ids and e["target"] in node_ids:
            parents[e["target"]].add(e["source"])

    def ancestors(nid: str) -> set[str]:
        """nid보다 앞에 있는(거슬러 올라가면 닿는) 모든 노드"""
        seen, stack = set(), list(parents[nid])
        while stack:
            p = stack.pop()
            if p not in seen:
                seen.add(p)
                stack.extend(parents[p])
        return seen

    types = {n["id"]: n["type"] for n in nodes}
    for n in nodes:
        upstream = ancestors(n["id"])
        for ref_id, path in find_refs(n.get("config", {})):
            if ref_id not in node_ids:
                issues.append({"node_id": n["id"], "message": f"없는 노드를 참조합니다: {ref_id}"})
            elif ref_id not in upstream:
                issues.append({"node_id": n["id"], "message": f"앞에 연결된 노드만 참조할 수 있습니다: {ref_id}"})
            elif path:
                outputs = {o["key"] for o in NODE_DEFS.get(types[ref_id], {}).get("outputs", [])}
                if path[0] not in outputs:
                    issues.append({
                        "node_id": n["id"],
                        "message": f"{ref_id} 노드에는 '{path[0]}' 출력이 없습니다 (가능: {', '.join(sorted(outputs))})",   
                    })

    return issues