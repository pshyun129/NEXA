package com.ssafy.approval_service.domain.approval.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 반려된 문서 재기안 요청. 차수가 1 올라간다.
 * 결재선은 받지 않는다. 위임전결 규정과 재기안 시점의 조직도로 서버가 다시 만든다 (예전 요청의 approvalLines 는 무시된다)
 */
public record ResubmitRequest(
        @NotBlank @Size(max = 255) String title    // 제목
) {
}
