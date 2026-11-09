package com.ssafy.user_service.domain.user.service;

import com.ssafy.user_service.domain.user.dto.DutyResponse;
import com.ssafy.user_service.domain.user.repository.DutyRepository;
import com.ssafy.user_service.global.exception.BusinessException;
import com.ssafy.user_service.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DutyServiceImplV1 implements DutyService {

    private final DutyRepository dutyRepository;

    @Override
    public DutyResponse getDuty(Long dutyId) {
        // DUTY_NOT_FOUND(400)는 사원 등록 시 입력 검증용이라, 조회에서는 404인 NOT_FOUND를 쓴다
        return dutyRepository.findById(dutyId)
                .map(DutyResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않는 직책입니다."));
    }

    @Override
    public List<DutyResponse> findAll() {
        return dutyRepository.findAll(Sort.by("dutyId")).stream()
                .map(DutyResponse::from)
                .toList();
    }
}
