package com.ssafy.leaveservice.domain.leavetype;

import com.ssafy.leaveservice.domain.leavetype.dto.LeaveTypeUpdateRequest;
import com.ssafy.leaveservice.domain.leavetype.entity.BalanceMode;
import com.ssafy.leaveservice.domain.leavetype.entity.LeaveType;
import com.ssafy.leaveservice.domain.leavetype.entity.RequestUnit;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveBalanceTypeRepository;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveTypeRepository;
import com.ssafy.leaveservice.domain.leavetype.service.LeaveTypeService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeaveTypePatchServiceTest {

    private final LeaveTypeRepository leaveTypeRepository = mock(LeaveTypeRepository.class);
    private final LeaveBalanceTypeRepository leaveBalanceTypeRepository = mock(LeaveBalanceTypeRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final LeaveTypeService service = new LeaveTypeService(
            leaveTypeRepository, employeeAccess, leaveBalanceTypeRepository
    );
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LeaveType leaveType = spy(LeaveType.builder()
            .name("기존 이름")
            .paid(false)
            .balanceMode(BalanceMode.NONE)
            .requestUnit(RequestUnit.DAY)
            .maxMinutes(480)
            .active(true)
            .build());

    @BeforeEach
    void setUp() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
        when(employeeAccess.isAdmin(42L)).thenReturn(true);
        when(leaveTypeRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(leaveType));
    }

    @Test
    void neverChangesManagedEntityBeforeReferenceValidationCompletes() {
        LeaveTypeUpdateRequest request = objectMapper.readValue(
                "{\"name\":\"새 이름\",\"isPaid\":true,\"isActive\":false}", LeaveTypeUpdateRequest.class
        );
        when(leaveTypeRepository.existsReferences(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.updateType(1L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.LEAVE_TYPE_IN_USE));

        assertThat(leaveType.getName()).isEqualTo("기존 이름");
        assertThat(leaveType.isActive()).isTrue();
        assertThat(leaveType.isPaid()).isFalse();
        verify(leaveType, never()).updateDetails(any(), anyBoolean(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void neverChangesManagedEntityBeforeBalanceLookupCompletes() {
        LeaveTypeUpdateRequest request = objectMapper.readValue(
                "{\"name\":\"새 이름\",\"balanceTypeId\":999}", LeaveTypeUpdateRequest.class
        );
        when(leaveBalanceTypeRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateType(1L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BALANCE_TYPE_NOT_FOUND));
        verify(leaveType, never()).updateDetails(any(), anyBoolean(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void usesManagedEntityWithoutCallingSave() {
        LeaveTypeUpdateRequest request = objectMapper.readValue("{\"isActive\":false}", LeaveTypeUpdateRequest.class);

        service.updateType(1L, request);

        assertThat(leaveType.isActive()).isFalse();
        verify(leaveTypeRepository, never()).save(any());
    }

    @Test
    void rejectsNonAdminBeforeDatabaseAccess() {
        LeaveTypeUpdateRequest request = objectMapper.readValue("{\"name\":\"새 이름\"}", LeaveTypeUpdateRequest.class);
        when(employeeAccess.isAdmin(42L)).thenReturn(false);

        assertThatThrownBy(() -> service.updateType(1L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        verifyNoInteractions(leaveTypeRepository, leaveBalanceTypeRepository);
    }
}
