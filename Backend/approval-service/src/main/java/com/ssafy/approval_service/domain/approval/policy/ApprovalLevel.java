package com.ssafy.approval_service.domain.approval.policy;

/**
 * 전결 단계. 위임전결 규정에서 이 단계의 부서장이 최종 결재권자(전결권자)다.
 * user-service 부서 유형(dept_type)과 이름이 같다
 */
public enum ApprovalLevel {
    TEAM,       // 팀장 전결
    DIVISION,   // 본부장 전결
    COMPANY;    // 대표이사 결재

    /** 부서 유형 문자열(user-service dept_type)을 단계로. 값이 없거나 모르는 값이면 가장 낮은 TEAM 으로 본다 */
    public static ApprovalLevel ofDeptType(String deptType) {
        if (deptType == null) {
            return TEAM;
        }
        try {
            return valueOf(deptType);
        } catch (IllegalArgumentException e) {
            return TEAM;
        }
    }

    /** 이 단계가 기준 단계 이상인지 (TEAM < DIVISION < COMPANY) */
    public boolean isAtLeast(ApprovalLevel other) {
        return this.ordinal() >= other.ordinal();
    }
}
