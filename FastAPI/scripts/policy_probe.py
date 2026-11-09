"""규정 검토 확인용 (로컬 전용, 커밋 안 함)"""
from datetime import date

from ai.rag.policy import check_receipt

CARD = {"결제 수단": "법인카드"}
CASES = [
    ("① 야근 식대 1인 11,000 (정보 다 있음)", {"store": "김밥천국 역삼점", "total": 22000, "date": "2026-11-04"},
     {**CARD, "사용 목적": "야근", "참석 인원": 2, "참석자": ["김OO", "이OO"], "결제 시각": "19:40", "구매 품목": ["참치김밥", "라면"]}),
    ("② 야근 식대 1인 15,000 (한도 초과)", {"store": "한솥도시락 역삼점", "total": 30000, "date": "2026-11-04"},
     {**CARD, "사용 목적": "야근", "참석 인원": 2, "참석자": ["김OO", "이OO"], "결제 시각": "20:10", "구매 품목": ["도시락"]}),
    ("③ 토요일 밤 단란주점 회식 1인 45,000", {"store": "OO 단란주점", "total": 180000, "date": "2026-10-31"},
     {**CARD, "사용 목적": "회식", "참석 인원": 4, "결제 시각": "23:40"}),
    ("④ 정보 없음 (영수증만)", {"store": "명동칼국수 선릉점", "total": 45000, "date": "2026-11-03"}, {}),
    ("⑤ 소모품, 46일 지나서 정산", {"store": "알파문구 강남점", "total": 85000, "date": "2026-09-20"},
     {**CARD, "사용 목적": "소모품", "결제 시각": "14:20", "구매 품목": ["복사용지", "토너"]}),
]
EXPECT = ["준수", "위반", "위반", "확인 필요", "위반"]

for (name, fields, extra), expect in zip(CASES, EXPECT):
    r = check_receipt(fields, extra, today=date(2026, 11, 5))
    mark = "✅" if r["verdict"] == expect else "❌"
    print(f"\n=== {name} → {r['verdict']} (기대 {expect}) {mark}")
    for f in r["findings"]:
        num = f" [{' / '.join(f['numeric'])}]" if f["numeric"] else ""
        print(f"  {f['clause_id']:7} {f['verdict']:5} (LLM {f['llm_verdict']}){num} {f['reason'][:50]}")
        for c in f["checks"]:
            print(f"           ⚠️ {c}")
            if "인용" in c:
                print(f"              인용: {f['quote'][:80]!r}")
    for s in r["system_rules"]:
        print(f"  {s['clause_id']:7} [{s['enforcement']}] {s['note'][:50]}")
    if r["skipped_by_purpose"]:
        print(f"  목적이 달라 제외: {r['skipped_by_purpose']}")
    if r["needed_facts"]:
        print(f"  👉 추가로 필요한 정보: {r['needed_facts']}")