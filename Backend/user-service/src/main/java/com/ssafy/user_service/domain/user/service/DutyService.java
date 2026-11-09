package com.ssafy.user_service.domain.user.service;

import com.ssafy.user_service.domain.user.dto.DutyResponse;

import java.util.List;

public interface DutyService {

    /** 직책 ID로 직책을 조회한다. 없는 ID면 BusinessException(NOT_FOUND) */
    DutyResponse getDuty(Long dutyId);

    /** 직책 전체, dutyId 오름차순 (선택 목록용) */
    List<DutyResponse> findAll();
}
