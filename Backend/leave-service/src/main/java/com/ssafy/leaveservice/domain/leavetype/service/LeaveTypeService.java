package com.ssafy.leaveservice.domain.leavetype.service;

import com.ssafy.leaveservice.domain.leavetype.dto.LeaveTypeResponse;
import com.ssafy.leaveservice.domain.leavetype.dto.LeaveTypeCreateRequest;
import com.ssafy.leaveservice.domain.leavetype.dto.LeaveTypeUpdateRequest;
import com.ssafy.leaveservice.domain.leavetype.entity.BalanceMode;
import com.ssafy.leaveservice.domain.leavetype.entity.LeaveBalanceType;
import com.ssafy.leaveservice.domain.leavetype.entity.LeaveType;
import com.ssafy.leaveservice.domain.leavetype.entity.RequestUnit;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveBalanceTypeRepository;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveTypeRepository;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.List;
import java.util.Objects;

@com.ssafy.leaveservice.global.auth.LeaveAdministratorOnly
@Service
@RequiredArgsConstructor
public class LeaveTypeService {
    private final LeaveTypeRepository leaveTypeRepository;
    private final EmployeeAccess employeeAccess;
    private final LeaveBalanceTypeRepository leaveBalanceTypeRepository;

    @Transactional(readOnly = true)
    public List<LeaveTypeResponse> getAllTypes() {
        // 직원 조회 및 관리자 권한 검증
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 전체 휴가 유형 조회
        List<LeaveType> leaveTypes = leaveTypeRepository.findAllWithBalanceType();

        // 응답 생성
        List<LeaveTypeResponse> response = leaveTypes.stream()
                .map(LeaveTypeResponse::from)
                .toList();
        return response;
    }

    // 휴가 유형 생성
    @Transactional
    public LeaveTypeResponse createType(LeaveTypeCreateRequest request) {
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // ACCOUNT 유형은 차감할 잔액 유형이 반드시 필요하다.
        if (request.balanceMode() == BalanceMode.ACCOUNT && request.balanceTypeId() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "ACCOUNT 유형은 잔액 유형 ID가 필수입니다.");
        }

        LeaveBalanceType balanceType = null;
        if (request.balanceTypeId() != null) {
            balanceType = leaveBalanceTypeRepository.findById(request.balanceTypeId()).orElseThrow(
                    () -> new BusinessException(ErrorCode.BALANCE_TYPE_NOT_FOUND)
            );
        }

        LeaveType leaveType = LeaveType.builder()
                .balanceType(balanceType)
                .name(request.name())
                .paid(request.isPaid())
                .balanceMode(request.balanceMode())
                .requestUnit(request.requestUnit())
                .maxMinutes(request.maxMinutes())
                .active(request.isActive())
                .build();
        LeaveType savedLeaveType = leaveTypeRepository.save(leaveType);

        LeaveTypeResponse response = LeaveTypeResponse.from(savedLeaveType);
        return response;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LeaveTypeResponse updateType(Long leaveTypeId, LeaveTypeUpdateRequest request) {
        Long employeeId = employeeAccess.currentEmployeeId();
        if (!employeeAccess.isAdmin(employeeId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (request.suppliedFields().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "수정할 항목을 하나 이상 지정해야 합니다.");
        }

        // 잠금 대기 중 참조가 먼저 커밋됐다면 다음 조회에서 확인할 수 있도록 READ COMMITTED를 사용한다.
        LeaveType leaveType = leaveTypeRepository.findByIdForUpdate(leaveTypeId).orElseThrow(
                () -> new BusinessException(ErrorCode.LEAVE_TYPE_NOT_FOUND)
        );

        // 아직 Entity를 변경하지 않고, 생략한 필드는 기존 값으로 병합한다.
        String updatedName = request.hasField("name") ? request.name() : leaveType.getName();
        boolean updatedPaid = request.hasField("isPaid") ? request.isPaid() : leaveType.isPaid();
        BalanceMode updatedBalanceMode =
                request.hasField("balanceMode") ? request.balanceMode() : leaveType.getBalanceMode();
        LeaveBalanceType currentBalanceType = leaveType.getBalanceType();
        Long currentBalanceTypeId = currentBalanceType == null ? null : currentBalanceType.getBalanceTypeId();
        Long updatedBalanceTypeId = request.hasField("balanceTypeId") ? request.balanceTypeId() : currentBalanceTypeId;
        RequestUnit updatedRequestUnit =
                request.hasField("requestUnit") ? request.requestUnit() : leaveType.getRequestUnit();
        Integer updatedMaxMinutes = request.hasField("maxMinutes") ? request.maxMinutes() : leaveType.getMaxMinutes();
        boolean updatedActive = request.hasField("isActive") ? request.isActive() : leaveType.isActive();

        boolean protectedSettingsChanged = updatedPaid != leaveType.isPaid()
                || updatedBalanceMode != leaveType.getBalanceMode()
                || !Objects.equals(updatedBalanceTypeId, currentBalanceTypeId)
                || updatedRequestUnit != leaveType.getRequestUnit()
                || !Objects.equals(updatedMaxMinutes, leaveType.getMaxMinutes());
        if (protectedSettingsChanged && leaveTypeRepository.existsReferences(leaveTypeId)) {
            throw new BusinessException(ErrorCode.LEAVE_TYPE_IN_USE);
        }
        if (updatedBalanceMode == BalanceMode.ACCOUNT && updatedBalanceTypeId == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "ACCOUNT 유형은 잔액 유형 ID가 필수입니다.");
        }

        LeaveBalanceType updatedBalanceType = currentBalanceType;
        if (updatedBalanceTypeId == null) {
            updatedBalanceType = null;
        } else if (!Objects.equals(updatedBalanceTypeId, currentBalanceTypeId)) {
            updatedBalanceType = leaveBalanceTypeRepository.findById(updatedBalanceTypeId).orElseThrow(
                    () -> new BusinessException(ErrorCode.BALANCE_TYPE_NOT_FOUND)
            );
        }

        // 모든 검증이 끝난 뒤 한 번만 반영한다. save 없이 JPA 변경 감지로 같은 ID의 행을 갱신한다.
        leaveType.updateDetails(
                updatedName,
                updatedPaid,
                updatedBalanceMode,
                updatedBalanceType,
                updatedRequestUnit,
                updatedMaxMinutes,
                updatedActive
        );
        LeaveTypeResponse response = LeaveTypeResponse.from(leaveType);
        return response;
    }
}
