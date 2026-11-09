package com.ssafy.user_service.domain.user.controller;

import com.ssafy.user_service.domain.user.dto.GradeResponse;
import com.ssafy.user_service.domain.user.dto.JobCodeResponse;
import com.ssafy.user_service.domain.user.dto.PositionResponse;
import com.ssafy.user_service.domain.user.entity.enums.JobCodeType;
import com.ssafy.user_service.domain.user.service.ReferenceCodeService;
import com.ssafy.user_service.global.dto.ApiResult;
import com.ssafy.user_service.global.security.SecurityConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 코드 목록 API (직위·직급·직무 코드). 사원 등록·필터의 선택 목록용이라 인증 사용자 모두 조회할 수 있다.
 * 직책 목록은 같은 경로 규칙으로 DutyController(GET /api/v1/duties)에 있다.
 * gateway 에 각 경로의 라우트가 있어야 외부에서 호출된다
 */
@RestController
@RequiredArgsConstructor
public class ReferenceCodeController {

    private final ReferenceCodeService referenceCodeService;

    @GetMapping(SecurityConstants.POSITION_BASE_PATH)
    public ResponseEntity<ApiResult<List<PositionResponse>>> getPositions() {
        return ResponseEntity.ok(ApiResult.success(referenceCodeService.findPositions()));
    }

    @GetMapping(SecurityConstants.GRADE_BASE_PATH)
    public ResponseEntity<ApiResult<List<GradeResponse>>> getGrades() {
        return ResponseEntity.ok(ApiResult.success(referenceCodeService.findGrades()));
    }

    /** type = FAMILY(직군) / KIND(직종) / ROLE(직무). 없으면 전체 */
    @GetMapping(SecurityConstants.JOB_CODE_BASE_PATH)
    public ResponseEntity<ApiResult<List<JobCodeResponse>>> getJobCodes(
            @RequestParam(required = false) JobCodeType type) {
        return ResponseEntity.ok(ApiResult.success(referenceCodeService.findJobCodes(type)));
    }
}
