"""규정 노드 확인용 (로컬 전용, 커밋 안 함): DB 없이 노드만 직접 실행"""
from nodes.base import NodeContext
from nodes.impl.policy_check import PolicyCheckNode
from nodes.impl.rag_search import RagSearchNode

# 앞 노드(자동 검증) 출력이 있다고 가정
EXT = {"fields": {"store": "한솥도시락 역삼점", "total": 30000, "date": "2026-11-04"},
       "doc_type": "영수증", "model_version": "receipt@v1"}
# 실행 입력 폼 값 (폼 필드 키 = purpose, headcount ...)
INPUTS = {"purpose": "야근 식대", "headcount": "2", "attendees": "김OO, 이OO",
          "pay_time": "20:10", "pay_method": "법인카드", "items": "도시락"}


def ctx(config: dict, inputs: dict | None = None) -> NodeContext:
    return NodeContext(run_id=0, node_id="p1", config=config, inputs=inputs or {}, outputs={"n3": EXT})


def show(title, r):
    print(f"\n=== {title} → 분기: {r.branch}")
    for line in r.output.get("reasons", []):
        print("  -", line)


show("실행 입력만 (2명, 1인 15,000)", PolicyCheckNode().run(ctx({"doc_type": "영수증"}, INPUTS)))
show("설정에서 인원 3명 (1인 10,000)", PolicyCheckNode().run(ctx({"doc_type": "영수증", "headcount": "3"}, INPUTS)))
show("입력 없음", PolicyCheckNode().run(ctx({"doc_type": "영수증"})))

for q in ["야근 식대 한도", "회사 주차장 정기권 신청"]:
    r = RagSearchNode().run(ctx({"query": q, "doc_set": "경비 규정"}))
    print(f"\n=== 근거 검색 '{q}' → {r.branch} (최고 관련도 {r.output['top_rel']})")
    for c in r.output["clauses"]:
        print(f"  {c['clause_id']} ({c['rel']}) {c['label']}")