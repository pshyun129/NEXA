"""조건 분기 노드 E2E 확인 (개발용, 커밋 안 함).

흐름: 파일 업로드 → OCR → 항목 추출 → 조건 분기(합계 >= 기준)
모든 입력은 바인딩으로 연결 ({{n0.files}}, {{n1.text}}, {{n2.fields.total}})

  python scripts/check_condition.py ~/Downloads/receipt2.jpg --threshold 20000   # 23,450 → 참
  python scripts/check_condition.py ~/Downloads/receipt1.jpg --threshold 20000   # 17,800 → 거짓
"""
import argparse
import json
import os
import time
from pathlib import Path

import httpx

BASE = "http://localhost:8086/api/v1/ai"


def data(res: httpx.Response):
    body = res.json()
    if not body.get("success"):
        raise SystemExit(f"[{res.status_code}] {json.dumps(body, ensure_ascii=False)}")
    return body["data"]


def build_graph(threshold: str) -> dict:
    def node(nid, ntype, x, config=None):
        return {"id": nid, "type": ntype, "position": {"x": x, "y": 0}, "config": config or {}}

    def edge(eid, src, dst, branch=None):
        return {"id": eid, "source": src, "target": dst, "branch": branch}

    return {
        "nodes": [
            node("n0", "file_upload", 0, {"accept": "이미지"}),
            node("n1", "ocr_extract", 250, {"files": "{{n0.files}}"}),
            node("n2", "structured_extract", 500, {"doc_type": "영수증", "text": "{{n1.text}}"}),
            node("n3", "condition", 750, {"value": "{{n2.fields.total}}", "operator": "이상 (>=)", "target": threshold}),
        ],
        "edges": [
            edge("e0", "n0", "n1"),
            edge("e1", "n1", "n2", "성공"),
            edge("e2", "n1", "n2", "확인 필요"),
            edge("e3", "n2", "n3", "성공"),
            edge("e4", "n2", "n3", "확인 필요"),
            # 참/거짓은 연결하지 않음 → 분기 결과만 확인
        ],
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("image", type=Path)
    p.add_argument("--threshold", default="20000")
    args = p.parse_args()

    token = os.environ.get("TOKEN")
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    with httpx.Client(base_url=BASE, timeout=30, headers=headers) as client:
        wf = data(client.post("/workflows", json={"name": "조건 분기 테스트"}))
        data(client.put(f"/workflows/{wf['id']}/graph", json=build_graph(args.threshold)))
        print("검사:", json.dumps(data(client.post(f"/workflows/{wf['id']}/validate")), ensure_ascii=False))

        with args.image.open("rb") as f:
            up = data(client.post("/runs/files", files={"file": (args.image.name, f, "image/jpeg")}))
        run_id = data(client.post(f"/workflows/{wf['id']}/test-runs", json={"fileIds": [up["id"]]}))["runId"]

        for _ in range(120):
            run = data(client.get(f"/runs/{run_id}"))
            if run["status"] in {"SUCCEEDED", "FAILED", "WAITING"}:
                break
            time.sleep(1)

        print(f"run {run_id}: {run['status']}")
        for s in data(client.get(f"/runs/{run_id}/steps")):
            out = json.dumps(s.get("output"), ensure_ascii=False)
            print(f"[{s['nodeId']}] {s['nodeType']:<20} {s['status']:<10} 분기={s['branch']}  {out[:150]}")
            if s.get("error"):
                print("    에러:", s["error"])


if __name__ == "__main__":
    main()