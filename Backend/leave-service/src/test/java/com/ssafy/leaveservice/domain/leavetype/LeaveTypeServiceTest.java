package com.ssafy.leaveservice.domain.leavetype;

import com.ssafy.leaveservice.domain.leavetype.entity.*;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveTypeRepository;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveBalanceTypeRepository;
import com.ssafy.leaveservice.domain.leavetype.service.LeaveTypeService;
import com.ssafy.leaveservice.global.auth.EmployeeAccess;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeaveTypeServiceTest {
    private final LeaveTypeRepository repository = mock(LeaveTypeRepository.class);
    private final EmployeeAccess access = mock(EmployeeAccess.class);
    private final LeaveBalanceTypeRepository balanceTypeRepository = mock(LeaveBalanceTypeRepository.class);
    private final LeaveTypeService service = new LeaveTypeService(repository, access, balanceTypeRepository);

    @BeforeEach
    void setUp() {
        when(access.currentEmployeeId()).thenReturn(42L);
        when(access.isAdmin(42L)).thenReturn(true);
    }

    @Test
    void returnsAllTypesIncludingInactiveBalanceAndNullableFields() throws Exception {
        var balance = instantiate(LeaveBalanceType.class);
        ReflectionTestUtils.setField(balance, "balanceTypeId", 3L);
        ReflectionTestUtils.setField(balance, "name", "연차");
        ReflectionTestUtils.setField(balance, "active", false);

        var annual = instantiate(LeaveType.class);
        ReflectionTestUtils.setField(annual, "leaveTypeId", 1L);
        ReflectionTestUtils.setField(annual, "name", "연차");
        ReflectionTestUtils.setField(annual, "paid", true);
        ReflectionTestUtils.setField(annual, "balanceMode", BalanceMode.ACCOUNT);
        ReflectionTestUtils.setField(annual, "requestUnit", RequestUnit.DAY);
        ReflectionTestUtils.setField(annual, "maxMinutes", 480);
        ReflectionTestUtils.setField(annual, "balanceType", balance);

        var unpaid = instantiate(LeaveType.class);
        ReflectionTestUtils.setField(unpaid, "leaveTypeId", 2L);
        ReflectionTestUtils.setField(unpaid, "name", "무급휴가");
        ReflectionTestUtils.setField(unpaid, "balanceMode", BalanceMode.NONE);
        ReflectionTestUtils.setField(unpaid, "requestUnit", RequestUnit.MIXED);
        when(repository.findAllWithBalanceType()).thenReturn(List.of(annual, unpaid));

        var result = service.getAllTypes();

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().leaveTypeId()).isEqualTo(1L);
        assertThat(result.getFirst().name()).isEqualTo("연차");
        assertThat(result.getFirst().paid()).isTrue();
        assertThat(result.getFirst().isActive()).isTrue();
        assertThat(result.getFirst().balanceMode()).isEqualTo(BalanceMode.ACCOUNT);
        assertThat(result.getFirst().requestUnit()).isEqualTo(RequestUnit.DAY);
        assertThat(result.getFirst().maxMinutes()).isEqualTo(480);
        assertThat(result.getFirst().balanceType().balanceTypeId()).isEqualTo(3L);
        assertThat(result.getFirst().balanceType().name()).isEqualTo("연차");
        assertThat(result.getFirst().balanceType().active()).isFalse();
        assertThat(result.get(1).balanceType()).isNull();
        assertThat(result.get(1).maxMinutes()).isNull();
        assertThat(result.get(1).paid()).isFalse();
    }

    @Test
    void returnsEmptyListWhenNoTypesExist() {
        when(repository.findAllWithBalanceType()).thenReturn(List.of());
        assertThat(service.getAllTypes()).isEmpty();
    }

    @Test
    void rejectsNonAdminBeforeReadingRepository() {
        when(access.isAdmin(42L)).thenReturn(false);
        assertThatThrownBy(service::getAllTypes)
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        verifyNoInteractions(repository);
    }

    private static <T> T instantiate(Class<T> type) throws Exception {
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}
