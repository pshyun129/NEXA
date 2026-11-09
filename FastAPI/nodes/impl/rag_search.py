"""규정 근거 검색 노드: 검색할 내용으로 사내 규정 조항을 찾아 원문 그대로 넘긴다.

LLM을 쓰지 않음 → 지어낸 내용이 끼어들 수 없음 (규정 원문만 전달).
쓰임: 결재 본문에 근거 조항 붙이기, 사람 확인 화면에 참고 조항 보여주기, 조건 분기의 재료.
검색 = 키워드(BM25) + 임베딩 후보 → 재정렬 모델 관련도. 관련도가 기준 미만이면 '근거 없음' (억지로 붙이지 않음)
"""
from ai.rag.search import MIN_REL, retrieve
from nodes.base import BaseNode, NodeContext, NodeResult

# 화면 이름 → 규정 코드 (DOCS/regulations front matter의 규정코드)
DOC_SETS = {
    "전체": None,
    "경비 규정": ["EXP"],
    "출장 규정": ["TRV"],
    "근무·휴가 규정": ["LEV"],
}


class RagSearchNode(BaseNode):
    node_type = "rag_search"
    name = "규정 근거 검색"
    category = "ai"
    description = "사내 규정에서 관련 조항을 찾아 원문 그대로 넘김 (LLM 없음)"
    config_fields = [
        {"key": "query", "label": "검색할 내용 (예: 야근 식대 한도, {{n3.fields.store}} 법인카드 사용)", "type": "text",
         "required": True},
        {"key": "doc_set", "label": "규정 범위", "type": "select", "options": list(DOC_SETS), "required": False},
        {"key": "k", "label": "최대 조항 수 (기본 3)", "type": "number", "required": False},
    ]
    branches = ["근거 있음", "근거 없음"]
    output_vars = [
        {"key": "evidence", "label": "근거 조항 원문", "type": "text"},
        {"key": "clauses", "label": "근거 조항 목록", "type": "list"},
        {"key": "top_rel", "label": "최고 관련도", "type": "number"},
        {"key": "index_version", "label": "규정 색인 버전", "type": "text"},
    ]

    def run(self, ctx: NodeContext) -> NodeResult:
        query = str(ctx.config.get("query") or "").strip()
        if not query:
            raise ValueError("검색할 내용이 비어 있습니다")
        doc_codes = DOC_SETS.get(ctx.config.get("doc_set") or "전체")
        k = int(ctx.config.get("k") or 3)

        r = retrieve(query, doc_codes=doc_codes, k=k)
        hits = [] if r.abstain else [h for h in r.hits if h.rel >= MIN_REL]   # 기준 미만 조항은 버림
        clauses = [{"clause_id": h.clause.id, "label": h.clause.label, "body": h.clause.body,
                    "rel": round(h.rel, 4)} for h in hits]
        out = {
            "evidence": "\n\n".join(f"[{c['label']}]\n{c['body']}" for c in clauses),
            "clauses": clauses,
            "top_rel": round(r.top_rel, 4),
            "index_version": r.index_version,
            "query": query,
        }
        return NodeResult(output=out, branch="근거 있음" if clauses else "근거 없음")