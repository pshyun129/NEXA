package com.ssafy.user_service.domain.user.service;

import com.ssafy.user_service.domain.user.dto.GradeResponse;
import com.ssafy.user_service.domain.user.dto.JobCodeResponse;
import com.ssafy.user_service.domain.user.dto.PositionResponse;
import com.ssafy.user_service.domain.user.entity.enums.JobCodeType;

import java.util.List;

/** 사원 등록·필터의 선택 목록용 코드 조회 (직위·직급·직무 코드). 직책 목록은 DutyService */
public interface ReferenceCodeService {

    /** 직위 전체, 서열(rankOrder) 오름차순 */
    List<PositionResponse> findPositions();

    /** 직급 전체, gradeId 오름차순 (직급 → 호봉 순) */
    List<GradeResponse> findGrades();

    /** 직무 코드. type 이 있으면 그 종류만. 직군 → 직종 → 직무, 같은 종류 안에서는 ID 오름차순 */
    List<JobCodeResponse> findJobCodes(JobCodeType type);
}
