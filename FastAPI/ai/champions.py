"""서빙 모델 자동 반영 + 자동 롤백 (ai-service 안의 백그라운드 스레드).

밤 작업(mlops/nightly.py)이 게이트를 통과한 후보를 MLflow Model Registry에 등록하고 별칭 champion을 옮기면,
이 스레드가 주기마다 champion을 확인해 재시작 없이 바꿔 끼운다.

  receipt-extractor  영수증 추출 DSPy 프로그램  → ai.modules.receipt.swap_program
  policy-judge       규정 판정 DSPy 프로그램    → ai.rag.policy.swap_judge
  receipt-adapter    영수증 추출 QLoRA 어댑터   → ai.modules.receipt.set_adapter (vLLM에 그 어댑터가 떠 있을 때만)

MLflow가 꺼져 있으면: 마지막으로 받은 champion(업로드 볼륨에 저장)을 계속 씀 → 서비스는 영향 없음
champion이 없으면(처음 또는 롤백으로 별칭이 지워짐): Git에 포함된 ai/programs 파일 기준

자동 롤백 (운영 DB에서만 = 골든셋 전송이 켜진 곳, 개발 PC의 테스트 데이터로 운영 모델을 되돌리지 않게)
  승격된 버전이 사람 확인을 rollback_min_samples장 이상 받았는데, 사람이 고친 비율이 이전 버전보다
  rollback_max_edit_increase 넘게 높으면 → 별칭 champion을 이전 버전으로 되돌리고 버전에 rolled_back 태그
  (규정 판정은 사람 확인 라벨이 없어서 자동 롤백 대상이 아님 — 게이트의 '놓친 위반 0'이 안전장치)

mlflow 패키지 없이 REST API로 (서비스 이미지에 무거운 의존성을 안 넣음, golden_sync와 같은 방식)
"""
import json
import logging
import tempfile
import threading
import time
from pathlib import Path

import httpx
from sqlalchemy import select

from core.config import settings
from core.metrics import CHAMPION_SYNC

log = logging.getLogger(__name__)

RECEIPT, POLICY, ADAPTER = "receipt-extractor", "policy-judge", "receipt-adapter"
ALIAS = "champion"
FIRST_DELAY = 60             # 서버 시작 후 첫 확인까지 (초)

_stop = threading.Event()
_thread: threading.Thread | None = None
_loaded: dict[str, str] = {}             # 모델 이름 → 지금 끼워 둔 버전 ("" = Git 파일 기준)


def _cache_dir() -> Path:
    d = Path(settings.upload_dir) / "_champions"      # 업로드 볼륨 → 컨테이너를 다시 만들어도 남음
    d.mkdir(parents=True, exist_ok=True)
    return d


# ---------------------------------------------------------------- MLflow REST
def _client() -> httpx.Client:
    return httpx.Client(base_url=settings.mlflow_tracking_uri.rstrip("/"), timeout=15.0)


def _tags(mv: dict) -> dict:
    return {t["key"]: t["value"] for t in mv.get("tags", [])}


def get_champion(c: httpx.Client, name: str) -> dict | None:
    """별칭 champion이 붙은 버전 (없으면 None)"""
    r = c.get("/api/2.0/mlflow/registered-models/alias", params={"name": name, "alias": ALIAS})
    # 모델 이름이 없으면 RESOURCE_DOES_NOT_EXIST, 별칭만 없으면(롤백으로 지워짐) INVALID_PARAMETER_VALUE "... not found"
    if r.status_code in (400, 404) and ("RESOURCE_DOES_NOT_EXIST" in r.text or "not found" in r.text):
        return None
    r.raise_for_status()
    mv = r.json()["model_version"]
    return {"version": mv["version"], "tags": _tags(mv)}


def _download(c: httpx.Client, uri: str) -> bytes:
    """mlflow-artifacts:/<경로> → 서버가 대신 내려주는 주소로 받기"""
    path = uri.removeprefix("mlflow-artifacts:").lstrip("/")
    r = c.get(f"/api/2.0/mlflow-artifacts/artifacts/{path}")
    r.raise_for_status()
    return r.content


def _set_alias(c: httpx.Client, name: str, version: str | None):
    if version:
        c.post("/api/2.0/mlflow/registered-models/alias",
               json={"name": name, "alias": ALIAS, "version": version}).raise_for_status()
    else:                                    # 이전 버전이 Git 파일 기준이었으면 별칭을 지움 → 서비스가 Git 파일로 돌아감
        c.request("DELETE", "/api/2.0/mlflow/registered-models/alias",
                  json={"name": name, "alias": ALIAS}).raise_for_status()


