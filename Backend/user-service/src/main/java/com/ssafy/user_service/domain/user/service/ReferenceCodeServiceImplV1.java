package com.ssafy.user_service.domain.user.service;

import com.ssafy.user_service.domain.user.dto.GradeResponse;
import com.ssafy.user_service.domain.user.dto.JobCodeResponse;
import com.ssafy.user_service.domain.user.dto.PositionResponse;
import com.ssafy.user_service.domain.user.entity.JobCode;
import com.ssafy.user_service.domain.user.entity.enums.JobCodeType;
import com.ssafy.user_service.domain.user.repository.GradeRepository;
import com.ssafy.user_service.domain.user.repository.JobCodeRepository;
import com.ssafy.user_service.domain.user.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReferenceCodeServiceImplV1 implements ReferenceCodeService {

    private final PositionRepository positionRepository;
    private final GradeRepository gradeRepository;
    private final JobCodeRepository jobCodeRepository;

    @Override
    public List<PositionResponse> findPositions() {
        return positionRepository.findAll(Sort.by("rankOrder", "positionId")).stream()
                .map(PositionResponse::from)
                .toList();
    }

    @Override
    public List<GradeResponse> findGrades() {
        return gradeRepository.findAll(Sort.by("gradeId")).stream()
                .map(GradeResponse::from)
                .toList();
    }

    @Override
    public List<JobCodeResponse> findJobCodes(JobCodeType type) {
        // 종류는 문자열로 저장되어 DB 정렬이 enum 순서와 다를 수 있어, 행 수가 적은 코드 표라 메모리에서 정렬한다
        List<JobCode> codes = type == null ? jobCodeRepository.findAll() : jobCodeRepository.findByType(type);
        return codes.stream()
                .sorted(Comparator.comparing(JobCode::getType).thenComparing(JobCode::getJobCodeId))
                .map(JobCodeResponse::from)
                .toList();
    }
}
