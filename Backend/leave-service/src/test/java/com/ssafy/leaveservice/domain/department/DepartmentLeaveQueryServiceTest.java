package com.ssafy.leaveservice.domain.department;

import com.ssafy.leaveservice.domain.account.repository.LeaveAccountRepository;
import com.ssafy.leaveservice.domain.account.repository.projection.DepartmentAccountBalance;
import com.ssafy.leaveservice.domain.department.service.DepartmentLeaveQueryService;
import com.ssafy.leaveservice.domain.request.repository.LeaveRequestRepository;
import com.ssafy.leaveservice.global.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DepartmentLeaveQueryServiceTest {
    private final LeaveRequestRepository requests = mock(LeaveRequestRepository.class);
    private final LeaveAccountRepository accounts = mock(LeaveAccountRepository.class);
    private final DepartmentLeaveQueryService service = new DepartmentLeaveQueryService(requests,accounts);
    private final OffsetDateTime asOf = OffsetDateTime.parse("2030-01-01T00:00:00Z");

    @ParameterizedTest
    @CsvSource({"9223372036854775808,0","480,9223372036854775808","-1,0","480,-1","480,481"})
    void preservesOriginalOverflowAndIntegrityChecks(String quantity,String reserved) {
        DepartmentAccountBalance balance = mock(DepartmentAccountBalance.class);
        when(balance.getEmployeeId()).thenReturn(10L);
        when(balance.getQuantityMinutes()).thenReturn(new BigDecimal(quantity));
        when(balance.getReservedMinutes()).thenReturn(new BigDecimal(reserved));
        when(accounts.findBalancesByEmployeeIds(List.of(10L),asOf)).thenReturn(List.of(balance));
        assertThatThrownBy(()->service.findBalances(3L,List.of(10L),asOf))
                .isInstanceOfSatisfying(BusinessException.class,error->assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DATA_INTEGRITY_VIOLATION));
    }

    @Test
    void emptyMembersNeverRunUnrestrictedQuery() {
        assertThat(service.findRequests(List.of())).isEmpty();
        assertThat(service.findBalances(3L,List.of(),asOf).employees()).isEmpty();
        verifyNoInteractions(requests,accounts);
    }

    @Test
    void missingAccountsDoNotMergeOrFabricateBalances() {
        when(accounts.findBalancesByEmployeeIds(List.of(10L,11L),asOf)).thenReturn(List.of());
        assertThat(service.findBalances(3L,List.of(10L,11L),asOf).employees())
                .allSatisfy(employee->assertThat(employee.balances()).isEmpty());
        verify(accounts,times(1)).findBalancesByEmployeeIds(List.of(10L,11L),asOf);
    }
}
