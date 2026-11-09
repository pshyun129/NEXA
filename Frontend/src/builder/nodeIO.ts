import type { Graph, NodeDetail } from '@/api/builder';

/**
 * 노드별 "앞 노드에서 받아야 하는 값"(설계 6.6 경고 두 겹 중 클라이언트 사전 경고).
 * 서버 카탈로그에는 입력 정보가 없어서 입력 키만 여기 둔다. 출력 키는 GET /nodes/{type}의 outputs를 쓴다.
 * 출처: FastAPI nodes/impl/*.py(develop b772e32)의 latest_output(ctx.outputs, key) 호출.
 * ref: 이 값을 직접 지정하는 설정 칸. 채워져 있으면 앞 노드가 없어도 경고하지 않는다.
 */
export const NODE_INPUTS: Record<string, { key: string; ref?: string }[]> = {
  structured_extract: [{ key: 'text', ref: 'text' }],
  llm_verify: [{ key: 'fields', ref: 'extraction' }],
  human_confirm: [{ key: 'fields', ref: 'extraction' }],
  policy_check: [{ key: 'fields', ref: 'extraction' }],
  approval_draft: [{ key: 'fields', ref: 'fields' }],
  wait_approval: [{ key: 'request_key', ref: 'draft' }],
};

export interface OrderWarning { nodeId: string; message: string }

/** 엣지(위상) 기준 선행 노드 집합. 위치를 옮기는 것만으로는 바뀌지 않는다 */
function ancestors(graph: Graph): Map<string, Set<string>> {
  const preds = new Map<string, string[]>();
  for (const e of graph.edges) preds.set(e.target, [...(preds.get(e.target) ?? []), e.source]);
  const memo = new Map<string, Set<string>>();
  const visit = (id: string, path: Set<string>): Set<string> => {
    const hit = memo.get(id);
    if (hit) return hit;
    const acc = new Set<string>();
    for (const p of preds.get(id) ?? []) {
      if (path.has(p)) continue; // 순환은 서버 검사가 알려 준다
      acc.add(p);
      visit(p, new Set([...path, p])).forEach((a) => acc.add(a));
    }
    memo.set(id, acc);
    return acc;
  };
  for (const n of graph.nodes) visit(n.id, new Set([n.id]));
  return memo;
}

/** 앞 노드 중 필요한 값을 내는 노드가 없으면 경고. 메시지는 "'서류 항목 추출' 앞에 'OCR 글자 인식'이 없습니다" */
export function orderWarnings(graph: Graph, catalog: NodeDetail[]): OrderWarning[] {
  const byType = new Map(catalog.map((n) => [n.nodeType, n]));
  const typeOf = new Map(graph.nodes.map((n) => [n.id, n.type]));
  const anc = ancestors(graph);
  const out: OrderWarning[] = [];
  for (const node of graph.nodes) {
    for (const input of NODE_INPUTS[node.type] ?? []) {
      if (input.ref && String(node.config[input.ref] ?? '').trim()) continue;
      const has = [...(anc.get(node.id) ?? [])].some((a) =>
        byType.get(typeOf.get(a) ?? '')?.outputs.some((o) => o.key === input.key));
      if (has) continue;
      // 이 값을 내는 노드(카탈로그 순서 첫 번째)를 안내한다
      const producer = catalog.find((c) => c.nodeType !== node.type && c.outputs.some((o) => o.key === input.key));
      const name = byType.get(node.type)?.name ?? node.type;
      out.push({
        nodeId: node.id,
        message: producer
          ? `'${name}' 앞에 '${producer.name}'이(가) 없습니다`
          : `'${name}'에 필요한 값(${input.key})을 내는 앞 노드가 없습니다`,
      });
    }
  }
  return out;
}
