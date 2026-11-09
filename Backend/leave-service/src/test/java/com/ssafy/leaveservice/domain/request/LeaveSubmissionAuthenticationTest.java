package com.ssafy.leaveservice.domain.request;

import com.ssafy.leaveservice.client.attendance.HolidayClient;
import com.ssafy.leaveservice.domain.request.dto.LeaveRequestPreviewRequest;
import com.ssafy.leaveservice.domain.request.entity.LeaveRequestPortion;
import com.ssafy.leaveservice.domain.request.service.*;
import com.ssafy.leaveservice.global.auth.*;
import com.ssafy.leaveservice.global.exception.*;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import com.ssafy.leaveservice.support.TestTokens;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeaveSubmissionAuthenticationTest {
    private static final ValidatorFactory FACTORY=Validation.buildDefaultValidatorFactory();
    private final HolidayClient holidays=mock(HolidayClient.class);
    private final LeaveSubmissionTransactionService transaction=mock(LeaveSubmissionTransactionService.class);
    private final EmployeeAccess tokenAccess=new TokenEmployeeAccess(org.mockito.Mockito.mock(com.ssafy.leaveservice.global.auth.LeaveAdministratorAccess.class));
    private final LeaveRequestPreviewRequest input=new LeaveRequestPreviewRequest(1L,null,List.of(
            new LeaveRequestPreviewRequest.DateRequest(LocalDate.of(2035,1,1),LeaveRequestPortion.FULL)));

    @AfterAll static void closeFactory() { FACTORY.close(); }

    @AfterEach void clearToken() { TestTokens.clear(); }

    @Test
    void withoutTokenFailsBeforeExternalAndStorage() {
        assertThatThrownBy(()->service(tokenAccess).submit("key",input))
                .isInstanceOfSatisfying(BusinessException.class,error->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verifyNoInteractions(holidays,transaction);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs={0,-1})
    void invalidEmployeeCannotSubmit(Long id) {
        EmployeeAccess access=mock(EmployeeAccess.class);
        when(access.currentEmployeeId()).thenReturn(id);
        assertThatThrownBy(()->service(access).submit("key",input)).isInstanceOfSatisfying(BusinessException.class,
                error->assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verifyNoInteractions(holidays,transaction);
    }

    @Test
    void tokenEmployeeSubmitsWithoutRequiringAdmin() {
        TestTokens.authenticate(1L,"USER");
        EmployeeAccess access=spy(tokenAccess);
        when(holidays.findHolidays(any(),any())).thenReturn(new HolidayClient.HolidayResult(true,List.of()));
        service(access).submit("key",input);
        verify(access).currentEmployeeId();
        verify(access,never()).isAdmin(any());
        verify(transaction).submit(eq("key"),matches("[0-9a-f]{64}"),eq(input),any());
    }

    private LeaveSubmissionService service(EmployeeAccess access) {
        return new LeaveSubmissionService(access,FACTORY.getValidator(),new SubmissionPayloadHash(),holidays,transaction);
    }
}
