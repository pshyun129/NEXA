package com.ssafy.user_service.domain.user.controller;

import com.ssafy.user_service.domain.user.dto.DutyResponse;
import com.ssafy.user_service.domain.user.service.DutyService;
import com.ssafy.user_service.global.dto.ApiResult;
import com.ssafy.user_service.global.security.SecurityConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 직책 조회 API. 목록(선택 목록용)과 단건(토큰의 dutyId를 직책명으로 바꿀 때). 인증 사용자 모두 조회 가능 */
@RestController
@RequiredArgsConstructor
@RequestMapping(SecurityConstants.DUTY_BASE_PATH)
public class DutyController {

    private final DutyService dutyService;

    /** 직책 전체 (사원 등록·필터의 선택 목록용) */
    @GetMapping
    public ResponseEntity<ApiResult<List<DutyResponse>>> getDuties() {
        return ResponseEntity.ok(ApiResult.success(dutyService.findAll()));
    }

    @GetMapping("/{dutyId}")
    public ResponseEntity<ApiResult<DutyResponse>> getDuty(@PathVariable Long dutyId) {
        return ResponseEntity.ok(ApiResult.success(dutyService.getDuty(dutyId)));
    }
}
