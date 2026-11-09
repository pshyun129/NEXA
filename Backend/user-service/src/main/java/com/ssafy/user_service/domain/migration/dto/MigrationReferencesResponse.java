package com.ssafy.user_service.domain.migration.dto;

import com.ssafy.user_service.domain.user.dto.DutyResponse;
import com.ssafy.user_service.domain.user.dto.GradeResponse;
import com.ssafy.user_service.domain.user.dto.JobCodeResponse;
import com.ssafy.user_service.domain.user.dto.PositionResponse;

import java.util.List;

/** 지금 있는 기준정보 (migration 이 파일의 값 중 새로 만들 것을 고르는 데 쓴다) */
public record MigrationReferencesResponse(
        List<PositionResponse> positions,
        List<GradeResponse> grades,
        List<DutyResponse> duties,
        List<JobCodeResponse> jobCodes
) {
}