def _set_tag(c: httpx.Client, name: str, version: str, key: str, value: str):
    c.post("/api/2.0/mlflow/model-versions/set-tag",
           json={"name": name, "version": version, "key": key, "value": value}).raise_for_status()


# ---------------------------------------------------------------- 바꿔 끼우기
def _apply_program(name: str, version: str, label: str, data: bytes | None):
    """DSPy 프로그램 상태(JSON)를 불러와 교체. data None = Git 파일 기준으로"""
    from ai.modules.receipt import ReceiptExtractor, swap_program
    from ai.rag.policy import PolicyJudge, swap_judge

    module = None
    if data is not None:
        module = ReceiptExtractor() if name == RECEIPT else PolicyJudge()
        with tempfile.NamedTemporaryFile("wb", suffix=".json", delete=False) as f:
            f.write(data)
        try:
            module.load(f.name)
        finally:
            Path(f.name).unlink(missing_ok=True)
    (swap_program if name == RECEIPT else swap_judge)(module, label if module else None)
    _loaded[name] = version
    log.info("서빙 반영: %s → %s", name, label if module else "Git 파일 기준")


def _sync_program(c: httpx.Client, name: str):
    ch = get_champion(c, name)
    if ch is None:
        if _loaded.get(name):                          # 별칭이 지워짐(롤백) → Git 파일로
            _apply_program(name, "", "", None)
            for suffix in (".json", ".meta.json"):
                (_cache_dir() / f"{name}{suffix}").unlink(missing_ok=True)
            CHAMPION_SYNC.labels(name, "reverted").inc()
        return
    if _loaded.get(name) == ch["version"]:
        CHAMPION_SYNC.labels(name, "unchanged").inc()
        return
    data = _download(c, ch["tags"]["artifact"])
    label = ch["tags"].get("label", f"{name}@v{ch['version']}")
    _apply_program(name, ch["version"], label, data)
    # 다음 시작 때 MLflow가 꺼져 있어도 이 버전으로 뜨도록 저장
    (_cache_dir() / f"{name}.json").write_bytes(data)
    (_cache_dir() / f"{name}.meta.json").write_text(json.dumps({"version": ch["version"], "label": label}), encoding="utf-8")
    CHAMPION_SYNC.labels(name, "swapped").inc()


def _vllm_models() -> set[str]:
    r = httpx.get(f"{settings.local_llm_base_url.rstrip('/')}/models", timeout=10.0,
                  headers={"Authorization": f"Bearer {settings.local_llm_api_key}"})
    r.raise_for_status()
    return {m["id"] for m in r.json().get("data", [])}


def _sync_adapter(c: httpx.Client):
    """어댑터 champion이 있고 vLLM에 그 이름이 떠 있을 때만 사용 (vLLM은 매일 아침 champion 어댑터를 붙여 뜸)"""
    from ai.modules.receipt import set_adapter

    ch = get_champion(c, ADAPTER)
    want = ch["tags"].get("adapter_name") if ch else None
    if want and settings.local_llm_base_url:
        try:
            if want not in _vllm_models():
                log.warning("어댑터 champion %s가 vLLM에 없음 → 기본 모델로 추출 (GPU 서버 start_day 확인)", want)
                want = None
        except httpx.HTTPError as e:
            log.warning("vLLM 모델 목록 확인 실패 (지금 설정 유지): %s", e)
            return
    if _loaded.get(ADAPTER) != (want or ""):
        set_adapter(want)
        _loaded[ADAPTER] = want or ""
        log.info("서빙 반영: 추출 모델 → %s", want or settings.local_llm_model)
        CHAMPION_SYNC.labels(ADAPTER, "swapped").inc()


# ---------------------------------------------------------------- 자동 롤백
def _split_version(model_version: str | None) -> tuple[str, str]:
    """'receipt@v3+receipt-qlora-20261112' → ('receipt@v3', 'receipt-qlora-20261112'), 어댑터 없으면 ''"""
    program, _, adapter = (model_version or "").partition("+")
    return program, adapter


def edit_rates(db, part: int) -> dict[str, tuple[int, float]]:
    """사람이 확인한 영수증만: 버전(프로그램 part=0 / 어댑터 part=1)별 (장수, 사람이 고친 비율)"""
    from runs.models import RunFeedback

    rows = db.execute(select(RunFeedback.model_version, RunFeedback.edited_fields)
                      .where(RunFeedback.label_source == "human", RunFeedback.doc_type == "영수증")).all()
    acc: dict[str, list[int]] = {}
    for mv, edited in rows:
        key = _split_version(mv)[part]
        n_edit = acc.setdefault(key, [0, 0])
        n_edit[0] += 1
        n_edit[1] += 1 if edited else 0
    return {k: (n, e / n) for k, (n, e) in acc.items()}


