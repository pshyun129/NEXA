package com.ssafy.approval_service.domain.approval.event;

import com.ssafy.approval_service.domain.document.model.entity.Document;
import com.ssafy.approval_service.domain.document.model.entity.enums.DocumentStatus;
import com.ssafy.approval_service.domain.document.model.entity.enums.FormType;
import com.ssafy.approval_service.domain.document.model.entity.enums.SourceType;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 결재 결과 이벤트 (토픽 approval.document.result, 메시지 키 = documentId).
 * 출처가 있는 문서(휴가·근태·업무 자동화)가 최종 승인·반려·취소되면 발행한다. 수신 측은 sourceType 으로 자기 것만 처리한다.
 * 필드를 추가할 때는 eventVersion 은 그대로 두고(수신 측은 모르는 필드 무시), 의미가 바뀌는 변경일 때만 올린다.
 */
public record ApprovalResultEvent(
        UUID eventId,               // 이벤트마다 고유. 수신 측 중복 판단용
        int eventVersion,           // 이벤트 형식 버전
        String eventType,           // DOCUMENT_COMPLETED
        OffsetDateTime occurredAt,  // 결재가 끝난 시각
        Long documentId,
        String docNo,
        FormType formType,
        SourceType sourceType,      // LEAVE | ATTENDANCE | AUTOMATION
        Long sourceId,              // 원본 신청 ID
        UUID requestKey,            // 생성 요청 키 (원본 서비스가 보낸 UUID)
        DocumentStatus status       // APPROVED | REJECTED | CANCEL
) {
    public static final int VERSION = 1;
    public static final String TYPE_COMPLETED = "DOCUMENT_COMPLETED";

    public static ApprovalResultEvent completed(Document document) {
        return new ApprovalResultEvent(
                UUID.randomUUID(),
                VERSION,
                TYPE_COMPLETED,
                document.getCompletedAt(),
                document.getDocId(),
                document.getDocNo(),
                document.getFormType(),
                document.getSourceType(),
                document.getSourceId(),
                document.getRequestKey(),
                document.getStatus());
    }
}
