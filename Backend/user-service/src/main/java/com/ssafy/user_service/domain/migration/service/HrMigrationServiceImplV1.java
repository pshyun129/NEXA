package com.ssafy.user_service.domain.migration.service;

import com.ssafy.user_service.domain.migration.dto.MigrationBatchSummaryResponse;
import com.ssafy.user_service.domain.migration.dto.MigrationReferencesResponse;
import com.ssafy.user_service.domain.migration.dto.MigrationUpsertRequest;
import com.ssafy.user_service.domain.migration.dto.MigrationUpsertResponse;
import com.ssafy.user_service.domain.migration.repository.MigrationBatchRowRepository;
import com.ssafy.user_service.domain.migration.repository.MigrationBatchRowRepository.Action;
import com.ssafy.user_service.domain.migration.repository.MigrationBatchRowRepository.Entry;
import com.ssafy.user_service.domain.migration.repository.MigrationBatchRowRepository.Target;
import com.ssafy.user_service.domain.user.service.DutyService;
import com.ssafy.user_service.domain.user.service.ReferenceCodeService;
import com.ssafy.user_service.global.exception.BusinessException;
import com.ssafy.user_service.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HrMigrationServiceImplV1 implements HrMigrationService {

    private final ReferenceMigrationLoader referenceLoader;
    private final DepartmentMigrationLoader departmentLoader;
    private final EmployeeMigrationLoader employeeLoader;
    private final MigrationBatchRowRepository batchRowRepository;
    private final ReferenceCodeService referenceCodeService;
    private final DutyService dutyService;

    @Override
    @Transactional
    public MigrationUpsertResponse upsertReferences(String batchId, MigrationUpsertRequest request) {
        return referenceLoader.upsert(batchId, request.rows());
    }

    @Override
    @Transactional
    public MigrationUpsertResponse upsertDepartments(String batchId, MigrationUpsertRequest request) {
        return departmentLoader.upsert(batchId, request.rows());
    }

    @Override
    @Transactional
    public MigrationUpsertResponse upsertEmployees(String batchId, MigrationUpsertRequest request) {
        return employeeLoader.upsert(batchId, request.rows());
    }

    /**
     * 1) 이 batch 가 건드린 행을 뒤에 다른 batch 가 또 건드렸으면 거부 (그 batch 부터 되돌려야 값이 맞다)
     * 2) 수정한 행을 최근 것부터 복원 → 새로 만든 행을 가리키던 참조가 먼저 풀린다
     * 3) 새로 만든 부서끼리의 상위·부서장 참조를 끊고, 만든 행을 최근 것부터 삭제
     * 적재 도중 실패해 일부만 남은 batch 도 같은 방식으로 지운다.
     */
    @Override
    @Transactional
    public void rollback(String batchId) {
        List<Entry> entries = batchRowRepository.entries(batchId);
        if (entries.isEmpty()) {
            return;
        }
        List<String> later = batchRowRepository.laterBatchesTouchingSameRows(batchId);
        if (!later.isEmpty()) {
            throw new BusinessException(ErrorCode.MIGRATION_ROLLBACK_CONFLICT,
                    "이후 batch(" + String.join(", ", later) + ")가 같은 행을 다시 적재했습니다. 그 batch 부터 되돌리세요");
        }
        try {
            entries.stream().filter(e -> e.action() == Action.UPDATED).forEach(batchRowRepository::restore);
            batchRowRepository.detachDepartments(entries.stream()
                    .filter(e -> e.action() == Action.CREATED && e.target() == Target.DEPARTMENT)
                    .map(Entry::targetId).toList());
            entries.stream().filter(e -> e.action() == Action.CREATED)
                    .forEach(e -> batchRowRepository.delete(e.target(), e.targetId()));
        } catch (DataIntegrityViolationException e) {
            // 예: 이 batch 로 만든 부서에 이관 밖에서 사원을 등록했거나, 다른 batch 의 부서가 하위로 붙은 경우,
            //     이 batch 로 만든 직위·직급·직책·직무를 아직 사원이 쓰는 경우
            log.warn("Migration rollback blocked: batch={}, cause={}", batchId, e.getMostSpecificCause().getMessage());
            throw new BusinessException(ErrorCode.MIGRATION_ROLLBACK_CONFLICT,
                    "이 batch 로 만든 부서·사원·기준정보를 다른 데이터가 참조하고 있어 되돌릴 수 없습니다");
        }
        batchRowRepository.deleteBatch(batchId);
    }

    @Override
    @Transactional(readOnly = true)
    public MigrationBatchSummaryResponse summarize(String batchId) {
        return new MigrationBatchSummaryResponse(batchRowRepository.countRows(batchId));
    }

    @Override
    @Transactional(readOnly = true)
    public MigrationReferencesResponse references() {
        return new MigrationReferencesResponse(referenceCodeService.findPositions(), referenceCodeService.findGrades(),
                dutyService.findAll(), referenceCodeService.findJobCodes(null));
    }
}
