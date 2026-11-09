"""규정 검색 확인용 (로컬 전용, 커밋 안 함)"""
from ai.rag.search import retrieve

QUESTIONS = [
    "법인카드로 유흥주점에서 결제해도 되나요",
    "야근할 때 저녁 식대 한도가 얼마예요",
    "해외 출장 가면 일비 얼마 나와요",
    "연차는 다음 해로 며칠까지 넘길 수 있어요",
    "회사 주차장 정기권 신청 방법",          # 규정에 없는 질문 → 기권(abstain)해야 정상
]
for q in QUESTIONS:
    r = retrieve(q, k=3)
    hits = ", ".join(f"{h.clause.id}({h.rel:.2f})" for h in r.hits)
    print(f"{'기권' if r.abstain else '검색'} | 최고 관련도 {r.top_rel:.2f} | {hits} | {q}")