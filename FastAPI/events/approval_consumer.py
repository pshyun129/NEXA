"""결재 결과 이벤트 구독 (토픽 approval.document.result).

결재 서비스 계약 (DOCS/approval-result-event.md):
  - sourceType이 AUTOMATION인 것만 처리, 나머지는 무시 (enum이 아니라 문자열로 비교)
  - 최소 한 번 전달 → 같은 이벤트가 두 번 올 수 있음 → handle()은 멱등해야 함
  - 처리에 성공한 뒤에만 offset 커밋 → 서버가 죽어도 이벤트를 잃지 않음
"""
import json
import logging
import threading

from confluent_kafka import Consumer, KafkaError, TopicPartition
from events.approval_results import resume_if_ready, save_result

from core.config import settings
from core.metrics import KAFKA_UP

log = logging.getLogger(__name__)

TOPIC = "approval.document.result"
SOURCE_TYPE = "AUTOMATION"
FINAL_STATUSES = {"APPROVED", "REJECTED", "CANCEL"}
MAX_ATTEMPTS = 3          # 같은 메시지 처리 실패 허용 횟수 (팀 규칙: 3회 재시도 후 포기)
RETRY_WAIT_SECONDS = 2

_stop = threading.Event()
_thread: threading.Thread | None = None


def handle(event: dict) -> None:
    """결재 결과 1건 처리. 4-4 ②에서 '결과 보관 + 실행 재개'로 채움"""
    save_result(event)
    if resume_if_ready(event["requestKey"]):
        return
    # 아직 대기 노드에 도착하지 않은 실행 → 대기 노드가 보관함을 보고 바로 지나감
    log.info("결재 결과 보관: requestKey=%s status=%s (기다리는 실행 없음)",
             event["requestKey"], event["status"])


def _parse(raw: bytes | None) -> dict | None:
    """우리 것(AUTOMATION)이고 형식이 맞으면 dict, 아니면 None(건너뜀)"""
    try:
        event = json.loads(raw)
    except (TypeError, ValueError):
        log.error("결재 결과 JSON 형식 오류 → 건너뜀")   # 원문은 로그에 남기지 않음
        return None
    if not isinstance(event, dict) or event.get("sourceType") != SOURCE_TYPE:
        return None                                       # 휴가·근태 등 다른 서비스 것
    if not event.get("requestKey") or event.get("status") not in FINAL_STATUSES:
        log.error("결재 결과 필수 값 누락 → 건너뜀: eventId=%s", event.get("eventId"))
        return None
    return event


def _on_error(err) -> None:
    """librdkafka 연결 오류: 브로커가 전부 안 보이면 끊김으로 표시"""
    if err.code() in (KafkaError._ALL_BROKERS_DOWN, KafkaError._TRANSPORT):
        KAFKA_UP.set(0)


def _run() -> None:
    consumer = Consumer({
        "bootstrap.servers": settings.kafka_bootstrap_servers,
        "group.id": settings.kafka_group_id,
        "enable.auto.commit": False,          # 처리 끝난 뒤 직접 커밋
        "auto.offset.reset": "earliest",      # 처음 붙을 때 쌓여 있던 이벤트부터
        "allow.auto.create.topics": False,    # 토픽은 결재 서비스가 만듦
        "reconnect.backoff.ms": 1000,         # 브로커가 꺼져 있을 때 재접속 간격 (로그 폭주 방지)
        "reconnect.backoff.max.ms": 30000,
        "logger": logging.getLogger("kafka"), # librdkafka 로그를 파이썬 로그로
        "error_cb": _on_error,                # 연결이 끊기면 게이지 0
    })
    consumer.subscribe([TOPIC], on_assign=lambda c, partition: KAFKA_UP.set(1))
    attempts: dict[tuple[int, int], int] = {}   # (파티션, 오프셋) → 실패 횟수
    log.info("결재 결과 구독 시작: %s (group=%s)", TOPIC, settings.kafka_group_id)

    try:
        while not _stop.is_set():
            msg = consumer.poll(1.0)
            if msg is None:
                continue
            if msg.error():
                if msg.error().code() != KafkaError._PARTITION_EOF:
                    log.warning("Kafka 수신 오류: %s", msg.error())
                continue

            event = _parse(msg.value())
            if event is not None:
                pos = (msg.partition(), msg.offset())
                try:
                    handle(event)
                except Exception:
                    attempts[pos] = attempts.get(pos, 0) + 1
                    if attempts[pos] < MAX_ATTEMPTS:
                        log.exception("결재 결과 처리 실패 (%d/%d) → 다시 시도", attempts[pos], MAX_ATTEMPTS)
                        # 커밋하지 않고 같은 메시지로 되감아서 잠시 뒤 다시 받음
                        consumer.seek(TopicPartition(msg.topic(), msg.partition(), msg.offset()))
                        _stop.wait(RETRY_WAIT_SECONDS)
                        continue
                    # 계속 실패: 로그로 남기고 넘어감 (by-request 조회로 수동 복구)
                    log.exception("결재 결과 처리 %d회 실패 → 건너뜀: requestKey=%s",
                                  MAX_ATTEMPTS, event["requestKey"])
                attempts.pop(pos, None)

            consumer.commit(message=msg, asynchronous=False)
    finally:
        consumer.close()
        log.info("결재 결과 구독 종료")


def start() -> None:
    """서버 시작 시 호출. KAFKA_BOOTSTRAP_SERVERS가 비어 있으면 구독하지 않음"""
    global _thread
    if not settings.kafka_bootstrap_servers:
        log.info("KAFKA_BOOTSTRAP_SERVERS 없음 → 결재 결과 구독 안 함")
        return
    _stop.clear()
    _thread = threading.Thread(target=_run, name="approval-result-consumer", daemon=True)
    _thread.start()


def stop() -> None:
    """서버 종료 시 호출. 처리 중인 메시지를 끝내고 정리"""
    _stop.set()
    if _thread is not None:
        _thread.join(timeout=10)