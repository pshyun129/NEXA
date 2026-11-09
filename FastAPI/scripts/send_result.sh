#!/usr/bin/env bash
# 결재 결과 이벤트를 로컬 Kafka에 보냄 (결재 서비스가 보내는 것과 같은 형식, 개발용)
#   사용법: bash scripts/send_result.sh <requestKey> <APPROVED|REJECTED|CANCEL>
set -euo pipefail

KEY="${1:?requestKey가 필요합니다}"
STATUS="${2:-APPROVED}"

printf '{"eventId":"e-%s","eventVersion":1,"eventType":"DOCUMENT_COMPLETED","sourceType":"AUTOMATION","requestKey":"%s","status":"%s","documentId":1,"docNo":"DOC-0001"}\n' \
  "$RANDOM$RANDOM" "$KEY" "$STATUS" \
| MSYS_NO_PATHCONV=1 docker exec -i approval-service-kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic approval.document.result

echo "보냄: requestKey=$KEY status=$STATUS"