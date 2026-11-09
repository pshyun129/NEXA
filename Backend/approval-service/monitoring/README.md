# approval-service 로컬 모니터링

결재 결과가 원본 서비스(leave·attendance)까지 전달되는지 보는 로컬 환경입니다.
**approval → 아웃박스 → Kafka → 수신 서비스** 구간을 대시보드와 알림으로 확인합니다.

## 띄우기

```bash
cd Backend/approval-service

# 브로커·DB + 모니터링 4개 (app 컨테이너는 빼고 띄운다)
docker compose --profile monitoring up -d postgres kafka prometheus kafka-exporter grafana

# 앱은 호스트에서 (지금 코드 그대로)
./gradlew bootRun
```

> ⚠️ `docker compose --profile monitoring up -d` 처럼 **서비스 이름 없이** 실행하면 profile 이 없는 `app` 컨테이너도 함께 뜹니다.
> `app` 이미지가 예전에 빌드된 것이면 옛 코드가 8083 을 차지합니다. 앱을 컨테이너로 띄우려면 `docker compose up -d --build app` 으로 새로 빌드하세요.

끄기: `docker compose --profile monitoring stop` (데이터는 볼륨에 남음)

## 주소

| 도구 | 주소 | 용도 |
|---|---|---|
| Grafana | http://localhost:13000 | 대시보드 **결재 결과 전달 (아웃박스 · Kafka)**, 알림 규칙. 로그인 없이 보기 가능, 편집은 `admin` / `admin` |
| Prometheus | http://localhost:19090 | 수집 대상 상태(Status → Targets), 쿼리 |
| approval 메트릭 | http://localhost:8083/actuator/prometheus | Prometheus 가 수집하는 원본 |

## 구성

| 컨테이너 | 하는 일 | 설정 파일 |
|---|---|---|
| `prometheus` | approval(`/actuator/prometheus`)과 kafka-exporter 를 15초마다 수집 | `prometheus/prometheus.yml` |
| `kafka-exporter` | Kafka 컨슈머 그룹 LAG, 토픽 오프셋을 Prometheus 형식으로 | (compose `command`) |
| `grafana` | 대시보드·알림. 데이터 소스, 대시보드, 알림 규칙을 파일로 자동 등록 | `grafana/provisioning/**`, `grafana/dashboards/*.json` |

## 대시보드

| 영역 | 패널 |
|---|---|
| approval → Kafka 발행 | 수집 상태, 미발행 이벤트 수, 가장 오래 기다린 이벤트, 최근 1시간 발행 실패, 발행 대기 추이, 분당 발행 처리량 |
| Kafka → 수신 서비스 | 컨슈머 그룹별 LAG, 결과 토픽·DLT 누적 메시지 수 |
| 아웃박스 테이블 | 미발행 이벤트 목록 (DB 직접 조회: 문서 ID, 대기 시간, 결과, 출처) |

## 알림 규칙 (Grafana → Alerting → Alert rules)

| 규칙 | 조건 | 의미 |
|---|---|---|
| 아웃박스 발행 지연 | 가장 오래된 미발행 이벤트 > 5분, 1분 지속 | approval → Kafka 발행이 막힘 (브로커 장애 등) |
| 결재 결과 수신 지연 (LAG) | 컨슈머 그룹 LAG > 0, 10분 지속 | 수신 서비스가 결과를 처리하지 못하고 있음 |
| 결재 결과 DLT 메시지 발생 | 최근 10분 DLT 메시지 증가 > 0 | 수신 서비스가 재시도 후에도 처리 못 한 메시지 발생 |

알림 채널(Slack 등)은 아직 없습니다. 상태는 Grafana 화면에서 봅니다.

## 메트릭 이름

| approval 코드 (Micrometer) | Prometheus |
|---|---|
| `approval.outbox.pending` (게이지) | `approval_outbox_pending` |
| `approval.outbox.oldest.age` (게이지, 초) | `approval_outbox_oldest_age_seconds` |
| `approval.outbox.published` (카운터) | `approval_outbox_published_total` |
| `approval.outbox.publish.failures` (카운터) | `approval_outbox_publish_failures_total` |

Kafka (kafka-exporter): `kafka_consumergroup_lag_sum{consumergroup, topic}`, `kafka_topic_partition_current_offset{topic, partition}`

## 메시지 내용·DLT 보기 (CLI)

메모리를 아끼려고 Kafka UI 는 넣지 않았습니다(JVM 이라 약 370MB). 메시지 내용은 CLI 로 봅니다.

```bash
export MSYS_NO_PATHCONV=1   # Git Bash 에서만
K="docker exec approval-service-kafka /opt/kafka/bin"

# 결과 메시지 / DLT 메시지 (키 포함, 5초 동안 없으면 종료)
$K/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic approval.document.result --from-beginning --property print.key=true --timeout-ms 5000
$K/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic approval.document.result.DLT --from-beginning --property print.key=true --timeout-ms 5000

# 컨슈머 그룹 LAG
$K/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups
```

## 대시보드·규칙 고치기

- Grafana 화면에서 고친 내용은 컨테이너를 다시 만들면 사라집니다. 바꾸려면 **JSON·YAML 파일을 고치고** `docker compose --profile monitoring restart grafana`
- 화면에서 만든 패널을 파일로 옮기려면: 대시보드 → Share → Export → JSON 을 `grafana/dashboards/approval-outbox-kafka.json` 에 덮어쓰기

## 공용 서버

이 구성은 로컬 전용입니다. 공용 서버에 올리려면 infra 담당과 정해야 할 것:
서버 메모리(3개 합쳐 로컬 실측 약 110MB, 데이터가 쌓이면 Prometheus 가 늘어남), Grafana 접근 제어(익명 보기 끄기, 인증), 알림 채널, `/actuator/prometheus` 수집 경로.
