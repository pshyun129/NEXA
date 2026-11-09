"""전체 노드 실서버 E2E (개발용, 커밋 안 함 · scripts/는 .git/info/exclude).

흐름 (영수증 경비 처리, 팀원 서비스 4개 + Kafka + GPU LLM·OCR 전부 지나감)
  OCR → 문서 분류 ─영수증→ 항목 추출 → 자동 검증 ─자동 확정─┐
                 └그 외→ 업무 완료(보류)        └사람 확인→ 사람 확인 ─확인─┤
  → 근태 조회(attendance) → 규정 검토(RAG) ─준수→ 자유 프롬프트(결재 의견) → 사원 조회(user)
  → 휴가 잔액(leave, 연동 확인용) → 결재 상신(approval) → 결재 대기(Kafka) ─승인→ 엑셀 작성 → 업무 완료
  규정 위반·확인 필요 → 업무 완료(보류) / 사람 반려·결재 반려·회수 → 업무 완료(반려)

환경변수
  E2E_BASE      ai-service API 주소 (기본 http://localhost:8086/api/v1/ai, 실서버는 https://도메인/api/v1/ai)
  TOKEN         실행할 사람의 accessToken (브라우저 개발자도구 → Application → Cookies → accessToken)
                read -rs TOKEN && export TOKEN 으로 넣기 (화면·기록에 안 남음)
  RESUME_TOKEN  사람 확인 재개용 (없으면 TOKEN)

사용 예
  python -X utf8 scripts/e2e_full.py mlops/data/receipts_v2/dev/r0162.jpg \
      --fact "items=콜드브루, 치즈케이크" --fact "pay_time=16:49" --fact "reason=팀 주간 회의 다과"
  python -X utf8 scripts/e2e_full.py <이미지> --mode human --set total=17900   # 사람 확인 경로
  python -X utf8 scripts/e2e_full.py --watch 123                            # 결재 승인 뒤 이어서 확인
  python -X utf8 scripts/e2e_full.py --validate-only                     # 로컬 서버에서 그래프 검사만
"""
import argparse
import json
import os
import time
from pathlib import Path

import httpx

BASE = os.environ.get("E2E_BASE", "http://localhost:8086/api/v1/ai").rstrip("/")
DONE = {"SUCCEEDED", "FAILED", "CANCELED"}
OTHER_DOCS = ["세금계산서", "출장 정산서", "휴가 신청서", "기타"]


def build_graph(mode: str, facts: dict, leave_branches: list | None) -> dict:
    # auto: GMS 교차 검증 / human: 기준을 일부러 높여서 무조건 사람 확인으로
    verify = {"use_gms": "사용", "min_confidence": 0.8} if mode == "auto" else \
             {"use_gms": "사용 안 함", "min_confidence": 0.999}
    fields = "{{n5.fields || n4.fields}}"          # 사람이 고쳤으면 그 값, 아니면 자동 확정 값

    def node(nid, ntype, x, y=0, config=None):
        return {"id": nid, "type": ntype, "position": {"x": x, "y": y}, "config": config or {}}

    def edge(src, dst, branch=None):
        return {"id": f"{src}-{dst}-{branch or ''}", "source": src, "target": dst, "branch": branch}

    nodes = [
        node("n1", "ocr_extract", 0),
        node("n2", "doc_classify", 200, config={"text": "{{n1.text}}"}),
        node("n3", "structured_extract", 400, config={"doc_type": "영수증", "text": "{{n1.text}}"}),
        node("n4", "llm_verify", 600, config={**verify, "extraction": "{{n3}}", "ocr": "{{n1}}"}),
        node("n5", "human_confirm", 600, 200, {"message": "영수증 값을 확인해 주세요", "extraction": "{{n4}}"}),
        node("n6", "attendance_lookup", 800, config={"date": "{{n5.fields.date || n4.fields.date}}"}),
        node("n7", "policy_check", 1000, config={"doc_type": "영수증", "extraction": "{{n5 || n4}}",
                                                 "attendance": "{{n6}}", **facts}),
        node("n8", "free_prompt", 1200, config={"instruction": "경비 내역을 결재 의견용으로 2줄 요약해줘",
                                                "context": "{{n7.known_facts}}"}),
        node("n9", "employee_lookup", 1400),
        node("n10", "leave_balance", 1600),
        node("n11", "approval_draft", 1800, config={"form_type": "일반 기안", "fields": fields,
                                                    "title": "[경비] {store} {total:,}원 ({requester})"}),
        node("n12", "wait_approval", 2000, config={"draft": "{{n11}}"}),
        node("n13", "excel_write", 2200, config={"data": fields, "columns": "store:상호, total:금액, date:거래일",
                                                 "file_name": "경비_E2E"}),
        node("n14", "complete", 2400, config={"result": "완료",
                                              "message": "{{n11.doc_no}} 승인 완료 · {{n8.answer}}"}),
        node("n90", "complete", 400, 400, {"result": "보류", "message": "영수증이 아닌 문서: {{n2.doc_type}}"}),
        node("n91", "complete", 1200, 400, {"result": "보류", "message": "규정 검토 {{n7.verdict}}: {{n7.reasons}}"}),
        node("n92", "complete", 1000, 600, {"result": "반려", "message": "사람 확인에서 반려"}),
        node("n93", "complete", 2200, 400, {"result": "반려", "message": "결재 {{n12.approval_status}}"}),
    ]
    edges = [
        edge("n1", "n2", "성공"), edge("n1", "n2", "확인 필요"),
        edge("n2", "n3", "영수증"), *[edge("n2", "n90", t) for t in OTHER_DOCS],
        edge("n3", "n4", "성공"), edge("n3", "n4", "확인 필요"),
        edge("n4", "n6", "자동 확정"), edge("n4", "n5", "사람 확인"),
        edge("n5", "n6", "확인"), edge("n5", "n92", "반려"),
        edge("n6", "n7", "기록 있음"), edge("n6", "n7", "기록 없음"),
        edge("n7", "n8", "준수"), edge("n7", "n91", "위반"), edge("n7", "n91", "확인 필요"),
        *[edge("n8", "n9", b) for b in ("완료", "정보 부족", "확인 필요")],
        edge("n9", "n10"), edge("n11", "n12"),
        # 휴가 잔액: 서버 버전에 분기가 있으면(잔액 있음/없음) 분기마다, 없으면(예전 버전) 하나로
        *([edge("n10", "n11", b) for b in (leave_branches or [])] or [edge("n10", "n11")]),
        edge("n12", "n13", "승인"), edge("n12", "n93", "반려"), edge("n12", "n93", "회수"),
        edge("n13", "n14"),
    ]
    if leave_branches is None:                     # --no-leave: 휴가 잔액 노드 빼고 사원 조회 → 결재 상신 바로
        nodes = [n for n in nodes if n["id"] != "n10"]
        edges = [e for e in edges if "n10" not in (e["source"], e["target"])] + [edge("n9", "n11")]
    return {"nodes": nodes, "edges": edges}


