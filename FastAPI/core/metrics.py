"""Prometheus 지표 정의 (5-8 모니터링).

여기서 정의한 지표는 /metrics 로 노출되고, 팀 Prometheus가 15초마다 가져간다.
이름 규칙: ai_ 접두어 + 단위 접미어(_seconds, _total) → Prometheus 관례
라벨에는 값 종류가 적은 것만 넣는다 (run_id 같은 걸 넣으면 시계열이 끝없이 늘어나 Prometheus가 무거워짐)
"""
from prometheus_client import Counter, Gauge, Histogram

# 노드 실행 시간. outcome = ok(성공) / fail(에러) / wait(사람·결재 대기로 멈춤)
NODE_SECONDS = Histogram(
    "ai_node_duration_seconds", "노드 실행 시간", ["node_type", "outcome"],
    buckets=(0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30, 60),
)

# 분기 노드가 고른 길 (조건 참/거짓, 검증 자동 확정/사람 확인 등)
NODE_BRANCH = Counter("ai_node_branch_total", "노드가 고른 분기", ["node_type", "branch"])

# 끝난 실행 (SUCCEEDED / FAILED / CANCELED)
RUNS = Counter("ai_runs_finished_total", "끝난 실행 수", ["status"])

# LLM 호출. provider = local / gms, outcome = ok / fallback(로컬 실패 → GMS로 넘김) / error
LLM_CALLS = Counter("ai_llm_calls_total", "LLM 호출 결과", ["provider", "outcome"])

# OCR 서버 호출
OCR_SECONDS = Histogram(
    "ai_ocr_duration_seconds", "OCR 서버 호출 시간",
    buckets=(0.1, 0.25, 0.5, 1, 2, 5, 10, 30),
)
OCR_ERRORS = Counter("ai_ocr_errors_total", "OCR 실패 수")

# 검증 결과. route = auto(자동 확정) / human(사람 확인) → 모델 버전별 자동화율
VERIFY = Counter("ai_verify_total", "검증 결과", ["doc_type", "model_version", "route"])
# GMS 교차 추출 호출. purpose = ambiguous(싼 검증에 걸린 장) / audit(통과한 장 표본 감사), result = agree / disagree
#   audit의 disagree 비율 = 싼 검증이 놓치는 비율
VERIFY_GMS = Counter("ai_verify_gms_total", "자동 검증의 GMS 호출", ["purpose", "result"])

# Kafka 결재 결과 구독 상태: 1 = 연결, 0 = 끊김
# 끊기면 결재 결과가 안 와서 wait_approval 노드가 계속 대기
KAFKA_UP = Gauge("ai_kafka_consumer_up", "Kafka 결재 결과 구독 상태 (1 연결 / 0 끊김)")

# 규정 검토 결과. verdict = 준수 / 위반 / 확인 필요 → 판정 모델 버전별 자동 통과율
POLICY_CHECKS = Counter("ai_policy_check_total", "규정 검토 결과", ["doc_type", "verdict", "judge_version"])

# 규정 검토 단계별 시간. phase = retrieval(검색·재정렬, CPU) / judge(조항 판정, LLM) / total
POLICY_SECONDS = Histogram(
    "ai_policy_check_seconds", "규정 검토 단계별 시간", ["phase"],
    buckets=(0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 20, 30, 60),
)
# 규정 검색 캐시. result = hit(같은 질문 재사용) / miss(검색·재정렬 실행)
JUDGE_CACHE = Counter("ai_policy_judge_cache_total", "규정 판정 캐시 (같은 조항·같은 사실)", ["result"])
RETRIEVAL_CACHE = Counter("ai_policy_retrieval_cache_total", "규정 검색 캐시", ["result"])

GOLDEN_SYNC = Counter("ai_golden_sync_total", "골든셋 MLflow 전송 결과", ["outcome"])  # pushed/unchanged/empty/error