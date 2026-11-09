package com.ssafy.user_service.domain.user.entity.enums;

/** 부서 유형. 결재 전결 단계(팀장/본부장/대표이사)를 정하는 기준 */
public enum DeptType {
    COMPANY,    // 회사 (부서장 = 대표이사)
    DIVISION,   // 본부 (부서장 = 본부장)
    TEAM        // 팀 (부서장 = 팀장)
}
