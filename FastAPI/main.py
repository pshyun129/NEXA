import logging
import threading
from contextlib import asynccontextmanager

from prometheus_fastapi_instrumentator import Instrumentator
import py_eureka_client.eureka_client as eureka_client
from fastapi import Depends, FastAPI

import runs.models
import workflows.models  # noqa: F401  테이블 등록용
from core.auth import get_current_user
from core.config import settings
from core.database import Base, SessionLocal, engine
from engine.checkpointer import close_checkpointer, init_checkpointer
from core.exceptions import register_exception_handlers
from events import approval_consumer
from nodes.routers import router as nodes_router
from runs.routers import router as runs_router
from workflows.routers import router as workflows_router
from ai.routers import router as ai_router
from ai.rag.policy import warm_up as warm_up_rag   # 모델 + 고정 질문 검색 결과 미리 계산
from runs import golden_sync
from ai import champions
from runs.duplicates import backfill_keys
from runs.service import fail_interrupted_runs


# 우리 코드의 INFO 로그가 보이게 (httpx 요청 로그는 너무 많아서 경고 이상만)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [%(name)s] %(message)s")
logging.getLogger("httpx").setLevel(logging.WARNING)

API_PREFIX = "/api/v1/ai"
SERVICE_NAME = "ai-service"


@asynccontextmanager
def _backfill_receipt_keys() -> None:
    try:
        with SessionLocal() as db:
            n = backfill_keys(db)
        if n:
            logging.getLogger(__name__).info("중복 영수증 색인 %d줄 채움", n)
    except Exception:
        logging.getLogger(__name__).exception("중복 영수증 색인 채우기 실패")


async def lifespan(app: FastAPI):
    # 서버 켤 때
    Base.metadata.create_all(engine)
    # 재시작(배포) 전에 돌던 실행은 이어서 돌릴 수 없으므로 실패로 정리 (RUNNING으로 영원히 남지 않게)
    with SessionLocal() as db:
        interrupted = fail_interrupted_runs(db)
    if interrupted:
        logging.getLogger(__name__).warning("서버 재시작으로 중단된 실행 %d개를 실패 처리", interrupted)
    # 실행끼리 중복 영수증 색인(receipt_key)이 비어 있으면 지난 실행에서 한 번 채움 (오래 걸릴 수 있어 백그라운드)
    threading.Thread(target=_backfill_receipt_keys, name="receipt-key-backfill", daemon=True).start()
    init_checkpointer()
    approval_consumer.start()          # 결재 결과 구독 시작 (KAFKA_BOOTSTRAP_SERVERS 없으면 건너뜀)
    # 규정 RAG 모델 미리 불러오기: 수 초~수십 초 걸려서 백그라운드로 (헬스체크·다른 요청을 막지 않게)
    threading.Thread(target=warm_up_rag, name="rag-warmup", daemon=True).start()
    golden_sync.start()                # 골든셋 → MLflow 자동 전송 (MLFLOW_TRACKING_URI·GOLDEN_SYNC_HOURS 있을 때만)
    champions.start()                  # 밤 작업이 승격한 모델을 재시작 없이 반영 + 자동 롤백 (MLflow champion)
    if settings.eureka_server_url:
        options = {}
        if settings.instance_host:
            options["instance_host"] = settings.instance_host
        await eureka_client.init_async(
            eureka_server=settings.eureka_server_url,
            app_name=SERVICE_NAME,
            instance_port=settings.instance_port,
            **options,
        )
    yield
    # 서버 끌 때: 구독을 먼저 멈춰야 재개 처리 중에 DB 연결이 끊기지 않음
    approval_consumer.stop()
    golden_sync.stop()
    champions.stop()
    close_checkpointer()
    if settings.eureka_server_url:
        await eureka_client.stop_async()


app = FastAPI(title="업무 자동화", lifespan=lifespan)

# /metrics: HTTP 요청 수·지연(자동) + core/metrics.py 지표를 Prometheus 형식으로 노출
# 인증 없음 (팀 Prometheus가 compose 내부망에서 fastapi:8086 으로 직접 수집, 게이트웨이는 /api/** 만 연결해서 외부엔 안 열림)
Instrumentator(excluded_handlers=["/metrics", "/health"]).instrument(app).expose(
    app, endpoint="/metrics", include_in_schema=False
)

register_exception_handlers(app)

# /api/v1/ai/** 는 전부 로그인 필요 (/health 는 헬스체크용으로 열어둠)
AUTH = [Depends(get_current_user)]
app.include_router(nodes_router, prefix=API_PREFIX, dependencies=AUTH)
app.include_router(workflows_router, prefix=API_PREFIX, dependencies=AUTH)
app.include_router(runs_router, prefix=API_PREFIX, dependencies=AUTH)

# 개발용 LLM 테스트 API는 설정으로 켰을 때만 등록
if settings.llm_test_api_enabled:
    app.include_router(ai_router, prefix=API_PREFIX, dependencies=AUTH)


@app.get("/health")
def health():
    return {"status": "ok"}