"""잘못된 입력 바인딩이 흐름 검사(validate)에서 막히는지 확인 (개발용, 커밋 안 함).

  python scripts/check_binding.py
"""
import json
import os

import httpx

BASE = "http://localhost:8086/api/v1/ai"


def node(nid, ntype, x, config=None):
    return {"id": nid, "type": ntype, "position": {"x": x, "y": 0}, "config": config or {}}


def edge(eid, src, dst, branch=None):
    return {"id": eid, "source": src, "target": dst, "branch": branch}


def graph(n2_text: str, n3_extraction: str = "{{n2}}") -> dict:
    """n1 OCR → n2 추출 → n3 검증. n2·n3의 바인딩만 바꿔가며 검사"""
    return {
        "nodes": [
            node("n1", "ocr_extract", 0),
            node("n2", "structured_extract", 250, {"doc_type": "영수증", "text": n2_text}),
            node("n3", "llm_verify", 500, {"extraction": n3_extraction, "ocr": "{{n1}}"}),
        ],
        "edges": [
            edge("e1", "n1", "n2", "성공"),
            edge("e2", "n2", "n3", "성공"),
        ],
    }


CASES = [
    ("정상: 앞 노드 참조", graph("{{n1.text}}")),
    ("뒤에 있는 노드 참조", graph("{{n3.fields}}")),
    ("자기 자신 참조", graph("{{n2.fields}}")),
    ("없는 노드 참조", graph("{{n9.text}}")),
    ("출력 키 오타", graph("{{n1.txt}}")),
    ("|| 중 하나만 잘못됨", graph("{{n1.text}}", "{{n2 || n9}}")),
]


def main():
    token = os.environ.get("TOKEN")
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    with httpx.Client(base_url=BASE, timeout=30, headers=headers) as client:
        wf = client.post("/workflows", json={"name": "바인딩 검사 테스트"}).json()["data"]
        for title, g in CASES:
            client.put(f"/workflows/{wf['id']}/graph", json=g).raise_for_status()
            result = client.post(f"/workflows/{wf['id']}/validate").json()["data"]
            print(f"\n[{title}] valid={result['valid']}")
            for issue in result["issues"]:
                print("   -", json.dumps(issue, ensure_ascii=False))


if __name__ == "__main__":
    main()