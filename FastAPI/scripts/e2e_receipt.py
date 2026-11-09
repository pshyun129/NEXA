"""영수증 워크플로우 E2E 테스트 (개발용, 커밋 안 함).

흐름: OCR → 항목 추출 → AI 교차 검증 → (사람 확인)
      [--approval] → 사원 조회 → 결재 상신 → 결재 대기(Kafka 결과)

토큰: 환경변수 TOKEN(실행), RESUME_TOKEN(재개, 없으면 TOKEN)
      둘 다 없으면 서버의 개발용 우회(AUTH_DEV_EMPLOYEE_ID)로 동작

사용 예:
  TOKEN=$TOKEN_104 python scripts/e2e_receipt.py ~/Downloads/receipt2.jpg --mode auto --approval
  TOKEN=$TOKEN_104 python scripts/e2e_receipt.py ~/Downloads/receipt1.jpg --mode human --approval --set total=17900
  TOKEN=$TOKEN_104 python scripts/e2e_receipt.py ~/Downloads/receipt2.jpg --mode auto --approval --bind
"""
import argparse
import json
import os
import time
from pathlib import Path

import httpx

BASE = "http://localhost:8086/api/v1/ai"
DONE = {"SUCCEEDED", "FAILED", "CANCELED"}


def build_graph(mode: str, approval: bool = False, bind: bool = False) -> dict:
    # auto: GMS 교차 검증 / human: 기준을 일부러 높여서 무조건 사람 확인으로
    if mode == "auto":
        verify_config = {"use_gms": "사용", "min_confidence": 0.8}
    else:
        verify_config = {"use_gms": "사용 안 함", "min_confidence": 0.999}

    # --bind: 모든 입력을 바인딩으로 명시 (안 쓰면 칸을 비워서 "가장 최근 값" 기본 동작)
    b = (lambda value: value) if bind else (lambda value: None)

    def node(nid, ntype, x, config=None):
        config = {k: v for k, v in (config or {}).items() if v is not None}  # 바인딩 안 쓰면 칸 자체를 뺌
        return {"id": nid, "type": ntype, "position": {"x": x, "y": 0}, "config": config}

    def edge(eid, src, dst, branch=None):
        return {"id": eid, "source": src, "target": dst, "branch": branch}

    nodes = [
        node("n1", "ocr_extract", 0),
        node("n2", "structured_extract", 250, {"doc_type": "영수증", "text": b("{{n1.text}}")}),
        node("n3", "llm_verify", 500, {**verify_config, "extraction": b("{{n2}}"), "ocr": b("{{n1}}")}),
        node("n4", "human_confirm", 750, {"extraction": b("{{n3}}")}),
    ]
    edges = [
        # OCR·추출은 성공이든 확인 필요든 다음으로 넘김 (판단은 검증 노드가 함)
        edge("e1", "n1", "n2", "성공"),
        edge("e2", "n1", "n2", "확인 필요"),
        edge("e3", "n2", "n3", "성공"),
        edge("e4", "n2", "n3", "확인 필요"),
        edge("e5", "n3", "n4", "사람 확인"),
    ]
    if approval:
        # 자동 확정이든 사람이 확인했든 → 사원 조회 → 결재 상신 → 결재 대기
        nodes += [
            node("n5", "employee_lookup", 1000),
            node("n6", "approval_draft", 1250, {
                "form_type": "일반 기안",
                "title": "[경비] {store} {total:,}원 ({requester})",
                "fields": b("{{n4.fields || n3.fields}}"),   # 사람이 확인했으면 n4, 아니면 n3
                "employee": b("{{n5.employee}}"),
            }),
            node("n7", "wait_approval", 1500, {"draft": b("{{n6}}")}),
        ]
        edges += [
            edge("e6", "n3", "n5", "자동 확정"),
            edge("e7", "n4", "n5", "확인"),
            edge("e8", "n5", "n6"),
            edge("e9", "n6", "n7"),
            # 승인/반려/회수는 연결하지 않음 → 그 자리에서 끝 (결과는 n7 출력과 분기로 확인)
        ]
    return {"nodes": nodes, "edges": edges}


def data(res: httpx.Response):
    """ApiResult 껍데기를 벗겨서 data만 꺼냄. 실패면 에러를 그대로 보여줌."""
    body = res.json()
    if not body.get("success"):
        raise SystemExit(f"[{res.status_code}] {json.dumps(body, ensure_ascii=False)}")
    return body["data"]


def wait_run(client: httpx.Client, run_id: int, timeout: float = 180) -> dict:
    """끝나거나(SUCCEEDED/FAILED) 무언가를 기다릴 때(WAITING)까지 1초마다 확인"""
    start = time.time()
    while time.time() - start < timeout:
        run = data(client.get(f"/runs/{run_id}"))
        if run["status"] in DONE or run["status"] == "WAITING":
            return run
        time.sleep(1)
    raise SystemExit(f"시간 초과: run {run_id}가 {timeout}초 안에 끝나지 않음")


def wait_left_waiting(client: httpx.Client, run_id: int, timeout: float) -> bool:
    """WAITING에서 벗어날 때까지 대기. 벗어났으면 True"""
    start = time.time()
    while time.time() - start < timeout:
        if data(client.get(f"/runs/{run_id}"))["status"] != "WAITING":
            return True
        time.sleep(0.5)
    return False


