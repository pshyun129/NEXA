package com.ssafy.user_service.domain.user.dto;

import com.ssafy.user_service.domain.user.entity.JobCode;
import com.ssafy.user_service.domain.user.entity.enums.JobCodeType;

/** 직무 코드 목록 항목. 직군(FAMILY) → 직종(KIND) → 직무(ROLE) 계층이고 parentId 로 잇는다 */
public record JobCodeResponse(
        Long jobCodeId,
        JobCodeType type,       // 종류 (FAMILY/KIND/ROLE)
        String name,            // 코드명
        Long parentId           // 상위 코드ID (직군은 null)
) {
    public static JobCodeResponse from(JobCode jobCode) {
        JobCode parent = jobCode.getParent();   // LAZY 프록시여도 ID 읽기는 초기화하지 않는다
        return new JobCodeResponse(jobCode.getJobCodeId(), jobCode.getType(), jobCode.getName(),
                parent == null ? null : parent.getJobCodeId());
    }
}