def should_rollback(cur: tuple[int, float] | None, prev: tuple[int, float] | None) -> str | None:
    """되돌릴 이유 (없으면 None). 양쪽 다 충분히 쌓였을 때만 비교 (적은 수로 판단하면 우연에 흔들림)"""
    need = settings.rollback_min_samples
    if not cur or not prev or cur[0] < need or prev[0] < need:
        return None
    if cur[1] - prev[1] > settings.rollback_max_edit_increase:
        return f"사람 수정률 {prev[1]:.0%}({prev[0]}장) → {cur[1]:.0%}({cur[0]}장)"
    return None


def _check_rollback(c: httpx.Client):
    from core.database import SessionLocal

    with SessionLocal() as db:
        rates = {RECEIPT: edit_rates(db, 0), ADAPTER: edit_rates(db, 1)}
    for name, by in rates.items():
        ch = get_champion(c, name)
        if not ch or ch["tags"].get("rolled_back"):
            continue
        t = ch["tags"]
        cur_key = t.get("label", "") if name == RECEIPT else t.get("adapter_name", "")
        prev_key = t.get("previous_label", "") if name == RECEIPT else t.get("previous_adapter", "")
        reason = should_rollback(by.get(cur_key), by.get(prev_key))
        if not reason:
            continue
        prev_version = t.get("previous_version") or None
        _set_alias(c, name, prev_version)
        _set_tag(c, name, ch["version"], "rolled_back", f"{time.strftime('%Y-%m-%d %H:%M')} {reason}")
        CHAMPION_SYNC.labels(name, "rolled_back").inc()
        log.warning("자동 롤백: %s v%s → %s (%s)", name, ch["version"], prev_version or "Git 파일 기준", reason)


# ---------------------------------------------------------------- 실행
def load_cached():
    """시작할 때: 지난번에 받아 둔 champion이 있으면 먼저 끼움 (MLflow가 꺼져 있어도 같은 버전으로 서빙)"""
    for name in (RECEIPT, POLICY):
        data_p, meta_p = _cache_dir() / f"{name}.json", _cache_dir() / f"{name}.meta.json"
        if data_p.exists() and meta_p.exists():
            try:
                meta = json.loads(meta_p.read_text(encoding="utf-8"))
                _apply_program(name, meta["version"], meta["label"], data_p.read_bytes())
            except Exception:
                log.exception("저장해 둔 champion 불러오기 실패: %s (Git 파일 기준으로 시작)", name)


def sync_once():
    with _client() as c:
        for name in (RECEIPT, POLICY):
            _sync_program(c, name)
        _sync_adapter(c)
        if settings.golden_sync_hours > 0:            # 운영 DB에서만 (골든셋 전송이 켜진 곳)
            _check_rollback(c)
            for name in (RECEIPT, POLICY):            # 롤백했으면 바로 반영
                _sync_program(c, name)
            _sync_adapter(c)


def _loop():
    if _stop.wait(FIRST_DELAY):
        return
    while True:
        try:
            sync_once()
        except Exception as e:                        # MLflow 꺼짐·네트워크 → 지금 버전 유지, 다음 주기에 다시
            CHAMPION_SYNC.labels("-", "error").inc()
            log.warning("champion 확인 실패 (지금 버전 유지): %s", e)
        if _stop.wait(settings.champion_sync_minutes * 60):
            return


def start():
    """MLflow 주소가 있고 주기가 0보다 클 때만 (개발 PC에서 끄려면 .env에 CHAMPION_SYNC_MINUTES=0)"""
    global _thread
    try:
        load_cached()
    except Exception:
        log.exception("저장해 둔 champion 확인 실패")
    if not settings.mlflow_tracking_uri or settings.champion_sync_minutes <= 0:
        log.info("champion 자동 반영 꺼짐 (MLFLOW_TRACKING_URI 없음 또는 CHAMPION_SYNC_MINUTES=0)")
        return
    _stop.clear()
    _thread = threading.Thread(target=_loop, name="champion-sync", daemon=True)
    _thread.start()
    log.info("champion 자동 반영 시작: %s분마다 → %s", settings.champion_sync_minutes, settings.mlflow_tracking_uri)


def stop():
    _stop.set()