def print_steps(client: httpx.Client, run_id: int) -> list[dict]:
    steps = data(client.get(f"/runs/{run_id}/steps"))
    print(f"\n--- run {run_id} 단계 ---")
    for s in steps:
        print(f"[{s['nodeId']}] {s['nodeType']:<20} {s['status']:<10} 분기={s['branch']}")
        if s.get("error"):
            print(f"    에러: {s['error']}")
        if s.get("output"):
            print("    " + json.dumps(s["output"], ensure_ascii=False)[:300])
    return steps


def waiting_for(steps: list[dict]) -> dict:
    """멈춘 단계가 기다리는 내용 (human_confirm / approval_wait)"""
    out = next((s["output"] for s in steps if s["status"] == "WAITING"), None) or {}
    return out.get("waiting_for") or {}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("image", type=Path)
    p.add_argument("--mode", choices=["auto", "human"], default="auto")
    p.add_argument("--reject", action="store_true", help="사람 확인에서 반려")
    p.add_argument("--set", action="append", default=[], help="사람이 고친 값 key=value (여러 번 가능)")
    p.add_argument("--approval", action="store_true", help="사원 조회 + 결재 상신 + 결재 대기까지")
    p.add_argument("--approval-timeout", type=float, default=300, help="결재 결과를 기다릴 최대 시간(초)")
    p.add_argument("--bind", action="store_true", help="입력을 모두 바인딩으로 명시 ({{n1.text}} 등)")
    args = p.parse_args()

    # 토큰: TOKEN(실행), RESUME_TOKEN(재개, 없으면 TOKEN) / 둘 다 없으면 개발용 우회
    token = os.environ.get("TOKEN")
    resume_token = os.environ.get("RESUME_TOKEN") or token
    headers = {"Authorization": f"Bearer {token}"} if token else {}

    with httpx.Client(base_url=BASE, timeout=30, headers=headers) as client:
        # 1) 워크플로우 만들고 그래프 저장
        wf = data(client.post("/workflows", json={"name": f"영수증 E2E ({args.mode})"}))
        wf_id = wf["id"]
        data(client.put(f"/workflows/{wf_id}/graph", json=build_graph(args.mode, args.approval, args.bind)))
        check = data(client.post(f"/workflows/{wf_id}/validate"))
        print(f"워크플로우 {wf_id} 검사 결과: {json.dumps(check, ensure_ascii=False)}")

        # 2) 파일 업로드
        with args.image.open("rb") as f:
            up = data(client.post("/runs/files", files={"file": (args.image.name, f, "image/jpeg")}))
        print(f"파일 업로드: id={up['id']} {up['originalName']} ({up['size']} bytes)")

        # 3) 테스트 실행
        created = data(client.post(f"/workflows/{wf_id}/test-runs", json={"fileIds": [up["id"]]}))
        run_id = created["runId"]
        print(f"실행 시작: run {run_id}")

        # 4) 멈출 때마다 무엇을 기다리는지 보고 처리 (사람 확인 → 재개 / 결재 대기 → 이벤트 기다림)
        resumed_human = False
        while True:
            run = wait_run(client, run_id)
            print(f"상태: {run['status']} (현재 노드: {run.get('currentNodeId')})")
            steps = print_steps(client, run_id)
            if run["status"] != "WAITING":
                break

            waiting = waiting_for(steps)
            if waiting.get("type") == "human_confirm" and not resumed_human:
                edits = dict(kv.split("=", 1) for kv in args.set)
                for k, v in edits.items():   # 숫자 필드는 int로
                    if v.isdigit():
                        edits[k] = int(v)
                body = {"approved": not args.reject, "comment": "E2E 테스트", "data": edits or None}
                print(f"\n[사람 확인] resume 보냄: {json.dumps(body, ensure_ascii=False)}")
                resume_headers = {"Authorization": f"Bearer {resume_token}"} if resume_token else {}
                data(client.post(f"/runs/{run_id}/resume", json=body, headers=resume_headers))
                resumed_human = True
                wait_left_waiting(client, run_id, 10)

            elif waiting.get("type") == "approval_wait":
                key = waiting["request_key"]
                print(f"\n[결재 대기] requestKey={key}  documentId={waiting.get('document_id')}")
                # 결재 대기는 직접 재개가 막혀 있어야 함 (409 RUN_006)
                res = client.post(f"/runs/{run_id}/resume", json={"approved": True})
                print(f"  직접 resume 시도 → {res.status_code} {res.json().get('error')}")
                print(f"  Kafka 이벤트를 보내면 이어서 진행됩니다 (최대 {args.approval_timeout:.0f}초 대기)")
                if not wait_left_waiting(client, run_id, args.approval_timeout):
                    raise SystemExit("결재 결과가 오지 않아 종료 (실행은 계속 WAITING)")

            else:
                raise SystemExit(f"알 수 없는 대기: {waiting}")

        if run.get("error"):
            print(f"\n실행 에러: {run['error']}")


if __name__ == "__main__":
    main()