package com.ssafy.user_service.domain.user.service;

import com.ssafy.user_service.domain.user.dto.EmployeeCreateRequest;
import com.ssafy.user_service.domain.user.dto.EmployeeTerminateRequest;
import com.ssafy.user_service.domain.user.entity.Employee;
import com.ssafy.user_service.domain.user.entity.enums.EmploymentStatus;
import com.ssafy.user_service.domain.user.entity.enums.JobCodeType;
import com.ssafy.user_service.domain.user.repository.*;
import com.ssafy.user_service.global.exception.BusinessException;
import com.ssafy.user_service.global.exception.ErrorCode;
import com.ssafy.user_service.global.security.Role;
import com.ssafy.user_service.global.security.TokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class EmployeeServiceImplV1Test {

    private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    private final DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
    private final GradeRepository gradeRepository = mock(GradeRepository.class);
    private final PositionRepository positionRepository = mock(PositionRepository.class);
    private final DutyRepository dutyRepository = mock(DutyRepository.class);
    private final JobCodeRepository jobCodeRepository = mock(JobCodeRepository.class);
    private final TokenStore tokenStore = mock(TokenStore.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private EmployeeServiceImplV1 service;

    @BeforeEach
    void setUp() {
        service = new EmployeeServiceImplV1(employeeRepository, departmentRepository, gradeRepository,
                positionRepository, dutyRepository, jobCodeRepository, encoder, tokenStore);
        when(departmentRepository.existsById(1L)).thenReturn(true);
        when(positionRepository.existsById(1L)).thenReturn(true);
        when(gradeRepository.existsById(1L)).thenReturn(true);
        when(dutyRepository.existsById(1L)).thenReturn(true);
        when(jobCodeRepository.existsByJobCodeIdAndType(1L, JobCodeType.FAMILY)).thenReturn(true);
        when(jobCodeRepository.existsByJobCodeIdAndType(2L, JobCodeType.KIND)).thenReturn(true);
        when(jobCodeRepository.existsByJobCodeIdAndType(3L, JobCodeType.ROLE)).thenReturn(true);
        when(employeeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static EmployeeCreateRequest request(Long positionId, Long jobFamilyId, Role role) {
        return new EmployeeCreateRequest("u@x.io", "password1", "홍길동", null, null,
                1L, positionId, 1L, 1L, jobFamilyId, 2L, 3L,
                null, null, null, null, null, null, null, null, null, null, null, null, role);
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    @Test
    void 중복_이메일은_409코드() {
        when(employeeRepository.existsByLoginId("u@x.io")).thenReturn(true);
        assertThatThrownBy(() -> service.create(request(1L, 1L, null)))
                .matches(t -> codeOf(t) == ErrorCode.DUPLICATE_LOGIN_ID);
        verify(employeeRepository, never()).save(any());
    }

    @Test
    void 없는_직위는_400코드() {
        assertThatThrownBy(() -> service.create(request(99L, 1L, null)))
                .matches(t -> codeOf(t) == ErrorCode.POSITION_NOT_FOUND);
    }

    @Test
    void 직군_자리에_직종코드를_넣으면_거부() {
        assertThatThrownBy(() -> service.create(request(1L, 2L, null)))
                .matches(t -> codeOf(t) == ErrorCode.JOB_CODE_NOT_FOUND);
    }

    @Test
    void 성공시_비밀번호는_암호화되고_role_기본값은_USER() {
        service.create(request(1L, 1L, null));
        ArgumentCaptor<Employee> saved = ArgumentCaptor.forClass(Employee.class);
        verify(employeeRepository).save(saved.capture());
        Employee e = saved.getValue();
        assertThat(e.getPasswordEnc()).isNotEqualTo("password1");
        assertThat(encoder.matches("password1", e.getPasswordEnc())).isTrue();
        assertThat(e.getRole()).isEqualTo(Role.USER);
        assertThat(e.getStatus()).isEqualTo(EmploymentStatus.EMPLOYED);
        assertThat(e.getJob().getDeptId()).isEqualTo(1L);
    }

    @Test
    void 퇴직처리는_상태를_바꾸고_refresh_세션을_삭제() {
        Employee e = Employee.builder().loginId("u@x.io").name("A").build();
        when(employeeRepository.findById(5L)).thenReturn(Optional.of(e));

        service.terminate(5L, new EmployeeTerminateRequest(LocalDate.of(2026, 10, 19), "이직"));

        assertThat(e.isTerminated()).isTrue();
        assertThat(e.getResignDate()).isEqualTo(LocalDate.of(2026, 10, 19));
        verify(tokenStore).deleteRefreshToken(5L);

        assertThatThrownBy(() -> service.terminate(5L, new EmployeeTerminateRequest(LocalDate.now(), null)))
                .matches(t -> codeOf(t) == ErrorCode.EMPLOYEE_ALREADY_TERMINATED);
    }

    @Test
    void 없는_사원_퇴직처리는_404코드() {
        when(employeeRepository.findById(anyLong())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.terminate(7L, new EmployeeTerminateRequest(LocalDate.now(), null)))
                .matches(t -> codeOf(t) == ErrorCode.EMPLOYEE_NOT_FOUND);
        verifyNoInteractions(tokenStore);
    }
}