def data(res: httpx.Response):
    """ApiResult 껍데기를 벗겨서 data만 꺼냄. 실패면 에러를 그대로 보여줌."""
    try:
        body = res.json()
    except ValueError:
        raise SystemExit(f"[{res.status_code}] JSON 아님: {res.text[:300]}")
    if not body.get("success"):
        raise SystemExit(f"[{res.status_code}] {json.dumps(body, ensure_ascii=False)}")
    return body["data"]


def wait_run(client: httpx.Client, run_id: int, timeout: float = 300) -> dict:
    """끝나거나 기다림(WAITING)이 될 때까지 2초마다 확인"""
    start = time.time()
    while time.time() - start < timeout:
        run = data(client.get(f"/runs/{run_id}"))
        if run["status"] in DONE or run["status"] == "WAITING":
            return run
        time.sleep(2)
    raise SystemExit(f"시간 초과: run {run_id}가 {timeout}초 안에 끝나지 않음")


def waiting_for(steps: list[dict]) -> dict:
    out = next((s["output"] for s in steps if s["status"] == "WAITING"), None) or {}
    return out.get("waiting_for") or {}


def print_steps(client: httpx.Client, run_id: int) -> list[dict]:
    steps = data(client.get(f"/runs/{run_id}/steps"))
    print(f"\n--- run {run_id} 단계 ---")
    for s in steps:
        print(f"[{s['nodeId']:>3}] {s['nodeType']:<18} {s['status']:<10} 분기={s['branch']}")
        if s.get("error"):
            print(f"      에러: {s['error']}")
        if s.get("output"):
            print("      " + json.dumps(s["output"], ensure_ascii=False)[:260])
    return steps


