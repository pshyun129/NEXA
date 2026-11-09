package com.ssafy.leaveservice.domain.leavetype;

import com.ssafy.leaveservice.domain.leavetype.dto.RequestableLeaveTypeResponse;
import com.ssafy.leaveservice.domain.leavetype.entity.RequestUnit;
import com.ssafy.leaveservice.domain.leavetype.repository.LeaveTypeRepository;
import com.ssafy.leaveservice.domain.leavetype.repository.projection.RequestableLeaveTypeSettings;
import com.ssafy.leaveservice.domain.leavetype.service.RequestableLeaveTypeService;
import com.ssafy.leaveservice.domain.policy.dto.MyLeavePolicyResponse;
import com.ssafy.leaveservice.domain.policy.entity.PolicyAllowedUnits;
import com.ssafy.leaveservice.domain.policy.service.MyLeavePolicyService;
import com.ssafy.leaveservice.global.exception.BusinessException;
import com.ssafy.leaveservice.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestableLeaveTypeServiceTest {

    private final MyLeavePolicyService policyService = mock(MyLeavePolicyService.class);
    private final LeaveTypeRepository repository = mock(LeaveTypeRepository.class);
    private final RequestableLeaveTypeService service = new RequestableLeaveTypeService(policyService, repository);

    @BeforeEach
    void setUp() {
        MyLeavePolicyResponse policy = mock(MyLeavePolicyResponse.class);
        when(policy.policyVersionId()).thenReturn(11L);
        when(policyService.getMyPolicy()).thenReturn(policy);
    }

    static Stream<Arguments> units() {
        List<Arguments> cases = new ArrayList<>();
        for (RequestUnit master : RequestUnit.values()) {
            for (PolicyAllowedUnits policy : PolicyAllowedUnits.values()) {
                boolean compatible = master == RequestUnit.MIXED
                        || (master == RequestUnit.DAY && policy == PolicyAllowedUnits.FULL)
                        || (master == RequestUnit.HALF_DAY && policy == PolicyAllowedUnits.HALF)
                        || (master == RequestUnit.HOUR && policy == PolicyAllowedUnits.HOUR);
                cases.add(Arguments.of(master, policy, compatible));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("units")
    void enforcesAllSixteenMasterAndPolicyUnitCombinations(RequestUnit master, PolicyAllowedUnits policy, boolean compatible) {
        when(repository.findRequestableSettingsByVersionId(11L)).thenReturn(List.of(
                new RequestableLeaveTypeSettings(1L, "연차", master, null, policy, null, null)
        ));
        if (compatible) {
            assertThat(service.getRequestableTypes()).containsExactly(
                    new RequestableLeaveTypeResponse(1L, "연차", policy, null, null)
            );
        } else {
            assertConflict();
        }
        verify(policyService).getMyPolicy();
        verify(repository).findRequestableSettingsByVersionId(11L);
    }

    static Stream<Arguments> limits() {
        return Stream.of(
                Arguments.of(480, 60, 240, 240), Arguments.of(480, null, null, 480),
                Arguments.of(null, 30, 240, 240), Arguments.of(null, null, null, null),
                Arguments.of(480, 480, 480, 480), Arguments.of(480, 480, null, 480)
        );
    }

    @ParameterizedTest
    @MethodSource("limits")
    void returnsEffectiveLimitsWithoutTreatingNullPolicyCapAsRemovalOfMasterCap(
            Integer master, Integer min, Integer max, Integer effectiveMax
    ) {
        when(repository.findRequestableSettingsByVersionId(11L)).thenReturn(List.of(settings(master, min, max)));
        assertThat(service.getRequestableTypes()).containsExactly(
                new RequestableLeaveTypeResponse(1L, "연차", PolicyAllowedUnits.FULL, min, effectiveMax)
        );
    }

    static Stream<Arguments> invalidLimits() {
        return Stream.of(Arguments.of(120, 121, null), Arguments.of(120, null, 121),
                Arguments.of(null, 90, 60), Arguments.of(null, 0, null), Arguments.of(null, null, 0),
                Arguments.of(null, -1, null), Arguments.of(null, null, -1), Arguments.of(0, null, null));
    }

    @ParameterizedTest
    @MethodSource("invalidLimits")
    void rejectsConflictsInsteadOfClampingOrHidingInvalidRows(Integer master, Integer min, Integer max) {
        when(repository.findRequestableSettingsByVersionId(11L)).thenReturn(List.of(
                settings(480, 60, 240), settings(master, min, max)
        ));
        assertConflict();
        verifyNoMoreInteractions(policyService, repository);
    }

    @Test
    void validPolicyWithNoRequestableTypesReturnsEmptyList() {
        assertThat(service.getRequestableTypes()).isEmpty();
        verify(policyService).getMyPolicy();
        verify(repository).findRequestableSettingsByVersionId(11L);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {
            "UNAUTHORIZED", "AUTHENTICATION_UNAVAILABLE", "CURRENT_LEAVE_POLICY_NOT_FOUND", "CURRENT_LEAVE_POLICY_CONFLICT"
    })
    void preservesExistingIdentityAndPolicyErrorsWithoutQueryingTypes(ErrorCode errorCode) {
        when(policyService.getMyPolicy()).thenThrow(new BusinessException(errorCode));
        assertThatThrownBy(service::getRequestableTypes).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
        verifyNoInteractions(repository);
    }

    private RequestableLeaveTypeSettings settings(Integer master, Integer min, Integer max) {
        return new RequestableLeaveTypeSettings(1L, "연차", RequestUnit.DAY, master, PolicyAllowedUnits.FULL, min, max);
    }

    private void assertConflict() {
        assertThatThrownBy(service::getRequestableTypes).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.LEAVE_POLICY_TYPE_RULE_CONFLICT));
        verify(policyService).getMyPolicy();
        verify(repository).findRequestableSettingsByVersionId(11L);
    }
}
