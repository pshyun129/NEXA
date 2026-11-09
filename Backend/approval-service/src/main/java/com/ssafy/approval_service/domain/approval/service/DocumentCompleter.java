package com.ssafy.approval_service.domain.approval.service;

import com.ssafy.approval_service.domain.approval.event.ApprovalResultEventRecorder;
import com.ssafy.approval_service.domain.document.model.entity.Document;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문서를 최종 상태(승인·반려·취소)로 바꾸고 결과 이벤트를 함께 남긴다.
 * 최종 처리는 모두 여기를 거친다 → 어느 경로(결재, 전결, 반려, 회수, 자가결재)로 끝나도 원본 서비스가 결과를 받는다.
 * 이력은 경로마다 동작·코멘트가 달라 호출하는 쪽이 남긴다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class DocumentCompleter {

    private final ApprovalResultEventRecorder resultEventRecorder;

    public void approve(Document document) {
        document.approve();
        resultEventRecorder.recordCompleted(document);
    }

    public void reject(Document document) {
        document.reject();
        resultEventRecorder.recordCompleted(document);
    }

    public void cancel(Document document) {
        document.cancel();
        resultEventRecorder.recordCompleted(document);
    }
}