def follow(client: httpx.Client, run_id: int, args, resume_headers: dict) -> None:
    """사람 확인이면 재개, 결재 대기면 승인될 때까지 기다리며 결과까지 보여줌"""
    run = wait_run(client, run_id)
    while run["status"] == "WAITING":
        steps = print_steps(client, run_id)
        wait = waiting_for(steps)
        if wait.get("type") == "approval_wait":
            print(f"\n⏳ 결재 대기: 문서번호 {wait.get('doc_no')} → 프론트(결재함)에서 결재선 승인해 주세요 "
                  f"({args.approval_timeout}초까지 기다림, 끊겨도 --watch {run_id}로 이어서 확인)")
            start = time.time()
            while time.time() - start < args.approval_timeout:
                time.sleep(10)
                run = data(client.get(f"/runs/{run_id}"))
                if run["status"] != "WAITING":
                    break
            else:
                print("결재 결과가 아직 없음 → 나중에 --watch로 확인")
                return
            run = wait_run(client, run_id)
            continue
        # 사람 확인 (human_confirm)
        print("\n사람에게 보여줄 내용:\n" + json.dumps(wait, ensure_ascii=False, indent=2)[:1500])
        edits = {k: int(v) if v.isdigit() else v for k, v in (kv.split("=", 1) for kv in args.set)}
        body = {"approved": not args.reject, "comment": "E2E 테스트", "data": edits or None}
        print(f"\nresume 보냄: {json.dumps(body, ensure_ascii=False)}")
        data(client.post(f"/runs/{run_id}/resume", json=body, headers=resume_headers))
        time.sleep(2)                                   # 202 직후엔 아직 WAITING일 수 있음
        run = wait_run(client, run_id)

    steps = print_steps(client, run_id)
    print(f"\n최종 상태: {run['status']}" + (f" | 에러: {run['error']}" if run.get("error") else ""))
    excel = next((s["output"] for s in steps if s["nodeType"] == "excel_write" and s.get("output")), None)
    if excel:
        res = client.get(f"/runs/files/{excel['file_id']}/download")
        path = Path(f"e2e_{run_id}_{excel['file_name']}")
        path.write_bytes(res.content)
        print(f"엑셀 다운로드: [{res.status_code}] {path} ({len(res.content)} bytes)")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("image", type=Path, nargs="?")
    p.add_argument("--mode", choices=["auto", "human"], default="auto")
    p.add_argument("--reject", action="store_true", help="사람 확인에서 반려")
    p.add_argument("--set", action="append", default=[], help="사람이 고친 값 key=value (여러 번 가능)")
    p.add_argument("--purpose", default="회의", help="규정 검토용 사용 목적")
    p.add_argument("--headcount", default="3", help="규정 검토용 참석 인원")
    p.add_argument("--fact", action="append", default=[],
                   help="규정 검토 사실 key=값 (items, reason, pay_time, pay_method, attendees, 여러 번 가능)")
    p.add_argument("--no-leave", action="store_true", help="휴가 잔액 노드 빼기 (휴가 정책이 없는 계정으로 테스트할 때)")
    p.add_argument("--approval-timeout", type=int, default=600)
    p.add_argument("--watch", type=int, help="이미 만든 run 이어서 확인")
    p.add_argument("--validate-only", action="store_true", help="그래프 저장·검사까지만 (팀원 서비스 없이 로컬에서 확인)")
    args = p.parse_args()

    token = os.environ.get("TOKEN")
    resume_token = os.environ.get("RESUME_TOKEN") or token
    # 게이트웨이는 accessToken 쿠키만 믿고 Authorization 헤더는 지움 → 쿠키로 보냄
    # 로컬 서버(게이트웨이 없이 직접)는 Authorization을 읽으므로 둘 다 보냄
    def auth(t):
        return {"Authorization": f"Bearer {t}", "Cookie": f"accessToken={t}"} if t else {}
    headers, resume_headers = auth(token), auth(resume_token)
    print(f"대상: {BASE}")

    with httpx.Client(base_url=BASE, timeout=60, headers=headers) as client:
        if args.watch:
            follow(client, args.watch, args, resume_headers)
            return
        if not args.image and not args.validate_only:
            raise SystemExit("이미지 경로가 필요합니다 (또는 --watch RUN_ID / --validate-only)")

        catalog = data(client.get("/nodes"))
        items = catalog.get("nodes", catalog) if isinstance(catalog, dict) else catalog
        print(f"노드 목록: {len(items)}개")
        # 분기 목록은 노드 목록(요약)엔 없고 상세 API에만 있음
        leave = data(client.get("/nodes/leave_balance"))
        leave_branches = None if args.no_leave else (leave.get("branches") or [])

        wf = data(client.post("/workflows", json={"name": f"전체 노드 E2E ({args.mode})"}))
        wf_id = wf["id"]
        facts = {"purpose": args.purpose, "headcount": args.headcount,
                 **dict(kv.split("=", 1) for kv in args.fact)}
        data(client.put(f"/workflows/{wf_id}/graph", json=build_graph(args.mode, facts, leave_branches)))
        check = data(client.post(f"/workflows/{wf_id}/validate"))
        print(f"워크플로우 {wf_id} 검사: {json.dumps(check, ensure_ascii=False)[:600]}")
        if isinstance(check, dict) and check.get("valid") is False:
            raise SystemExit("그래프 검사 실패 → 위 오류 확인")
        if args.validate_only:
            return

        with args.image.open("rb") as f:
            up = data(client.post("/runs/files", files={"file": (args.image.name, f, "image/jpeg")}))
        print(f"파일 업로드: id={up['id']} {up['originalName']} ({up['size']} bytes)")

        run_id = data(client.post(f"/workflows/{wf_id}/test-runs", json={"fileIds": [up["id"]]}))["runId"]
        print(f"실행 시작: run {run_id}")
        follow(client, run_id, args, resume_headers)


if __name__ == "__main__":
    main()