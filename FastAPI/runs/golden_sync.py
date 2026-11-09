"""골든셋 자동 전송: ai-service(EC2) → MLflow (실험 golden-set). 주기마다, 바뀐 게 있을 때만.

왜 EC2에서 보내나: 사람 확정 라벨은 EC2 DB에만 있고, GPU 서버·PC는 그 DB에 못 들어감
  → 서비스가 직접 MLflow에 올리면 PC·야간 작업이 거기서 받아 평가 (python -m mlops.export_golden --from-mlflow)
서빙과 분리
  - 별도 스레드, 서버 시작 10분 뒤 첫 실행 → 시작을 느리게 하지 않음
  - MLflow가 꺼져 있으면 로그만 남기고 다음 주기에 다시 (서비스는 영향 없음)
보내는 것: 사람 확정 라벨 + 카드·승인·전화번호를 가린 OCR 텍스트 (사용자 정보·업로드 파일은 안 보냄)
mlflow 패키지 없이 REST API로 → 서비스 이미지에 무거운 의존성을 안 넣음

  python -X utf8 -m runs.golden_sync --dry-run                     # 무엇을 보낼지 화면으로만
  python -X utf8 -m runs.golden_sync --experiment golden-set-test  # 로컬 확인용 (진짜 실험을 테스트 데이터로 더럽히지 않게)
"""
import argparse
import logging
import socket
import threading
import time

import httpx

from core.config import settings
from core.database import SessionLocal
from core.metrics import GOLDEN_SYNC
from runs.golden import collect_golden, golden_sha, to_jsonl

log = logging.getLogger(__name__)

EXPERIMENT = "golden-set"
DOC_TYPES = ["영수증"]
FIRST_DELAY = 600            # 서버 시작 후 첫 실행까지 (초)

_stop = threading.Event()
_thread: threading.Thread | None = None


def _api(client: httpx.Client, method: str, path: str, **kw) -> dict:
    r = client.request(method, f"/api/2.0/mlflow/{path}", **kw)
    if r.status_code == 404:
        return {"_not_found": True}
    r.raise_for_status()
    return r.json() if r.content else {}


def _experiment_id(client: httpx.Client, name: str) -> str:
    """실험 id (없으면 만듦)"""
    got = _api(client, "GET", "experiments/get-by-name", params={"experiment_name": name})
    if not got.get("_not_found"):
        return got["experiment"]["experiment_id"]
    return _api(client, "POST", "experiments/create", json={"name": name})["experiment_id"]


def _already_pushed(client: httpx.Client, exp_id: str, doc_type: str, sha: str) -> bool:
    found = _api(client, "POST", "runs/search", json={
        "experiment_ids": [exp_id], "max_results": 1,
        "filter": f"tags.golden_sha = '{sha}' and tags.doc_type = '{doc_type}'",
    })
    return bool(found.get("runs"))


def _push(client: httpx.Client, exp_id: str, doc_type: str, rows: list[dict], sha: str) -> str:
    """run 만들기 → ocr.jsonl 올리기 → 지표 기록 → 끝냄"""
    now = int(time.time() * 1000)
    tags = {"stage": "golden", "doc_type": doc_type, "golden_sha": sha, "source_host": socket.gethostname()}
    run = _api(client, "POST", "runs/create", json={
        "experiment_id": exp_id, "run_name": f"golden-{doc_type}-{time.strftime('%Y%m%d-%H%M')}",
        "start_time": now, "tags": [{"key": k, "value": v} for k, v in tags.items()],
    })["run"]["info"]
    run_id = run["run_id"]
    # artifact_uri 예: mlflow-artifacts:/5/<run_id>/artifacts → 서버가 대신 저장해 주는 업로드 주소로
    path = run["artifact_uri"].removeprefix("mlflow-artifacts:/").strip("/")
    up = client.put(f"/api/2.0/mlflow-artifacts/artifacts/{path}/ocr.jsonl",
                    content=to_jsonl(rows).encode("utf-8"))
    up.raise_for_status()
    edited = sum(1 for r in rows if r["meta"]["edited_fields"])
    _api(client, "POST", "runs/log-batch", json={"run_id": run_id, "metrics": [
        {"key": k, "value": v, "timestamp": now, "step": 0}
        for k, v in {"n": len(rows), "edited_n": edited, "approved_n": len(rows) - edited}.items()
    ]})
    _api(client, "POST", "runs/update", json={"run_id": run_id, "status": "FINISHED", "end_time": int(time.time() * 1000)})
    return run_id


def sync_once(experiment: str = EXPERIMENT, dry_run: bool = False) -> dict[str, str]:
    """문서 종류별로 한 번 전송. 결과: {문서 종류: pushed / unchanged / empty / dry-run}"""
    results = {}
    with SessionLocal() as db:
        sets = {t: collect_golden(db, t, masked=True)[0] for t in DOC_TYPES}
    if dry_run:
        for t, rows in sets.items():
            print(f"[{t}] {len(rows)}장 sha {golden_sha(rows)}")
            if rows:
                print("  첫 행 텍스트(가린 뒤):\n  " + rows[0]["text"].replace("\n", "\n  "))
            results[t] = "dry-run"
        return results

    with httpx.Client(base_url=settings.mlflow_tracking_uri.rstrip("/"), timeout=10.0) as client:
        exp_id = _experiment_id(client, experiment)
        for t, rows in sets.items():
            if not rows:
                results[t] = "empty"
                continue
            sha = golden_sha(rows)
            if _already_pushed(client, exp_id, t, sha):
                results[t] = "unchanged"
                continue
            run_id = _push(client, exp_id, t, rows, sha)
            log.info("골든셋 전송: %s %d장 sha=%s run=%s", t, len(rows), sha, run_id)
            results[t] = "pushed"
    return results


def _loop():
    if _stop.wait(FIRST_DELAY):
        return
    while True:
        try:
            for outcome in sync_once().values():
                GOLDEN_SYNC.labels(outcome).inc()
        except Exception as e:                      # MLflow 꺼짐·네트워크 등 → 다음 주기에 다시
            GOLDEN_SYNC.labels("error").inc()
            log.warning("골든셋 전송 실패 (다음 주기에 다시): %s", e)
        if _stop.wait(settings.golden_sync_hours * 3600):
            return


def start():
    """MLflow 주소가 있고 주기가 0보다 클 때만 (로컬 개발 PC는 .env에 GOLDEN_SYNC_HOURS=0)"""
    global _thread
    if not settings.mlflow_tracking_uri or settings.golden_sync_hours <= 0:
        log.info("골든셋 자동 전송 꺼짐 (MLFLOW_TRACKING_URI 없음 또는 GOLDEN_SYNC_HOURS=0)")
        return
    _stop.clear()
    _thread = threading.Thread(target=_loop, name="golden-sync", daemon=True)
    _thread.start()
    log.info("골든셋 자동 전송 시작: %s시간마다 → %s", settings.golden_sync_hours, settings.mlflow_tracking_uri)


def stop():
    _stop.set()


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--experiment", default=EXPERIMENT)
    p.add_argument("--dry-run", action="store_true")
    a = p.parse_args()
    print(sync_once(a.experiment, a.dry_run))