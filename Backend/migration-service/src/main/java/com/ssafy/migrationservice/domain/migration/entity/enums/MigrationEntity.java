package com.ssafy.migrationservice.domain.migration.entity.enums;

/** 이관 대상 데이터 종류 (MVP). 선언 순서 = 적재 순서 */
public enum MigrationEntity {
    HR_CODE,
    HR_ORG,
    HR_EMPLOYEE,
    LEAVE_BALANCE,
    PAY_PROFILE,
    PAY_RESULT_HISTORY,
    PAY_YTD_BALANCE,
    /** 적재하지 않는 참고 자료 (근태기록·발령대장 등). 읽기·수식 검사·규칙 추출·파일 간 대조에만 쓴다 */
    REFERENCE
}
