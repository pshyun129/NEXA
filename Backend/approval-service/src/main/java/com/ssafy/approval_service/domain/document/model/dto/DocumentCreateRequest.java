package com.ssafy.approval_service.domain.document.model.dto;

import com.ssafy.approval_service.domain.approval.policy.ApprovalAttributes;
import com.ssafy.approval_service.domain.document.model.entity.enums.FormType;
import com.ssafy.approval_service.domain.document.model.entity.enums.SourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 문서 생성 + 상신 요청. 생성 즉시 INPROGRESS 상태로 결재가 시작된다.
 * sourceType 을 생략하면 DIRECT(직접 기안). DIRECT 가 아니면 sourceId(원본 신청 ID)와 requestKey 가 필요하다.
 * requestKey 가 같은 요청이 다시 오면 문서를 새로 만들지 않고 처음 만든 문서를 돌려준다.
 * 문서번호(docNo)는 서버가 만든다. 요청에 docNo 가 있어도 무시한다.
 *
 * 결재선은 받지 않는다. 위임전결 규정과 조직도로 서버가 만든다 (예전 요청의 approvalLines 는 무시된다).
 * 참조자·열람자, 추가 결재자는 상신 후 결재선 API 로 넣는다.
 * attributes 는 전결 단계를 정하는 신청 값이다 (휴가 일수, 해외 출장 여부, 금액 등).
 * sourceLabel 은 출처를 화면에 보여 줄 이름이다 (예: 업무 자동화 워크플로우 이름). 선택이고, 직접 기안이면 보낼 수 없다.
 */
public record DocumentCreateRequest(
        @NotBlank @Size(max = 255) String title,            // 제목
        @NotNull FormType formType,                         // 문서양식
        SourceType sourceType,                              // 출처 서비스 (생략 시 DIRECT)
        Long sourceId,                                      // 출처 서비스의 원본 신청 ID
        UUID requestKey,                                    // 생성 요청 키 (출처가 있으면 필수)
        @Valid ApprovalAttributes attributes,               // 전결 판단용 신청 값 (선택)
        @Size(max = 100) String sourceLabel                 // 출처 표시 이름 (선택, 예: 워크플로우 이름). 뒤에 추가해 기존 생성자 호출 순서를 유지
) {
    public DocumentCreateRequest {
        if (sourceType == null) {
            sourceType = SourceType.DIRECT;
        }
        // 공백만 있으면 보내지 않은 것으로 본다
        sourceLabel = sourceLabel == null || sourceLabel.isBlank() ? null : sourceLabel.strip();
    }
}
