package com.ssafy.leaveservice.domain.grant;

import com.ssafy.leaveservice.domain.account.entity.LeaveAccount;
import com.ssafy.leaveservice.domain.grant.dto.MyLeaveGrantResponse;
import com.ssafy.leaveservice.domain.grant.entity.GrantKind;
import com.ssafy.leaveservice.domain.grant.entity.LeaveGrant;
import com.ssafy.leaveservice.domain.grant.repository.LeaveGrantRepository;
import com.ssafy.leaveservice.domain.grant.service.MyLeaveGrantService;
import com.ssafy.leaveservice.domain.leavetype.entity.LeaveBalanceType;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MyLeaveGrantServiceTest {

    private final LeaveGrantRepository repository = mock(LeaveGrantRepository.class);
    private final EmployeeAccess employeeAccess = mock(EmployeeAccess.class);
    private final MyLeaveGrantService service = new MyLeaveGrantService(repository, employeeAccess);

    @BeforeEach
    void setUp() {
        when(employeeAccess.currentEmployeeId()).thenReturn(42L);
    }

    @Test
    void mapsStoredGrantAndAccountBalanceTypeWithoutReinterpretingQuantityOrPolicy() {
        LeaveBalanceType balanceType = mock(LeaveBalanceType.class);
        when(balanceType.getBalanceTypeId()).thenReturn(2L);
        when(balanceType.getName()).thenReturn("연차");
        LeaveAccount account = mock(LeaveAccount.class);
        when(account.getAccountId()).thenReturn(901L);
        when(account.getBalanceType()).thenReturn(balanceType);
        LeaveGrant grantRecord = mock(LeaveGrant.class);
        OffsetDateTime grantedOn = OffsetDateTime.parse("2020-01-01T09:00:00+09:00");
        OffsetDateTime validFrom = OffsetDateTime.parse("2020-02-01T00:00:00Z");
        OffsetDateTime expiresOn = OffsetDateTime.parse("2021-01-01T00:00:00Z");
        when(grantRecord.getGrantId()).thenReturn(5L);
        when(grantRecord.getAccount()).thenReturn(account);
        when(grantRecord.getPolicyVersionId()).thenReturn(11L);
        when(grantRecord.getRuleId()).thenReturn(51L);
        when(grantRecord.getParentGrantId()).thenReturn(3L);
        when(grantRecord.getGrantKind()).thenReturn(GrantKind.CARRY_OVER);
        when(grantRecord.getGrantedOn()).thenReturn(grantedOn);
        when(grantRecord.getOriginalMinutes()).thenReturn(7200);
        when(grantRecord.getValidFrom()).thenReturn(validFrom);
        when(grantRecord.getExpiresOn()).thenReturn(expiresOn);
        when(grantRecord.getCreatedAt()).thenReturn(grantedOn);
        when(repository.findAllWithAccountAndBalanceTypeByEmployeeId(42L)).thenReturn(List.of(grantRecord));

        assertThat(service.getMyGrants()).containsExactly(new MyLeaveGrantResponse(
                5L, 901L, 2L, "연차", 11L, 51L, 3L, GrantKind.CARRY_OVER,
                grantedOn, 7200, validFrom, expiresOn, grantedOn
        ));
        verify(repository).findAllWithAccountAndBalanceTypeByEmployeeId(42L);
        verifyNoMoreInteractions(repository);
        verify(employeeAccess, never()).isAdmin(anyLong());
    }

    @Test
    void returnsEmptyListWithoutRequiringCurrentPolicy() {
        assertThat(service.getMyGrants()).isEmpty();
        verify(repository).findAllWithAccountAndBalanceTypeByEmployeeId(42L);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidAuthenticatedIdentityDoesNotReadDatabase(Long employeeId) {
        when(employeeAccess.currentEmployeeId()).thenReturn(employeeId);
        assertError(ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UNAUTHORIZED", "AUTHENTICATION_UNAVAILABLE"})
    void preservesAuthenticationFailureBeforeQuery(ErrorCode errorCode) {
        when(employeeAccess.currentEmployeeId()).thenThrow(new BusinessException(errorCode));
        assertError(errorCode);
        verifyNoInteractions(repository);
    }

    private void assertError(ErrorCode errorCode) {
        assertThatThrownBy(service::getMyGrants).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
        verify(employeeAccess, never()).isAdmin(anyLong());
    }
}
